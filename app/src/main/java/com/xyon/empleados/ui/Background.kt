package com.xyon.empleados.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
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
import com.xyon.empleados.data.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackgroundEntryPoint {
    fun api(): Api
    fun session(): SessionStore
    fun offline(): OfflineFichajes
    fun http(): okhttp3.OkHttpClient
}

/**
 * Work that keeps running with the app closed and needs no push service (no Firebase):
 *  - sending clock-ins made without connection as soon as there is network;
 *  - checking for new notices every 15 minutes and showing them as system notifications.
 */
object Background {
    private const val SYNC = "fichaje_offline_sync"
    private const val POLL = "notifications_poll"
    internal const val CHANNEL = "avisos"
    private const val PREFS = "notifications_seen"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Send queued offline clock-ins when the network is available (retries with backoff). */
    fun syncFichajes(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            SYNC, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FichajeSyncWorker>().setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build(),
        )
    }

    /** Start polling for notices after login (15 min is the WorkManager minimum). */
    fun startNotifications(ctx: Context) {
        Realtime.start(ctx) // instant delivery while the connection is up; the poll below is the safety net
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            POLL, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NotificationsWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
        )
    }

    /** Logout: stop polling and forget what was shown (next login starts clean). */
    fun stop(ctx: Context) {
        Realtime.stop(ctx)
        WorkManager.getInstance(ctx).cancelUniqueWork(POLL)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    internal fun seenPrefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun notify(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Avisos de la empresa", NotificationManager.IMPORTANCE_DEFAULT))
        }
        // Opens the app on Home (whitelisted deep link), where the bell shows the notice
        val open = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java).setData(Uri.parse("xyon://open/home")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xyon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}

/** Sends offline clock-ins in order; rejected ones are dropped and the user is told why. */
class FichajeSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val e = EntryPointAccessors.fromApplication(applicationContext, BackgroundEntryPoint::class.java)
        val session = e.session()
        if (session.token == null && !session.load()) return Result.success() // logged out: nothing to send
        val queue = e.offline()
        for (p in queue.all()) {
            try {
                e.api().fichar(CreateFichajeRequest(p.tipo, p.latitude, p.longitude, p.accuracy, p.workMode, offline = true, clientTimestamp = p.clientTimestamp))
                queue.remove(p.id)
            } catch (ex: IOException) {
                return Result.retry() // still no network: keep order and try later
            } catch (ex: HttpException) {
                if (ex.code() == 401 || ex.code() >= 500) return Result.retry()
                // Rejected by the server (too old, conflict…): drop it and tell the user
                val (_, message) = ex.apiError()
                queue.remove(p.id)
                Background.notify(applicationContext, p.id.hashCode(), "No se pudo registrar un fichaje sin conexión", message)
            }
        }
        return Result.success()
    }
}

/** Polls /notifications and shows unread notices that were not shown before. */
class NotificationsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val e = EntryPointAccessors.fromApplication(applicationContext, BackgroundEntryPoint::class.java)
        val session = e.session()
        if (session.token == null && !session.load()) return Result.success()
        val list = try { e.api().notifications().notifications } catch (ex: Exception) { return Result.success() }
        val prefs = Background.seenPrefs(applicationContext)
        val seen = prefs.getStringSet("ids", null)
        if (seen == null) {
            // First run after login: everything that exists now is "seen" (no flood of old notices)
            prefs.edit().putStringSet("ids", list.map { it.id }.toSet()).apply()
            return Result.success()
        }
        val fresh = list.filter { !it.read && it.id !in seen }.take(5)
        fresh.forEach { Background.notify(applicationContext, it.id.hashCode(), it.title, it.message) }
        // Keep the set small: only ids still on the server
        prefs.edit().putStringSet("ids", (seen + fresh.map { it.id }).intersect(list.map { it.id }.toSet()) + fresh.map { it.id }).apply()
        return Result.success()
    }
}
