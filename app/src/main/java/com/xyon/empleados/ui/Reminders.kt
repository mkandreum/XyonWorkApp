package com.xyon.empleados.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xyon.empleados.MainActivity
import com.xyon.empleados.R
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Local clock-in/out reminders (no push service needed):
 *  - while clocked in: a nudge 15 min after the shift's expected exit;
 *  - while out: a nudge 10 min after the usual entry time on the next working day.
 * Scheduling is refreshed every time Home sees the current status.
 */
object Reminders {
    private const val CHANNEL = "fichaje"
    private const val ENTRY = "remind_entry"
    private const val EXIT = "remind_exit"
    private const val PREFS = "reminders"

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        if (!on) WorkManager.getInstance(ctx).run { cancelUniqueWork(ENTRY); cancelUniqueWork(EXIT) }
    }

    /** Called with the latest status; [expectedEntry]/[expectedExit] are "HH:mm" from the shift. */
    fun sync(ctx: Context, clockedIn: Boolean, expectedEntry: String?, expectedExit: String?) {
        val wm = WorkManager.getInstance(ctx)
        expectedEntry?.let { prefs(ctx).edit().putString("entry", it).apply() }
        if (!enabled(ctx)) return
        val now = LocalDateTime.now()
        if (clockedIn) {
            wm.cancelUniqueWork(ENTRY)
            val exit = parse(expectedExit) ?: return
            val at = now.toLocalDate().atTime(exit).plusMinutes(15)
            if (at.isAfter(now)) schedule(ctx, EXIT, at, "¿Ya has terminado?", "Tu turno acababa a las $expectedExit. No olvides fichar la salida.")
            else wm.cancelUniqueWork(EXIT)
        } else {
            wm.cancelUniqueWork(EXIT)
            val entry = parse(prefs(ctx).getString("entry", null)) ?: return
            var at = now.toLocalDate().atTime(entry).plusMinutes(10)
            if (!at.isAfter(now)) at = at.plusDays(1)
            while (at.dayOfWeek == DayOfWeek.SATURDAY || at.dayOfWeek == DayOfWeek.SUNDAY) at = at.plusDays(1)
            schedule(ctx, ENTRY, at, "Aún no has fichado", "Tu entrada habitual es a las ${entry}. Ficha desde la app en un toque.")
        }
    }

    private fun schedule(ctx: Context, name: String, at: LocalDateTime, title: String, text: String) {
        val delay = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - System.currentTimeMillis()
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("title" to title, "text" to text))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
    }

    private fun parse(hhmm: String?): LocalTime? = runCatching { LocalTime.parse(hhmm!!.take(5)) }.getOrNull()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun notify(ctx: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recordatorios de fichaje", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xyon)
            .setColor(0xFF2563EB.toInt())
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(title.hashCode(), n) }
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Reminders.notify(applicationContext, inputData.getString("title") ?: return Result.success(), inputData.getString("text").orEmpty())
        return Result.success()
    }
}
