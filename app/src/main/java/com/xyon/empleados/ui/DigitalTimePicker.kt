package com.xyon.empleados.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xyon.empleados.ui.theme.Xyon
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Minutes since midnight (local) of an ISO instant, and back to "HH:mm". */
fun minutesOfDay(iso: String?): Int? =
    runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalTime().let { it.hour * 60 + it.minute } }.getOrNull()
fun hhmm(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
/** "H:mm" / "HH:mm" → minutes since midnight, or null when blank or malformed. */
fun parseHhmm(s: String?): Int? = s?.trim()?.split(":")?.takeIf { it.size == 2 }?.let { (h, m) ->
    val hh = h.toIntOrNull(); val mm = m.toIntOrNull()
    if (hh != null && mm != null && hh in 0..23 && mm in 0..59) hh * 60 + mm else null
}

private val digitStyle = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Black, fontFeatureSettings = "tnum")

/** A time of day picked on a big digital clock: arrows (hold to repeat) or a vertical drag on each pair of digits. */
@Composable
fun DigitalTimePicker(label: String, minutes: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, error: String? = null, onClear: (() -> Unit)? = null) {
    val x = Xyon.colors
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(x.card)
                .border(1.5.dp, if (error != null) MaterialTheme.colorScheme.error else x.outline, shape)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = x.inkSoft, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (onClear != null) TextButton(onClear) { Text("Quitar", color = x.blue, fontWeight = FontWeight.Bold) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DigitColumn("horas", minutes / 60, 24) { onChange(it * 60 + minutes % 60) }
                Text(":", style = digitStyle, color = x.ink)
                DigitColumn("minutos", minutes % 60, 60) { onChange(minutes / 60 * 60 + it) }
            }
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
    }
}

/**
 * Digital clock for forms that keep times as "HH:mm" text. When [optional], "" means no time:
 * the card then offers to add one (starting at [default]) and the clock offers to remove it.
 */
@Composable
fun TimeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, optional: Boolean = false, default: String = "09:00", error: String? = null) {
    val minutes = parseHhmm(value)
    if (minutes == null && !optional) {
        // A required time always holds a value, so the form never waits on an invisible one
        LaunchedEffect(Unit) { onChange(hhmm(parseHhmm(default) ?: 9 * 60)) }
    }
    if (minutes == null && optional) {
        val x = Xyon.colors
        val shape = RoundedCornerShape(22.dp)
        Row(
            modifier.fillMaxWidth().clip(shape).background(x.card).border(1.5.dp, x.outline, shape).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = x.inkSoft, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text("Sin hora", color = x.ink, fontWeight = FontWeight.Bold)
            }
            TextButton({ onChange(hhmm(parseHhmm(default) ?: 9 * 60)) }) { Text("Añadir hora", color = x.blue, fontWeight = FontWeight.Bold) }
        }
        return
    }
    DigitalTimePicker(label, minutes ?: parseHhmm(default) ?: 9 * 60, { onChange(hhmm(it)) }, modifier, error, onClear = if (optional) ({ onChange("") }) else null)
}

/** One pair of clock digits (hours or minutes) that wraps around. */
@Composable
private fun DigitColumn(name: String, value: Int, count: Int, onSet: (Int) -> Unit) {
    val x = Xyon.colors
    val current by rememberUpdatedState(value)
    val set by rememberUpdatedState(onSet)
    fun step(d: Int) { set(((current + d) % count + count) % count); Haptics.tick() }
    val stepPx = with(LocalDensity.current) { 18.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RepeatArrow(Icons.Filled.KeyboardArrowUp, "Subir $name") { step(1) }
        Box(
            Modifier.clip(RoundedCornerShape(16.dp)).background(x.cardAlt).widthIn(min = 92.dp)
                .draggable(rememberDraggableState { d ->
                    drag += d
                    while (drag <= -stepPx) { drag += stepPx; step(1) }
                    while (drag >= stepPx) { drag -= stepPx; step(-1) }
                }, Orientation.Vertical, onDragStopped = { drag = 0f })
                .semantics { contentDescription = "$value $name" },
            contentAlignment = Alignment.Center,
        ) { RollingText("%02d".format(value), digitStyle, x.ink) }
        RepeatArrow(Icons.Filled.KeyboardArrowDown, "Bajar $name") { step(-1) }
    }
}

/** Arrow that steps once on tap and keeps stepping while held. */
@Composable
private fun RepeatArrow(icon: ImageVector, label: String, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    Box(
        Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(12.dp))
            .semantics { role = Role.Button; contentDescription = label; onClick { step(); true } }
            .pointerInput(Unit) {
                coroutineScope {
                    detectTapGestures(onPress = {
                        step()
                        val repeat = launch { delay(400); while (true) { step(); delay(70) } }
                        tryAwaitRelease()
                        repeat.cancel()
                    })
                }
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Xyon.colors.blue, modifier = Modifier.size(32.dp)) }
}
