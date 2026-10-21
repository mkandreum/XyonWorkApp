package com.xyon.empleados.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xyon.empleados.MainActivity
import com.xyon.empleados.R
import com.xyon.empleados.data.PlannedReminder
import com.xyon.empleados.data.ReminderPolicy
import dagger.hilt.android.EntryPointAccessors
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local clock-in reminders.
 *
 * The schedule is resolved by [com.xyon.empleados.data.SchedulePlanner] (assigned
 * shifts + overrides + approved absences) and each reminder is stored as an exact
 * wall-clock alarm five minutes before the shift starts. This works with the app
 * closed and without a push service; [ReminderSyncWorker] refreshes the plan
 * periodically so schedule changes made on the web are picked up.
 */
object Reminders {
    private const val CHANNEL = "fichaje_reminders"
    private const val PREFS = "reminders"
    private const val KEY_PLAN = "plan"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CLOCKED_IN = "clocked_in"
    private const val KEY_CLOCKED_IN_AT = "clocked_in_at"
    private const val SYNC_WORK = "reminder_sync"
    private const val SYNC_NOW = "reminder_sync_now"
    /** A forgotten entry older than this is treated as stale for exit reminders. */
    private const val MAX_OPEN_ENTRY_MS = 36L * 60 * 60 * 1000
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_TEXT = "text"
    internal const val EXTRA_KIND = "kind"
    internal const val EXTRA_SHIFT_START = "shift_start"

    private val json = Json { ignoreUnknownKeys = true }
    private val planSerializer = ListSerializer(PlannedReminder.serializer())

    /** Invalidates any in-flight sync when the user disables reminders or logs out. */
    private val generationCounter = AtomicInteger(0)
    /** Makes "apply plan" atomic with "cancel all" so a race cannot re-arm alarms. */
    private val lock = Any()

    internal fun generation(): Int = generationCounter.get()

    fun enabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        generationCounter.incrementAndGet()
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) {
            ensurePeriodicSync(ctx)
            requestImmediateSync(ctx)
        } else {
            cancelAll(ctx)
            stopSync(ctx)
        }
    }

    /**
     * Replaces the currently scheduled alarms with [plans]. [expectedGeneration]
     * is captured before the network calls; if the user disabled reminders or
     * logged out meanwhile, the stale plan is discarded.
     */
    fun applyPlan(ctx: Context, plans: List<PlannedReminder>, expectedGeneration: Int = generation()) {
        synchronized(lock) {
            if (!enabled(ctx) || generationCounter.get() != expectedGeneration) return
            cancelScheduledAlarms(ctx)
            val now = System.currentTimeMillis()
            val future = plans.filter { it.atMillis() > now }
            prefs(ctx).edit().putString(KEY_PLAN, json.encodeToString(planSerializer, future)).apply()
            future.forEach { runCatching { schedule(ctx, it) } }
        }
    }

    /** Cancels alarms and forgets the plan (logout, reminders disabled). */
    fun cancelAll(ctx: Context) {
        synchronized(lock) {
            generationCounter.incrementAndGet()
            cancelScheduledAlarms(ctx)
            prefs(ctx).edit().remove(KEY_PLAN).apply()
        }
    }

    /** Re-arms the stored plan after a reboot or a timezone/time change. */
    fun reschedule(ctx: Context) {
        synchronized(lock) {
            if (!enabled(ctx)) return
            cancelScheduledAlarms(ctx)
            val now = System.currentTimeMillis()
            val plan = readPlan(ctx).filter { it.atMillis() > now }
            prefs(ctx).edit().putString(KEY_PLAN, json.encodeToString(planSerializer, plan)).apply()
            plan.forEach { runCatching { schedule(ctx, it) } }
        }
    }

    /** Last known clock-in state, used to avoid reminding someone who is already working. */
    fun setClockedIn(ctx: Context, clockedIn: Boolean, atMillis: Long? = null) {
        prefs(ctx).edit().apply {
            putBoolean(KEY_CLOCKED_IN, clockedIn)
            if (clockedIn && atMillis != null && atMillis > 0) putLong(KEY_CLOCKED_IN_AT, atMillis)
            else remove(KEY_CLOCKED_IN_AT)
        }.apply()
    }

    /**
     * True while an entry is still open. A date comparison would break overnight
     * shifts (entry Monday 22:00, exit Tuesday 06:00), so the state is tied to the
     * entry timestamp with a generous staleness bound instead.
     */
    fun isClockedIn(ctx: Context): Boolean {
        if (!prefs(ctx).getBoolean(KEY_CLOCKED_IN, false)) return false
        val at = prefs(ctx).getLong(KEY_CLOCKED_IN_AT, 0L)
        if (at <= 0L) return true
        return System.currentTimeMillis() - at <= MAX_OPEN_ENTRY_MS
    }

    fun clockedInAt(ctx: Context): Long = prefs(ctx).getLong(KEY_CLOCKED_IN_AT, 0L)

    /** Pulls the latest schedule in the background and recalculates the plan. */
    fun requestImmediateSync(ctx: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<ReminderSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(SYNC_NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }

    /** Keeps the plan fresh while the app is not used (schedule changes, new month…). */
    fun ensurePeriodicSync(ctx: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<ReminderSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            val workManager = WorkManager.getInstance(ctx)
            // Remove the WorkManager reminders used before the AlarmManager rewrite.
            workManager.cancelUniqueWork("remind_entry")
            workManager.cancelUniqueWork("remind_exit")
            workManager.enqueueUniquePeriodicWork(SYNC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    fun stopSync(ctx: Context) {
        runCatching {
            WorkManager.getInstance(ctx).cancelUniqueWork(SYNC_WORK)
            WorkManager.getInstance(ctx).cancelUniqueWork(SYNC_NOW)
        }
    }

    @Suppress("DEPRECATION")
    private fun schedule(ctx: Context, reminder: PlannedReminder) {
        val at = reminder.atMillis()
        if (at <= System.currentTimeMillis()) return
        val alarm = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(ctx, reminder)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        try {
            when {
                canExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                canExact ->
                    alarm.setExact(AlarmManager.RTC_WAKEUP, at, pending)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    // Exact-alarm access denied (Android 14+): keep the reminder, it may
                    // arrive a few minutes late but is still useful.
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                else ->
                    alarm.set(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarm.set(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }

    private fun pendingIntent(ctx: Context, reminder: PlannedReminder): PendingIntent {
        val shiftStart = reminder.shiftStart.takeIf { it.isNotBlank() }?.let {
            runCatching {
                LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrDefault(0L)
        } ?: 0L
        val intent = Intent(ctx, ReminderAlarmReceiver::class.java)
            .putExtra(EXTRA_TITLE, reminder.title)
            .putExtra(EXTRA_TEXT, reminder.text)
            .putExtra(EXTRA_KIND, reminder.kind)
            .putExtra(EXTRA_SHIFT_START, shiftStart)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, reminder.requestCode(), intent, flags)
    }

    private fun cancelScheduledAlarms(ctx: Context) {
        val alarm = ctx.getSystemService(AlarmManager::class.java) ?: return
        readPlan(ctx).forEach { alarm.cancel(pendingIntent(ctx, it)) }
    }

    private fun readPlan(ctx: Context): List<PlannedReminder> {
        val raw = prefs(ctx).getString(KEY_PLAN, null) ?: return emptyList()
        return runCatching { json.decodeFromString(planSerializer, raw) }.getOrDefault(emptyList())
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun notify(ctx: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Recordatorios de fichaje", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisos de entrada y salida del turno"
                    enableVibration(true)
                },
            )
        }

        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("xyon://open/home")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xyon)
            .setColor(0xFF2563EB.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(title.hashCode(), notification) }
    }
}

/** Fires at the exact time of one reminder. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(Reminders.EXTRA_KIND) ?: PlannedReminder.KIND_ENTRY
        val clockedIn = Reminders.isClockedIn(context)
        val allowed = if (kind == PlannedReminder.KIND_EXIT) {
            ReminderPolicy.shouldNotifyExit(
                clockedIn = clockedIn,
                entryAt = Reminders.clockedInAt(context),
                shiftStart = intent.getLongExtra(Reminders.EXTRA_SHIFT_START, 0L),
            )
        } else {
            ReminderPolicy.shouldNotifyEntry(clockedIn)
        }
        if (!allowed) return

        val title = intent.getStringExtra("title") ?: return
        val text = intent.getStringExtra("text").orEmpty()
        Reminders.notify(context, title, text)
    }
}

/** Re-arms alarms after a reboot or a wall-clock/timezone change. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                Reminders.reschedule(context)
                Reminders.requestImmediateSync(context)
                // After a reboot or an app update the real-time connection has to be opened again
                if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Realtime.start(context)
            }
        }
        if (Reminders.enabled(context)) Reminders.ensurePeriodicSync(context)
    }
}

/** Refreshes the plan from the API; Hilt dependencies come from the app entry point. */
class ReminderSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext, ReminderWorkerEntryPoint::class.java,
        )
        entry.scheduler().refresh()
        return Result.success()
    }
}
