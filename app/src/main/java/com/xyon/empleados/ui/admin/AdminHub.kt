package com.xyon.empleados.ui.admin

import androidx.compose.foundation.layout.*
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

private data class Tool(val route: String, val label: String, val icon: ImageVector, val tint: androidx.compose.ui.graphics.Color, val adminOnly: Boolean = true)

private val TOOLS = listOf(
    Tool("users", "Usuarios", Icons.Filled.People, Primary400),
    Tool("departments", "Departamentos", Icons.Filled.Apartment, Violet),
    Tool("vac", "Vacaciones", Icons.Filled.BeachAccess, Success, adminOnly = false),
    Tool("abs", "Ausencias", Icons.Filled.EventBusy, Warning, adminOnly = false),
    Tool("adjust", "Ajustes de fichaje", Icons.Filled.EditCalendar, Cyan, adminOnly = false),
    Tool("fichajes", "Fichajes", Icons.Filled.AccessTime, Primary400, adminOnly = false),
    Tool("teamweek", "Semana del equipo", Icons.Filled.Groups, Success, adminOnly = false),
    Tool("overrides", "Excepciones de horario", Icons.Filled.DateRange, Violet, adminOnly = false),
    Tool("late", "Avisos de retraso", Icons.Filled.NotificationsActive, Warning, adminOnly = false),
    Tool("shifts", "Turnos y horarios", Icons.Filled.Schedule, Violet),
    Tool("assign", "Asignar turnos", Icons.Filled.CalendarMonth, Cyan, adminOnly = false),
    Tool("events", "Eventos", Icons.Filled.Event, Success),
    Tool("benefits", "Beneficios", Icons.Filled.CardGiftcard, Warning),
    Tool("news", "Noticias", Icons.Filled.Newspaper, Primary400),
    Tool("payroll", "Nóminas", Icons.Filled.Payments, Success),
    Tool("invites", "Invitaciones", Icons.Filled.ConfirmationNumber, Violet),
    Tool("settings", "Configuración", Icons.Filled.Settings, Cyan),
    Tool("layouts", "Diseño del panel", Icons.Filled.Dashboard, Warning),
)

@Composable
fun AdminHubScreen(user: User, onBack: () -> Unit, onOpen: (String) -> Unit, snack: (String) -> Unit, vm: AdminHubVm = hiltViewModel()) {
    val admin = user.role == "ADMIN"
    val st by vm.stats.state.collectAsState()
    val tools = TOOLS.filter { admin || !it.adminOnly }
    if (admin) SecureScreen()
    AdminPage(if (admin) "Administración" else "Mi equipo", if (admin) "Todas las herramientas" else "Herramientas de ${user.department}", onBack, vm, snack) {
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (admin) st.data?.let { s ->
                item(span = { GridItemSpan(2) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatPill("Usuarios", "${s.totalUsers}", Icons.Filled.People, Primary400, Modifier.weight(1f))
                        StatPill("Pendientes", "${s.pendingVacations}", Icons.Filled.HourglassTop, Warning, Modifier.weight(1f))
                    }
                }
            }
            items(tools, key = { it.route }) { t ->
                GlassCard(Modifier.fillMaxWidth(), onClick = { onOpen(t.route) }, accent = t.tint) {
                    Icon(t.icon, null, tint = t.tint, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(t.label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Start)
                }
            }
        }
    }
}

/** Registers the hub plus every admin/manager sub-screen. Routes: adm/{tool}. */
fun NavGraphBuilder.adminRoutes(nav: NavHostController, user: User, snack: (String) -> Unit) {
    val back: () -> Unit = { nav.popBackStack() }
    val admin = user.role == "ADMIN"
    val open: (String) -> Unit = { nav.navigate("adm/$it") { launchSingleTop = true } }
    composable("admin") { AdminHubScreen(user, back, open, snack) }
    composable("team") { AdminHubScreen(user, back, open, snack) }
    composable("adm/users") { AdminUsersScreen(back, snack, onImport = { open("import") }) }
    composable("adm/import") { AdminImportScreen(back, snack) }
    composable("adm/teamweek") { AdminTeamWeekScreen(admin, back, snack) }
    composable("adm/overrides") { AdminOverridesScreen(admin, user.department, back, snack) }
    composable("adm/departments") { AdminDepartmentsScreen(back, snack) }
    composable("adm/vac") { AdminRequestsScreen("vac", !admin, back, snack) }
    composable("adm/abs") { AdminRequestsScreen("abs", !admin, back, snack) }
    composable("adm/adjust") { AdminAdjustmentsScreen(back, snack) }
    composable("adm/fichajes") { AdminFichajesScreen(admin, back, snack) }
    composable("adm/late") { AdminLateScreen(back, snack) }
    composable("adm/shifts") { AdminShiftsScreen(back, snack) }
    composable("adm/assign") { AdminAssignScreen(admin, back, snack) }
    composable("adm/events") { AdminEventsScreen(back, snack) }
    composable("adm/benefits") { AdminBenefitsScreen(back, snack) }
    composable("adm/news") { AdminNewsScreen(back, snack) }
    composable("adm/payroll") { AdminPayrollScreen(back, snack) }
    composable("adm/invites") { AdminInvitesScreen(back, snack) }
    composable("adm/settings") { AdminSettingsScreen(back, snack) }
    composable("adm/layouts") { AdminLayoutsScreen(back, snack) }
}
