package com.xyon.empleados.ui.admin

import com.xyon.empleados.ui.HapticSwitch
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.xyon.empleados.data.*
import com.xyon.empleados.data.admin.*
import com.xyon.empleados.ui.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import javax.inject.Inject

// ───────────────────────── Bulk import (CSV) ─────────────────────────

@HiltViewModel
class AdminImportVm @Inject constructor(private val api: AdminApi) : AdminVm() {
    val csv = MutableStateFlow<String?>(null)
    val fileName = MutableStateFlow("")
    val rowCount = MutableStateFlow(0)
    val result = MutableStateFlow<ImportResponse?>(null)

    fun setFile(name: String, text: String) {
        val clean = text.removePrefix("\uFEFF")
        csv.value = clean; fileName.value = name; result.value = null
        rowCount.value = (clean.lines().count { it.isNotBlank() } - 1).coerceAtLeast(0)
    }

    fun run(dryRun: Boolean, invites: Boolean) {
        val c = csv.value ?: return
        act(if (dryRun) "Validación completada" else "Importación completada", { api.importUsers(ImportUsersRequest(c, dryRun, invites)) }) {
            result.value = it
        }
    }
}

private fun displayName(ctx: Context, uri: Uri): String = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull() ?: "archivo.csv"

private fun importTone(status: String) = when (status) {
    "CREATED" -> Tone.Success; "WOULD_CREATE" -> Tone.Info; "ERROR" -> Tone.Danger; else -> Tone.Neutral
}

private fun importLabel(status: String) = when (status) {
    "CREATED" -> "Creado"; "WOULD_CREATE" -> "Se creará"; "SKIPPED" -> "Omitido"; "ERROR" -> "Error"; else -> status
}

@Composable
fun AdminImportScreen(onBack: () -> Unit, snack: (String) -> Unit, vm: AdminImportVm = hiltViewModel()) {
    val ctx = LocalContext.current
    val csv by vm.csv.collectAsState()
    val name by vm.fileName.collectAsState()
    val rows by vm.rowCount.collectAsState()
    val res by vm.result.collectAsState()
    val busy by vm.busy.collectAsState()
    var invites by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u ->
            val bytes = readPicked(ctx, u)?.first
            when {
                bytes == null -> vm.say("No se pudo leer el archivo (máx. 10 MB)")
                bytes.size > 90_000 -> vm.say("El CSV supera el límite del servidor (100 KB)")
                else -> {
                    val text = bytes.toString(Charsets.UTF_8)
                    vm.setFile(displayName(ctx, u), text)
                    if (text.lines().count { it.isNotBlank() } - 1 > 300) vm.say("Máximo 300 filas por importación")
                }
            }
        }
    }
    val tooMany = rows > 300
    val dry = res?.takeIf { it.dryRun }
    val done = res?.takeIf { !it.dryRun }
    AdminPage("Importar usuarios", "Alta masiva desde CSV", onBack, vm, snack) {
        ListScreenColumn {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("Archivo CSV", style = MaterialTheme.typography.titleMedium)
                    Muted("Columnas: name,email,role,department,position,joinDate (máx. 300 filas). Cada usuario nuevo recibe un enlace de alta por email; las contraseñas del CSV se ignoran.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton({ pick.launch("*/*") }, enabled = !busy) {
                        Icon(Icons.Filled.UploadFile, null); Spacer(Modifier.width(8.dp)); Text(if (csv == null) "Seleccionar CSV" else "Cambiar archivo")
                    }
                    if (csv != null) Muted("$name · $rows filas")
                }
            }
            if (csv == null) item { EmptyState(Icons.Filled.UploadFile, "Selecciona un CSV para empezar") }
            else {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HapticSwitch(invites, { invites = it }, enabled = done == null)
                            Spacer(Modifier.width(8.dp)); Text("Enviar invitaciones por email")
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ vm.run(true, invites) }, enabled = !busy && rows > 0 && !tooMany) { Text("Validar (simulación)") }
                            if (dry != null && dry.summary.wouldCreate > 0)
                                Button({ confirm = true }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = com.xyon.empleados.ui.theme.Success)) { Text("Importar") }
                        }
                        if (rows == 0) Muted("El archivo no contiene filas de datos.")
                        if (tooMany) Muted("Hay más de 300 filas: divide el archivo.")
                    }
                }
            }
            res?.let { r ->
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(if (r.dryRun) "Resultado de la simulación" else "Resultado de la importación", style = MaterialTheme.typography.titleMedium)
                        val s = r.summary
                        Muted("Total ${s.total} · " + (if (r.dryRun) "se crearán ${s.wouldCreate}" else "creados ${s.created}") +
                            " · omitidos ${s.skipped} · errores ${s.errors}" + (if (!r.dryRun) " · invitaciones ${s.invitesSent}" else ""))
                    }
                }
                if (r.results.isEmpty()) item { EmptyState(Icons.Filled.Inbox, "Sin filas procesadas") }
                items(r.results, key = { it.row }) { row ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Fila ${row.row} · ${row.email ?: "—"}", style = MaterialTheme.typography.titleMedium)
                                row.error?.let { Muted(it) }
                                if (row.inviteSent) Muted("Invitación enviada")
                            }
                            StatusChip(importLabel(row.status), importTone(row.status))
                        }
                    }
                }
            }
        }
    }
    if (confirm) ConfirmDialog(
        "Se crearán ${dry?.summary?.wouldCreate ?: 0} usuarios" + if (invites) " y se enviarán invitaciones por email." else " sin enviar invitaciones.",
        "Importar", onConfirm = { vm.run(false, invites) }, onDismiss = { confirm = false })
}

// ───────────────────────── Schedule overrides ─────────────────────────

@HiltViewModel
class AdminOverridesVm @Inject constructor(private val api: AdminApi) : AdminVm() {
    val people = MutableStateFlow(LoadState<List<User>>())
    val items = MutableStateFlow(LoadState<List<ScheduleOverride>>(loading = false))
    private var lastDept = ""; private var lastUser = ""

    fun loadPeople(admin: Boolean) {
        viewModelScope.launch {
            people.value = LoadState(loading = true)
            safeCall { if (admin) api.users() else api.teamMembers() }
                .onSuccess { people.value = LoadState(data = it.sortedBy { u -> u.name }, loading = false) }
                .onFailure { people.value = LoadState(loading = false, error = it.userMessage()) }
        }
    }

    fun load(dept: String, userId: String) {
        lastDept = dept; lastUser = userId
        viewModelScope.launch {
            items.value = LoadState(loading = true)
            safeCall { api.overrides(dept.ifBlank { null }, userId.ifBlank { null }, LocalDate.now().minusDays(30).toString(), null) }
                .onSuccess { items.value = LoadState(data = it.sortedBy { o -> o.date }, loading = false) }
                .onFailure { items.value = LoadState(loading = false, error = it.userMessage()) }
        }
    }
    fun reload() { if (lastDept.isNotBlank()) load(lastDept, lastUser) }
    fun save(r: ScheduleOverrideRequest) = act("Excepción guardada", { api.setOverride(r) }) { reload() }
    fun delete(id: String) = act("Excepción eliminada", { api.deleteOverride(id) }) { reload() }
}

@Composable
fun AdminOverridesScreen(admin: Boolean, myDept: String, onBack: () -> Unit, snack: (String) -> Unit, vm: AdminOverridesVm = hiltViewModel()) {
    val people by vm.people.collectAsState()
    val items by vm.items.collectAsState()
    var dept by remember { mutableStateOf(if (admin) "" else myDept) }
    var uid by remember { mutableStateOf("") }
    var form by remember { mutableStateOf<ScheduleOverride?>(null) }
    var creating by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<ScheduleOverride?>(null) }
    LaunchedEffect(Unit) { vm.loadPeople(admin) }
    LaunchedEffect(dept, uid) { if (dept.isNotBlank()) vm.load(dept, uid) }
    AdminPage("Excepciones de horario", "Cambios puntuales por día", onBack, vm, snack,
        actions = { if (dept.isNotBlank()) IconAction(Icons.Filled.Add, "Nueva excepción") { creating = true } }) {
        LoadScreen(people, { vm.loadPeople(admin) }) { all ->
            val depts = all.map { it.department }.filter { it.isNotBlank() }.distinct().sorted()
            val inDept = all.filter { it.department == dept }
            Column(Modifier.fillMaxSize()) {
                if (admin) Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Dropdown("Departamento", dept, depts.map { it to it }, { dept = it; uid = "" })
                } else Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Muted("Departamento: $dept") }
                if (dept.isNotBlank()) Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Dropdown("Ámbito", uid, listOf("" to "Todo el departamento") + inDept.map { it.id to it.name }, { uid = it })
                }
                if (dept.isBlank()) EmptyState(Icons.Filled.Apartment, "Selecciona un departamento")
                else LoadScreen(items, { vm.reload() }) { list ->
                    ListScreenColumn {
                        if (list.isEmpty()) item { EmptyState(Icons.Filled.EventAvailable, "Sin excepciones de horario") }
                        items(list, key = { it.id }) { o ->
                            GlassCard(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(fmtDate(o.date), style = MaterialTheme.typography.titleMedium)
                                        Muted(if (o.dayOff) "Día libre" else listOfNotNull(
                                            if (o.horaEntrada != null || o.horaSalida != null) "${o.horaEntrada ?: "—"} – ${o.horaSalida ?: "—"}" else null,
                                            o.toleranciaMinutos?.let { "tolerancia $it min" }).joinToString(" · "))
                                        o.note?.let { Muted(it) }
                                        val who = if (o.userId != null) inDept.firstOrNull { it.id == o.userId }?.name ?: "Empleado" else "Departamento"
                                        StatusChip(who, if (o.userId != null) Tone.Warning else Tone.Info)
                                    }
                                    IconAction(Icons.Filled.Edit, "Editar") { form = o }
                                    IconAction(Icons.Filled.Delete, "Eliminar", danger = true) { del = o }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating || form != null) {
        val o = form
        var date by remember(o) { mutableStateOf(o?.date ?: LocalDate.now().toString()) }
        var dayOff by remember(o) { mutableStateOf(o?.dayOff ?: false) }
        var inT by remember(o) { mutableStateOf(o?.horaEntrada ?: "") }
        var outT by remember(o) { mutableStateOf(o?.horaSalida ?: "") }
        var tol by remember(o) { mutableStateOf(o?.toleranciaMinutos?.toString() ?: "") }
        var note by remember(o) { mutableStateOf(o?.note ?: "") }
        val optOk = { v: String -> v.isBlank() || TIME_RE.matches(v) }
        val tolV = tol.toIntOrNull()
        val tolOk = tol.isBlank() || (tolV != null && tolV in 0..240)
        val valid = date.isNotBlank() && (dayOff || ((inT.isNotBlank() || outT.isNotBlank() || tol.isNotBlank()) && optOk(inT) && optOk(outT) && tolOk))
        val targetUid = o?.userId ?: uid.ifBlank { null }
        val close = { creating = false; form = null }
        FormDialog(if (o == null) "Nueva excepción" else "Editar excepción", enabled = valid, onDismiss = close, onConfirm = {
            vm.save(ScheduleOverrideRequest(
                date = date, dayOff = dayOff, userId = targetUid, department = if (targetUid == null) dept else null,
                horaEntrada = if (dayOff) null else inT.ifBlank { null }, horaSalida = if (dayOff) null else outT.ifBlank { null },
                toleranciaMinutos = if (dayOff) null else tolV, note = note.trim().ifBlank { null }))
            close()
        }) {
            Muted(if (targetUid != null) "Aplica a ${people.data?.firstOrNull { it.id == targetUid }?.name ?: "el empleado"} y prevalece sobre el departamento." else "Aplica a todo el departamento $dept.")
            DateField("Fecha", date, { date = it })
            Row(verticalAlignment = Alignment.CenterVertically) { HapticSwitch(dayOff, { dayOff = it }); Spacer(Modifier.width(8.dp)); Text("Día libre") }
            if (!dayOff) {
                TimeField("Entrada (opcional)", inT, { inT = it }, optional = true)
                TimeField("Salida (opcional)", outT, { outT = it }, optional = true, default = "18:00")
                XField(tol, { tol = it }, "Tolerancia (min, 0-240)", keyboard = androidx.compose.ui.text.input.KeyboardType.Number, error = if (!tolOk) "0-240" else null)
                if (inT.isBlank() && outT.isBlank() && tol.isBlank()) Muted("Indica al menos una hora o la tolerancia.")
            }
            XField(note, { note = it.take(300) }, "Nota (opcional)", singleLine = false, minLines = 2)
        }
    }
    del?.let { o ->
        ConfirmDialog("¿Eliminar la excepción del ${fmtDate(o.date)}? Se volverá al horario habitual.", onConfirm = { vm.delete(o.id) }, onDismiss = { del = null })
    }
}

// ───────────────────────── Team week ─────────────────────────

@HiltViewModel
class AdminTeamWeekVm @Inject constructor(private val api: AdminApi, private val deps: DepartmentsRepo) : AdminVm() {
    val state = MutableStateFlow(LoadState<TeamWeek>(loading = false))
    private val _deps = MutableStateFlow<List<String>>(emptyList())
    val departments: StateFlow<List<String>> = _deps.asStateFlow()
    private var lastDept: String? = null; private var lastWeek: LocalDate = LocalDate.now()

    fun loadDepartments() { viewModelScope.launch { _deps.value = deps.names() } }

    fun load(dept: String?, week: LocalDate) {
        lastDept = dept; lastWeek = week
        viewModelScope.launch {
            state.value = LoadState(loading = true)
            safeCall { api.teamWeek(dept?.ifBlank { null }, week.toString()) }
                .onSuccess { state.value = LoadState(data = it, loading = false) }
                .onFailure { state.value = LoadState(loading = false, error = it.userMessage()) }
        }
    }
    fun reload() = load(lastDept, lastWeek)
}

private fun monday(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

@Composable
fun AdminTeamWeekScreen(admin: Boolean, onBack: () -> Unit, snack: (String) -> Unit, vm: AdminTeamWeekVm = hiltViewModel()) {
    val st by vm.state.collectAsState()
    val deps by vm.departments.collectAsState()
    var dept by remember { mutableStateOf("") }
    var week by remember { mutableStateOf(monday(LocalDate.now())) }
    var open by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { if (admin) vm.loadDepartments() }
    LaunchedEffect(dept, week) { if (!admin || dept.isNotBlank()) vm.load(dept.ifBlank { null }, week) }
    AdminPage("Semana del equipo", if (admin) "Fichajes y ausencias por departamento" else "Tu departamento", onBack, vm, snack) {
        if (admin) Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Dropdown("Departamento", dept, deps.map { it to it }, { dept = it })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Filled.ChevronLeft, "Semana anterior") { week = week.minusWeeks(1) }
            Text("${fmtDate(week.toString())} – ${fmtDate(week.plusDays(6).toString())}", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            IconAction(Icons.Filled.ChevronRight, "Semana siguiente") { week = week.plusWeeks(1) }
        }
        TextButton({ week = monday(LocalDate.now()) }, Modifier.align(Alignment.CenterHorizontally)) { Text("Esta semana") }
        if (admin && dept.isBlank()) EmptyState(Icons.Filled.Apartment, "Selecciona un departamento")
        else LoadScreen(st, { vm.reload() }) { tw ->
            ListScreenColumn {
                if (tw.members.isEmpty()) item { EmptyState(Icons.Filled.Groups, "Sin empleados en ${tw.department}") }
                items(tw.members, key = { it.user.id }) { m ->
                    val expanded = m.user.id in open
                    GlassCard(Modifier.fillMaxWidth(), onClick = { open = if (expanded) open - m.user.id else open + m.user.id }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(m.user.name, 40); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.user.name, style = MaterialTheme.typography.titleMedium)
                                Muted("%.1f h en la semana".format(m.totalHours))
                            }
                            if (!m.user.active) StatusChip("Inactivo", Tone.Danger)
                            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            m.days.forEach { d -> DayCell(d, Modifier.weight(1f)) }
                        }
                        if (expanded) m.days.forEach { d ->
                            Spacer(Modifier.height(6.dp))
                            Text(fmtDate(d.date), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            Muted(dayDetail(d))
                        }
                    }
                }
            }
        }
    }
}

private fun dayTone(d: TeamDay): Tone = when {
    d.ovr?.dayOff == true -> Tone.Neutral
    d.vacations.isNotEmpty() || d.absences.isNotEmpty() -> Tone.Info
    d.isLate || d.isEarlyDeparture || (d.fichajes.isNotEmpty() && !d.isComplete) -> Tone.Warning
    d.fichajes.isNotEmpty() -> Tone.Success
    else -> Tone.Neutral
}

private fun dayValue(d: TeamDay): String = when {
    d.ovr?.dayOff == true -> "Libre"
    d.vacations.isNotEmpty() -> "Vac."
    d.absences.isNotEmpty() -> "Aus."
    d.fichajes.isEmpty() -> "–"
    else -> "%.1f".format(d.hours)
}

private fun dayDetail(d: TeamDay): String {
    val parts = mutableListOf<String>()
    parts += if (d.fichajes.isEmpty()) "Sin fichajes" else d.fichajes.joinToString("  ") { "${if (it.tipo == "ENTRADA") "E" else "S"} ${fmtTimeOf(it.timestamp)}" } + " · %.1f h".format(d.hours)
    if (d.isLate) parts += "Retraso"
    if (d.isEarlyDeparture) parts += "Salida anticipada"
    if (d.fichajes.isNotEmpty() && !d.isComplete) parts += "Incompleto"
    if (d.vacations.isNotEmpty()) parts += "Vacaciones"
    d.absences.forEach { parts += typeLabel(it.type) }
    d.shift?.let { parts += "Turno ${it.name}" }
    d.ovr?.let { o ->
        parts += "Excepción: " + if (o.dayOff) "día libre" else listOfNotNull(
            if (o.horaEntrada != null || o.horaSalida != null) "${o.horaEntrada ?: "—"}–${o.horaSalida ?: "—"}" else null,
            o.toleranciaMinutos?.let { "tol. $it min" }).joinToString(" ")
        o.note?.let { parts += it }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun DayCell(d: TeamDay, modifier: Modifier = Modifier) {
    val tone = dayTone(d)
    val letter = runCatching { LocalDate.parse(d.date.take(10)).dayOfWeek.getDisplayName(TextStyle.NARROW, ES).uppercase() }.getOrDefault("")
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(tone.color.copy(alpha = .28f)).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(letter, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(dayValue(d), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
