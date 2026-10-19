package com.xyon.empleados.ui

import androidx.activity.result.contract.ActivityResultContracts

import androidx.activity.compose.rememberLauncherForActivityResult

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.xyon.empleados.BuildConfig
import com.xyon.empleados.data.*
import com.xyon.empleados.ui.theme.*
import com.xyon.empleados.ui.admin.RoleHero
import java.io.File

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Shared-element modifier (no-op when scopes are absent, e.g. previews). */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedKey(key: String): Modifier {
    val s = LocalSharedScope.current; val a = LocalAnimScope.current
    return if (s != null && a != null) with(s) { this@sharedKey.sharedBounds(rememberSharedContentState(key), a, resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds()) } else this
}

private fun imageUrl(u: String?): String? = com.xyon.empleados.data.ServerConfig.absolute(u)

@Composable
fun NewsScreen(vm: NewsViewModel, onOpen: (NewsItem) -> Unit) {
    val st by vm.news.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Noticias", "Lo último de la empresa")
        LoadScreen(st, { vm.news.refresh() }) { list ->
            ListScreenColumn {
                if (list.isEmpty()) item { EmptyState(Icons.Filled.Newspaper, "No hay noticias") }
                staggeredItems(list.sortedByDescending { it.date }, { it.id }) { _, n ->
                    GlassCard(Modifier.fillMaxWidth().sharedKey("news-${n.id}"), onClick = { onOpen(n) }, accent = if (n.category == "URGENT") Danger else null) {
                        imageUrl(n.imageUrl)?.let {
                            AsyncImage(it, null, Modifier.fillMaxWidth().height(150.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
                            Spacer(Modifier.height(10.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusChip(n.category, if (n.category == "URGENT") Tone.Danger else if (n.category == "EVENT") Tone.Warning else Tone.Info)
                            Spacer(Modifier.weight(1f)); Text(fmtDate(n.date), color = Slate500, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(n.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                        Text(n.summary, color = Slate400, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun NewsDetailScreen(item: NewsItem?, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Noticia", null, onBack)
        if (item == null) EmptyState(Icons.Filled.Newspaper, "Noticia no disponible")
        else GlassCard(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 16.dp).sharedKey("news-${item.id}")) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                imageUrl(item.imageUrl)?.let {
                    AsyncImage(it, null, Modifier.fillMaxWidth().height(200.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
                    Spacer(Modifier.height(12.dp))
                }
                StatusChip(item.category, if (item.category == "URGENT") Tone.Danger else Tone.Info)
                Text(item.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
                Text(fmtDate(item.date), color = Slate500, modifier = Modifier.padding(bottom = 12.dp))
                Text(item.content.ifBlank { item.summary }, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ───────── Payroll ─────────

@Composable
fun PayrollScreen(onBack: (() -> Unit)?, vm: PayrollViewModel = hiltViewModel(), snack: (String) -> Unit) {
    SecureScreen()
    val ctx = LocalContext.current
    val st by vm.items.state.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { vm.msg.collect { snack(it) } }
    var pendingSave by remember { mutableStateOf<Payroll?>(null) }
    val storagePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pendingSave; pendingSave = null
        if (ok && p != null) vm.open(p, save = true) else if (!ok) snack("Sin permiso de almacenamiento no se puede guardar")
    }
    fun save(p: Payroll) {
        if (needsLegacyStoragePermission(ctx)) { pendingSave = p; storagePerm.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else vm.open(p, save = true)
    }
    LaunchedEffect(Unit) {
        vm.file.collect { f ->
            if (f.save) {
                runCatching { savePdfToDownloads(ctx, f.bytes, f.name) }
                    .onSuccess { snack("Guardada en $it") }
                    .onFailure { snack("No se pudo guardar la nómina") }
            } else {
                val dir = File(ctx.cacheDir, "docs").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val file = File(dir, pdfFileName(f.name))
                file.writeBytes(f.bytes)
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Abrir nómina"))
            }
        }
    }
    val prompt by vm.password.collectAsState()
    prompt?.let { req ->
        var pw by remember(req.payroll.id, req.save) { mutableStateOf("") }
        AlertDialog({ if (!req.loading) vm.cancelPassword() }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Confirma que eres tú") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Las nóminas están protegidas. Introduce la contraseña de tu cuenta para abrirlas durante unos minutos.", style = MaterialTheme.typography.bodyMedium)
                XField(pw, { pw = it }, "Contraseña", password = true, error = req.error)
            } },
            confirmButton = { TextButton(enabled = pw.isNotEmpty() && !req.loading, onClick = { vm.submitPassword(pw) }) { Text(if (req.loading) "Comprobando…" else "Abrir") } },
            dismissButton = { TextButton(enabled = !req.loading, onClick = { vm.cancelPassword() }) { Text("Cancelar") } })
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Nóminas", "Ábrelas o guárdalas en Descargas", onBack)
        LoadScreen(st, { vm.items.refresh() }) { list ->
            ListScreenColumn {
                if (list.isEmpty()) item { EmptyState(Icons.Filled.Receipt, "Sin nóminas disponibles") }
                if (list.isNotEmpty()) item {
                    val months = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
                    fun idx(p: Payroll) = p.year * 12 + (months.indexOf(p.month.lowercase()).takeIf { it >= 0 } ?: (p.month.toIntOrNull()?.minus(1) ?: 0))
                    val sorted = list.sortedByDescending(::idx)
                    val year = sorted.first().year
                    val total = list.filter { it.year == year }.sumOf { it.amount }
                    val last = sorted.first()
                    val prev = sorted.getOrNull(1)
                    val diff = if (prev != null && prev.amount > 0) (last.amount - prev.amount) / prev.amount * 100 else null
                    Row(Modifier.fillMaxWidth().staggered(0), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatPill("Total $year", money(total), Icons.Filled.Savings, Lime, Modifier.weight(1.3f))
                        StatPill(
                            if (diff == null) "Última" else "vs. anterior",
                            if (diff == null) money(last.amount) else "%+.1f%%".format(diff),
                            if (diff != null && diff < 0) Icons.Filled.TrendingDown else Icons.Filled.TrendingUp,
                            if (diff != null && diff < 0) Coral else Lilac, Modifier.weight(1f),
                        )
                    }
                }
                staggeredItems(list.sortedWith(compareByDescending<Payroll> { it.year }.thenByDescending { it.month }), { it.id }) { _, p ->
                    GlassCard(Modifier.fillMaxWidth(), onClick = { vm.open(p) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${p.month} ${p.year}", style = MaterialTheme.typography.titleMedium)
                                Text(money(p.amount), color = Primary400, fontWeight = FontWeight.Bold)
                            }
                            if (busy == p.id) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else {
                                StatusChip(statusLabel(p.status), statusTone(p.status))
                                Spacer(Modifier.width(4.dp))
                                IconButton({ save(p) }) { Icon(Icons.Filled.Download, "Guardar en Descargas", tint = Xyon.colors.ink) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────── Profile ─────────

@Composable
fun ProfileScreen(
    user: User, vm: SessionViewModel, onNotifications: () -> Unit, onPayroll: () -> Unit, onFichajes: () -> Unit,
    onTeam: () -> Unit, onAdmin: () -> Unit, roleVm: RoleViewModel = hiltViewModel(),
    onEnableBiometric: (Boolean) -> Unit,
) {
    SecureScreen()
    val x = Xyon.colors
    val bio by vm.biometricEnabled.collectAsState(false)
    val pending by roleVm.pending.collectAsState()
    LaunchedEffect(user.id) { roleVm.load(user) }
    var edit by remember { mutableStateOf(false) }
    var pwd by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp).padding(bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        XyonCard(Modifier.fillMaxWidth().staggered(0), accent = Lime) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(user.name, 68)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(user.name, style = MaterialTheme.typography.headlineMedium)
                    Text(user.position.ifBlank { "Sin cargo" }, color = x.inkSoft)
                    Text(user.email, color = x.inkSoft, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoleChip(user.role)
                StatusChip(user.department.ifBlank { "Sin departamento" }, Tone.Neutral)
            }
        }
        // Manager / admin: their tools front and centre, with what is waiting for them
        if (user.isManager) {
            RoleHero(
                title = if (user.isAdmin) "Administración" else "Tu equipo · ${user.department}",
                pending = pending,
                onReview = { if (user.isAdmin) onAdmin() else onTeam() },
                modifier = Modifier.staggered(1),
                actionLabel = if (user.isAdmin) "Abrir administración" else "Gestionar mi equipo",
            )
        }
        Box(Modifier.staggered(2)) { XyonCard(Modifier.fillMaxWidth()) {
            Text("Mis datos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            InfoRow(Icons.Filled.Phone, "Teléfono", user.phone)
            InfoRow(Icons.Filled.Home, "Dirección", user.address)
            InfoRow(Icons.Filled.ContactEmergency, "Contacto de emergencia", user.emergencyContact)
            TextButton({ edit = true }) { Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(6.dp)); Text("Editar datos") }
        } }
        Box(Modifier.staggered(3)) { XyonCard(Modifier.fillMaxWidth()) {
            Text("Seguridad y avisos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            NavRow(Icons.Filled.Notifications, "Notificaciones", onNotifications)
            val ctx = LocalContext.current
            var reminders by remember { mutableStateOf(Reminders.enabled(ctx)) }
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Alarm, null, tint = x.blue); Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Recordatorios de fichaje")
                    Text("Aviso 5 minutos antes del inicio de tu turno", color = x.inkSoft, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(reminders, { on -> reminders = on; Reminders.setEnabled(ctx, on) },
                    colors = SwitchDefaults.colors(checkedTrackColor = x.blue, checkedThumbColor = Lime))
            }
            // Special access that existing users may need to grant after Android 14.
            if (reminders && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val alarms = ctx.getSystemService(android.app.AlarmManager::class.java)
                if (alarms?.canScheduleExactAlarms() != true) {
                    NavRow(Icons.Filled.Alarm, "Permitir avisos exactos", {
                        val pkg = android.net.Uri.parse("package:${ctx.packageName}")
                        runCatching {
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
                        }.onFailure {
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
                        }
                    })
                }
            }
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Fingerprint, null, tint = x.blue); Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bloqueo con huella")
                    Text("Al volver a la app y al abrir nóminas", color = x.inkSoft, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(bio, { onEnableBiometric(it) }, colors = SwitchDefaults.colors(checkedTrackColor = x.blue, checkedThumbColor = Lime))
            }
            NavRow(Icons.Filled.Password, "Cambiar contraseña", { pwd = true })
            NavRow(Icons.AutoMirrored.Filled.Logout, "Cerrar sesión", { vm.signOut() }, Coral)
        } }
    }
    if (edit) {
        var phone by remember { mutableStateOf(user.phone.orEmpty()) }
        var addr by remember { mutableStateOf(user.address.orEmpty()) }
        var emer by remember { mutableStateOf(user.emergencyContact.orEmpty()) }
        AlertDialog({ edit = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, title = { Text("Editar perfil") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                XField(phone, { phone = it }, "Teléfono", keyboard = androidx.compose.ui.text.input.KeyboardType.Phone)
                XField(addr, { addr = it }, "Dirección"); XField(emer, { emer = it }, "Contacto de emergencia")
            } },
            confirmButton = { TextButton({ vm.updateProfile(UpdateProfileRequest(phone, addr, emer)); edit = false }) { Text("Guardar") } },
            dismissButton = { TextButton({ edit = false }) { Text("Cancelar") } })
    }
    if (pwd) {
        var cur by remember { mutableStateOf("") }; var new by remember { mutableStateOf("") }
        AlertDialog({ pwd = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, title = { Text("Cambiar contraseña") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                XField(cur, { cur = it }, "Contraseña actual", password = true); XField(new, { new = it }, "Nueva contraseña (12+)", password = true)
            } },
            confirmButton = { TextButton(enabled = cur.isNotBlank() && new.length >= 12, onClick = { vm.changePassword(cur, new); pwd = false }) { Text("Cambiar") } },
            dismissButton = { TextButton({ pwd = false }) { Text("Cancelar") } })
    }
}

/** Flat role pill: admin = coral, manager = sun, employee = lime. */
@Composable
private fun RoleChip(role: String) {
    val (label, color) = when (role) {
        "ADMIN" -> "Administrador" to Coral
        "MANAGER" -> "Responsable" to Sun
        else -> "Empleado" to Lime
    }
    Text(label, color = InkBlack, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clip(CircleShape).background(color).padding(horizontal = 12.dp, vertical = 5.dp))
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String?) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Xyon.colors.inkSoft, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
        Column { Text(label, color = Xyon.colors.inkSoft, style = MaterialTheme.typography.labelSmall); Text(value?.ifBlank { null } ?: "—") }
    }
}

@Composable
fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tint: androidx.compose.ui.graphics.Color = Color.Unspecified) {
    val x = Xyon.colors
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (tint == Color.Unspecified) x.blue else tint); Spacer(Modifier.width(14.dp)); Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Icon(Icons.Filled.ChevronRight, null, tint = x.inkSoft)
    }
}
