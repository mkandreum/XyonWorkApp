package com.xyon.empleados.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.xyon.empleados.data.RegisterRequest
import com.xyon.empleados.ui.theme.*

@Composable
private fun AuthShell(title: String, subtitle: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    SecureScreen()
    val x = Xyon.colors
    BlueField {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val minPanel = maxHeight * 0.66f
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { shown = true }
                Column(Modifier.padding(horizontal = 24.dp).heightIn(min = 200.dp), verticalArrangement = Arrangement.Bottom) {
                    if (onBack != null) {
                        Box(Modifier.padding(top = 12.dp).size(46.dp).clip(CircleShape).background(Color.White).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = InkBlack)
                        }
                        Spacer(Modifier.height(24.dp))
                    } else Spacer(Modifier.height(48.dp))
                    AnimatedVisibility(shown, enter = fadeIn(tween(500)) + slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)) { it / 2 }) {
                        Column {
                            Text(title, style = MaterialTheme.typography.displayMedium, color = Color.White)
                            Text(subtitle, color = Color.White.copy(alpha = .85f), modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
                        }
                    }
                }
                AnimatedVisibility(shown, enter = fadeIn(tween(500, 150)) + slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)) { it / 3 }) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = minPanel).clip(RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp))
                            .background(x.bg).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp), content = content,
                    )
                }
            }
        }
    }
}

@Composable
fun LoginScreen(vm: SessionViewModel, onRegister: () -> Unit, onForgot: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    AuthShell("Bienvenido", "Accede a tu portal del empleado", null) {
        XField(email, { email = it }, "Email", keyboard = KeyboardType.Email, leading = Icons.Filled.Email)
        XField(pass, { pass = it }, "Contraseña", password = true, leading = Icons.Filled.Lock)
        GradientButton("Iniciar sesión", { vm.doLogin(email, pass) }, Modifier.fillMaxWidth(), enabled = email.isNotBlank() && pass.length >= 8, loading = busy)
        TextButton(onForgot, Modifier.align(Alignment.CenterHorizontally)) { Text("¿Olvidaste tu contraseña?") }
        HorizontalDivider(color = Xyon.colors.outline.copy(alpha = .3f))
        TextButton(onRegister, Modifier.align(Alignment.CenterHorizontally)) { Text("¿Primera vez? Regístrate con tu código de invitación") }
    }
}

@Composable
fun RegisterScreen(vm: SessionViewModel, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var dept by remember { mutableStateOf("") }
    var pos by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    val strong = pass.length >= 12 && pass.any { it.isUpperCase() } && pass.any { it.isLowerCase() } && pass.any { it.isDigit() } && pass.any { it in "@$!%*?&" }
    AuthShell("Crear cuenta", "Necesitas un código de invitación", onBack) {
        XField(code, { code = it }, "Código de invitación", leading = Icons.Filled.VpnKey)
        XField(name, { name = it }, "Nombre completo", leading = Icons.Filled.Person)
        XField(email, { email = it }, "Email", keyboard = KeyboardType.Email, leading = Icons.Filled.Email)
        XField(dept, { dept = it }, "Departamento (opcional)")
        XField(pos, { pos = it }, "Puesto (opcional)")
        XField(pass, { pass = it }, "Contraseña", password = true, leading = Icons.Filled.Lock,
            error = if (pass.isNotEmpty() && !strong) "Mín. 12 caracteres, mayúscula, minúscula, número y símbolo (@\$!%*?&)" else null)
        GradientButton("Registrarme", {
            vm.doRegister(RegisterRequest(name.trim(), email.trim(), pass, dept.ifBlank { null }, pos.ifBlank { null }, code.trim()))
        }, Modifier.fillMaxWidth(), enabled = strong && name.length >= 2 && email.isNotBlank() && code.isNotBlank(), loading = busy)
    }
}

@Composable
fun ForgotScreen(vm: SessionViewModel, onBack: () -> Unit, onHaveToken: () -> Unit) {
    var email by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    AuthShell("Recuperar acceso", "Te enviaremos un enlace por email", onBack) {
        XField(email, { email = it }, "Email", keyboard = KeyboardType.Email, leading = Icons.Filled.Email)
        GradientButton("Enviar enlace", { vm.forgot(email) { onHaveToken() } }, Modifier.fillMaxWidth(), enabled = email.contains("@"), loading = busy)
        TextButton(onHaveToken, Modifier.align(Alignment.CenterHorizontally)) { Text("Ya tengo un código") }
    }
}

@Composable
fun ResetScreen(vm: SessionViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    AuthShell("Nueva contraseña", "Pega el token y email del enlace recibido (…reset-password?token=…&email=…)", onBack) {
        XField(token, { token = it }, "Token", leading = Icons.Filled.VpnKey)
        XField(email, { email = it }, "Email", keyboard = KeyboardType.Email, leading = Icons.Filled.Email)
        XField(pass, { pass = it }, "Nueva contraseña", password = true, leading = Icons.Filled.Lock)
        GradientButton("Cambiar contraseña", { vm.reset(token, email, pass, onDone) }, Modifier.fillMaxWidth(),
            enabled = token.isNotBlank() && email.contains("@") && pass.length >= 12, loading = busy)
    }
}

/** Shown when a token exists and biometric unlock is enabled. */
@Composable
fun LockScreen(vm: SessionViewModel) {
    val act = LocalContext.current as FragmentActivity
    SecureScreen()
    var failed by remember { mutableStateOf(false) }
    fun ask() = promptBiometric(act, "Desbloquea Xyon", { vm.unlock() }, { failed = true })
    LaunchedEffect(Unit) { ask() }
    BlueField {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val t = rememberInfiniteTransition(label = "lock")
            val s by t.animateFloat(1f, 1.08f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
            Icon(Icons.Filled.Fingerprint, null, tint = Lime, modifier = Modifier.size(120.dp).scale(s))
            Spacer(Modifier.height(16.dp))
            Text("Sesión bloqueada", style = MaterialTheme.typography.displayMedium, color = Color.White)
            Text("Usa tu huella, rostro o PIN", color = Color.White.copy(alpha = .85f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            XyonButton("Desbloquear", { ask() }, Modifier.fillMaxWidth(), style = XyonButtonStyle.Lime)
            TextButton({ vm.signOut() }) { Text(if (failed) "Cerrar sesión" else "Usar otra cuenta", color = Color.White) }
        }
    }
}
