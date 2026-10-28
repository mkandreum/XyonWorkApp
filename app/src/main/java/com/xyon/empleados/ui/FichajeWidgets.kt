package com.xyon.empleados.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
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
import com.xyon.empleados.data.CreateFichajeRequest
import com.xyon.empleados.data.FichajeStatus
import com.xyon.empleados.data.PendingFichaje
import com.xyon.empleados.data.apiError
import com.xyon.empleados.data.isOffline
import com.xyon.empleados.data.ServerConfig
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Home-screen clock-in widgets (2×2 and 4×2).
 *
 * The app pushes the state the moment it changes ([publish]: clock in/out, Home refresh,
 * offline queue sent), so the widget never lags behind a clock-in. The running time is a
 * Chronometer anchored to the entry time: it ticks on its own, with no wake-ups.
 * A worker re-reads the server state when a widget is added, after boot, every 30 min and when
 * a clock-in is made elsewhere (web, another phone), so it also stays right with the app closed.
 *
 * The big button clocks in/out right from the widget ([FichajeWidgetActionReceiver] → [toggle]): same
 * request as the app, location only when the company's policy asks for it and the permission is
 * already granted (otherwise the button opens the app), offline queue when there is no network.
 *
 * Only what the widget shows is kept, in app-private storage: in/out, entry time, mode,
 * expected shift and this week's hours. Everything is wiped on logout ([clear]).
 */
object FichajeWidgets {
    private const val PREFS = "widget_fichaje"
    private const val REFRESH = "widget_fichaje_refresh"
    private const val PERIODIC = "widget_fichaje_periodic"
    private const val BUSY_MS = 15_000L
    const val ACTION_TOGGLE = "com.xyon.empleados.widget.TOGGLE"
    const val ACTION_REFRESH = "com.xyon.empleados.widget.REFRESH"

    private val providers = listOf(FichajeWidgetSmall::class.java, FichajeWidgetWide::class.java)

    private data class State(
        val loggedIn: Boolean, val clockedIn: Boolean, val sinceMillis: Long, val workMode: String?,
        val shift: String?, val weekHours: Double?, val busy: Boolean, val locOffice: Boolean, val locRemote: Boolean,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(ctx: Context): State = prefs(ctx).run {
        State(
            loggedIn = getBoolean("logged", false), clockedIn = getBoolean("in", false), sinceMillis = getLong("since", 0L),
            workMode = getString("mode", null), shift = getString("shift", null),
            weekHours = if (contains("week")) getFloat("week", 0f).toDouble() else null,
            busy = System.currentTimeMillis() - getLong("busy_at", 0L) < BUSY_MS,
            locOffice = getBoolean("loc_office", true), locRemote = getBoolean("loc_remote", false),
        )
    }

    /** New server/app state: store it and redraw every widget now. [weekHours] null keeps the last value. */
    fun publish(ctx: Context, status: FichajeStatus, weekHours: Double? = null) {
        val entry = status.currentFichaje ?: status.missingExit
        val since = entry?.timestamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
        val clockedIn = status.hasActiveEntry && since > 0
        val shift = status.turno?.let { t ->
            listOfNotNull(t.label, listOfNotNull(t.expectedEntry, t.expectedExit).takeIf { it.isNotEmpty() }?.joinToString(" – ")).joinToString(" · ")
        }?.takeIf { it.isNotBlank() }
        prefs(ctx).edit().apply {
            putBoolean("logged", true)
            putBoolean("in", clockedIn)
            putLong("since", if (clockedIn) since else 0L)
            putString("mode", if (clockedIn) status.currentFichaje?.workMode else null)
            putString("shift", shift)
            putBoolean("loc_office", status.wantsLocation("OFFICE"))
            putBoolean("loc_remote", status.wantsLocation("REMOTE"))
            remove("busy_at")
            weekHours?.let { putFloat("week", it.toFloat()) }
        }.apply()
        redraw(ctx)
    }

    /** Logout: forget everything and show the "sign in" state. */
    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        redraw(ctx)
    }

    /** Re-read the state from the server in the background (only if there is a widget). */
    fun requestRefresh(ctx: Context) {
        if (!hasWidgets(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            REFRESH, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FichajeWidgetWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }

    internal fun ensurePeriodic(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FichajeWidgetWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }

    internal fun cancelPeriodic(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)

    private fun hasWidgets(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return false
        return providers.any { mgr.getAppWidgetIds(ComponentName(ctx, it)).isNotEmpty() }
    }

    /** Redraws all placed widgets from the stored state. */
    fun redraw(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        val s = read(ctx)
        providers.forEach { cls ->
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            if (ids.isEmpty()) return@forEach
            val layout = if (cls == FichajeWidgetWide::class.java) R.layout.widget_fichaje_wide else R.layout.widget_fichaje_small
            mgr.updateAppWidget(ids, build(ctx, layout, s, wide = layout == R.layout.widget_fichaje_wide))
        }
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 7301,
        Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("xyon://open/home"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, FichajeWidgetActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun workModePref(ctx: Context) =
        ctx.getSharedPreferences("fichaje_prefs", Context.MODE_PRIVATE).getString("work_mode", "OFFICE") ?: "OFFICE"

    private fun build(ctx: Context, layout: Int, s: State, wide: Boolean): RemoteViews {
        val v = RemoteViews(ctx.packageName, layout)
        // The card opens Home; only the button clocks in/out
        v.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
        if (wide) v.setOnClickPendingIntent(R.id.w_refresh, broadcast(ctx, ACTION_REFRESH, 7303))

        if (!s.loggedIn) {
            pill(v, ctx.getString(R.string.widget_out), on = false)
            v.setTextViewText(R.id.w_status, ServerConfig.companyName ?: ctx.getString(R.string.app_name))
            showTimer(v, false, 0L)
            v.setTextViewText(R.id.w_detail, "Inicia sesión para ver tu jornada")
            action(v, ctx.getString(R.string.widget_open), R.drawable.ic_widget_play, R.drawable.widget_btn_in)
            v.setOnClickPendingIntent(R.id.w_action, openApp(ctx))
            if (wide) { v.setTextViewText(R.id.w_week, ctx.getString(R.string.widget_dash)); v.setTextViewText(R.id.w_shift, ""); v.setTextViewText(R.id.w_company, "") }
            return v
        }

        pill(v, ctx.getString(if (s.clockedIn) R.string.widget_in else R.string.widget_out), on = s.clockedIn)
        showTimer(v, s.clockedIn, s.sinceMillis)
        v.setTextViewText(
            R.id.w_detail,
            if (s.clockedIn) "Desde las ${fmtTimeOf(Instant.ofEpochMilli(s.sinceMillis).toString())}" + if (s.workMode == "REMOTE") " · teletrabajo" else " · oficina"
            else s.shift?.let { "Turno: $it" } ?: "Toca el botón para fichar",
        )
        if (s.busy) {
            action(v, ctx.getString(R.string.widget_busy), if (s.clockedIn) R.drawable.ic_widget_stop else R.drawable.ic_widget_play, R.drawable.widget_btn_busy)
            v.setOnClickPendingIntent(R.id.w_action, broadcast(ctx, ACTION_REFRESH, 7303))
        } else {
            action(
                v, ctx.getString(if (s.clockedIn) R.string.widget_clock_out else R.string.widget_clock_in),
                if (s.clockedIn) R.drawable.ic_widget_stop else R.drawable.ic_widget_play,
                if (s.clockedIn) R.drawable.widget_btn_out else R.drawable.widget_btn_in,
            )
            // Location policy needs a permission the widget cannot ask for: the app does it
            val mode = if (s.clockedIn) s.workMode ?: workModePref(ctx) else workModePref(ctx)
            val needsApp = (if (mode == "REMOTE") s.locRemote else s.locOffice) && !hasLocationPermission(ctx)
            v.setOnClickPendingIntent(R.id.w_action, if (needsApp) openApp(ctx) else broadcast(ctx, ACTION_TOGGLE, 7302))
        }
        if (wide) {
            v.setTextViewText(R.id.w_week, s.weekHours?.let { formatHours(it) } ?: ctx.getString(R.string.widget_dash))
            v.setTextViewText(R.id.w_shift, s.shift ?: "Sin turno asignado hoy")
            v.setTextViewText(R.id.w_company, ServerConfig.companyName ?: "")
        }
        return v
    }

    private fun pill(v: RemoteViews, text: String, on: Boolean) {
        v.setTextViewText(R.id.w_status, text)
        v.setInt(R.id.w_pill, "setBackgroundResource", if (on) R.drawable.widget_pill_on else R.drawable.widget_pill_off)
        val ink = if (on) 0xFF0C0C12.toInt() else 0xFFFFFFFF.toInt()
        v.setTextColor(R.id.w_status, ink)
        v.setInt(R.id.w_dot, "setColorFilter", ink)
    }

    private fun action(v: RemoteViews, label: String, icon: Int, bg: Int) {
        v.setTextViewText(R.id.w_action_label, label)
        v.setImageViewResource(R.id.w_action_icon, icon)
        v.setInt(R.id.w_action, "setBackgroundResource", bg)
    }

    /** Button pressed: clock in or out against the server, then redraw with what it accepted. */
    internal suspend fun toggle(ctx: Context) {
        val s = read(ctx)
        if (!s.loggedIn || s.busy) return
        val entering = !s.clockedIn
        val mode = if (entering) workModePref(ctx) else s.workMode ?: workModePref(ctx)
        val wantsLoc = if (mode == "REMOTE") s.locRemote else s.locOffice
        if (wantsLoc && !hasLocationPermission(ctx)) { toast(ctx, "Abre la app para dar permiso de ubicación"); return }
        prefs(ctx).edit().putLong("busy_at", System.currentTimeMillis()).apply()
        redraw(ctx)

        val e = EntryPointAccessors.fromApplication(ctx.applicationContext, BackgroundEntryPoint::class.java)
        val session = e.session()
        if (session.token == null && !session.load()) { clear(ctx); return }
        val loc = if (wantsLoc) lastLocation(ctx) else null
        val req = CreateFichajeRequest(if (entering) "ENTRADA" else "SALIDA", loc?.latitude, loc?.longitude, loc?.accuracy, mode)
        val queue = e.offline()
        try {
            if (queue.all().isNotEmpty()) throw java.io.IOException("queued before")
            val res = e.api().fichar(req)
            val status = res.status ?: e.api().fichajeCurrent()
            val week = runCatching { e.api().fichajeWeek(e.me().id()).sumOf { it.horasTrabajadas } }.getOrNull()
            Reminders.setClockedIn(ctx, status.hasActiveEntry || status.missingExit != null,
                (status.currentFichaje ?: status.missingExit)?.timestamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() })
            publish(ctx, status, week)
            toast(ctx, res.warning ?: if (entering) "Entrada registrada" else "Salida registrada")
        } catch (ex: Exception) {
            if (ex.isOffline) {
                // No network: saved on the phone with its time, sent when it is back (same queue as the app)
                val now = Instant.now().toString()
                queue.add(PendingFichaje(java.util.UUID.randomUUID().toString(), req.tipo, mode, now, req.latitude, req.longitude, req.accuracy))
                Background.syncFichajes(ctx)
                prefs(ctx).edit().apply {
                    putBoolean("in", entering); putLong("since", if (entering) System.currentTimeMillis() else 0L)
                    putString("mode", if (entering) mode else null); remove("busy_at")
                }.apply()
                redraw(ctx)
                toast(ctx, "Sin conexión: ${if (entering) "entrada" else "salida"} guardada, se enviará al recuperar la red")
            } else {
                prefs(ctx).edit().remove("busy_at").apply()
                redraw(ctx)
                requestRefresh(ctx) // stale state (clocked in elsewhere): show the real one
                toast(ctx, runCatching { ex.apiError().second }.getOrNull() ?: "No se pudo fichar")
            }
        }
    }

    internal fun settle(ctx: Context) {
        prefs(ctx).edit().remove("busy_at").apply()
        redraw(ctx)
        requestRefresh(ctx)
    }

    private fun toast(ctx: Context, text: String) =
        Handler(Looper.getMainLooper()).post { Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_LONG).show() }

    /** Chronometer base is on the elapsed-realtime clock: recomputed on every draw, so reboots are fine. */
    private fun showTimer(v: RemoteViews, running: Boolean, sinceMillis: Long) {
        if (running) {
            val elapsed = (System.currentTimeMillis() - sinceMillis).coerceAtLeast(0L)
            v.setChronometer(R.id.w_timer, SystemClock.elapsedRealtime() - elapsed, null, true)
            v.setViewVisibility(R.id.w_timer, View.VISIBLE)
            v.setViewVisibility(R.id.w_idle, View.GONE)
        } else {
            v.setChronometer(R.id.w_timer, SystemClock.elapsedRealtime(), null, false)
            v.setViewVisibility(R.id.w_timer, View.GONE)
            v.setViewVisibility(R.id.w_idle, View.VISIBLE)
        }
    }

    internal fun formatHours(h: Double): String {
        val totalMin = Math.round(h * 60).toInt().coerceAtLeast(0)
        return "%dh %02dm".format(totalMin / 60, totalMin % 60)
    }
}

/** 2×2: status, live timer and the clock-in button. */
class FichajeWidgetSmall : FichajeWidgetBase()

/** 4×2: the same plus this week's hours, the expected shift and the company. */
class FichajeWidgetWide : FichajeWidgetBase()

/** The widget's own buttons: clock in/out and refresh, with the app closed. */
class FichajeWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        if (intent.action == FichajeWidgets.ACTION_REFRESH) { FichajeWidgets.settle(ctx); return }
        if (intent.action != FichajeWidgets.ACTION_TOGGLE) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // A request still in the air after 9 s is not assumed lost: the real state is re-read
                if (withTimeoutOrNull(9_000L) { FichajeWidgets.toggle(ctx) } == null) FichajeWidgets.settle(ctx)
            } finally { pending.finish() }
        }
    }
}

open class FichajeWidgetBase : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        FichajeWidgets.redraw(context)           // instant: last known state
        FichajeWidgets.requestRefresh(context)   // then the real one from the server
    }

    override fun onEnabled(context: Context) = FichajeWidgets.ensurePeriodic(context)

    override fun onDisabled(context: Context) {
        // Last widget removed (of this size): stop polling only if the other size is gone too
        val mgr = AppWidgetManager.getInstance(context)
        val left = listOf(FichajeWidgetSmall::class.java, FichajeWidgetWide::class.java)
            .sumOf { mgr.getAppWidgetIds(ComponentName(context, it)).size }
        if (left == 0) FichajeWidgets.cancelPeriodic(context)
    }
}

/** Reads /fichajes/current (and this week's hours) for the widgets. */
class FichajeWidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val e = EntryPointAccessors.fromApplication(applicationContext, BackgroundEntryPoint::class.java)
        val session = e.session()
        if (session.token == null && !session.load()) { FichajeWidgets.clear(applicationContext); return Result.success() }
        val status = runCatching { e.api().fichajeCurrent() }.getOrElse { return Result.success() }
        val week = runCatching { e.api().fichajeWeek(e.me().id()).sumOf { it.horasTrabajadas } }.getOrNull()
        FichajeWidgets.publish(applicationContext, status, week)
        return Result.success()
    }
}
