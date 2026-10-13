package com.xyon.empleados.ui.admin

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.xyon.empleados.data.*
import com.xyon.empleados.data.admin.*
import com.xyon.empleados.ui.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

// ───────────────────────── Fichajes per user + report ─────────────────────────

@HiltViewModel
class AdminFichajesVm @Inject constructor(private val api: AdminApi, private val lf: LoaderFactory) : AdminVm() {
    val people = MutableStateFlow(LoadState<List<User>>())
    val days = MutableStateFlow(LoadState<List<FichajeDayStats>>(loading = false))
    private val _csv = MutableSharedFlow<Pair<ByteArray, String>>(extraBufferCapacity = 1)
    val csv: SharedFlow<Pair<ByteArray, String>> = _csv.asSharedFlow()
    var isAdmin = false
    private var lastUser = ""; private var lastMode = "week"

    fun loadPeople(admin: Boolean) {
        isAdmin = admin
        viewModelScope.launch {
            people.value = LoadState(loading = true)
            safeCall { if (admin) api.users() else api.teamMembers() }
                .onSuccess { people.value = LoadState(data = it.sortedBy { u -> u.name }, loading = false) }
                .onFailure { people.value = LoadState(loading = false, error = it.userMessage()) }
        }
    }

    fun load(userId: String, mode: String) {
        lastUser = userId; lastMode = mode
        viewModelScope.launch {
            days.value = LoadState(loading = true)
            safeCall { if (mode == "week") api.week(userId) else api.month(userId) }
                .onSuccess { days.value = LoadState(data = it.sortedByDescending { d -> d.date }, loading = false) }
                .onFailure { days.value = LoadState(loading = false, error = it.userMessage()) }
        }
    }
    fun reload() { if (lastUser.isNotBlank()) load(lastUser, lastMode) }

    fun warnLate(userId: String, d: FichajeDayStats) {
        val f = d.fichajes.firstOrNull { it.tipo == "ENTRADA" } ?: d.fichajes.firstOrNull() ?: return say("Sin fichaje asociado")
        act("Aviso de retraso enviado", { api.lateNotify(LateNotificationRequest(userId, f.id, d.date.take(10))) })
    }

    fun export(userId: String?, month: Int, year: Int, who: String) = act("Informe generado", {
        val bytes = api.report(month, year, userId).bytes()
        _csv.tryEmit(bytes to "asistencia_${who}_${year}_${"%02d".format(month)}.csv")
    })
}

@Composable
fun AdminFichajesScreen(admin: Boolean, onBack: () -> Unit, snack: (String) -> Unit, vm: AdminFichajesVm = hiltViewModel()) {
    val ctx = LocalContext.current
    val people by vm.people.collectAsState()
    val days by vm.days.collectAsState()
    var uid by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("week") }
    var exportOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadPeople(admin) }
    LaunchedEffect(uid, mode) { if (uid.isNotBlank()) vm.load(uid, mode) }
    LaunchedEffect(Unit) {
        vm.csv.collect { (bytes, name) ->
            val dir = File(ctx.cacheDir, "docs").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val f = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { writeBytes(bytes) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Exportar informe"))
        }
    }
    AdminPage("Fichajes", if (admin) "Todos los empleados" else "Tu equipo", onBack, vm, snack,
        actions = { IconAction(Icons.Filled.FileDownload, "Exportar informe") { exportOpen = true } }) {
        LoadScreen(people, { vm.loadPeople(admin) }) { list ->
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Dropdown("Empleado", uid, list.map { it.id to "${it.name} · ${it.department}" }, { uid = it })
                }
                ChipsRow(listOf("week" to "Esta semana", "month" to "Este mes"), mode, { mode = it })
                if (uid.isBlank()) EmptyState(Icons.Filled.Person, "Selecciona un empleado")
                else LoadScreen(days, { vm.reload() }) { ds ->
                    ListScreenColumn {
                        if (ds.isEmpty()) item { EmptyState(Icons.Filled.EventBusy, "Sin fichajes en el periodo") }
                        items(ds, key = { it.date }) { d ->
                            GlassCard(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(fmtDate(d.date), style = MaterialTheme.typography.titleMedium)
                                        Muted(d.fichajes.joinToString("  ") { "${if (it.tipo == "ENTRADA") "E" else "S"} ${fmtTimeOf(it.timestamp)}" }.ifBlank { "Sin fichajes" })
                                        Muted("%.1f h trabajadas".format(d.horasTrabajadas))
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        if (d.isLate) StatusChip("Retraso", Tone.Warning)
                                        if (d.isEarlyDeparture) StatusChip("Salida anticipada", Tone.Warning)
                                        if (!d.isComplete && d.fichajes.isNotEmpty()) StatusChip("Incompleto", Tone.Neutral)
                                    }
                                }
                                if (d.isLate) TextButton({ vm.warnLate(uid, d) }) { Text("Enviar aviso de retraso") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (exportOpen) {
        val now = LocalDate.now()
        var m by remember { mutableStateOf(now.monthValue.toString()) }
        var y by remember { mutableStateOf(now.year.toString()) }
        val person = people.data?.firstOrNull { it.id == uid }
        FormDialog("Exportar informe CSV", "Exportar", y.toIntOrNull() != null && (!admin || true), {
            // Backend only honours userId for admins; managers get their own report.
            vm.export(if (admin && uid.isNotBlank()) uid else null, m.toInt(), y.toInt(), person?.name ?: "propio"); exportOpen = false
        }, { exportOpen = false }) {
            Muted(if (admin) (person?.let { "Empleado: ${it.name}" } ?: "Sin empleado seleccionado: se exporta tu propio informe") else "Se exporta tu propio informe (limitación del servidor).")
            Dropdown("Mes", m, MONTHS_ES.mapIndexed { i, n -> (i + 1).toString() to n }, { m = it })
            XField(y, { y = it }, "Año", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
        }
    }
}

// ───────────────────────── Late notifications sent ─────────────────────────

@HiltViewModel
class AdminLateVm @Inject constructor(private val api: AdminApi, lf: LoaderFactory) : AdminVm() {
    val sent = lf.create<List<LateNotification>>(viewModelScope, "adm_late_sent") { api.lateSent() }
}

@Composable
fun AdminLateScreen(onBack: () -> Unit, snack: (String) -> Unit, vm: AdminLateVm = hiltViewModel()) {
    val st by vm.sent.state.collectAsState()
    AdminPage("Avisos de retraso", "Enviados por ti (se crean desde Fichajes)", onBack, vm, snack) {
        LoadScreen(st, { vm.sent.refresh() }) { list ->
            ListScreenColumn {
                if (list.isEmpty()) item { EmptyState(Icons.Filled.NotificationsNone, "No has enviado avisos") }
                items(list, key = { it.id }) { n ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(n.user?.name ?: "—", style = MaterialTheme.typography.titleMedium)
                                Muted(fmtDate(n.fecha))
                                n.justificacionTexto?.let { Muted("Justificación: $it") }
                            }
                            StatusChip(if (n.justificado) "Justificado" else "Sin justificar", if (n.justificado) Tone.Success else Tone.Warning)
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Shifts (CRUD) ─────────────────────────

@HiltViewModel
class AdminShiftsVm @Inject constructor(private val api: AdminApi, lf: LoaderFactory, private val deps: DepartmentsRepo) : AdminVm() {
    val shifts = lf.create<List<DepartmentShift>>(viewModelScope, "adm_shifts") { api.allShifts() }
    private val _deps = MutableStateFlow<List<String>>(emptyList())
    val departments: StateFlow<List<String>> = _deps.asStateFlow()
    init { viewModelScope.launch { _deps.value = deps.names() } }
    fun create(r: ShiftRequest) = act("Turno creado", { api.createShift(r) }) { shifts.refresh() }
    fun update(id: String, r: ShiftUpdateRequest) = act("Turno actualizado", { api.updateShift(id, r) }) { shifts.refresh() }
    fun delete(id: String) = act("Turno eliminado", { api.deleteShift(id) }) { shifts.refresh() }
}

private val DAYS = listOf("LUNES" to "L", "MARTES" to "M", "MIERCOLES" to "X", "JUEVES" to "J", "VIERNES" to "V", "SABADO" to "S", "DOMINGO" to "D")

@Composable
fun AdminShiftsScreen(onBack: () -> Unit, snack: (String) -> Unit, vm: AdminShiftsVm = hiltViewModel()) {
    val st by vm.shifts.state.collectAsState()
    val deps by vm.departments.collectAsState()
    var edit by remember { mutableStateOf<DepartmentShift?>(null) }
    var creating by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<DepartmentShift?>(null) }
    AdminPage("Turnos y horarios", "Horarios por departamento", onBack, vm, snack,
        actions = { IconAction(Icons.Filled.Add, "Nuevo turno") { creating = true } }) {
        LoadScreen(st, { vm.shifts.refresh() }) { list ->
            ListScreenColumn {
                if (list.isEmpty()) item { EmptyState(Icons.Filled.Schedule, "Aún no hay turnos") }
                items(list.sortedWith(compareBy({ it.department }, { it.name })), key = { it.id }) { s ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("${s.department} · ${s.name}", style = MaterialTheme.typography.titleMedium)
                        Muted("${s.horaEntrada} – ${s.horaSalida}" + (if (!s.horaEntradaTarde.isNullOrBlank()) " (partido ${s.horaSalidaManana ?: ""} / ${s.horaEntradaTarde})" else ""))
                        Muted("Días: ${s.activeDays.split(",").joinToString(" ") { d -> DAYS.firstOrNull { it.first == d.trim() }?.second ?: d }} · tolerancia ${s.toleranciaMinutos} min" + if (s.flexibleSchedule) " · flexible" else "")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconAction(Icons.Filled.Edit, "Editar") { edit = s }
                            IconAction(Icons.Filled.Delete, "Eliminar", danger = true) { del = s }
                        }
                    }
                }
            }
        }
    }
    if (creating || edit != null) {
        val s = edit
        var dept by remember { mutableStateOf(s?.department ?: "") }
        var name by remember { mutableStateOf(s?.name ?: "General") }
        var days by remember { mutableStateOf((s?.activeDays ?: "LUNES,MARTES,MIERCOLES,JUEVES,VIERNES").split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()) }
        var inT by remember { mutableStateOf(s?.horaEntrada ?: "09:00") }
        var outT by remember { mutableStateOf(s?.horaSalida ?: "18:00") }
        var inLate by remember { mutableStateOf(s?.horaEntradaTarde ?: "") }
        var outMorn by remember { mutableStateOf(s?.horaSalidaManana ?: "") }
        var tol by remember { mutableStateOf((s?.toleranciaMinutos ?: 10).toString()) }
        var flex by remember { mutableStateOf(s?.flexibleSchedule ?: false) }
        val optOk = { v: String -> v.isBlank() || TIME_RE.matches(v) }
        val valid = dept.isNotBlank() && name.isNotBlank() && days.isNotEmpty() && TIME_RE.matches(inT) && TIME_RE.matches(outT) &&
            optOk(inLate) && optOk(outMorn) && tol.toIntOrNull() != null
        val close = { creating = false; edit = null }
        FormDialog(if (s == null) "Nuevo turno" else "Editar turno", enabled = valid, onDismiss = close, onConfirm = {
            val csv = DAYS.map { it.first }.filter { it in days }.joinToString(",")
            if (s == null) vm.create(ShiftRequest(dept.trim(), name.trim(), csv, inT, outT, inLate, outMorn, tol.toInt(), flex))
            else vm.update(s.id, ShiftUpdateRequest(name.trim(), csv, inT, outT, inLate, outMorn, tol.toInt(), flex))
            close()
        }) {
            XField(dept, { dept = it }, "Departamento")
            if (s == null && deps.isNotEmpty()) ChipsRow(deps.map { it to it }, dept, { dept = it })
            XField(name, { name = it }, "Nombre del turno")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DAYS.forEach { (d, l) -> FilterChip(d in days, { days = if (d in days) days - d else days + d }, { Text(l) }) }
            }
            Muted("Días activos: " + days.joinToString(", ") { it.lowercase() })
            XField(inT, { inT = it }, "Entrada (HH:mm)", error = if (!TIME_RE.matches(inT)) "HH:mm" else null)
            XField(outT, { outT = it }, "Salida (HH:mm)", error = if (!TIME_RE.matches(outT)) "HH:mm" else null)
            XField(outMorn, { outMorn = it }, "Salida mañana (turno partido, opcional)", error = if (!optOk(outMorn)) "HH:mm" else null)
            XField(inLate, { inLate = it }, "Entrada tarde (turno partido, opcional)", error = if (!optOk(inLate)) "HH:mm" else null)
            XField(tol, { tol = it }, "Tolerancia (minutos)", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(flex, { flex = it }); Spacer(Modifier.width(8.dp)); Text("Horario flexible") }
        }
    }
    del?.let { ConfirmDialog("¿Eliminar el turno ${it.name} de ${it.department}? Se borrarán sus asignaciones.", onConfirm = { vm.delete(it.id) }, onDismiss = { del = null }) }
}

// ───────────────────────── Shift assignment ─────────────────────────

@HiltViewModel
class AdminAssignVm @Inject constructor(private val api: AdminApi) : AdminVm() {
    val people = MutableStateFlow(LoadState<List<User>>())
    val items = MutableStateFlow(LoadState<List<ShiftAssignment>>(loading = false))
    val shifts = MutableStateFlow<List<DepartmentShift>>(emptyList())
    private var cur: Triple<String, Int, Int>? = null

    fun loadPeople(admin: Boolean) = viewModelScope.launch {
        people.value = LoadState(loading = true)
        safeCall { if (admin) api.users() else api.teamMembers() }
            .onSuccess { people.value = LoadState(data = it.sortedBy { u -> u.name }, loading = false) }
            .onFailure { people.value = LoadState(loading = false, error = it.userMessage()) }
    }
    fun load(userId: String, dept: String, month: Int, year: Int) {
        cur = Triple(userId, month, year)
        viewModelScope.launch {
            items.value = LoadState(loading = true)
            shifts.value = safeCall { api.shiftsOf(dept) }.getOrDefault(emptyList()).filter { it.id != "default" }
            safeCall { api.assignments(userId, month, year) }
                .onSuccess { items.value = LoadState(data = it.sortedBy { a -> a.date }, loading = false) }
                .onFailure { items.value = LoadState(loading = false, error = it.userMessage()) }
        }
    }
    fun reload(dept: String) { cur?.let { load(it.first, dept, it.second, it.third) } }
    fun assign(userId: String, shiftId: String, date: String, dept: String) =
        act("Turno asignado", { api.assign(AssignShiftRequest(userId, shiftId, date)) }) { reload(dept) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminAssignScreen(admin: Boolean, onBack: () -> Unit, snack: (String) -> Unit, vm: AdminAssignVm = hiltViewModel()) {
    val people by vm.people.collectAsState()
    val items by vm.items.collectAsState()
    val shifts by vm.shifts.collectAsState()
    val now = LocalDate.now()
    var uid by remember { mutableStateOf("") }
    var month by remember { mutableStateOf(now.monthValue.toString()) }
    var picking by remember { mutableStateOf(false) }
    val person = people.data?.firstOrNull { it.id == uid }
    val dept = person?.department ?: ""
    LaunchedEffect(Unit) { vm.loadPeople(admin) }
    LaunchedEffect(uid, month) { if (uid.isNotBlank()) vm.load(uid, dept, month.toInt(), now.year) }
    AdminPage("Asignar turnos", "Turno por día y empleado", onBack, vm, snack,
        actions = { if (uid.isNotBlank()) IconAction(Icons.Filled.Add, "Asignar") { picking = true } }) {
        LoadScreen(people, { vm.loadPeople(admin) }) { list ->
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Dropdown("Empleado", uid, list.map { it.id to "${it.name} · ${it.department}" }, { uid = it }) }
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Dropdown("Mes ${now.year}", month, MONTHS_ES.mapIndexed { i, n -> (i + 1).toString() to n }, { month = it }) }
                if (uid.isBlank()) EmptyState(Icons.Filled.Person, "Selecciona un empleado")
                else LoadScreen(items, { vm.reload(dept) }) { l ->
                    ListScreenColumn {
                        if (l.isEmpty()) item { EmptyState(Icons.Filled.Schedule, "Sin turnos asignados este mes") }
                        items(l, key = { it.id }) { a ->
                            GlassCard(Modifier.fillMaxWidth()) {
                                Text(fmtDate(a.date), style = MaterialTheme.typography.titleMedium)
                                Muted(a.shift?.let { "${it.name} · ${it.horaEntrada}–${it.horaSalida}" } ?: "Turno")
                            }
                        }
                    }
                }
            }
        }
    }
    if (picking) {
        val ds = rememberDatePickerState()
        var shiftId by remember { mutableStateOf(shifts.firstOrNull()?.id ?: "") }
        val date = ds.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
        FormDialog("Asignar turno", "Asignar", date != null && shiftId.isNotBlank(), {
            vm.assign(uid, shiftId, date!!, dept); picking = false
        }, { picking = false }) {
            if (shifts.isEmpty()) Muted("Este departamento no tiene turnos definidos.")
            else Dropdown("Turno", shiftId, shifts.map { it.id to "${it.name} (${it.horaEntrada}–${it.horaSalida})" }, { shiftId = it })
            DatePicker(ds, showModeToggle = false, title = null, headline = null)
        }
    }
}
