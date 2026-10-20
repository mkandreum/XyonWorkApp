package com.xyon.empleados.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

private const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

/**
 * Tracks when the app itself sends the user outside (PIN screen of a biometric prompt, file pickers,
 * share sheets…). Those trips must not trigger the "lock on leaving the app" rule.
 */
object AppLock {
    @Volatile var externalActive = false
        private set

    fun beginExternal() { externalActive = true }
    fun endExternal() { externalActive = false }
}

fun canAuthenticate(a: FragmentActivity) =
    BiometricManager.from(a).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

fun promptBiometric(a: FragmentActivity, title: String, onOk: () -> Unit, onFail: (String) -> Unit = {}, subtitle: String = "XyonWork") {
    AppLock.beginExternal()   // the PIN fallback opens a separate system screen
    val prompt = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { AppLock.endExternal(); onOk() }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { AppLock.endExternal(); onFail(errString.toString()) }
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTH).build()
    )
}

/** Route requested from outside the UI (launcher shortcuts): "home", "payroll", "requests"… */
object DeepLinks {
    /** Enlace de reset del correo, ya validado y listo para prellenar la pantalla. */
    data class ResetRequest(val token: String, val email: String)

    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val pendingReset = kotlinx.coroutines.flow.MutableStateFlow<ResetRequest?>(null)

    /**
     * Extrae token y email de `https://<dominio>/reset-password?token=…&email=…`.
     * Sin android.net.Uri (puro) para poder cubrirlo con un test JVM. Solo https y la
     * ruta exacta: cualquier otra cosa se ignora y el enlace se abre en el navegador.
     */
    fun parseReset(uri: String?): ResetRequest? {
        val parsed = runCatching { java.net.URI(uri ?: return null) }.getOrNull() ?: return null
        if (parsed.scheme != "https") return null
        if ((parsed.path ?: "").trimEnd('/') != "/reset-password") return null
        val params = (parsed.rawQuery ?: "").split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else decode(part.substring(0, i)) to decode(part.substring(i + 1))
        }.toMap()
        val token = params["token"]?.trim().orEmpty()
        val email = params["email"]?.trim().orEmpty()
        if (token.isEmpty() || !email.contains('@')) return null
        return ResetRequest(token, email)
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    fun from(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        parseReset(uri.toString())?.let { pendingReset.value = it; return }
        if (uri.scheme == "xyon" && uri.host == "open") uri.lastPathSegment?.let { pending.value = it }
    }
}
