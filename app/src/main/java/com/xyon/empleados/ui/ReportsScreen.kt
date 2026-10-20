package com.xyon.empleados.ui

import android.app.Activity
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xyon.empleados.data.*
import com.xyon.empleados.data.admin.AdminApi
import com.xyon.empleados.data.admin.DepartmentsRepo
import com.xyon.empleados.ui.admin.ChipsRow
import com.xyon.empleados.ui.admin.Dropdown
import com.xyon.empleados.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Inclusive date range for the presets (weeks start on Monday). */
internal fun presetRange(preset: String, today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> = when (preset) {
    "this-week" -> today.with(DayOfWeek.MONDAY).let { it to it.plusDays(6) }
    "last-week" -> today.with(DayOfWeek.MONDAY).minusWeeks(1).let { it to it.plusDays(6) }
    "last-month" -> today.minusMonths(1).withDayOfMonth(1).let { it to it.withDayOfMonth(it.lengthOfMonth()) }
    else -> today.withDayOfMonth(1).let { it to it.withDayOfMonth(it.lengthOfMonth()) }
}

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val api: Api, private val adminApi: AdminApi, private val deps: DepartmentsRepo,
) : ViewModel() {
    private val _departments = MutableStateFlow<List<String>>(emptyList())
    val departments = _departments.asStateFlow()
    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users = _users.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _msg = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val msg = _msg.asSharedFlow()
    private val _pdf = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1) // html, job name
    val pdf = _pdf.asSharedFlow()

    fun load(user: User) = viewModelScope.launch {
        if (user.isAdmin) {
            _departments.value = runCatching { deps.names() }.getOrDefault(emptyList()).filter { it != "Sin asignar" }
        } else if (user.isManager) {
            _departments.value = listOf(user.department)
            _users.value = runCatching { api.teamMembers() }.getOrDefault(emptyList()).sortedBy { it.name }
        }
    }

    fun loadUsers(department: String, user: User) = viewModelScope.launch {
        if (user.isAdmin) _users.value = runCatching { adminApi.users() }.getOrDefault(emptyList()).filter { it.department == department }.sortedBy { it.name }
    }

    fun download(ctx: android.content.Context, from: LocalDate, to: LocalDate, userId: String?, department: String?, csv: Boolean) = viewModelScope.launch {
        _busy.value = true
        safeCall {
            if (csv) {
                val bytes = api.reportCsv(from.toString(), to.toString(), userId, department, "csv").bytes()
                val label = department ?: userId?.let { id -> _users.value.firstOrNull { it.id == id }?.name } ?: "mi-registro"
                saveToDownloads(ctx, bytes, "registro-jornada-$label-${from}_$to.csv", "text/csv")
            } else {
                val data = api.reportData(from.toString(), to.toString(), userId, department, "json")
                _pdf.tryEmit(reportHtml(data, ServerConfig.companyName) to data.fileBase)
                null
            }
        }.onSuccess { where -> where?.let { _msg.tryEmit("Guardado en $it") } }
            .onFailure { _msg.tryEmit(it.userMessage()) }
        _busy.value = false
    }
}

/**
 * Report screen. [self] = the employee's own record ("Mi registro"); otherwise a manager chooses
 * a person of their team and an admin a department or a person.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(user: User, self: Boolean, onBack: () -> Unit, snack: (String) -> Unit, vm: ReportsViewModel = hiltViewModel()) {
    SecureScreen()
    val x = Xyon.colors
    val activity = LocalContext.current as Activity
    val departments by vm.departments.collectAsState()
    val users by vm.users.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { if (!self) vm.load(user) }
    LaunchedEffect(Unit) { vm.msg.collect { snack(it) } }
    LaunchedEffect(Unit) { vm.pdf.collect { (html, name) -> printHtml(activity, html, name) } }

    var scope by remember { mutableStateOf(if (user.isAdmin) "department" else "user") }
    var department by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf("this-month") }
    var custom by remember { mutableStateOf(presetRange("this-month")) }
    var picking by remember { mutableStateOf(false) }
    var csv by remember { mutableStateOf(false) }

    LaunchedEffect(departments) { if (department.isBlank()) department = departments.firstOrNull() ?: "" }
    LaunchedEffect(department, scope) { if (!self && scope == "user" && department.isNotBlank()) vm.loadUsers(department, user) }
    LaunchedEffect(users) { if (users.none { it.id == userId }) userId = users.firstOrNull()?.id ?: "" }

    val range = if (preset == "custom") custom else presetRange(preset)
    val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    val tooLong = range.second.toEpochDay() - range.first.toEpochDay() + 1 > 366
    val ready = !busy && !tooLong && (self || (scope == "department" && department.isNotBlank()) || (scope == "user" && userId.isNotBlank()))

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(if (self) "Mi registro" else "Informes", if (self) "Tu registro de jornada" else "Asistencia en PDF o CSV", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!self) GlassCard(Modifier.fillMaxWidth()) {
                if (user.isAdmin) {
                    Text("Para quién", fontWeight = FontWeight.Bold, color = x.ink)
                    ChipsRow(listOf("department" to "Departamento", "user" to "Empleado"), scope, { scope = it })
                    if (departments.isNotEmpty()) Dropdown("Departamento", department, departments.map { it to it }, { department = it })
                }
                if (scope == "user") {
                    if (users.isEmpty()) Text("Sin empleados en este departamento", color = x.inkSoft)
                    else Dropdown("Empleado", userId, users.map { it.id to it.name }, { userId = it })
                }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Periodo", fontWeight = FontWeight.Bold, color = x.ink)
                ChipsRow(listOf("this-week" to "Esta semana", "last-week" to "Semana pasada", "this-month" to "Este mes", "last-month" to "Mes pasado", "custom" to "Fechas…"), preset, {
                    if (it == "custom") { custom = range; picking = true }
                    preset = it
                })
                Text("${range.first.format(fmt)} – ${range.second.format(fmt)}", color = x.inkSoft, modifier = Modifier.padding(top = 6.dp))
                if (tooLong) Text("El máximo es un año", color = Danger, fontWeight = FontWeight.Bold)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Formato", fontWeight = FontWeight.Bold, color = x.ink)
                ChipsRow(listOf("pdf" to "PDF", "csv" to "CSV (Excel)"), if (csv) "csv" else "pdf", { csv = it == "csv" })
                Text(if (csv) "Se guarda en Descargas." else "Se abre el diálogo del sistema para guardarlo como PDF o imprimirlo.", color = x.inkSoft)
            }
            Text("Contiene datos personales: guárdalo solo donde sea necesario.", color = x.inkSoft)
            XyonButton("Descargar", {
                AppLock.beginExternal() // the print dialog is another screen: don't lock the app
                vm.download(activity, range.first, range.second,
                    if (self || scope != "user") null else userId,
                    if (self || scope != "department") null else department, csv)
            }, Modifier.fillMaxWidth(), enabled = ready, loading = busy, icon = Icons.Filled.Download)
            Spacer(Modifier.height(120.dp))
        }
    }

    if (picking) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = custom.first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            initialSelectedEndDateMillis = custom.second.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null, onClick = {
                    val toDate = { ms: Long -> Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate() }
                    custom = toDate(state.selectedStartDateMillis!!) to toDate(state.selectedEndDateMillis!!)
                    picking = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Cancelar") } },
        ) { DateRangePicker(state, Modifier.heightIn(max = 520.dp)) }
    }
}

// WebView kept alive until the print job is created
private var printView: WebView? = null

/** Renders the report HTML and opens the system "Save as PDF / Print" dialog. */
private fun printHtml(activity: Activity, html: String, jobName: String) {
    val view = WebView(activity)
    printView = view
    view.webViewClient = object : WebViewClient() {
        override fun onPageFinished(v: WebView, url: String?) {
            val pm = activity.getSystemService(PrintManager::class.java)
            pm.print(jobName, v.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
            printView = null
            AppLock.endExternal()
        }
    }
    view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
}

private fun esc(s: String?) = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private val KIND_STYLE = mapOf(
    "ok" to "background:#dcfce7;color:#166534", "incident" to "background:#FFF5CC;color:#6B5300",
    "missing" to "background:#FFE3DB;color:#8A2A14", "absence" to "background:#EEE8FF;color:#3B2A80",
)
private val MODE = mapOf("OFFICE" to "Oficina", "REMOTE" to "Casa", "MIXED" to "Mixta")

/** Same look as the web PDF: ink header, lime rule, summary pills, coloured status cells. */
internal fun reportHtml(d: AttendanceReportData, company: String?): String {
    val generated = runCatching {
        Instant.parse(d.generatedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
    }.getOrDefault("")
    val sections = d.reports.joinToString("") { r ->
        val s = r.summary
        val pills = listOf(
            "Días trabajados" to "${s.daysWorked}" to "#EEFBC4", "Horas" to "%.2f h".format(s.totalHours).replace('.', ',') to "#EEE8FF",
            "Sin fichar" to "${s.daysAbsent}" to "#FFE3DB", "Llegadas tarde" to "${s.daysLate}" to "#FFF5CC",
            "Vacaciones" to "${s.daysVacation}" to "#dcfce7", "Teletrabajo" to "${s.daysRemote}" to "#EAE7DC",
        ).joinToString("") { (lv, bg) -> "<div class='pill' style='background:$bg'><b>${esc(lv.second)}</b><span>${esc(lv.first)}</span></div>" }
        val rows = r.days.joinToString("") { day ->
            val muted = day.kind == "weekend" || day.kind == "future"
            val date = day.date.split("-").reversed().joinToString("/")
            "<tr${if (muted) " class='muted'" else ""}><td>$date</td><td>${esc(day.dayName)}</td><td>${esc(day.firstEntry ?: "–")}</td><td>${esc(day.lastExit ?: "–")}</td>" +
                "<td class='r'>${day.hours?.let { "%.2f".format(it).replace('.', ',') } ?: "–"}</td><td>${esc(day.workMode?.let { MODE[it] } ?: "")}</td>" +
                "<td class='st' style='${KIND_STYLE[day.kind] ?: ""}'>${esc(day.status)}</td><td>${esc(day.observations)}</td></tr>"
        }
        "<section><h2>${esc(r.user.name)}</h2><p class='sub'>${esc(listOfNotNull(r.user.department, r.user.position).joinToString(" · "))}</p>" +
            "<div class='pills'>$pills</div><table><thead><tr><th>Fecha</th><th>Día</th><th>Entrada</th><th>Salida</th><th>Horas</th><th>Lugar</th><th>Estado</th><th>Observaciones</th></tr></thead><tbody>$rows</tbody></table></section>"
    }
    return """<!DOCTYPE html><html lang="es"><head><meta charset="utf-8"><style>
@page { size: A4; margin: 14mm 12mm 16mm }
body { font-family: sans-serif; color:#0C0C12; font-size: 9pt; margin:0 }
header { background:#0C0C12; color:#F4F2EA; padding:14px 16px; border-bottom:4px solid #C8F53C; display:flex; justify-content:space-between; align-items:center }
header h1 { margin:0; font-size:16pt } header .p { color:#C8F53C; margin-top:2px } header .c { font-size:9pt }
section { page-break-after: always; margin-top:14px } section:last-of-type { page-break-after: auto }
h2 { margin:0; font-size:13pt } .sub { color:#474955; margin:2px 0 8px }
.pills { display:flex; gap:6px; margin-bottom:8px } .pill { flex:1; border:1.5px solid #0C0C12; border-radius:8px; padding:5px 7px }
.pill b { display:block; font-size:11pt } .pill span { color:#474955; font-size:7.5pt }
table { width:100%; border-collapse:collapse } th { background:#0C0C12; color:#F4F2EA; text-align:left; padding:4px 5px; font-size:8pt }
td { padding:3px 5px; border-bottom:0.5px solid #D9D5C7 } td.r { text-align:right } td.st { font-weight:bold }
tr.muted td { color:#8A8C96 } thead { display: table-header-group }
footer { margin-top:10px; color:#474955; font-size:7pt; border-top:0.5px solid #D9D5C7; padding-top:4px }
</style></head><body>
<header><div><h1>Registro de jornada</h1><div class="p">${esc(d.period.label)}</div></div><div class="c">${esc(company)}</div></header>
$sections
<footer>Generado el $generated · Documento confidencial: contiene datos personales (RGPD). No lo compartas fuera de la empresa.</footer>
</body></html>"""
}
