package com.xyon.empleados.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.xyon.empleados.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = false
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton fun json(): Json = AppJson

    @Provides @Singleton
    fun okHttp(session: SessionStore): OkHttpClient {
        require(BuildConfig.API_BASE_URL.startsWith("https://")) { "API_BASE_URL must be HTTPS" }
        // Intentionally NO logging interceptor: requests carry credentials and bearer tokens.
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                var req = chain.request()
                // Retrofit is built once with the build's base URL; requests go to the company server
                // chosen on this device (code, URL or QR), see ServerConfig.
                val placeholder = BuildConfig.API_BASE_URL
                val current = ServerConfig.baseUrl
                val url = req.url.toString()
                // Requests to a candidate server (company check) go exactly where they say, without token
                val direct = req.header(DIRECT_HEADER) != null
                if (direct) req = req.newBuilder().removeHeader(DIRECT_HEADER).build()
                else if (current != placeholder && url.startsWith(placeholder)) {
                    req = req.newBuilder().url(current + url.removePrefix(placeholder)).build()
                }
                val b = req.newBuilder()
                // Only attach the token to the current company server
                if (!direct && req.url.host == current.toHttpUrl().host) {
                    session.token?.let { b.header("Authorization", "Bearer $it") }
                }
                val resp = chain.proceed(b.build())
                val isAuthCall = req.url.encodedPath.contains("/auth/")
                if ((resp.code == 401 || resp.code == 403) && !isAuthCall && !direct && session.token != null) {
                    val body = runCatching { resp.peekBody(4096).string() }.getOrDefault("")
                    when {
                        body.contains("ACCOUNT_DISABLED") -> session.accountDisabled.tryEmit(Unit)
                        resp.code == 401 || body.contains("TOKEN_EXPIRED") || body.contains("INVALID_TOKEN") ->
                            session.sessionExpired.tryEmit(Unit)
                    }
                }
                resp
            }
            // Pinning-ready: add .certificatePinner(...) or enable <pin-set> in network_security_config.xml
            .build()
    }

    @Provides @Singleton
    fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides @Singleton fun api(r: Retrofit): Api = r.create(Api::class.java)
}

/** Human-readable (Spanish) error from any throwable; server `error` field is surfaced when present. */
fun Throwable.userMessage(): String = when (this) {
    is HttpException -> {
        val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()
        val parsed = raw?.let { runCatching { AppJson.decodeFromString<MessageResponse>(it) }.getOrNull() }
        if (parsed?.code == "ACCOUNT_DISABLED") ACCOUNT_DISABLED_MSG
        else parsed?.error ?: when (code()) {
            401 -> "Credenciales o sesión no válidas"
            403 -> "No tienes permiso para esta acción"
            429 -> "Demasiados intentos. Inténtalo más tarde"
            in 500..599 -> "Error del servidor"
            else -> "Error ${code()}"
        }
    }
    is IOException -> "Sin conexión. Mostrando datos guardados."
    else -> message ?: "Error inesperado"
}

/** Server error `code` (e.g. WRONG_PASSWORD) and readable message, reading the error body only once. */
fun Throwable.apiError(): Pair<String?, String> {
    if (this !is HttpException) return null to userMessage()
    val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()
    val parsed = raw?.let { runCatching { AppJson.decodeFromString<MessageResponse>(it) }.getOrNull() }
    return parsed?.code to (parsed?.error ?: userMessage())
}

/** Internal marker: send the request as-is (no server rewrite, no token). Never leaves the device. */
const val DIRECT_HEADER = "X-Xyon-Direct"

const val ACCOUNT_DISABLED_MSG = "Tu cuenta está desactivada. Contacta con administración."

/** Multipart file part whose filename carries an extension derived from the mime type. */
fun uploadFilePart(field: String, bytes: ByteArray, mime: String, baseName: String = "upload"): okhttp3.MultipartBody.Part {
    val m = mime.lowercase()
    val ext = when {
        m.contains("png") -> "png"; m.contains("gif") -> "gif"; m.contains("webp") -> "webp"
        m.contains("pdf") -> "pdf"; else -> "jpg"
    }
    val type = runCatching { mime.toMediaType() }.getOrNull() ?: "application/octet-stream".toMediaType()
    return okhttp3.MultipartBody.Part.createFormData(field, "$baseName.$ext", bytes.toRequestBody(type))
}

val Throwable.isOffline get() = this is IOException

suspend fun <T> safeCall(block: suspend () -> T): Result<T> =
    try { Result.success(block()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { Result.failure(e) }
