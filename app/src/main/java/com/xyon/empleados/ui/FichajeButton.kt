package com.xyon.empleados.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xyon.empleados.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hero clock-in/out: a big abstract blob that morphs (lobes, colour, rotation) between out/in states
 * with springs, haptics and a sparkle burst on success. Meant to sit on a blue field.
 */
@Composable
fun FichajeHero(
    clockedIn: Boolean, since: String?, busy: Boolean, expected: String?,
    successEvents: Flow<Boolean>, onToggle: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) .88f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")
    val morph by animateFloatAsState(if (clockedIn) 1f else 0f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessVeryLow), label = "morph")
    val burst = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        successEvents.collect {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            burst.snapTo(0f); burst.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))
        }
    }
    val t = rememberInfiniteTransition(label = "blob")
    val spin by t.animateFloat(0f, 2f * Math.PI.toFloat(), infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "spin")
    val blobColor by animateColorAsState(if (clockedIn) Coral else Lime, tween(600), label = "c")

    var elapsed by remember { mutableStateOf("") }
    LaunchedEffect(clockedIn, since) {
        while (clockedIn && since != null) {
            val d = runCatching { Duration.between(Instant.parse(since), Instant.now()) }.getOrNull()
            elapsed = d?.let { "%02d:%02d:%02d".format(it.toHours(), it.toMinutesPart(), it.toSecondsPart()) } ?: ""
            delay(1000)
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(270.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2)
                val base = size.minDimension * .36f * press
                drawPath(blobPath(c, base * 1.18f, -spin, 5, .10f, 3, .05f), Color.White.copy(alpha = .18f))
                val p = Path()
                val steps = 90
                for (i in 0..steps) {
                    val a = i.toFloat() / steps * 2f * Math.PI.toFloat()
                    val r = base * (1f + (1f - morph) * .07f * cos(5 * a + spin) + morph * .11f * cos(8 * a - spin * 2) + .03f * sin(3 * a + spin * 3))
                    val x = c.x + r * cos(a); val y = c.y + r * sin(a)
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                drawPath(p, blobColor)
                drawSparkle(Offset(c.x + base * 1.05f, c.y - base * .95f), base * .2f, Color.White, spin * 57f)
                drawCircle(Color.White, base * .06f, Offset(c.x - base * 1.1f, c.y + base * .7f))
                val b = burst.value
                if (b > 0f && b < 1f) {
                    repeat(8) { i ->
                        val a = i * (2f * Math.PI.toFloat() / 8) + spin
                        val d = base * (1.1f + b * .9f)
                        drawSparkle(Offset(c.x + d * cos(a), c.y + d * sin(a)), base * .16f * (1f - b), Sun, b * 180f)
                    }
                }
            }
            Box(
                Modifier.size(200.dp).clip(CircleShape)
                    .clickable(interaction, null, enabled = !busy) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onToggle()
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(color = InkBlack, modifier = Modifier.size(36.dp))
                else AnimatedContent(clockedIn, label = "lbl", transitionSpec = { (scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) }) { inn ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (inn) Icons.Filled.Logout else Icons.Filled.Login, null, tint = InkBlack, modifier = Modifier.size(38.dp))
                        Text(if (inn) "SALIDA" else "ENTRADA", color = InkBlack, fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = (-.5).sp)
                    }
                }
            }
        }
        AnimatedContent(
            clockedIn, label = "status",
            transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) },
        ) { inn ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (inn) {
                    Text(elapsed.ifEmpty { "--:--:--" }, style = MaterialTheme.typography.displayMedium, color = Color.White)
                    Text("Jornada en curso", color = Color.White.copy(alpha = .8f))
                } else {
                    Text("Fuera de jornada", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text(expected ?: "Toca para fichar tu entrada", color = Color.White.copy(alpha = .8f))
                }
            }
        }
    }
}
