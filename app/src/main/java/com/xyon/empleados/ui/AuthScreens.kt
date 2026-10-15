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
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.xyon.empleados.data.RegisterRequest
import com.xyon.empleados.ui.theme.*

/** Same rule as the backend: 12+ chars with upper, lower, digit and any symbol. */
internal fun isStrongPassword(p: String) =
    p.length >= 12 && p.any { it.isUpperCase() } && p.any { it.isLowerCase() } && p.any { it.isDigit() } && p.any { !it.isLetterOrDigit() }

internal const val PASSWORD_HINT = "Mín. 12 caracteres, con mayúsculas, minúsculas, un número y un símbolo"

/**
 * Auth layout, matching the web login: brand row and a huge two-line headline on the animated
 * blue field, then a paper panel rising from the bottom with the form.
 */
@Composable
private fun AuthShell(
    headline: String,
    tagline: String?,
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SecureScreen()
    val x = Xyon.colors
    BlueField {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val minPanel = maxHeight * 0.6f
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { shown = true }

                Column(Modifier.padding(horizontal = 24.dp)) {
                    // Brand row (or back + brand on secondary screens)
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (onBack != null) {
                            Box(
                                Modifier.padding(end = 12.dp).size(44.dp).clip(CircleShape).background(Color.White).clickable(onClick = onBack),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = InkBlack) }
                        }
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Lime), contentAlignment = Alignment.Center) {
                            Text("X", color = InkBlack, fontWeight = FontWeight.Black, fontSize = 20.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("XyonEmpleados", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = (-.6).sp)
                    }

                    AnimatedVisibility(shown, enter = fadeIn(tween(500)) + slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)) { it / 2 }) {
                        Column(Modifier.padding(top = 40.dp, bottom = 28.dp)) {
                            Text(headline, color = Color.White, fontWeight = FontWeight.Black, fontSize = 52.sp, lineHeight = 50.sp, letterSpacing = (-2.6).sp)
                            tagline?.let {
                                Text(it, color = Color.White.copy(alpha = .85f), fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp))
                            }
                        }
                    }
                }

                AnimatedVisibility(shown, enter = fadeIn(tween(500, 150)) + slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)) { it / 3 }) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = minPanel).clip(RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp))
                            .background(x.bg).padding(horizontal = 24.dp, vertical = 30.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(title, style = MaterialTheme.typography.headlineLarge, color = x.ink)
                        subtitle?.let { Text(it, color = x.inkSoft, modifier = Modifier.padding(bottom = 8.dp)) }
                        content()
                        footer?.let { Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) { it() } }
                    }
                }
            }
        }
    }
}

@Composable
private fun FooterLink(prefix: String, action: String, onClick: () -> Unit) {
    val x = Xyon.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(prefix, color = x.inkSoft)
        Text(action, color = x.blue, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(6.dp))
    }
}

@Composable
fun LoginScreen(vm: SessionViewModel, onRegister: () -> Unit, onForgot: () -> Unit) {
    val x = Xyon.colors
    val remembered by vm.rememberedEmail.collectAsState()
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(false) }
    LaunchedEffect(remembered) { remembered?.let { if (email.isBlank()) { email = it; remember = true } } }
    val busy by vm.busy.collectAsState()
    AuthShell(
        "Tu jornada,\nsin fricción.", "Ficha, pide vacaciones y consulta tus nóminas desde un solo sitio.",
        "Hola de nuevo", "Inicia sesión en tu portal del empleado", null,
        footer = { FooterLink("¿No tienes cuenta? ", "Regístrate", onRegister) },
    ) {
        XField(email, { email = it }, "Correo electrónico", keyboard = KeyboardType.Email, leading = Icons.Filled.Email)
        XField(pass, { pass = it }, "Contraseña", password = true, leading = Icons.Filled.Lock)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { remember = !remember }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(remember, { remember = it }, colors = CheckboxDefaults.colors(checkedColor = x.blue, uncheckedColor = x.outline))
                Text("Recordarme", color = x.ink, fontWeight = FontWeight.SemiBold)
            }
            Text("¿Olvidaste tu contraseña?", color = x.blue, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onForgot).padding(6.dp))
        }
        XyonButton("Iniciar sesión", { vm.doLogin(email, pass, remember) }, Modifier.fillMaxWidth(), enabled = email.isNotBlank() && pass.length >= 8, loading = busy)
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
    val strong = isStrongPassword(pass)
    AuthShell(
        "Bienvenido\nal equipo.", "Crea tu cuenta con el código que te ha dado tu empresa.",
        "Crear cuenta", "Únete al portal del empleado", onBack,
        footer = { FooterLink("¿Ya tienes cuenta? ", "Inicia sesión", onBack) },
    ) {
        XField(code, { code = it.uppercase() }, "Código de invitación", leading = Icons.Filled.VpnKey)
        XField(name, { name = it }, "Nombre completo", leading = Icons.Filled.Person)
        XField(email, { email = it }, "Correo electrónico", keyboard = KeyboardType.Email, leading = Icons.Filled.Email)
        XField(dept, { dept = it }, "Departamento (opcional)", leading = Icons.Filled.Apartment)
        XField(pos, { pos = it }, "Cargo (opcional)", leading = Icons.Filled.Work)
        XField(pass, { pass = it }, "Contraseña", password = true, leading = Icons.Filled.Lock,
            error = if (pass.isNotEmpty() && !strong) PASSWORD_HINT else null)
        XyonButton("Crear cuenta", {
            vm.doRegister(RegisterRequest(name.trim(), email.trim(), pass, dept.ifBlank { null }, pos.ifBlank { null }, code.trim()))
        }, Modifier.fillMaxWidth(), enabled = strong && name.length >= 2 && email.isNotBlank() && code.isNotBlank(), loading = busy)
    }
}

@Composable
fun ForgotScreen(vm: SessionViewModel, onBack: () -> Unit, onHaveToken: () -> Unit) {
    var email by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    AuthShell(
        "¿Sin\ncontraseña?", "Te enviamos un enlace para crear una nueva en menos de un minuto.",
        "Recuperar acceso", "Escribe el email de tu cuenta.", onBack,
        footer = { FooterLink("¿Ya tienes el enlace? ", "Introducir código", onHaveToken) },
    ) {
        XField(email, { email = it }, "Correo electrónico", keyboard = KeyboardType.Email, leading = Icons.Filled.Email)
        XyonButton("Enviar enlace", { vm.forgot(email) { onHaveToken() } }, Modifier.fillMaxWidth(), enabled = email.contains("@"), loading = busy)
    }
}

@Composable
fun ResetScreen(vm: SessionViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    val strong = isStrongPassword(pass)
    AuthShell(
        "Empieza\nde nuevo.", "Elige una contraseña que no uses en otros sitios.",
        "Nueva contraseña", "Pega el código y el email del enlace que te hemos enviado.", onBack,
    ) {
        XField(token, { token = it }, "Código del enlace", leading = Icons.Filled.VpnKey)
        XField(email, { email = it }, "Correo electrónico", keyboard = KeyboardType.Email, leading = Icons.Filled.Email)
        XField(pass, { pass = it }, "Nueva contraseña", password = true, leading = Icons.Filled.Lock,
            error = if (pass.isNotEmpty() && !strong) PASSWORD_HINT else null)
        XyonButton("Cambiar contraseña", { vm.reset(token, email, pass, onDone) }, Modifier.fillMaxWidth(),
            enabled = token.isNotBlank() && email.contains("@") && strong, loading = busy)
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
    // Back must never reveal the session underneath: send the app to the background instead.
    androidx.activity.compose.BackHandler { act.moveTaskToBack(true) }
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
