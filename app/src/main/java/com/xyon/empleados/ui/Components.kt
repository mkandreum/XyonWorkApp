package com.xyon.empleados.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xyon.empleados.data.LoadState
import com.xyon.empleados.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Marks the window FLAG_SECURE while this composable is on screen (blocks screenshots / recents preview). */
@Composable
fun SecureScreen() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val w = (ctx as? Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

// ═════════════════════════ Abstract vector shapes ═════════════════════════

/** Wobbly closed blob. [phase] rotates the lobes; [lobes] is an integer so the outline always closes. */
fun blobPath(c: Offset, r: Float, phase: Float, lobes: Int = 5, amp: Float = .12f, lobes2: Int = 3, amp2: Float = .06f, steps: Int = 72): Path {
    val p = Path()
    for (i in 0..steps) {
        val a = (i.toFloat() / steps) * 2f * PI.toFloat()
        val rr = r * (1f + amp * cos(lobes * a + phase) + amp2 * sin(lobes2 * a - phase * 1.3f))
        val x = c.x + rr * cos(a); val y = c.y + rr * sin(a)
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

fun DrawScope.drawBlob(c: Offset, r: Float, color: Color, phase: Float = 0f, lobes: Int = 5, amp: Float = .12f) =
    drawPath(blobPath(c, r, phase, lobes, amp), color)

/** Four-point concave sparkle. */
fun DrawScope.drawSparkle(c: Offset, r: Float, color: Color, rotation: Float = 0f) {
    val k = r * .16f
    val p = Path().apply {
        moveTo(c.x + r, c.y)
        quadraticTo(c.x + k, c.y + k, c.x, c.y + r)
        quadraticTo(c.x - k, c.y + k, c.x - r, c.y)
        quadraticTo(c.x - k, c.y - k, c.x, c.y - r)
        quadraticTo(c.x + k, c.y - k, c.x + r, c.y)
        close()
    }
    rotate(rotation, c) { drawPath(p, color) }
}

/** Chunky rounded-ish star. */
fun DrawScope.drawStar(c: Offset, r: Float, color: Color, points: Int = 5, rotation: Float = 0f, inner: Float = .52f) {
    val p = Path()
    val n = points * 2
    for (i in 0 until n) {
        val a = (i.toFloat() / n) * 2f * PI.toFloat() - PI.toFloat() / 2f
        val rr = if (i % 2 == 0) r else r * inner
        val x = c.x + rr * cos(a); val y = c.y + rr * sin(a)
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    rotate(rotation, c) { drawPath(p, color, style = androidx.compose.ui.graphics.drawscope.Fill) }
}

/** Horizontal squiggle (wave) stroke. */
fun DrawScope.drawSquiggle(origin: Offset, width: Float, amp: Float, color: Color, strokeW: Float, phase: Float = 0f) {
    val p = Path()
    val steps = 40
    for (i in 0..steps) {
        val x = origin.x + width * i / steps
        val y = origin.y + amp * sin(i / steps.toFloat() * 4f * PI.toFloat() + phase)
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    drawPath(p, color, style = Stroke(strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round))
}

@Composable
private fun rememberDrift(durationMs: Int = 26000): State<Float> {
    val t = rememberInfiniteTransition(label = "drift")
    return t.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(durationMs, easing = LinearEasing)), label = "d")
}

/**
 * Full-bleed saturated blue field with big drifting vector shapes. Used by auth/lock screens.
 */
@Composable
fun BlueField(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val d = rememberDrift()
    val x = Xyon.colors
    Box(modifier.fillMaxSize().background(x.blue)) {
        Canvas(Modifier.fillMaxSize()) {
            val t = d.value
            val w = size.width; val h = size.height
            drawBlob(Offset(w * (.92f + .04f * sin(t)), h * (.10f + .02f * cos(t))), w * .46f, Lime, phase = t, lobes = 5)
            drawBlob(Offset(w * (.04f + .03f * cos(t)), h * (.40f + .03f * sin(t))), w * .30f, Lilac, phase = -t, lobes = 4, amp = .1f)
            drawSparkle(Offset(w * (.22f + .03f * sin(t * 2)), h * .12f), w * .12f, Color.White, rotation = t * 57.3f)
            drawStar(Offset(w * (.80f), h * (.34f + .015f * sin(t * 3))), w * .07f, Sun, 5, t * 57.3f * 2)
            drawCircle(Color.White.copy(alpha = .9f), w * .028f, Offset(w * .58f, h * (.07f + .01f * sin(t * 2))))
            drawCircle(Color.White.copy(alpha = .35f), w * .20f, Offset(w * (.62f + .02f * cos(t)), h * .30f), style = Stroke(w * .006f))
            drawSquiggle(Offset(w * .08f, h * .30f), w * .26f, w * .02f, Lime, w * .012f, t * 2)
            drawSparkle(Offset(w * .10f, h * (.22f + .01f * cos(t))), w * .035f, Lime, -t * 57.3f)
        }
        content()
    }
}

/** App-wide canvas: flat bg with a few quiet drifting shapes (kept subtle so content stays readable). */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val d = rememberDrift(32000)
    val x = Xyon.colors
    val a = if (x.dark) .10f else .55f
    Box(modifier.fillMaxSize().background(x.bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val t = d.value
            val w = size.width; val h = size.height
            drawBlob(Offset(w * (1.0f + .03f * sin(t)), h * (.05f + .02f * cos(t))), w * .5f, Lilac.copy(alpha = a), t, 5)
            drawBlob(Offset(w * (-.05f + .03f * cos(t)), h * (.62f + .03f * sin(t))), w * .36f, Lime.copy(alpha = if (x.dark) .07f else .5f), -t, 4)
            drawSparkle(Offset(w * .86f, h * (.80f + .01f * sin(t * 2))), w * .05f, x.blue.copy(alpha = if (x.dark) .25f else .5f), t * 57f)
        }
        content()
    }
}

// ═════════════════════════ Cards ═════════════════════════

/** Flat, thick-outlined card with a springy press. [accent] draws a small sparkle in the corner. */
@Composable
fun XyonCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    container: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val x = Xyon.colors
    val shape = RoundedCornerShape(28.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) .955f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")
    Column(
        modifier.scale(s).clip(shape)
            .background(container ?: x.card)
            .let { if (onClick != null) it.clickable(interaction, null, role = Role.Button) { Haptics.tick(); onClick() } else it }
            .drawBehind {
                if (accent != null) drawSparkle(Offset(size.width - 26.dp.toPx(), 26.dp.toPx()), 13.dp.toPx(), accent, 0f)
            }
            .border(BorderStroke(1.5.dp, x.outline), shape)
            .padding(18.dp),
        content = content,
    )
}

/** Legacy name kept for existing call sites. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) = XyonCard(modifier, onClick, accent, null, content)

/** Staggered spring entrance for list/grid children. */
@Composable
fun Modifier.staggered(index: Int, stepMs: Int = 60): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay((index.coerceAtMost(10) * stepMs).toLong()); a.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)) }
    return this.graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * 56.dp.toPx(); rotationZ = (1f - a.value) * -2f }
}

// ═════════════════════════ Chips / labels ═════════════════════════

enum class Tone(val color: Color) { Success(com.xyon.empleados.ui.theme.Success), Warning(com.xyon.empleados.ui.theme.Warning), Danger(com.xyon.empleados.ui.theme.Danger), Info(Primary600), Neutral(Color(0xFF5B6275)) }

private fun onFill(c: Color) = if (c.luminance() > .38f) InkBlack else Color.White

@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Text(
        text, modifier.clip(CircleShape).background(tone.color).padding(horizontal = 12.dp, vertical = 5.dp),
        color = onFill(tone.color), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, maxLines = 1,
    )
}

fun statusTone(status: String) = when (status) {
    "APPROVED", "PAID" -> Tone.Success
    "REJECTED" -> Tone.Danger
    "PENDING", "PENDING_MANAGER", "PENDING_ADMIN" -> Tone.Warning
    else -> Tone.Neutral
}

fun statusLabel(status: String) = when (status) {
    "APPROVED" -> "Aprobada"; "REJECTED" -> "Rechazada"; "PENDING" -> "Pendiente"
    "PENDING_MANAGER" -> "Pdte. responsable"; "PENDING_ADMIN" -> "Pdte. admin"; "PAID" -> "Pagada"
    else -> status
}

fun typeLabel(type: String) = when (type) {
    "VACATION" -> "Vacaciones"; "PERSONAL" -> "Asunto propio"; "SICK_LEAVE" -> "Baja"
    "MEDICAL_LEAVE" -> "Visita médica"; "OVERTIME" -> "Horas extra"; else -> "Otro"
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) =
    SectionHeader(text, modifier, action)

// ═════════════════════════ Buttons ═════════════════════════

enum class XyonButtonStyle { Ink, Blue, Lime, Light }

/** Big high-contrast pill with a round arrow bubble. */
@Composable
fun XyonButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false,
    style: XyonButtonStyle = XyonButtonStyle.Ink, icon: ImageVector? = null, showArrow: Boolean = true,
) {
    val x = Xyon.colors
    val (bg, fg, bubble) = when (style) {
        XyonButtonStyle.Ink -> Triple(x.ink, x.bg, Lime)
        XyonButtonStyle.Blue -> Triple(x.blue, Color.White, Lime)
        XyonButtonStyle.Lime -> Triple(Lime, InkBlack, InkBlack)
        XyonButtonStyle.Light -> Triple(x.card, x.ink, Lime)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sx by animateFloatAsState(if (pressed) .95f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "bx")
    val sy by animateFloatAsState(if (pressed) .89f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "by")
    val fill = if (enabled) bg else bg.copy(alpha = .32f)
    Row(
        modifier.graphicsLayer { scaleX = sx; scaleY = sy }.heightIn(min = 58.dp).clip(CircleShape).background(fill)
            .let { if (style == XyonButtonStyle.Light) it.border(1.5.dp, x.outline, CircleShape) else it }
            .clickable(interaction, null, enabled = enabled && !loading, role = Role.Button) { Haptics.click(); onClick() }
            .padding(start = 26.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = fg, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, letterSpacing = (-.2).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(42.dp).clip(CircleShape).background(if (enabled) bubble else bubble.copy(alpha = .4f)), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = if (bubble == InkBlack) Lime else InkBlack, strokeWidth = 2.5.dp)
            else if (showArrow || icon != null) Icon(icon ?: Icons.AutoMirrored.Filled.ArrowForward, null, tint = if (bubble == InkBlack) Lime else InkBlack, modifier = Modifier.size(22.dp))
        }
    }
}

/** Legacy name kept for existing call sites (brush is ignored; the new look is flat). */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, brush: Brush = BrandGradient) =
    XyonButton(text, onClick, modifier, enabled, loading)

// ═════════════════════════ Fields ═════════════════════════

@Composable
fun XyonTextField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    password: Boolean = false, keyboard: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    singleLine: Boolean = true, minLines: Int = 1, leading: ImageVector? = null, error: String? = null,
) {
    val x = Xyon.colors
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = singleLine, minLines = minLines,
        leadingIcon = leading?.let { { Icon(it, null) } },
        trailingIcon = if (password) ({ IconButton({ show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "mostrar") } }) else null,
        visualTransformation = if (password && !show) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (password) androidx.compose.ui.text.input.KeyboardType.Password else keyboard),
        isError = error != null, supportingText = error?.let { { Text(it) } },
        shape = RoundedCornerShape(22.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = x.card, unfocusedContainerColor = x.card,
            focusedBorderColor = x.blue, unfocusedBorderColor = x.outline,
            focusedLabelColor = x.blue, unfocusedLabelColor = x.inkSoft,
            focusedTextColor = x.ink, unfocusedTextColor = x.ink, cursorColor = x.blue,
            focusedLeadingIconColor = x.blue, unfocusedLeadingIconColor = x.inkSoft,
        ),
    )
}

@Composable
fun XField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    password: Boolean = false, keyboard: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    singleLine: Boolean = true, minLines: Int = 1, leading: ImageVector? = null, error: String? = null,
) = XyonTextField(value, onChange, label, modifier, password, keyboard, singleLine, minLines, leading, error)

// ═════════════════════════ Loading / states ═════════════════════════

/** Shimmering placeholder block. */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val x = Xyon.colors
    val t = rememberInfiniteTransition(label = "sk")
    val s by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    val base = if (x.dark) Color(0xFF1B1B26) else Color(0xFFE6E2D4)
    val hi = if (x.dark) Color(0xFF2A2A3A) else Color(0xFFF7F5EE)
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Brush.horizontalGradient(listOf(base, hi, base), startX = s * 600f, endX = s * 600f + 600f)))
}

/**
 * Standard screen body: pull-to-refresh, skeleton while loading, error+retry, offline banner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> LoadScreen(
    state: LoadState<T>, onRefresh: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { Haptics.click(); onRefresh() }, modifier = modifier.fillMaxSize()) {
        when {
            state.data != null -> Column(Modifier.fillMaxSize()) {
                androidx.compose.animation.AnimatedVisibility(state.offline) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clip(CircleShape).background(Sun).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.CloudOff, null, tint = InkBlack, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp)); Text("Sin conexión · datos guardados", color = InkBlack, style = MaterialTheme.typography.labelLarge)
                    }
                }
                content(state.data)
            }
            state.loading -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { Skeleton(Modifier.fillMaxWidth().height(if (it == 0) 200.dp else 90.dp)) }
            }
            else -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                BlobIcon(Icons.Filled.ErrorOutline, Coral, 96.dp)
                Spacer(Modifier.height(16.dp))
                Text(state.error ?: "Error", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                XyonButton("Reintentar", onRefresh, Modifier.width(220.dp))
            }
        }
    }
}

/** Icon sitting on a slowly wobbling flat blob. */
@Composable
fun BlobIcon(icon: ImageVector, color: Color, size: androidx.compose.ui.unit.Dp = 72.dp, modifier: Modifier = Modifier) {
    val d = rememberDrift(9000)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawBlob(center, this.size.minDimension * .42f, color, d.value, 5, .1f) }
        Icon(icon, null, tint = onFill(color), modifier = Modifier.size(size * .42f))
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BlobIcon(icon, Lilac, 104.dp)
        Spacer(Modifier.height(12.dp)); Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val x = Xyon.colors
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(
                Modifier.padding(end = 12.dp).size(48.dp).clip(CircleShape).background(x.card).border(1.5.dp, x.outline, CircleShape).clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = x.ink) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            subtitle?.let { Text(it, color = x.inkSoft, style = MaterialTheme.typography.bodyMedium) }
        }
        actions()
    }
}

/** List helper: items with staggered entrance. */
fun <T> LazyListScope.staggeredItems(items: List<T>, key: ((T) -> Any)? = null, row: @Composable (Int, T) -> Unit) {
    items.forEachIndexed { i, it ->
        val k = key?.invoke(it)
        // With a stable key, rows glide when the list changes (approved/rejected, new item…)
        item(key = k) { Box((if (k != null) Modifier.animateItem() else Modifier).staggered(i)) { row(i, it) } }
    }
}

@Composable
fun ListScreenColumn(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 130.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun Avatar(name: String, size: Int = 44) {
    val palette = listOf(Lime, Lilac, Sun, Coral, Primary300)
    val bg = palette[(name.hashCode() and 0x7fffffff) % palette.size]
    Box(Modifier.size(size.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(name.split(" ").take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString(""), color = InkBlack, fontWeight = FontWeight.Black, fontSize = (size / 2.6).sp)
    }
}

/**
 * Text that counts up to the first number it contains (e.g. "12.5h" -> 0 .. 12.5h).
 * Strings with several numbers or thousands separators are shown as-is.
 */
@Composable
fun AnimatedNumberText(value: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.headlineMedium, color: Color = Color.Unspecified) {
    val m = remember(value) { Regex("^([^\\d-]*)(-?\\d+)(?:[.,](\\d+))?([^\\d]*)$").matchEntire(value) }
    if (m == null) { Text(value, modifier, color = color, style = style, maxLines = 1); return }
    val pre = m.groupValues[1]; val dec = m.groupValues[3]; val suf = m.groupValues[4]
    val target = (m.groupValues[2] + if (dec.isNotEmpty()) ".$dec" else "").toFloatOrNull() ?: 0f
    val a = remember { Animatable(0f) }
    LaunchedEffect(target) { a.animateTo(target, tween(1100, easing = FastOutSlowInEasing)) }
    Text(pre + String.format(Locale.getDefault(), "%.${dec.length}f", a.value) + suf, modifier, color = color, style = style, maxLines = 1)
}

/** Flat saturated stat tile with a counting number. */
@Composable
fun StatPill(label: String, value: String, icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    val x = Xyon.colors
    val fg = onFill(tint)
    Column(
        modifier.clip(RoundedCornerShape(28.dp)).background(tint).border(1.5.dp, x.outline, RoundedCornerShape(28.dp)).padding(16.dp),
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(fg.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.height(14.dp))
        AnimatedNumberText(value, color = fg, style = MaterialTheme.typography.headlineLarge)
        Text(label, color = fg.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
