package com.xyon.empleados.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xyon.empleados.data.CacheStore
import com.xyon.empleados.data.DIRECT_HEADER
import com.xyon.empleados.data.ServerAddress
import com.xyon.empleados.data.ServerConfig
import com.xyon.empleados.data.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * "Conecta con tu empresa": checks a code / URL / QR against the server's public endpoint and,
 * once the user confirms the company it shows, stores it as this install's server.
 * Credentials are never sent before this confirmation.
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val store: SessionStore,
    private val cache: CacheStore,
) : ViewModel() {
    data class Found(val baseUrl: String, val host: String, val ownDomain: Boolean, val name: String?, val logoUrl: String?)

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _found = MutableStateFlow<Found?>(null)
    val found = _found.asStateFlow()

    fun clearError() { _error.value = null }
    fun dismiss() { _found.value = null }

    fun check(input: String) {
        if (_busy.value) return
        when (val r = ServerAddress.normalize(input)) {
            is ServerAddress.Result.Error -> _error.value = r.message
            is ServerAddress.Result.Ok -> viewModelScope.launch {
                _busy.value = true
                _error.value = null
                runCatching { withContext(Dispatchers.IO) { fetchInstance(r) } }
                    .onSuccess { _found.value = it }
                    .onFailure { _error.value = it.message?.takeIf { m -> m.startsWith("!") }?.drop(1) ?: "No se pudo conectar con ${r.host}. Revisa la dirección y tu conexión." }
                _busy.value = false
            }
        }
    }

    /** GET public/instance (servers ≥ 7.1); older servers only have public/logo. */
    private fun fetchInstance(r: ServerAddress.Result.Ok): Found {
        val obj = get(r.baseUrl + "public/instance") ?: get(r.baseUrl + "public/logo")
            ?: throw IllegalStateException("!${r.host} no es un servidor de XyonWork")
        val name = (obj["name"] ?: obj["companyName"])?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val logo = obj["logoUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else r.baseUrl.removeSuffix("api/") + it.removePrefix("/") }
            ?.takeIf { it.startsWith("https://") }
        return Found(r.baseUrl, r.host, r.ownDomain, name, logo)
    }

    private fun get(url: String): JsonObject? {
        val req = Request.Builder().url(url).header(DIRECT_HEADER, "1").header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            return runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        }
    }

    /** Saves the company; switching servers drops the previous session and cached data. */
    fun confirm(onDone: () -> Unit) {
        val f = _found.value ?: return
        viewModelScope.launch {
            if (f.baseUrl != ServerConfig.baseUrl) {
                store.clear()
                cache.clear()
            }
            ServerConfig.save(f.baseUrl, f.name, f.logoUrl)
            _found.value = null
            onDone()
        }
    }
}
