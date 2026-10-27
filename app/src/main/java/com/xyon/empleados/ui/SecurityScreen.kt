package com.xyon.empleados.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xyon.empleados.data.*
import com.xyon.empleados.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Two-step verification (TOTP) from the app: same server endpoints as the web. */
@HiltViewModel
class SecurityViewModel @Inject constructor(private val api: Api) : ViewModel() {
    private val _status = MutableStateFlow<MfaStatus?>(null)
    val status = _status.asStateFlow()
    private val _setup = MutableStateFlow<MfaSetupResponse?>(null)
    val setup = _setup.asStateFlow()
    private val _codes = MutableStateFlow<List<String>?>(null)
    val recoveryCodes = _codes.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _msg = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val msg = _msg.asSharedFlow()

    init { load() }
    fun load() = viewModelScope.launch { safeCall { api.mfaStatus() }.onSuccess { _status.value = it } }

    fun start() = action { _setup.value = api.mfaSetup() }

    private var enabledResponse: AuthResponse? = null

    fun enable(code: String, session: SessionViewModel) = action {
        val r = api.mfaEnable(MfaCodeRequest(code.trim()))
        // Token now (the old one is revoked); the user later, so the admin gate does not close
        // this screen before the one-time recovery codes are seen
        session.renewToken(r)
        enabledResponse = r
        _codes.value = r.recoveryCodes
        _setup.value = null
        load()
    }

    fun disable(password: String, code: String, session: SessionViewModel) = action {
        session.renewSession(api.mfaDisable(MfaDisableRequest(password, code.trim())))
        _msg.tryEmit("Verificación en dos pasos desactivada")
        load()
    }

    /** GDPR access/portability: all my data as JSON in Downloads. */
    fun exportMyData(ctx: Context) = viewModelScope.launch {
        _busy.value = true
        safeCall { saveToDownloads(ctx, api.myDataExport().bytes(), "mis-datos-${java.time.LocalDate.now()}.json", "application/json") }
            .onSuccess { _msg.tryEmit("Guardado en $it") }.onFailure { _msg.tryEmit(it.userMessage()) }
        _busy.value = false
    }

    fun doneWithCodes(session: SessionViewModel) {
        _codes.value = null
        enabledResponse?.let { session.renewSession(it) }
        enabledResponse = null
    }
    fun cancelSetup() { _setup.value = null }

    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        safeCall { block() }.onFailure { e ->
            val (code, message) = e.apiError()
            _msg.tryEmit(if (code == "MFA_INVALID") "Código incorrecto. Comprueba la hora del móvil." else message)
        }
        _busy.value = false
    }
}

@Composable
fun SecurityScreen(session: SessionViewModel, onBack: () -> Unit, snack: (String) -> Unit, vm: SecurityViewModel = hiltViewModel()) {
    SecureScreen()
    val x = Xyon.colors
    val ctx = LocalContext.current
    val status by vm.status.collectAsState()
    val setup by vm.setup.collectAsState()
    val codes by vm.recoveryCodes.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { vm.msg.collect { snack(it) } }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Seguridad", "Verificación en dos pasos y sesiones", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                val s = status
                Row {
                    Icon(if (s?.enabled == true) Icons.Filled.VerifiedUser else Icons.Filled.Shield, null, tint = x.ink)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Verificación en dos pasos ${if (s?.enabled == true) "activada" else "desactivada"}", fontWeight = FontWeight.Black, color = x.ink)
                        Text(
                            when {
                                s == null -> "Cargando…"
                                s.enabled -> "Te quedan ${s.recoveryCodesLeft} códigos de recuperación."
                                s.required -> "Obligatoria para administradores."
                                else -> "Recomendada: además de la contraseña se pedirá un código de tu móvil."
                            }, color = x.inkSoft,
                        )
                    }
                }
            }

            codes?.let { list ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("Guarda tus códigos de recuperación", fontWeight = FontWeight.Black, color = x.ink)
                    Text("Sirven si pierdes el móvil. Cada uno funciona una vez y no se volverán a mostrar.", color = x.inkSoft, modifier = Modifier.padding(vertical = 8.dp))
                    list.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth()) { row.forEach { Text(it, fontFamily = FontFamily.Monospace, color = x.ink, modifier = Modifier.weight(1f).padding(4.dp)) } }
                    }
                    Spacer(Modifier.height(10.dp))
                    XyonButton("Copiar", { copy(ctx, list.joinToString("\n")); snack("Copiados") }, Modifier.fillMaxWidth(), style = XyonButtonStyle.Light, icon = Icons.Filled.ContentCopy, showArrow = false)
                    Spacer(Modifier.height(8.dp))
                    XyonButton("Ya los he guardado", { vm.doneWithCodes(session) }, Modifier.fillMaxWidth())
                }
            }

            val st = status
            if (st != null && !st.enabled && codes == null) {
                val su = setup
                if (su == null) {
                    XyonButton("Activar verificación en dos pasos", { vm.start() }, Modifier.fillMaxWidth(), loading = busy)
                } else GlassCard(Modifier.fillMaxWidth()) {
                    Text("1. Añade la cuenta a tu app autenticadora", fontWeight = FontWeight.Bold, color = x.ink)
                    Spacer(Modifier.height(8.dp))
                    XyonButton("Abrir app autenticadora", {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(su.otpauthUri))) }
                            .onFailure { snack("No hay app autenticadora instalada. Usa la clave manual.") }
                    }, Modifier.fillMaxWidth(), style = XyonButtonStyle.Light, icon = Icons.AutoMirrored.Filled.OpenInNew, showArrow = false)
                    Text("Clave manual:", color = x.inkSoft, modifier = Modifier.padding(top = 10.dp))
                    Text(su.secret.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, color = x.ink)
                    TextButton({ copy(ctx, su.secret); snack("Clave copiada") }) { Text("Copiar clave") }
                    Text("2. Escribe el código de 6 dígitos", fontWeight = FontWeight.Bold, color = x.ink, modifier = Modifier.padding(top = 8.dp))
                    XField(code, { code = it.filter(Char::isDigit).take(6) }, "Código", keyboard = KeyboardType.Number)
                    Spacer(Modifier.height(8.dp))
                    XyonButton("Activar", { vm.enable(code, session); code = "" }, Modifier.fillMaxWidth(), enabled = code.length == 6, loading = busy)
                    TextButton({ vm.cancelSetup() }) { Text("Cancelar") }
                }
            }

            if (st != null && st.enabled && !st.required && codes == null) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("Desactivar", fontWeight = FontWeight.Bold, color = x.ink)
                    XField(password, { password = it }, "Contraseña", password = true)
                    XField(code, { code = it.take(20) }, "Código o código de recuperación")
                    Spacer(Modifier.height(8.dp))
                    XyonButton("Desactivar", { vm.disable(password, code, session); password = ""; code = "" }, Modifier.fillMaxWidth(),
                        enabled = password.isNotEmpty() && code.isNotBlank(), loading = busy, style = XyonButtonStyle.Light)
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("Tus datos", fontWeight = FontWeight.Bold, color = x.ink)
                Text("Descarga una copia de tus datos personales (perfil, fichajes, ausencias, nóminas…).", color = x.inkSoft, modifier = Modifier.padding(vertical = 8.dp))
                XyonButton("Descargar mis datos", { vm.exportMyData(ctx) }, Modifier.fillMaxWidth(), style = XyonButtonStyle.Light, icon = Icons.Filled.Download, showArrow = false, loading = busy)
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("Información legal", fontWeight = FontWeight.Bold, color = x.ink)
                Text("Cómo trata tu empresa tus datos, quién es el responsable y cómo ejercer tus derechos.", color = x.inkSoft, modifier = Modifier.padding(vertical = 8.dp))
                listOf(
                    "Protección de datos" to "privacidad",
                    "Aviso legal" to "aviso-legal",
                    "Eliminar mi cuenta" to "eliminar-cuenta",
                ).forEach { (label, path) ->
                    TextButton({ openLegalPage(ctx, path) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(label)
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("Sesiones abiertas", fontWeight = FontWeight.Bold, color = x.ink)
                Text("Cierra la sesión en todos tus dispositivos, también en este.", color = x.inkSoft, modifier = Modifier.padding(vertical = 8.dp))
                XyonButton("Cerrar todas las sesiones", { session.logoutAll() }, Modifier.fillMaxWidth(), style = XyonButtonStyle.Light, showArrow = false)
            }
        }
    }
}

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("xyon", text))
}
