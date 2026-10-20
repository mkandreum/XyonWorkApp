package com.xyon.empleados.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.xyon.empleados.data.AdminStats
import com.xyon.empleados.ui.allows
import com.xyon.empleados.data.LoaderFactory
import com.xyon.empleados.data.User
import com.xyon.empleados.data.admin.AdminApi
import com.xyon.empleados.ui.*
import com.xyon.empleados.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AdminHubVm @Inject constructor(private val api: AdminApi, lf: LoaderFactory) : AdminVm() {
    val stats = lf.create<AdminStats>(viewModelScope, "adm_stats") { api.stats() }
}

private data class Tool(
    val route: String, val label: String, val hint: String, val icon: ImageVector, val color: Color,
    val adminOnly: Boolean = true, val badge: ((RolePending) -> Int?)? = null,
)

/** Admin tools that belong to a plan module (see PlanViewModel). */
private val TOOL_FEATURE = mapOf(
    "adjust" to "adjustments", "late" to "lateNotices", "assign" to "shifts", "shifts" to "shifts",
    "payroll" to "payroll", "layouts" to "widgets",
)
private data class Section(val title: String, val tools: List<Tool>)

/** Tools grouped by intent, so each role sees "what to approve" first and "settings" last. */
private val SECTIONS = listOf(
    Section("Aprobar", listOf(
        Tool("vac", "Vacaciones", "Solicitudes de días libres", Icons.Filled.BeachAccess, Lime, adminOnly = false, badge = { it.requests }),
        Tool("abs", "Ausencias", "Bajas, horas y permisos", Icons.Filled.EventBusy, Lilac, adminOnly = false),
        Tool("adjust", "Ajustes de fichaje", "Correcciones de hora", Icons.Filled.EditCalendar, Sun, adminOnly = false, badge = { it.adjustments }),
        Tool("late", "Avisos de retraso", "Enviar y revisar avisos", Icons.Filled.NotificationsActive, Coral, adminOnly = false),
    )),
    Section("Tiempo y equipo", listOf(
        Tool("teamweek", "Semana del equipo", "Quién trabaja y quién falta", Icons.Filled.Groups, Lime, adminOnly = false),
        Tool("fichajes", "Fichajes", "Registros, edición e informes", Icons.Filled.AccessTime, Lilac, adminOnly = false),
        Tool("reports", "Informes", "Asistencia en PDF o CSV", Icons.Filled.Assessment, Coral, adminOnly = false),
        Tool("assign", "Asignar turnos", "Turno de cada persona por día", Icons.Filled.CalendarMonth, Sun, adminOnly = false),
        Tool("overrides", "Excepciones de horario", "Días libres o con otro horario", Icons.Filled.DateRange, Coral, adminOnly = false),
        Tool("shifts", "Turnos y horarios", "Definir turnos por departamento", Icons.Filled.Schedule, Lime),
    )),
    Section("Personas", listOf(
        Tool("users", "Usuarios", "Altas, roles y desactivar", Icons.Filled.People, Lilac),
        Tool("departments", "Departamentos", "Equipos y oficina GPS", Icons.Filled.Apartment, Sun),
        Tool("invites", "Invitaciones", "Códigos para registrarse", Icons.Filled.ConfirmationNumber, Coral),
    )),
    Section("Empresa", listOf(
        Tool("news", "Noticias", "Comunicados internos", Icons.Filled.Newspaper, Lime),
        Tool("events", "Eventos", "Calendario de empresa", Icons.Filled.Event, Lilac),
        Tool("benefits", "Beneficios", "Días y horas por departamento", Icons.Filled.CardGiftcard, Sun),
        Tool("payroll", "Nóminas", "Subir PDFs por empleado", Icons.Filled.Payments, Coral),
        Tool("layouts", "Diseño del inicio", "Widgets del panel", Icons.Filled.Dashboard, Lime),
        Tool("settings", "Configuración", "Empresa, email y plantillas", Icons.Filled.Settings, Lilac),
    )),
)

@Composable
fun AdminHubScreen(
    user: User, onBack: () -> Unit, onOpen: (String) -> Unit, snack: (String) -> Unit,
    vm: AdminHubVm = hiltViewModel(), roleVm: RoleViewModel = hiltViewModel(),
) {
    val admin = user.role == "ADMIN"
    val st by vm.stats.state.collectAsState()
    val pending by roleVm.pending.collectAsState()
    val planVm: com.xyon.empleados.ui.PlanViewModel = hiltViewModel(androidx.compose.ui.platform.LocalContext.current as androidx.fragment.app.FragmentActivity)
    val features by planVm.features.collectAsState()
    LaunchedEffect(Unit) { planVm.load() }
    LaunchedEffect(user.id) { roleVm.load(user) }
    if (admin) SecureScreen()
    AdminPage(if (admin) "Administración" else "Mi equipo", if (admin) "Toda la empresa" else user.department.ifBlank { null }, onBack, vm, snack) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Hero: what is waiting for this role
            item {
                RoleHero(
                    title = if (admin) "Panel de administración" else "Panel de responsable",
                    pending = pending,
                    onReview = { onOpen(if ((pending.requests ?: 0) > 0 || (pending.adjustments ?: 0) == 0) "vac" else "adjust") },
                    modifier = Modifier.staggered(0),
                )
            }
            if (admin) st.data?.let { s ->
                item {
                    Row(Modifier.fillMaxWidth().staggered(1), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatPill("Empleados", "${s.totalEmployees}", Icons.Filled.People, Lilac, Modifier.weight(1f))
                        StatPill("Aprobadas mes", "${s.approvedThisMonth}", Icons.Filled.CheckCircle, Lime, Modifier.weight(1f))
                        StatPill("Nóminas", "${s.payrollsProcessed}", Icons.Filled.Payments, Sun, Modifier.weight(1f))
                    }
                }
            }
            var idx = 2
            SECTIONS.forEach { section ->
                val tools = section.tools.filter { (admin || !it.adminOnly) && features.allows(TOOL_FEATURE[it.route]) }
                if (tools.isEmpty()) return@forEach
                item(key = "h_${section.title}") { SectionHeader(section.title) }
                tools.chunked(2).forEach { pair ->
                    val i = idx++
                    item(key = "r_${pair.first().route}") {
                        Row(Modifier.fillMaxWidth().staggered(i), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { t -> ToolTile(t, t.badge?.invoke(pending), Modifier.weight(1f)) { onOpen(t.route) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** Blue field card with the big "pending" number for the role (used by the hub, Home and Profile). */
@Composable
fun RoleHero(title: String, pending: RolePending, onReview: () -> Unit, modifier: Modifier = Modifier, actionLabel: String = "Revisar ahora") {
    val shape = RoundedCornerShape(32.dp)
    Box(modifier.fillMaxWidth().clip(shape).border(1.5.dp, Xyon.colors.outline, shape)) {
        BlueField(Modifier.matchParentSize()) {}
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(title, color = Color.White.copy(alpha = .85f), fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedNumberText("${pending.total}", color = Color.White, style = MaterialTheme.typography.displayLarge)
                Spacer(Modifier.width(10.dp))
                Text(if (pending.total == 1) "cosa por revisar" else "cosas por revisar", color = Color.White, fontWeight = FontWeight.Black,
                    fontSize = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                pending.requests?.let { HeroChip(if (it == 1) "1 solicitud" else "$it solicitudes", Lime) }
                pending.adjustments?.let { HeroChip(if (it == 1) "1 ajuste" else "$it ajustes", Sun) }
            }
            Spacer(Modifier.height(14.dp))
            XyonButton(if (pending.total > 0) actionLabel else "Abrir panel", onReview, Modifier.fillMaxWidth(), style = XyonButtonStyle.Lime)
        }
    }
}

@Composable
private fun HeroChip(text: String, color: Color) {
    Text(text, color = InkBlack, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
        modifier = Modifier.clip(CircleShape).background(color).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun ToolTile(t: Tool, badge: Int?, modifier: Modifier, onClick: () -> Unit) {
    XyonCard(modifier.heightIn(min = 150.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            BlobIcon(t.icon, t.color, 52.dp)
            Spacer(Modifier.weight(1f))
            if (badge != null && badge > 0) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Coral), contentAlignment = Alignment.Center) {
                    Text(if (badge > 99) "99+" else "$badge", color = InkBlack, fontWeight = FontWeight.Black, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(t.label, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Text(t.hint, color = Xyon.colors.inkSoft, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
    }
}

/** Registers the hub plus every admin/manager sub-screen. Routes: adm/{tool}. */
fun NavGraphBuilder.adminRoutes(nav: NavHostController, user: User, snack: (String) -> Unit, tabRoots: Set<String> = emptySet()) {
    val back: () -> Unit = { nav.popBackStack() }
    val admin = user.role == "ADMIN"
    val open: (String) -> Unit = { nav.navigate("adm/$it") { launchSingleTop = true } }
    // Screens that are bottom-bar tabs hide their back arrow
    fun tabAware(route: String, content: @Composable () -> Unit) = composable(route) {
        CompositionLocalProvider(LocalTabRoot provides (route in tabRoots)) { content() }
    }
    tabAware("admin") { AdminHubScreen(user, back, open, snack) }
    composable("team") { AdminHubScreen(user, back, open, snack) }
    tabAware("adm/all") { AdminRequestsScreen("all", !admin, back, snack) }
    tabAware("adm/users") { AdminUsersScreen(back, snack, onImport = { open("import") }) }
    composable("adm/import") { AdminImportScreen(back, snack) }
    composable("adm/teamweek") { AdminTeamWeekScreen(admin, back, snack) }
    composable("adm/overrides") { AdminOverridesScreen(admin, user.department, back, snack) }
    composable("adm/departments") { AdminDepartmentsScreen(back, snack) }
    composable("adm/vac") { AdminRequestsScreen("vac", !admin, back, snack) }
    composable("adm/abs") { AdminRequestsScreen("abs", !admin, back, snack) }
    composable("adm/adjust") { AdminAdjustmentsScreen(back, snack) }
    tabAware("adm/fichajes") { AdminFichajesScreen(admin, back, snack) }
    composable("adm/reports") { com.xyon.empleados.ui.ReportsScreen(user, self = false, back, snack) }
    composable("adm/late") { AdminLateScreen(back, snack) }
    composable("adm/shifts") { AdminShiftsScreen(back, snack) }
    composable("adm/assign") { AdminAssignScreen(admin, back, snack) }
    composable("adm/events") { AdminEventsScreen(back, snack) }
    composable("adm/benefits") { AdminBenefitsScreen(back, snack) }
    composable("adm/news") { AdminNewsScreen(back, snack) }
    composable("adm/payroll") {
        com.xyon.empleados.ui.BiometricGate("Nóminas", "Confirma que eres tú para gestionarlas", back) { AdminPayrollScreen(back, snack) }
    }
    composable("adm/invites") { AdminInvitesScreen(back, snack) }
    composable("adm/settings") { AdminSettingsScreen(back, snack) }
    composable("adm/layouts") { AdminLayoutsScreen(back, snack) }
}
