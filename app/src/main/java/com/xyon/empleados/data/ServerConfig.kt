package com.xyon.empleados.data

import android.content.Context
import android.content.SharedPreferences
import com.xyon.empleados.BuildConfig

/**
 * Turns what the user types or scans into the company's API base URL.
 *
 *  - "acme"                         → https://acme.xyonwork.com/api/
 *  - "acme.xyonwork.com"            → https://acme.xyonwork.com/api/
 *  - "https://rrhh.acme.com/login"  → https://rrhh.acme.com/api/   (own domain)
 *
 * Always HTTPS; rejects ports, credentials, IPs and reserved subdomains. Pure Kotlin (no Android
 * classes) so it is covered by unit tests.
 */
object ServerAddress {
    const val ROOT_DOMAIN = "xyonwork.com"
    val RESERVED = setOf("app", "api", "admin", "www", "status", "soporte", "mail", "cdn", "assets")

    sealed class Result {
        /** [ownDomain] = outside *.xyonwork.com: the app asks for an extra confirmation. */
        data class Ok(val baseUrl: String, val host: String, val ownDomain: Boolean) : Result()
        data class Error(val message: String) : Result()
    }

    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    fun normalize(raw: String): Result {
        var s = raw.trim().lowercase()
        if (s.isEmpty()) return Result.Error("Escribe el código o la dirección de tu empresa")
        if (s.startsWith("http://")) return Result.Error("Solo se permiten conexiones seguras (https)")
        s = s.removePrefix("https://")
        if (s.contains("://")) return Result.Error("Dirección no válida")
        // Keep only the host: drop path, query and fragment (a QR may carry …/login or …/conectar)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if (s.contains('@')) return Result.Error("Dirección no válida")
        if (s.contains(':')) return Result.Error("No se admiten puertos en la dirección")
        if (s.isEmpty() || s.any { it.isWhitespace() }) return Result.Error("Dirección no válida")
        s = s.trimEnd('.')

        val host = if ('.' in s) s else "$s.$ROOT_DOMAIN"
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { !LABEL.matches(it) }) return Result.Error("Dirección no válida")
        if (labels.all { l -> l.all { it.isDigit() } }) return Result.Error("Usa el dominio de tu empresa, no una IP")
        if (host.length > 253) return Result.Error("Dirección demasiado larga")

        val underRoot = host.endsWith(".$ROOT_DOMAIN")
        if (underRoot) {
            val sub = host.removeSuffix(".$ROOT_DOMAIN")
            if (sub.contains('.')) return Result.Error("Dirección no válida")
            if (sub in RESERVED) return Result.Error("Ese código no corresponde a ninguna empresa")
        }
        return Result.Ok("https://$host/api/", host, ownDomain = !underRoot)
    }
}

/**
 * The company server this install talks to. Plain SharedPreferences (it is not a secret and must be
 * readable synchronously by the OkHttp interceptor). Not configured = the build's default server,
 * so existing installs keep working without doing anything.
 */
object ServerConfig {
    private const val PREFS = "server_config"
    private const val K_BASE = "base_url"
    private const val K_NAME = "company_name"
    private const val K_LOGO = "logo_url"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** True once the user picked a company (code, URL or QR). */
    val isConfigured: Boolean get() = ::prefs.isInitialized && prefs.getString(K_BASE, null) != null

    /** Current API base, always ending in "/api/". */
    val baseUrl: String get() = (if (::prefs.isInitialized) prefs.getString(K_BASE, null) else null) ?: BuildConfig.API_BASE_URL

    /** Origin for relative file URLs ("/uploads/…"): base without "api/". */
    val origin: String get() = baseUrl.removeSuffix("api/")

    val host: String get() = baseUrl.removePrefix("https://").substringBefore('/')

    val companyName: String? get() = if (::prefs.isInitialized) prefs.getString(K_NAME, null) else null
    val logoUrl: String? get() = if (::prefs.isInitialized) prefs.getString(K_LOGO, null) else null

    fun save(baseUrl: String, companyName: String?, logoUrl: String?) {
        require(baseUrl.startsWith("https://") && baseUrl.endsWith("/api/")) { "Base URL must be https and end in /api/" }
        prefs.edit().putString(K_BASE, baseUrl).putString(K_NAME, companyName).putString(K_LOGO, logoUrl).apply()
    }

    /** Relative server paths ("/uploads/…") → absolute HTTPS URL on the current server. */
    fun absolute(u: String?): String? = u?.let { if (it.startsWith("http")) it else origin + it.removePrefix("/") }
        ?.takeIf { it.startsWith("https://") }
}
