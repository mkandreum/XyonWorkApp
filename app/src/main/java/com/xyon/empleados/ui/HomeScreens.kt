package com.xyon.empleados.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xyon.empleados.data.*
import com.xyon.empleados.ui.theme.*
import com.xyon.empleados.ui.admin.RoleHero

@Composable
fun HomeScreen(
    user: User, onNotifications: () -> Unit, onFichajes: () -> Unit, onPayroll: () -> Unit,
    onNews: () -> Unit, onTeam: () -> Unit, onAdmin: () -> Unit, notifVm: NotificationsViewModel = hiltViewModel(),
    vm: DashboardViewModel = hiltViewModel(), snack: (String) -> Unit, roleVm: RoleViewModel = hiltViewModel(),
) {
    val rolePending by roleVm.pending.collectAsState()
    LaunchedEffect(user.id) { roleVm.load(user) }
    val ctx = LocalContext.current
    val status by vm.status.state.collectAsState()
    val vacations by vm.vacations.state.collectAsState()
    val balance by vm.balance.state.collectAsState()
    val news by vm.news.state.collectAsState()
    val week by vm.week.state.collectAsState()
    val notifs by notifVm.items.state.collectAsState()
    val warnings by vm.warnings.state.collectAsState()
    var justifyFor by remember { mutableStateOf<AttendanceWarning?>(null) }
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { vm.events.collect { snack(it) } }

    var pendingToggle by remember { mutableStateOf<Boolean?>(null) }
    fun go(entering: Boolean) {
        val loc = lastLocation(ctx)
        vm.fichar(entering, loc?.latitude, loc?.longitude, loc?.accuracy)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingToggle?.let { e -> go(e); pendingToggle = null }
    }

    // Keep local clock-in/out reminders in sync with the latest status
    LaunchedEffect(status.data) {
        status.data?.let { s -> Reminders.sync(ctx, s.hasActiveEntry, s.turno?.expectedEntry, s.turno?.expectedExit) }
    }

    val unread = notifs.data?.notifications?.count { !it.read } ?: 0
    val clockedIn = status.data?.hasActiveEntry == true

    LoadScreen(
        status, onRefresh = { vm.refreshAll(); notifVm.items.refresh() },
    ) {
        ListScreenColumn {
            item {
                Row(Modifier.statusBarsPadding().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting(), color = Xyon.colors.inkSoft)
                        Text(user.name.substringBefore(" "), style = MaterialTheme.typography.displayMedium)
                    }
                    BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
                        IconButton(onNotifications) { Icon(Icons.Filled.Notifications, "Notificaciones") }
                    }
                    Spacer(Modifier.width(4.dp)); Avatar(user.name)
                }
            }
            item {
                Box(Modifier.fillMaxWidth().staggered(0).clip(RoundedCornerShape(40.dp)).border(1.5.dp, Xyon.colors.outline, RoundedCornerShape(40.dp))) { BlueField(Modifier.matchParentSize()) {}; Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FichajeHero(
                        clockedIn = clockedIn, since = it.currentFichaje?.timestamp, busy = busy,
                        expected = it.turno?.label ?: it.turno?.expectedEntry?.let { e -> "Entrada prevista $e" },
                        successEvents = vm.justDone,
                    ) {
                        val entering = !clockedIn
                        if (!hasLocationPermission(ctx)) {
                            pendingToggle = entering
                            permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        } else go(entering)
                    }
                } }
            }
            status.data?.missingExit?.let { m ->
                item {
                    val shape = RoundedCornerShape(28.dp)
                    Row(
                        Modifier.fillMaxWidth().clip(shape).background(Coral).border(1.5.dp, Xyon.colors.outline, shape).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.AlarmOff, null, tint = InkBlack)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Falta la salida del ${fmtDate(m.timestamp)}", color = InkBlack, fontWeight = FontWeight.Black)
                            Text("Entraste a las ${fmtTimeOf(m.timestamp)}. Pide a tu responsable que registre la salida.", color = InkBlack.copy(alpha = .8f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            val pendingWarnings = warnings.data.orEmpty().filter { w -> !w.justificado }
            if (pendingWarnings.isNotEmpty()) {
                item { AttendanceAlert(pendingWarnings, Modifier.staggered(1)) { w -> justifyFor = w } }
            }
            item {
                val pending = vacations.data?.count { v -> v.status.startsWith("PENDING") } ?: 0
                val left = balance.data?.vacationDaysUsed
                val hoursWeek = week.data?.sumOf { d -> d.horasTrabajadas } ?: 0.0
                Row(Modifier.fillMaxWidth().staggered(1), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatPill("Esta semana", "%.1fh".format(hoursWeek), Icons.Filled.Schedule, Lime, Modifier.weight(1f))
                    StatPill("Pendientes", "$pending", Icons.Filled.HourglassTop, Lilac, Modifier.weight(1f))
                    StatPill("Días usados", left?.let { v -> "%.0f".format(v) } ?: "-", Icons.Filled.BeachAccess, Sun, Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().staggered(2), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuickTile("Mis fichajes", Icons.Filled.History, onFichajes, Modifier.weight(1f))
                    QuickTile("Nóminas", Icons.Filled.Receipt, onPayroll, Modifier.weight(1f))
                }
            }
            if (user.isManager) {
                item {
                    RoleHero(
                        title = if (user.isAdmin) "Administración" else "Tu equipo · ${user.department}",
                        pending = rolePending,
                        onReview = { if (user.isAdmin) onAdmin() else onTeam() },
                        modifier = Modifier.staggered(3),
                        actionLabel = "Revisar ahora",
                    )
                }
            }
            val latest = news.data.orEmpty().take(5)
            if (latest.isNotEmpty()) {
                item { SectionTitle("Novedades", action = { TextButton(onNews) { Text("Ver todo") } }) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.staggered(3)) {
                        items(latest, key = { n -> n.id }) { n ->
                            GlassCard(Modifier.width(260.dp), onClick = onNews) {
                                StatusChip(n.category, if (n.category == "URGENT") Tone.Danger else Tone.Info)
                                Spacer(Modifier.height(8.dp))
                                Text(n.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(n.summary, color = Slate400, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            val upcoming = vacations.data.orEmpty().filter { v -> v.status == "APPROVED" }.sortedBy { v -> v.startDate }.take(3)
            if (upcoming.isNotEmpty()) {
                item { SectionTitle("Próximas ausencias") }
                staggeredItems(upcoming, { v -> v.id }) { i, v -> VacationRow(v, Modifier) }
            }
        }
    }
    justifyFor?.let { w ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { justifyFor = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Justificar aviso") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aviso del ${fmtDate(w.fecha)}${w.manager?.name?.let { n -> " · $n" } ?: ""}", color = Xyon.colors.inkSoft)
                    XField(text, { text = it }, "Explica el motivo", singleLine = false, minLines = 3)
                }
            },
            confirmButton = {
                TextButton(enabled = text.trim().length >= 5, onClick = { vm.justify(w.id, text); justifyFor = null }) { Text("Enviar") }
            },
            dismissButton = { TextButton({ justifyFor = null }) { Text("Cancelar") } },
        )
    }
}

/** Coral attendance-warning card (web Dashboard "Tienes N avisos de asistencia"). */
@Composable
private fun AttendanceAlert(list: List<AttendanceWarning>, modifier: Modifier = Modifier, onJustify: (AttendanceWarning) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Coral).border(1.5.dp, Xyon.colors.outline, shape)
            .drawBehind { drawSparkle(androidx.compose.ui.geometry.Offset(size.width - 34.dp.toPx(), 34.dp.toPx()), 22.dp.toPx(), Color.White.copy(alpha = .6f)) }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = InkBlack)
            Spacer(Modifier.width(8.dp))
            Text(
                "Tienes ${list.size} ${if (list.size == 1) "aviso" else "avisos"} de asistencia",
                color = InkBlack, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 36.dp),
            )
        }
        list.take(3).forEach { w ->
            Row(
                Modifier.fillMaxWidth().clip(CircleShape).background(Color.White.copy(alpha = .6f)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(fmtDate(w.fecha).replaceFirstChar { it.uppercase() }, color = InkBlack, fontWeight = FontWeight.Bold)
                    w.manager?.name?.let { Text(it, color = InkBlack.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall) }
                }
                Text("Justificar", color = Color.White, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clip(CircleShape).background(InkBlack).clickable { onJustify(w) }.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
        if (list.size > 3) Text("… y ${list.size - 3} más", color = InkBlack, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun QuickTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier) {
    GlassCard(modifier, onClick = onClick) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Lime), contentAlignment = Alignment.Center) { Icon(icon, null, tint = InkBlack, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
    }
}

@Composable
fun VacationRow(v: VacationRequest, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(v.user?.name ?: typeLabel(v.type), style = MaterialTheme.typography.titleMedium)
                if (v.user != null) Text(typeLabel(v.type), color = Slate400, style = MaterialTheme.typography.bodyMedium)
                Text("${fmtDate(v.startDate)} → ${fmtDate(v.endDate)}", color = Slate400, style = MaterialTheme.typography.bodyMedium)
                Text("${v.days.toInt()} día(s)", color = Slate400, style = MaterialTheme.typography.labelSmall)
            }
            StatusChip(statusLabel(v.status), statusTone(v.status))
        }
        trailing?.invoke()
    }
}

// ───────── Adjustment request (used from the calendar) ─────────

/** "Solicitar ajuste" dialog for one clock-in/out: corrected time (same day) and a reason. */
@Composable
fun AdjustFichajeDialog(f: Fichaje, onDismiss: () -> Unit, onSend: (CreateAdjustmentRequest) -> Unit) {
    var time by remember { mutableStateOf(fmtTimeOf(f.timestamp)) }
    var reason by remember { mutableStateOf("") }
    val valid = Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(time) && reason.trim().length >= 5
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Solicitar ajuste") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${if (f.tipo == "ENTRADA") "Entrada" else "Salida"} del ${fmtDate(f.timestamp)} a las ${fmtTimeOf(f.timestamp)}", color = Xyon.colors.inkSoft)
                XField(time, { time = it }, "Hora correcta (HH:mm)")
                XField(reason, { reason = it }, "Motivo (mín. 5 caracteres)", singleLine = false, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val date = parseDate(f.timestamp) ?: return@TextButton
                val ldt = java.time.LocalDateTime.of(date, java.time.LocalTime.parse(time))
                val iso = ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toString()
                onSend(CreateAdjustmentRequest(f.id, iso, reason.trim()))
            }) { Text("Enviar") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

// ───────── Notifications ─────────

@Composable
fun NotificationsScreen(onBack: () -> Unit, user: User? = null, vm: NotificationsViewModel = hiltViewModel(), onOpen: (String) -> Unit = {}) {
    val st by vm.items.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Notificaciones", null, onBack) {
            IconButton({ vm.readAll() }) { Icon(Icons.Filled.DoneAll, "Marcar todas") }
        }
        LoadScreen(st, { vm.items.refresh() }) { r ->
            ListScreenColumn {
                if (r.notifications.isEmpty()) item { EmptyState(Icons.Filled.NotificationsNone, "Estás al día") }
                staggeredItems(r.notifications, { it.id }) { _, n ->
                    val target = notificationRoute(n.title, n.message, user)
                    XyonCard(Modifier.fillMaxWidth(), onClick = {
                        if (!n.read) vm.read(n.id)
                        target?.let(onOpen)
                    }, accent = if (!n.read) Lime else null) {
                        Row(verticalAlignment = Alignment.Top) {
                            if (!n.read) Box(Modifier.padding(top = 6.dp, end = 10.dp).size(10.dp).clip(CircleShape).background(Coral))
                            Column(Modifier.weight(1f)) {
                                Text(n.title, style = MaterialTheme.typography.titleMedium)
                                Text(n.message, color = Xyon.colors.inkSoft)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(fmtDate(n.date ?: n.createdAt), color = Xyon.colors.inkSoft, style = MaterialTheme.typography.labelSmall)
                                    if (target != null) { Spacer(Modifier.width(8.dp)); Text("Abrir →", color = Xyon.colors.blue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                            IconButton({ vm.delete(n.id) }) { Icon(Icons.Filled.Close, "Borrar", tint = Xyon.colors.inkSoft) }
                        }
                    }
                }
            }
        }
    }
}

/** Where a notification leads (same mapping as the web): requests, clock-ins, payslips or news. */
fun notificationRoute(title: String, message: String, user: User?): String? {
    val t = "$title $message".lowercase()
    val staff = user?.isManager == true
    return when {
        "aviso" in t || "tarde" in t -> if (staff) "adm/late" else "home"
        "ajuste" in t || "fichaje" in t || "justific" in t -> if (staff) "adm/adjust" else "calendar"
        "vacacion" in t || "ausenc" in t || "permiso" in t || "solicitud" in t -> if (staff) "adm/vac" else "requests"
        "nómina" in t || "nomina" in t -> "payroll"
        "noticia" in t || "comunicado" in t || "evento" in t -> "news"
        else -> null
    }
}
