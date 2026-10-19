package com.xyon.empleados.ui.admin

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xyon.empleados.BuildConfig
import com.xyon.empleados.data.safeCall
import com.xyon.empleados.data.userMessage
import com.xyon.empleados.ui.*
import com.xyon.empleados.ui.theme.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

abstract class AdminVm : ViewModel() {
    private val _msg = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val msg: SharedFlow<String> = _msg.asSharedFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    fun say(m: String) { _msg.tryEmit(m) }

    /** Runs a mutation: busy flag, snackbar feedback, then [after] on success. */
    protected fun <T> act(ok: String, block: suspend () -> T, after: (T) -> Unit = {}) = viewModelScope.launch {
        _busy.value = true
        safeCall { block() }.onSuccess { _msg.tryEmit(ok); after(it) }.onFailure { _msg.tryEmit(it.userMessage()) }
        _busy.value = false
    }
}

/** True when the current admin screen is a bottom-bar tab: no back arrow there. */
val LocalTabRoot = staticCompositionLocalOf { false }

@Composable
fun AdminPage(
    title: String, subtitle: String?, onBack: () -> Unit, vm: AdminVm, snack: (String) -> Unit,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit,
) {
    LaunchedEffect(vm) { vm.msg.collect { snack(it) } }
    val busy by vm.busy.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, subtitle, if (LocalTabRoot.current) null else onBack, actions)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        content()
    }
}

@Composable
fun ConfirmDialog(text: String, confirm: String = "Eliminar", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismiss, title = { Text("Confirmar") }, text = { Text(text) },
        confirmButton = { TextButton({ onConfirm(); onDismiss() }) { Text(confirm, color = Danger) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}

@Composable
fun FormDialog(
    title: String, confirm: String = "Guardar", enabled: Boolean = true,
    onConfirm: () -> Unit, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(onDismiss, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        confirmButton = { TextButton(onClick = { onConfirm() }, enabled = enabled) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}

/** Simple dropdown selector. options = value to label. */
@Composable
fun Dropdown(label: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, GlassStroke, RoundedCornerShape(16.dp))
                .clickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(options.firstOrNull { it.first == value }?.second ?: value.ifBlank { "Seleccionar" }, Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, null)
            }
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem({ Text(l) }, { onSelect(v); open = false }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, GlassStroke, RoundedCornerShape(16.dp))
            .clickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value.ifBlank { "Seleccionar fecha" }, Modifier.weight(1f))
            Icon(Icons.Filled.CalendarMonth, null)
        }
    }
    if (open) {
        val init = runCatching { java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        val st = rememberDatePickerState(initialSelectedDateMillis = init)
        DatePickerDialog(
            { open = false },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let { onChange(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton({ open = false }) { Text("Cancelar") } },
        ) { DatePicker(st) }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, label: String = "Buscar") {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { XField(value, onChange, label, leading = Icons.Filled.Search) }
}

@Composable
fun ChipsRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, l) -> FilterChip(selected == v, { onSelect(v) }, { Text(l, maxLines = 1) }) }
    }
}

@Composable
fun IconAction(icon: ImageVector, desc: String, danger: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick) { Icon(icon, desc, tint = if (danger) Danger else MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
fun ApproveRejectRow(onApprove: () -> Unit, onReject: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onApprove() }, colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = InkBlack)) {
            Icon(Icons.Filled.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Aprobar", fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
        }
        OutlinedButton({ haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); onReject() }, border = androidx.compose.foundation.BorderStroke(1.5.dp, Xyon.colors.outline)) {
            Text("Rechazar", color = Xyon.colors.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)

fun readPicked(ctx: Context, uri: Uri): Pair<ByteArray, String>? = runCatching {
    val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    require(bytes.size <= 10_000_000) { "Archivo demasiado grande (máx. 10 MB)" }
    bytes to mime
}.getOrNull()

fun absUrl(u: String?): String? = com.xyon.empleados.data.ServerConfig.absolute(u)

val EMAIL_RE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
val TIME_RE = Regex("^([01]?[0-9]|2[0-3]):[0-5][0-9]$")

/** Backend rule: >=12 chars with upper, lower, digit and special char. */
fun strongPassword(p: String) = p.length in 12..128 && p.any { it.isUpperCase() } && p.any { it.isLowerCase() } &&
    p.any { it.isDigit() } && p.any { !it.isLetterOrDigit() }

val MONTHS_ES = listOf("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre")
