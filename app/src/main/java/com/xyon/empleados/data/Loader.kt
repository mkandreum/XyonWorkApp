package com.xyon.empleados.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import javax.inject.Inject
import javax.inject.Singleton

data class LoadState<T>(
    val data: T? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val offline: Boolean = false,
)

/** Cache-first loader: emits cached data instantly, then refreshes from network. Powers pull-to-refresh. */
class Loader<T>(
    private val scope: CoroutineScope,
    private val cache: CacheStore,
    private val json: Json,
    private val key: String,
    private val ser: KSerializer<T>,
    private val fetch: suspend () -> T,
    private val online: StateFlow<Boolean>? = null,
) {
    private val _state = MutableStateFlow(LoadState<T>())
    val state: StateFlow<LoadState<T>> = _state.asStateFlow()

    init {
        scope.launch {
            cache.get(key)?.let { raw ->
                runCatching { json.decodeFromString(ser, raw) }.getOrNull()?.let { d ->
                    _state.update { it.copy(data = d, loading = false) }
                }
            }
            load()
        }
        // Back online: refresh whatever was showing cached data or an error
        online?.let { flow ->
            scope.launch {
                flow.drop(1).distinctUntilChanged().collect { up ->
                    val st = _state.value
                    if (up && (st.offline || st.error != null)) refresh()
                }
            }
        }
    }

    fun refresh() { scope.launch { _state.update { it.copy(refreshing = true) }; load() } }

    private suspend fun load() {
        // No network: show the cache right away instead of waiting for the connection timeout
        if (online?.value == false) {
            _state.update { it.copy(loading = false, refreshing = false, offline = it.data != null,
                error = if (it.data == null) "Sin conexión. Se cargará al recuperar la red." else null) }
            return
        }
        safeCall(fetch).onSuccess { d ->
            _state.value = LoadState(data = d, loading = false)
            cache.put(key, json.encodeToString(ser, d))
        }.onFailure { e ->
            _state.update { it.copy(loading = false, refreshing = false, error = if (it.data == null || !e.isOffline) e.userMessage() else null, offline = e.isOffline && it.data != null) }
        }
    }

    /** Optimistic local override (e.g. after a mutation) without hitting the network. */
    fun set(d: T) { _state.update { it.copy(data = d) } }
}

@Singleton
class LoaderFactory @Inject constructor(private val cache: CacheStore, private val json: Json, private val network: NetworkMonitor) {
    fun <T> create(scope: CoroutineScope, key: String, ser: KSerializer<T>, fetch: suspend () -> T) =
        Loader(scope, cache, json, key, ser, fetch, network.online)

    inline fun <reified T> create(scope: CoroutineScope, key: String, noinline fetch: suspend () -> T) =
        create(scope, key, serializer<T>(), fetch)
}
