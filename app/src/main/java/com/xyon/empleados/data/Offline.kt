package com.xyon.empleados.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connectivity as a flow: screens fail fast when there is no network (instead of waiting for the
 * 20 s timeout) and loaders refresh on their own as soon as it comes back.
 */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(current())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _online.value = true }
                override fun onLost(network: Network) { _online.value = current() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        }
    }

    fun current(): Boolean = runCatching {
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)
}

/** A clock-in made without connection, sent later with the device time (server marks it APP_OFFLINE). */
@Serializable
data class PendingFichaje(
    val id: String, val tipo: String, val workMode: String, val clientTimestamp: String,
    val latitude: Double? = null, val longitude: Double? = null, val accuracy: Float? = null,
)

/** Ordered queue of offline clock-ins (SharedPreferences: small and readable from workers). */
@Singleton
class OfflineFichajes @Inject constructor(@ApplicationContext ctx: Context) {
    private val prefs = ctx.getSharedPreferences("offline_fichajes", Context.MODE_PRIVATE)
    private val ser = ListSerializer(PendingFichaje.serializer())
    private val _pending = MutableStateFlow(read())
    val pending: StateFlow<List<PendingFichaje>> = _pending.asStateFlow()

    private fun read(): List<PendingFichaje> =
        prefs.getString("queue", null)?.let { runCatching { AppJson.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()

    @Synchronized private fun write(list: List<PendingFichaje>) {
        prefs.edit().putString("queue", AppJson.encodeToString(ser, list)).apply()
        _pending.value = list
    }

    @Synchronized fun add(p: PendingFichaje) = write(read() + p)
    @Synchronized fun remove(id: String) = write(read().filterNot { it.id == id })
    fun all(): List<PendingFichaje> = read()
    @Synchronized fun clear() = write(emptyList())
}
