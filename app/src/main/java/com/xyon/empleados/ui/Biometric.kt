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

fun promptBiometric(a: FragmentActivity, title: String, onOk: () -> Unit, onFail: (String) -> Unit = {}, subtitle: String = "Xyon Empleados") {
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
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun from(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "xyon" && uri.host == "open") uri.lastPathSegment?.let { pending.value = it }
    }
}
