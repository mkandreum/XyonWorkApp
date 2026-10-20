package com.xyon.empleados.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xyon.empleados.data.*
import com.xyon.empleados.ui.theme.*
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth

// ───────── Calendar (personal / team) — also home of "Mis fichajes" ─────────

/** Everything the calendar knows about one day, computed once per render. */
private data class DayInfo(
    val date: LocalDate,
    val stats: FichajeDayStats?,
    val isToday: Boolean,
    /** Approved vacation or absence covering the day. */
    val absence: VacationRequest?,
    val worked: Double,
    val expected: Double,
    val hasRecords: Boolean,
    /** Today with an entry still open. */
    val live: Boolean,
    /** Late / early / open entry with no adjustment request yet. */
    val issue: Boolean,
    val overtime: Boolean,
    /** Past working day with no record and no absence. */
    val missed: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(user: User, vm: CalendarViewModel = hiltViewModel(), snack: (String) -> Unit = {}, onMyRecord: () -> Unit = {}, onGoHome: () -> Unit = {}) {
    val x = Xyon.colors
    val events by vm.events.state.collectAsState()
    val mine by vm.mine.state.collectAsState()
    val team by vm.team.state.collectAsState()
    val adj by vm.adjustments.state.collectAsState()
    val daysByMonth by vm.days.collectAsState()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var sheetOpen by remember { mutableStateOf(false) }
    var showTeam by remember { mutableStateOf(false) }
    var adjustFor by remember { mutableStateOf<Fichaje?>(null) }
    var requestExitFor by remember { mutableStateOf<Fichaje?>(null) }
    LaunchedEffect(Unit) { vm.msg.collect { snack(it) } }
    LaunchedEffect(month) { vm.loadMonth(month) }

    // Minute tick: today's open entry counts up to now
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); nowMillis = System.currentTimeMillis() } }
    val today = remember(nowMillis) { LocalDate.now() }

    val myVac = mine.data.orEmpty().filter { it.status == "APPROVED" }
    val teamVac = if (showTeam) team.data.orEmpty().filter { it.status == "APPROVED" } else emptyList()
    val evs = events.data.orEmpty()
    val requests = adj.data.orEmpty()
    val monthDays = daysByMonth[month].orEmpty()
    val statsByDate = monthDays.associateBy { parseDate(it.date) }

    fun vacOn(day: LocalDate, list: List<VacationRequest>) = list.filter {
        val s = parseDate(it.startDate); val e = parseDate(it.endDate)
        s != null && e != null && !day.isBefore(s) && !day.isAfter(e)
    }
    fun evOn(day: LocalDate) = evs.filter { parseDate(it.date) == day }

    fun info(day: LocalDate): DayInfo {
        val stats = statsByDate[day]
        val isToday = day == today
        val absence = vacOn(day, myVac).firstOrNull()
        val worked = CalendarMath.workedHours(stats, nowMillis, isToday)
        val expected = if (absence != null) 0.0 else CalendarMath.expectedHours(day, stats)
        val has = stats != null && stats.fichajes.isNotEmpty()
        return DayInfo(
            date = day, stats = stats, isToday = isToday, absence = absence, worked = worked, expected = expected, hasRecords = has,
            live = CalendarMath.liveEntry(stats, nowMillis) != null,
            issue = CalendarMath.hasUnresolvedIssue(stats, requests, isToday, nowMillis),
            overtime = CalendarMath.isOvertime(worked, expected),
            missed = !has && absence == null && expected > 0 && day.isBefore(today),
        )
    }

    val infos = (1..month.lengthOfMonth()).map { info(month.atDay(it)) }
    val infoByDay = infos.associateBy { it.date }
    val vacationDays = infos.count { it.absence?.type == "VACATION" }
    val otherAbsenceDays = infos.count { it.absence != null && it.absence.type != "VACATION" }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(if (showTeam) "Calendario del equipo" else "Calendario", "Tus fichajes, ausencias y eventos") {
            // Right of access to the own time-tracking record (RD-ley 8/2019)
            IconButton(onMyRecord) { Icon(Icons.Filled.Description, "Mi registro de jornada") }
            if (user.isManager) FilterChip(showTeam, { showTeam = !showTeam }, { Text("Equipo") }, leadingIcon = { Icon(Icons.Filled.Groups, null, Modifier.size(18.dp)) })
        }
        LoadScreen(events, { vm.refresh(month) }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 130.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                XyonCard(Modifier.fillMaxWidth().staggered(0)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ month = month.minusMonths(1) }) { Icon(Icons.Filled.ChevronLeft, "Mes anterior") }
                        Text("${monthName(month.monthValue)} ${month.year}", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
                        if (month != YearMonth.from(today)) TextButton({ month = YearMonth.from(today); selected = today }) { Text("Hoy", fontWeight = FontWeight.ExtraBold) }
                        IconButton({ month = month.plusMonths(1) }) { Icon(Icons.Filled.ChevronRight, "Mes siguiente") }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("L", "M", "X", "J", "V", "S", "D").forEach {
                            Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = x.inkSoft, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    val lead = month.atDay(1).dayOfWeek.value - 1
                    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
                    cells.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            for (i in 0 until 7) {
                                val day = week.getOrNull(i)
                                if (day == null) Spacer(Modifier.weight(1f).height(56.dp))
                                else DayCell(
                                    infoByDay.getValue(day), selected = day == selected,
                                    hasEvent = evOn(day).isNotEmpty(), hasTeam = vacOn(day, teamVac).isNotEmpty(),
                                    modifier = Modifier.weight(1f),
                                ) { selected = day; sheetOpen = true }
                            }
                        }
                    }

                    // Legend with the month's counts
                    val legend = buildList {
                        add(LegendEntry(Icons.Filled.Schedule, x.blue.copy(alpha = .15f), x.blue, "Trabajado", infos.count { it.hasRecords }))
                        if (infos.any { it.live }) add(LegendEntry(Icons.Filled.Sensors, Lime, InkBlack, "Fichando ahora", null))
                        add(LegendEntry(Icons.AutoMirrored.Filled.TrendingUp, Sun.copy(alpha = .4f), Warning, "Horas extra", infos.count { it.overtime }))
                        add(LegendEntry(Icons.Filled.Warning, Sun, InkBlack, "Incidencias sin resolver", infos.count { it.issue }))
                        add(LegendEntry(Icons.Filled.ErrorOutline, Coral.copy(alpha = .22f), Coral, "Sin fichar", infos.count { it.missed }))
                        add(LegendEntry(Icons.Filled.Flight, x.blue, Color.White, "Vacaciones", vacationDays))
                        add(LegendEntry(Icons.Filled.Coffee, Lilac.copy(alpha = .45f), x.ink, "Otras ausencias", otherAbsenceDays))
                        if (showTeam) add(LegendEntry(Icons.Filled.Groups, Violet.copy(alpha = .2f), Violet, "Equipo", null))
                        add(LegendEntry(Icons.Filled.Event, Coral.copy(alpha = .22f), Coral, "Evento", null))
                    }
                    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        legend.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { LegendItem(it, Modifier.weight(1f)) }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                MonthSummary(
                    worked = infos.sumOf { it.worked }, scheduled = infos.sumOf { it.expected },
                    daysWorked = infos.count { it.hasRecords }, workingDays = infos.count { it.expected > 0 },
                    overtimeHours = infos.filter { it.overtime }.sumOf { it.worked - it.expected },
                    modifier = Modifier.staggered(1),
                )

                if (requests.isNotEmpty()) {
                    SectionHeader("Mis solicitudes de ajuste")
                    requests.take(10).forEach { a ->
                        XyonCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${fmtDate(a.originalTimestamp)} · ${fmtTimeOf(a.originalTimestamp)} → ${fmtTimeOf(a.requestedTimestamp)}", style = MaterialTheme.typography.titleMedium)
                                    Text(a.reason, color = x.inkSoft, maxLines = 2)
                                    a.rejectionReason?.let { Text("Motivo: $it", color = Danger, style = MaterialTheme.typography.bodyMedium) }
                                }
                                StatusChip(statusLabel(a.status), statusTone(a.status))
                            }
                        }
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = x.bg,
        ) {
            DaySheet(
                info = info(selected), requests = requests, nowMillis = nowMillis,
                events = evOn(selected), mine = vacOn(selected, myVac), teamVac = vacOn(selected, teamVac),
                onClose = { sheetOpen = false },
                onClockNow = { sheetOpen = false; onGoHome() },
                onAdjust = { adjustFor = it },
                onRemind = { vm.remind(it.id) },
                onRequestExit = { requestExitFor = it },
            )
        }
    }
    adjustFor?.let { f ->
        AdjustFichajeDialog(f, onDismiss = { adjustFor = null }) { req -> vm.requestAdjustment(req); adjustFor = null }
    }
    requestExitFor?.let { f ->
        RequestExitDialog(f, onDismiss = { requestExitFor = null }) { req -> vm.requestAdjustment(req); requestExitFor = null }
    }
}

// ───────── Month grid ─────────

/** Rounded day cell: number, worked hours and the state marks (incident, live, events). */
@Composable
private fun DayCell(info: DayInfo, selected: Boolean, hasEvent: Boolean, hasTeam: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val x = Xyon.colors
    val weekend = info.date.dayOfWeek.value >= 6
    val vacation = info.absence?.type == "VACATION"
    val target = when {
        selected -> x.blue
        info.absence != null -> if (vacation) x.blue.copy(alpha = .14f) else Lilac.copy(alpha = .35f)
        info.missed -> Coral.copy(alpha = .16f)
        weekend -> x.ink.copy(alpha = .05f)
        else -> x.card
    }
    val bg by animateColorAsState(target, spring(Spring.DampingRatioMediumBouncy), label = "cell")
    val fg = if (selected) Color.White else x.ink
    val hours = when {
        info.hasRecords -> CalendarMath.formatHM(info.worked)
        info.absence != null -> if (vacation) "Vac." else "Aus."
        else -> ""
    }
    val hoursColor = when {
        selected -> if (info.overtime) Sun else Color.White
        info.overtime -> Warning
        else -> x.inkSoft
    }
    val stroke = if (info.isToday) BorderStroke(2.dp, x.blue) else BorderStroke(1.dp, if (selected) x.blue else x.outline.copy(alpha = .35f))
    val shape = RoundedCornerShape(14.dp)
    val label = buildString {
        append(info.date.dayOfMonth)
        if (info.hasRecords) append(", ${CalendarMath.formatHoursMinutes(info.worked)} trabajadas")
        if (info.issue) append(", incidencia sin resolver")
        if (info.missed) append(", sin fichar")
        if (info.absence != null) append(if (vacation) ", vacaciones" else ", ausencia")
    }
    Column(
        modifier.height(56.dp).clip(shape).background(bg).border(stroke, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp)
            .semantics { contentDescription = label },
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text("${info.date.dayOfMonth}", fontWeight = if (selected || info.isToday) FontWeight.Black else FontWeight.SemiBold, color = fg, fontSize = 14.sp, lineHeight = 14.sp)
            Spacer(Modifier.weight(1f))
            when {
                info.issue -> Icon(Icons.Filled.Warning, null, tint = if (selected) Sun else Warning, modifier = Modifier.size(13.dp))
                info.live -> Box(Modifier.padding(top = 2.dp).size(8.dp).clip(CircleShape).background(Lime))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(hours, color = hoursColor, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, lineHeight = 11.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (hasTeam) Box(Modifier.padding(start = 2.dp).size(5.dp).clip(CircleShape).background(Violet))
            if (hasEvent) Box(Modifier.padding(start = 2.dp).size(5.dp).clip(CircleShape).background(Coral))
        }
    }
}

private data class LegendEntry(val icon: ImageVector, val bg: Color, val fg: Color, val label: String, val count: Int?)

@Composable
private fun LegendItem(e: LegendEntry, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).background(e.bg), contentAlignment = Alignment.Center) {
            Icon(e.icon, null, tint = e.fg, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text("${e.label}${e.count?.let { " ($it)" }.orEmpty()}", color = Xyon.colors.inkSoft, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

// ───────── Month summary ─────────

@Composable
private fun MonthSummary(worked: Double, scheduled: Double, daysWorked: Int, workingDays: Int, overtimeHours: Double, modifier: Modifier = Modifier) {
    val x = Xyon.colors
    val pct = if (scheduled > 0) (worked / scheduled).coerceIn(0.0, 1.0).toFloat() else 0f
    val average = if (daysWorked > 0) worked / daysWorked else 0.0
    XyonCard(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryTile("Horas totales", CalendarMath.formatHoursMinutes(worked), Lime, InkBlack, Modifier.weight(1f))
            SummaryTile("Horas programadas", CalendarMath.formatHoursMinutes(scheduled), x.ink.copy(alpha = .06f), x.ink, Modifier.weight(1f))
        }
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(10.dp).clip(CircleShape).background(x.outline.copy(alpha = .3f))) {
            Box(Modifier.fillMaxWidth(pct).fillMaxHeight().clip(CircleShape).background(x.blue))
        }
        Text("${(pct * 100).toInt()}% de las horas del mes", color = x.inkSoft, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryTile("Días trabajados", "$daysWorked / $workingDays", Lilac.copy(alpha = .45f), x.ink, Modifier.weight(1f))
            SummaryTile(
                "Media / día", CalendarMath.formatHoursMinutes(average), Sun.copy(alpha = .45f), x.ink, Modifier.weight(1f),
                hint = if (overtimeHours > 0.01) "+${CalendarMath.formatHoursMinutes(overtimeHours)} extra" else null,
            )
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, bg: Color, fg: Color, modifier: Modifier = Modifier, hint: String? = null) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(bg).padding(14.dp)) {
        Text(label.uppercase(), color = fg.copy(alpha = .7f), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .6.sp, maxLines = 1)
        Text(value, color = fg, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
        hint?.let { Text(it, color = fg.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold) }
    }
}

// ───────── Day sheet ─────────

@Composable
private fun DaySheet(
    info: DayInfo, requests: List<FichajeAdjustment>, nowMillis: Long,
    events: List<EventItem>, mine: List<VacationRequest>, teamVac: List<VacationRequest>,
    onClose: () -> Unit, onClockNow: () -> Unit, onAdjust: (Fichaje) -> Unit, onRemind: (FichajeAdjustment) -> Unit, onRequestExit: (Fichaje) -> Unit,
) {
    val x = Xyon.colors
    val stats = info.stats
    val pairs = CalendarMath.pairs(stats?.fichajes.orEmpty(), nowMillis)
    val openEntry = pairs.lastOrNull()?.takeIf { it.entrada != null && it.salida == null }?.entrada
    // A shift still in progress is not a forgotten exit: no exit request or "incomplete" tag for it
    val canRequestExit = openEntry != null && !info.live && !info.date.isAfter(LocalDate.now())
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtDate(info.date.toString()).uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            IconButton(onClose) { Icon(Icons.Filled.Close, "Cerrar") }
        }
        if (stats != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (stats.isLate) StatusChip("Retraso", Tone.Warning)
                if (stats.isEarlyDeparture) StatusChip("Salida anticipada", Tone.Warning)
                if (stats.fichajes.isNotEmpty() && !info.isToday && !info.live && !stats.isComplete) StatusChip("Registro incompleto", Tone.Warning)
                stats.turno?.label?.let { StatusChip(it, Tone.Info) }
            }
            stats.daySchedule?.let { s ->
                if (s.horaEntrada != null && s.horaSalida != null) {
                    Text("Horario previsto: ${s.horaEntrada} - ${s.horaSalida}", color = x.inkSoft, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        // Same clock-in button as Home, for today
        if (info.isToday) {
            TextButton(onClockNow) {
                Icon(Icons.AutoMirrored.Filled.Login, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (info.live) "Fichar salida" else "Fichar ahora", fontWeight = FontWeight.Bold)
            }
        }

        pairs.forEach { p -> SessionCard(p, requests, onAdjust, onRemind) }

        if (canRequestExit) {
            TextButton({ onRequestExit(openEntry!!) }) {
                Icon(Icons.AutoMirrored.Filled.Logout, null, tint = Coral, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Solicitar salida", color = Coral, fontWeight = FontWeight.Bold)
            }
        }

        events.forEach { e ->
            XyonCard(Modifier.fillMaxWidth(), accent = Coral) {
                Text(e.title, style = MaterialTheme.typography.titleMedium)
                e.location?.let { Text(it, color = x.inkSoft) }; e.description?.let { Text(it, color = x.inkSoft) }
            }
        }
        mine.forEach { VacationRow(it) }
        teamVac.forEach { VacationRow(it) }

        if (pairs.isEmpty() && events.isEmpty() && mine.isEmpty() && teamVac.isEmpty()) {
            EmptyState(Icons.Filled.EventAvailable, if (info.date.isAfter(LocalDate.now())) "Nada programado" else "Sin fichajes ni ausencias")
        }

        if (pairs.isNotEmpty() || info.expected > 0) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Lime).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Total", Modifier.weight(1f), color = InkBlack, fontWeight = FontWeight.ExtraBold)
                Column(horizontalAlignment = Alignment.End) {
                    Text(CalendarMath.formatHoursMinutes(info.worked), color = InkBlack, fontWeight = FontWeight.Black, fontSize = 22.sp)
                    if (info.expected > 0) Text("de ${CalendarMath.formatHoursMinutes(info.expected)} previstas", color = InkBlack.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** One ENTRADA→SALIDA session: times (tap to ask for an adjustment), duration and request status. */
@Composable
private fun SessionCard(p: EntryPair, requests: List<FichajeAdjustment>, onAdjust: (Fichaje) -> Unit, onRemind: (FichajeAdjustment) -> Unit) {
    val x = Xyon.colors
    val reqIn = p.entrada?.let { e -> requests.firstOrNull { it.fichajeId == e.id } }
    val reqOut = p.salida?.let { s -> requests.firstOrNull { it.fichajeId == s.id } }
    val request = reqIn ?: reqOut
    val pending = request?.status == "PENDING"
    val accent = when { pending -> Sun; p.salida != null -> Lime; else -> x.blue }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(16.dp)).background(x.card)) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.entrada != null) TimeButton(p.entrada, reqIn, onAdjust)
                else Text("Turno de ayer", color = x.inkSoft, fontWeight = FontWeight.ExtraBold)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = x.inkSoft, modifier = Modifier.padding(horizontal = 8.dp).size(16.dp))
                if (p.salida != null) TimeButton(p.salida, reqOut, onAdjust)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(x.blue))
                    Text("En curso", Modifier.padding(start = 6.dp), color = x.blue, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    CalendarMath.formatHoursMinutes(p.hours), color = x.inkSoft, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clip(CircleShape).background(x.ink.copy(alpha = .07f)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            request?.let {
                val (text, tone) = when (it.status) {
                    "PENDING" -> "Esperando aprobación del responsable" to Tone.Warning
                    "APPROVED" -> "Ajuste aprobado" to Tone.Success
                    else -> "Ajuste rechazado" to Tone.Danger
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(text, tone)
                    if (it.status == "PENDING") {
                        TextButton({ onRemind(it) }) {
                            Icon(Icons.Filled.NotificationsActive, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Recordar al responsable", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }
}

/** A clock time; tapping it opens the adjustment request unless one is already pending. */
@Composable
private fun TimeButton(f: Fichaje, request: FichajeAdjustment?, onAdjust: (Fichaje) -> Unit) {
    val x = Xyon.colors
    val canAdjust = request?.status != "PENDING"
    val time = fmtTimeOf(f.timestamp)
    Row(
        Modifier.clip(RoundedCornerShape(10.dp))
            .then(if (canAdjust) Modifier.clickable(role = Role.Button) { onAdjust(f) }.semantics { contentDescription = "Solicitar ajuste del fichaje de $time" } else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, fontWeight = FontWeight.Black, fontSize = 18.sp)
        if (canAdjust) Icon(Icons.Filled.Edit, null, tint = x.inkSoft, modifier = Modifier.padding(start = 4.dp).size(13.dp))
    }
}

// ───────── Open entry: ask for the exit ─────────

/** Entrada abierta sin salida: el empleado pide al responsable que la añada (ADD_EXIT). */
@Composable
private fun RequestExitDialog(entry: Fichaje, onDismiss: () -> Unit, onSend: (CreateAdjustmentRequest) -> Unit) {
    var time by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    val valid = Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(time) && reason.trim().length >= 5
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Solicitar salida") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Entrada del ${fmtDate(entry.timestamp)} a las ${fmtTimeOf(entry.timestamp)} sin salida registrada.",
                    color = Xyon.colors.inkSoft,
                )
                XField(time, { time = it }, "Hora de salida (HH:mm)")
                XField(reason, { reason = it }, "Motivo (mín. 5 caracteres)", singleLine = false, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val date = parseDate(entry.timestamp) ?: return@TextButton
                val ldt = java.time.LocalDateTime.of(date, java.time.LocalTime.parse(time))
                val iso = ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toString()
                onSend(CreateAdjustmentRequest(entry.id, iso, reason.trim(), "ADD_EXIT"))
            }) { Text("Enviar") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}
