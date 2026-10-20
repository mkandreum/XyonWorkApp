package com.xyon.empleados.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xyon.empleados.data.*
import com.xyon.empleados.ui.theme.*
import java.time.*

private val ABSENCE_TYPES = listOf("PERSONAL", "SICK_LEAVE", "MEDICAL_LEAVE", "OVERTIME", "OTHER")

private fun readFile(ctx: Context, uri: Uri): Pair<ByteArray, String>? = runCatching {
    val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    require(bytes.size <= 8 * 1024 * 1024) { "too big" }
    bytes to mime
}.getOrNull()

/** Vacations and absences (same data, two views like the web app). */
@Composable
fun RequestsScreen(vm: VacationsViewModel = hiltViewModel(), snack: (String) -> Unit) {
    val st by vm.items.state.collectAsState()
    val bal by vm.balance.state.collectAsState()
    val busy by vm.busy.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var showForm by remember { mutableStateOf(false) }
    var cancelFor by remember { mutableStateOf<VacationRequest?>(null) }
    LaunchedEffect(Unit) { vm.msg.collect { snack(it) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Solicitudes", "Vacaciones y ausencias")
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                listOf("Vacaciones", "Ausencias").forEachIndexed { i, l ->
                    SegmentedButton(tab == i, { tab = i }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                }
            }
            Spacer(Modifier.height(8.dp))
            LoadScreen(st, { vm.items.refresh(); vm.balance.refresh() }) { all ->
                val list = all.filter { if (tab == 0) it.type == "VACATION" else it.type != "VACATION" }.sortedByDescending { it.startDate }
                ListScreenColumn {
                    bal.data?.let { b ->
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (tab == 0) StatPill("Días usados", "%.0f".format(b.vacationDaysUsed), Icons.Filled.BeachAccess, Lime, Modifier.weight(1f))
                                else {
                                    StatPill("Horas médicas", "%.0f".format(b.sickLeaveHoursUsed), Icons.Filled.LocalHospital, Coral, Modifier.weight(1f))
                                    StatPill("Horas extra", "%.0f".format(b.overtimeHoursUsed), Icons.Filled.MoreTime, Lilac, Modifier.weight(1f))
                                    StatPill("Permisos", "%.0f".format(b.paidAbsenceHoursUsed), Icons.Filled.EventAvailable, Sun, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    if (list.isEmpty()) item { EmptyState(Icons.Filled.EventBusy, "Aún no hay solicitudes") }
                    staggeredItems(list, { it.id }) { _, v ->
                        VacationRow(v, trailing = if (v.status.startsWith("PENDING")) ({
                            TextButton({ cancelFor = v }, Modifier.padding(top = 4.dp)) {
                                Icon(Icons.Filled.Close, null, tint = Coral, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                                Text("Cancelar solicitud", color = Coral, fontWeight = FontWeight.Bold)
                            }
                        }) else null)
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { showForm = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 108.dp),
            containerColor = Lime, contentColor = InkBlack, shape = androidx.compose.foundation.shape.CircleShape,
            icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Nueva") },
        )
    }

    if (showForm) NewRequestSheet(tab == 1, busy, { showForm = false }, { s, e -> vm.overlap(s, e) }) { req, file -> vm.create(req, file) { showForm = false } }
    cancelFor?.let { v ->
        AlertDialog(
            onDismissRequest = { cancelFor = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Cancelar solicitud") },
            text = { Text("¿Cancelar ${typeLabel(v.type).lowercase()} del ${fmtDate(v.startDate)} al ${fmtDate(v.endDate)}?") },
            confirmButton = { TextButton({ vm.cancel(v.id); cancelFor = null }) { Text("Cancelar solicitud", color = Coral) } },
            dismissButton = { TextButton({ cancelFor = null }) { Text("Volver") } },
        )
    }
}

/** Calendar and working (Mon–Fri) days between two dates, both inclusive — same rule as the server. */
private fun countDays(s: LocalDate, e: LocalDate): Pair<Int, Int> {
    var natural = 0; var business = 0
    var d = s
    while (!d.isAfter(e)) { natural++; if (d.dayOfWeek.value <= 5) business++; d = d.plusDays(1) }
    return natural to business
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewRequestSheet(
    absence: Boolean, busy: Boolean, onDismiss: () -> Unit,
    checkOverlap: suspend (String, String) -> OverlapResponse?,
    onSubmit: (CreateVacationRequest, Pair<ByteArray, String>?) -> Unit,
) {
    val ctx = LocalContext.current
    var type by remember { mutableStateOf(if (absence) "PERSONAL" else "VACATION") }
    var subtype by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    val picker = rememberDateRangePickerState()
    val picked = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { file = readFile(ctx, it) } }
    val start = picker.selectedStartDateMillis; val end = picker.selectedEndDateMillis ?: start
    fun d(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    val (natural, business) = if (start != null && end != null) countDays(d(start), d(end)) else (0 to 0)
    val hoursOnly = type == "SICK_LEAVE" && (hours.toIntOrNull() ?: 0) > 0
    // Vacations count working days; "Matrimonio" counts calendar days; medical hours are hour-based (0 days)
    val days = when {
        hoursOnly -> 0
        subtype.contains("Matrimonio", ignoreCase = true) -> natural
        else -> business
    }
    var overlap by remember { mutableStateOf<OverlapResponse?>(null) }
    LaunchedEffect(start, end) {
        overlap = null
        if (start != null && end != null) { kotlinx.coroutines.delay(300); overlap = checkOverlap(d(start).toString(), d(end).toString()) }
    }
    val valid = start != null && end != null && (if (hoursOnly) natural == 1 else days in 1..365)

    ModalBottomSheet(onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (absence) "Nueva ausencia" else "Nuevas vacaciones", style = MaterialTheme.typography.titleLarge)
            if (absence) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ABSENCE_TYPES.forEach { t -> FilterChip(type == t, { type = t }, { Text(typeLabel(t)) }) }
                }
                XField(subtype, { subtype = it }, "Detalle (opcional)")
                XField(hours, { hours = it.filter(Char::isDigit) }, "Horas (opcional)", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                OutlinedButton({ picked.launch("*/*") }) {
                    Icon(Icons.Filled.AttachFile, null); Spacer(Modifier.width(6.dp))
                    Text(if (file != null) "Justificante adjunto" else "Adjuntar justificante")
                }
            }
            DateRangePicker(picker, Modifier.height(430.dp), title = null, headline = null, showModeToggle = false,
                colors = DatePickerDefaults.colors(containerColor = Color.Transparent))
            // Live summary: counted days and colleagues already off
            if (start != null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    hoursOnly -> SummaryChip("${hours}h el ${fmtDate(d(start).toString())}", Lime)
                    days == 0 -> SummaryChip("Sin días laborables en ese rango", Coral)
                    else -> SummaryChip("$days ${if (days == 1) "día" else "días"} ${if (days == natural && natural != business) "naturales" else "laborables"}", Lime)
                }
                overlap?.let { o ->
                    if (o.count == 0) SummaryChip("Nadie de tu equipo está fuera", Xyon.colors.cardAlt)
                    else SummaryChip(if (o.count == 1) "${o.people.first().name.substringBefore(" ")} también estará fuera" else "${o.count} compañeros también fuera", Sun)
                }
            }
            if (hoursOnly && start != null && natural > 1) Text("Las horas médicas son de un solo día: elige una sola fecha.", color = Coral)
            XyonButton(when {
                !valid -> "Selecciona fechas"
                hoursOnly -> "Enviar ($hours h)"
                else -> "Enviar ($days día${if (days > 1) "s" else ""})"
            }, {
                onSubmit(CreateVacationRequest(d(start!!).toString(), d(end ?: start).toString(), days, hours.toIntOrNull(), type, subtype.ifBlank { null }), file)
            }, Modifier.fillMaxWidth(), enabled = valid, loading = busy)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SummaryChip(text: String, color: Color) {
    Text(text, color = InkBlack, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(CircleShape).background(color).padding(horizontal = 12.dp, vertical = 7.dp))
}
