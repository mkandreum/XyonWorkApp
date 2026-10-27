package com.xyon.empleados.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.xyon.empleados.BuildConfig
import com.xyon.empleados.MainActivity
import com.xyon.empleados.R
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Notices in real time WITHOUT Firebase: the app keeps one long-lived HTTPS connection to the
 * company server (Server-Sent Events, `GET notifications/stream`) and the server writes each
 * notification to it the moment it is created.
 *
 * It runs as a foreground service (a visible, silent notification is what lets Android keep it
 * alive). The 15-minute WorkManager poll in [Background] stays as the safety net for when the
 * system pauses the app. The person can switch it off in Profile.
 */
object Realtime {
    private const val PREFS = "realtime"
    private const val KEY = "enabled"

    /** Default on: notices that matter must arrive without the person having to open the app. */
    fun enabled(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        if (on) start(ctx) else stop(ctx)
    }

    /** Starts the connection if the person wants it. Safe to call often and from anywhere. */
    fun start(ctx: Context) {
        if (!enabled(ctx)) return
        // From the background Android may refuse (e.g. a worker): the next foreground start will do
        runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, RealtimeService::class.java)) }
    }

    fun stop(ctx: Context) {
        runCatching { ctx.stopService(Intent(ctx, RealtimeService::class.java)) }
    }

    internal const val CHANNEL = "tiempo_real"
    internal const val LAST_ID = "last_event_id"
}

/** One parsed Server-Sent Event. */
internal data class SseEvent(val id: String?, val event: String, val data: String)

/** Incremental parser for the text/event-stream format (lines, blank line = dispatch). */
internal class SseParser(private val onEvent: (SseEvent) -> Unit) {
    private var id: String? = null
    private var event = "message"
    private val data = StringBuilder()

    fun line(raw: String) {
        if (raw.isEmpty()) {
            if (data.isNotEmpty()) onEvent(SseEvent(id, event, data.toString().removeSuffix("\n")))
            event = "message"; data.setLength(0) // id persists, per the spec
            return
        }
        if (raw.startsWith(":")) return // comment / heartbeat
        val idx = raw.indexOf(':')
        val field = if (idx < 0) raw else raw.substring(0, idx)
        val value = if (idx < 0) "" else raw.substring(idx + 1).removePrefix(" ")
        when (field) {
            "id" -> id = value
            "event" -> event = value
            "data" -> data.append(value).append('\n')
        }
    }
}

class RealtimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var call: Call? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (!running) {
            running = true
            scope.launch { loop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        call?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(Realtime.CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(Realtime.CHANNEL, "Conexión en tiempo real", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Mantiene la conexión con tu empresa para recibir los avisos al instante" },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setData(Uri.parse("xyon://open/home")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, Realtime.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xyon)
            .setContentTitle("Avisos al instante activados")
            .setContentText("Recibes los avisos de tu empresa en cuanto se envían")
            .setOngoing(true).setSilent(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 4101, n, type)
    }

    /** Connect, read, and on any failure wait (1 s up to 60 s) and try again. */
    private suspend fun loop() {
        val e = EntryPointAccessors.fromApplication(applicationContext, BackgroundEntryPoint::class.java)
        val session = e.session()
        var backoff = 1_000L
        while (scope.isActive) {
            try {
                if (session.token == null && !session.load()) { stopSelf(); return } // logged out
                val client = e.http().newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() // stream: no read timeout
                val prefs = Background.seenPrefs(applicationContext)
                val req = Request.Builder()
                    .url(BuildConfig.API_BASE_URL + "notifications/stream") // the interceptor points it at the company server and adds the token
                    .header("Accept", "text/event-stream")
                    .apply { prefs.getString(Realtime.LAST_ID, null)?.let { header("Last-Event-ID", it) } }
                    .build()
                val c = client.newCall(req)
                call = c
                c.execute().use { resp ->
                    if (resp.code == 401 || resp.code == 403) { stopSelf(); return } // the interceptor already signalled the expired session
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    backoff = 1_000L // connected: next failure starts from 1 s again
                    val parser = SseParser { ev -> handle(ev) }
                    val src = resp.body!!.source()
                    while (scope.isActive && !src.exhausted()) parser.line(src.readUtf8Line() ?: break)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // network gone, server restarting, connection recycled by the server: just retry
            }
            if (!scope.isActive) return
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun handle(ev: SseEvent) {
        val prefs = Background.seenPrefs(applicationContext)
        ev.id?.let { prefs.edit().putString(Realtime.LAST_ID, it).apply() }
        // A clock-in made anywhere (web, another phone) or a reconnection: keep the widgets right
        if (ev.event == "fichaje" || ev.event == "ready") FichajeWidgets.requestRefresh(applicationContext)
        if (ev.event != "notification") return
        val o: JsonObject = runCatching { Json.parseToJsonElement(ev.data).jsonObject }.getOrNull() ?: return
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return
        val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return
        val message = o["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // Same "already shown" list as the poll worker: a notice never shows twice
        synchronized(Background) {
            val seen = prefs.getStringSet("ids", null)?.toMutableSet() ?: mutableSetOf()
            if (!seen.add(id)) return
            prefs.edit().putStringSet("ids", seen).apply()
        }
        Background.notify(applicationContext, id.hashCode(), title, message)
    }

    companion object {
        @Volatile private var running = false
    }
}
