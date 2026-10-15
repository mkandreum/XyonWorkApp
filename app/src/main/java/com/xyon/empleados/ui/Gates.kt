package com.xyon.empleados.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.xyon.empleados.ui.theme.*

/**
 * Asks for fingerprint / face / device PIN every time the wrapped screen is opened (e.g. payslips).
 * Devices with no screen lock at all cannot authenticate, so the content is shown directly there.
 */
@Composable
fun BiometricGate(title: String, reason: String, onCancel: () -> Unit, content: @Composable () -> Unit) {
    val act = LocalContext.current as FragmentActivity
    val available = remember { canAuthenticate(act) }
    var unlocked by rememberSaveable { mutableStateOf(!available) }
    var failed by remember { mutableStateOf(false) }
    fun ask() = promptBiometric(act, title, { unlocked = true }, { failed = true }, subtitle = reason)

    LaunchedEffect(Unit) { if (!unlocked) ask() }

    AnimatedContent(unlocked, label = "gate", transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }) { open ->
        if (open) content()
        else {
            SecureScreen()
            BlueField {
                Column(
                    Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    val t = rememberInfiniteTransition(label = "gate")
                    val s by t.animateFloat(1f, 1.08f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
                    Icon(Icons.Filled.Fingerprint, null, tint = Lime, modifier = Modifier.size(110.dp).scale(s))
                    Spacer(Modifier.height(16.dp))
                    Text(title, style = MaterialTheme.typography.displayMedium, color = Color.White, textAlign = TextAlign.Center)
                    Text(reason, color = Color.White.copy(alpha = .85f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(32.dp))
                    XyonButton(if (failed) "Reintentar" else "Desbloquear", { failed = false; ask() }, Modifier.fillMaxWidth(), style = XyonButtonStyle.Lime)
                    TextButton(onCancel) { Text("Volver", color = Color.White) }
                }
            }
        }
    }
}
