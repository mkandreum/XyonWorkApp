package com.xyon.empleados.ui

import com.xyon.empleados.ui.HapticCheckbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
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
import com.xyon.empleados.data.ServerConfig
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
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
                                Modifier.padding(end = 12.dp).size(48.dp).clip(CircleShape).background(Color.White).clickable(role = Role.Button, onClick = onBack),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = InkBlack) }
                        }
                        LogoTile(44.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("XyonWork", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = (-.6).sp)
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
        Text(action, color = x.blue, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(6.dp))
    }
}

/** "Privacidad · Aviso legal": the company's notices, readable before signing in (art. 13 RGPD). */
@Composable
private fun LegalLinks() {
    val ctx = LocalContext.current
    val x = Xyon.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Privacidad", color = x.inkSoft, fontSize = 13.sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { openLegalPage(ctx, "privacidad") }.padding(6.dp))
        Text("·", color = x.inkSoft, fontSize = 13.sp)
        Text("Aviso legal", color = x.inkSoft, fontSize = 13.sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { openLegalPage(ctx, "aviso-legal") }.padding(6.dp))
    }
}

/** "Acme · acme.xyonwork.com — Cambiar": which company this login goes to. */
@Composable
private fun CompanyRow(onChange: () -> Unit) {
    val x = Xyon.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(x.card).clickable(role = Role.Button, onClick = onChange).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Business, null, tint = x.inkSoft)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(ServerConfig.companyName ?: "Empresa", color = x.ink, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(ServerConfig.host, color = x.inkSoft, fontSize = 13.sp, maxLines = 1)
        }
        Text("Cambiar", color = x.blue, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun LoginScreen(vm: SessionViewModel, onRegister: () -> Unit, onForgot: () -> Unit, onChangeCompany: () -> Unit = {}) {
    val x = Xyon.colors
    val remembered by vm.rememberedEmail.collectAsState()
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(false) }
    LaunchedEffect(remembered) { remembered?.let { if (email.isBlank()) { email = it; remember = true } } }
    val busy by vm.busy.collectAsState()
    val challenge by vm.mfaChallenge.collectAsState()
    if (challenge != null) { MfaCodeStep(vm, busy); return }
    AuthShell(
        "Tu jornada,\nsin fricción.", "Ficha, pide vacaciones y consulta tus nóminas desde un solo sitio.",
        "Hola de nuevo", "Inicia sesión en tu portal del empleado", null,
        footer = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FooterLink("¿No tienes cuenta? ", "Regístrate", onRegister)
                LegalLinks()
            }
        },
    ) {
        CompanyRow(onChangeCompany)
        XField(email, { email = it }, "Correo electrónico", keyboard = KeyboardType.Email, leading = Icons.Filled.Email)
        XField(pass, { pass = it }, "Contraseña", password = true, leading = Icons.Filled.Lock)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox) { remember = !remember }, verticalAlignment = Alignment.CenterVertically) {
                HapticCheckbox(remember, { remember = it }, colors = CheckboxDefaults.colors(checkedColor = x.blue, uncheckedColor = x.outline))
                Text("Recordarme", color = x.ink, fontWeight = FontWeight.SemiBold)
            }
            Text("¿Olvidaste tu contraseña?", color = x.blue, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onForgot).padding(6.dp))
        }
        XyonButton("Iniciar sesión", { vm.doLogin(email, pass, remember) }, Modifier.fillMaxWidth(), enabled = email.isNotBlank() && pass.length >= 8, loading = busy)
    }
}

/** Second login step for accounts with two-step verification. */
@Composable
private fun MfaCodeStep(vm: SessionViewModel, busy: Boolean) {
    var code by remember { mutableStateOf("") }
    AuthShell(
        "Un paso\nmás.", "Tu cuenta tiene verificación en dos pasos.",
        "Código de verificación", "Abre tu app autenticadora o usa un código de recuperación", { vm.cancelMfa() },
    ) {
        XField(code, { code = it.take(20) }, "Código", keyboard = KeyboardType.Number, leading = Icons.Filled.Shield)
        XyonButton("Verificar", { vm.verifyMfa(code) }, Modifier.fillMaxWidth(), enabled = code.trim().length >= 6, loading = busy)
    }
}

@Composable
fun RegisterScreen(vm: SessionViewModel, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    val strong = isStrongPassword(pass)
    AuthShell(
        "Bienvenido\nal equipo.", "Crea tu cuenta con el código que te ha dado tu empresa. RRHH te asignará departamento y puesto.",
        "Crear cuenta", "Únete al portal del empleado", onBack,
        footer = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FooterLink("¿Ya tienes cuenta? ", "Inicia sesión", onBack)
                LegalLinks()
            }
        },
    ) {
        XField(code, { code = it.uppercase() }, "Código de invitación", leading = Icons.Filled.VpnKey)
        XField(name, { name = it }, "Nombre completo", leading = Icons.Filled.Person)
        XField(email, { email = it }, "Correo electrónico", keyboard = KeyboardType.Email, leading = Icons.Filled.Email)
        XField(pass, { pass = it }, "Contraseña", password = true, leading = Icons.Filled.Lock,
            error = if (pass.isNotEmpty() && !strong) PASSWORD_HINT else null)
        // First layer of the privacy notice at the moment of collection (art. 13 RGPD, art. 11 LOPDGDD)
        Text("Tu empresa tratará tus datos para gestionar tu relación laboral y el registro de jornada. Más información en «Privacidad», abajo.",
            color = Xyon.colors.inkSoft, fontSize = 13.sp)
        XyonButton("Crear cuenta", {
            // Department and job title are assigned by HR after sign-up
            vm.doRegister(RegisterRequest(name.trim(), email.trim(), pass, invitationCode = code.trim()))
        }, Modifier.fillMaxWidth(), enabled = strong && name.length >= 2 && email.isNotBlank() && code.isNotBlank(), loading = busy)
    }
}

@Composable
fun ForgotScreen(vm: SessionViewModel, onBack: () -> Unit, onHaveToken: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    // Correo al que ya se pidió el enlace: pasa a "Revisa tu correo" en vez de pedir el código
    var sentTo by rememberSaveable { mutableStateOf<String?>(null) }
    val busy by vm.busy.collectAsState()
    val ctx = LocalContext.current
    val x = Xyon.colors
    val sent = sentTo
    if (sent == null) {
        AuthShell(
            "¿Sin\ncontraseña?", "Te enviamos un enlace para crear una nueva en menos de un minuto.",
            "Recuperar acceso", "Escribe el email de tu cuenta.", onBack,
            footer = { FooterLink("¿Ya tienes el enlace? ", "Introducir código", onHaveToken) },
        ) {
            XField(email, { email = it }, "Correo electrónico", keyboard = KeyboardType.Email, leading = Icons.Filled.Email)
            XyonButton("Enviar enlace", { vm.forgot(email) { sentTo = email.trim() } }, Modifier.fillMaxWidth(), enabled = email.contains("@"), loading = busy)
        }
    } else {
        AuthShell(
            "Revisa\ntu correo.", "Si $sent tiene cuenta, te llegará un enlace en unos minutos.",
            "Correo enviado", "Abre el enlace del mensaje desde este móvil para elegir tu nueva contraseña.", onBack,
            footer = { FooterLink("¿Prefieres pegar el código? ", "Introducir código", onHaveToken) },
        ) {
            Icon(Icons.Filled.Email, null, tint = x.blue, modifier = Modifier.size(44.dp).align(Alignment.CenterHorizontally))
            Text(
                "Mira también en Spam o Promociones. El enlace caduca en 1 hora. Si ya pediste uno hace poco, usa ese: no enviamos otro hasta pasados 10 minutos.",
                color = x.inkSoft, textAlign = TextAlign.Center,
            )
            XyonButton("Abrir correo", {
                val intent = android.content.Intent.makeMainSelectorActivity(android.content.Intent.ACTION_MAIN, android.content.Intent.CATEGORY_APP_EMAIL)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                // Sin app de correo instalada no pasa nada: el usuario abre la suya
                runCatching { ctx.startActivity(intent) }
            }, Modifier.fillMaxWidth(), icon = Icons.Filled.Email)
            Text(
                "Usar otro correo",
                color = x.blue, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { sentTo = null }.padding(10.dp),
            )
        }
    }
}

@Composable
fun ResetScreen(
    vm: SessionViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    initialToken: String = "",
    initialEmail: String = "",
) {
    var token by remember(initialToken) { mutableStateOf(initialToken) }
    var email by remember(initialEmail) { mutableStateOf(initialEmail) }
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

/** The Xyon app icon (navy tile + mark), for brand rows inside the app. */
@Composable
fun LogoTile(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.xyon.empleados.R.drawable.ic_launcher_bg), null, Modifier.matchParentSize())
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.xyon.empleados.R.drawable.ic_launcher_fg), "Xyon", Modifier.matchParentSize().scale(1.45f))
    }
}

/**
 * "Conecta con tu empresa": code (acme), full address or QR. The server is checked and the company
 * it reports is shown for confirmation before any credentials are sent (ADR-003, ADR-015).
 */
@Composable
fun ConnectScreen(onDone: () -> Unit, onBack: (() -> Unit)?, vm: ConnectViewModel = hiltViewModel()) {
    val x = Xyon.colors
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val found by vm.found.collectAsState()

    AuthShell(
        "Conecta con\ntu empresa.", "Usa el código que te ha dado tu empresa, su dirección o escanea su QR.",
        "¿Cuál es tu empresa?", "Ejemplo: acme o acme.xyonwork.com", onBack,
    ) {
        XField(input, { input = it; vm.clearError() }, "Empresa", keyboard = KeyboardType.Uri, leading = Icons.Filled.Business, error = error)
        XyonButton("Continuar", { vm.check(input) }, Modifier.fillMaxWidth(), enabled = input.isNotBlank(), loading = busy)
        XyonButton("Escanear QR", {
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(
                ctx,
                com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build(),
            ).startScan()
                .addOnSuccessListener { code -> code.rawValue?.let { input = it; vm.check(it) } }
        }, Modifier.fillMaxWidth(), style = XyonButtonStyle.Light, icon = Icons.Filled.QrCodeScanner, showArrow = false)
        if (!ServerConfig.isConfigured) {
            Text("Continuar con ${ServerConfig.host}", color = x.blue, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { input = ServerConfig.host; vm.check(ServerConfig.host) }.padding(8.dp))
        }
    }

    found?.let { f ->
        AlertDialog({ vm.dismiss() }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("¿Es tu empresa?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (f.logoUrl != null) {
                            AsyncImage(f.logoUrl, null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color.White))
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            Text(f.name ?: f.host, fontWeight = FontWeight.Black, fontSize = 18.sp, color = x.ink)
                            Text(f.host, color = x.inkSoft)
                        }
                    }
                    if (f.ownDomain) {
                        Text("Esta dirección no es de xyonwork.com. Continúa solo si te la ha dado tu empresa: tus credenciales se enviarán a ${f.host}.",
                            color = x.ink, fontSize = 14.sp)
                    }
                }
            },
            confirmButton = { TextButton({ vm.confirm(onDone) }) { Text("Sí, conectar") } },
            dismissButton = { TextButton({ vm.dismiss() }) { Text("No") } })
    }
}

