package com.xyon.empleados.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.xyon.empleados.data.*
import com.xyon.empleados.data.admin.*
import com.xyon.empleados.ui.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import javax.inject.Inject

private fun filePart(field: String, bytes: ByteArray, mime: String): MultipartBody.Part {
    val ext = when { mime.contains("png") -> "png"; mime.contains("gif") -> "gif"; mime.contains("pdf") -> "pdf"; else -> "jpg" }
    return MultipartBody.Part.createFormData(field, "upload.$ext", bytes.toRequestBody(mime.toMediaType()))
}

// ───────────────────────── Events ─────────────────────────

@HiltViewModel
class AdminEventsVm @Inject constructor(private val api: AdminApi, lf: LoaderFactory) : AdminVm() {
    val events = lf.create<List<EventItem>>(viewModelScope, "adm_events") { api.events() }
    fun create(r: CreateEventRequest) = act("Evento creado", { api.createEvent(r) }) { events.refresh() }
    fun delete(id: String) = act("Evento eliminado", { api.deleteEvent(id) }) { events.refresh() }
}

@Composable
fun AdminEventsScreen(onBack: () -> Unit, snack: (String) -> Unit, vm: AdminEventsVm = hiltViewModel()) {
    val st by vm.events.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<EventItem?>(null) }
    AdminPage("Eventos", "Próximos eventos de la empresa", onBack, vm, snack,
        actions = { IconAction(Icons.Filled.Add, "Nuevo evento") { creating = true } }) {
        LoadScreen(st, { vm.events.refresh() }) { list ->
            ListScreenColumn {
                if (list.isEmpty()) item { EmptyState(Icons.Filled.Event, "No hay eventos próximos") }
                items(list, key = { it.id }) { e ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.title, style = MaterialTheme.typography.titleMedium)
                                Muted(fmtDate(e.date) + (e.location?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
                                e.description?.takeIf { it.isNotBlank() }?.let { Muted(it) }
                            }
                            IconAction(Icons.Filled.Delete, "Eliminar", danger = true) { del = e }
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        var t by remember { mutableStateOf("") }; var d by remember { mutableStateOf(LocalDate.now().plusDays(7).toString()) }
        var desc by remember { mutableStateOf("") }; var loc by remember { mutableStateOf("") }
        val dateOk = runCatching { LocalDate.parse(d) }.isSuccess
        FormDialog("Nuevo evento", "Crear", t.isNotBlank() && dateOk, { vm.create(CreateEventRequest(t.trim(), desc.trim(), d, loc.trim())); creating = false }, { creating = false }) {
            XField(t, { t = it }, "Título")
            XField(d, { d = it }, "Fecha (AAAA-MM-DD)", error = if (!dateOk) "Formato AAAA-MM-DD" else null)
            XField(loc, { loc = it }, "Lugar")
            XField(desc, { desc = it }, "Descripción", singleLine = false, minLines = 2)
        }
    }
    del?.let { ConfirmDialog("¿Eliminar el evento ${it.title}?", onConfirm = { vm.delete(it.id) }, onDismiss = { del = null }) }
}

// ───────────────────────── Benefits ─────────────────────────

@HiltViewModel
class AdminBenefitsVm @Inject constructor(private val api: AdminApi, lf: LoaderFactory, private val deps: DepartmentsRepo) : AdminVm() {
    val rows = lf.create<List<BenefitsRow>>(viewModelScope, "adm_benefits") { api.benefits() }
    val names = lf.create<List<String>>(viewModelScope, "adm_dep_names") { deps.names() }
    fun save(r: BenefitsRequest) = act("Beneficios guardados", { api.saveBenefits(r) }) { rows.refresh() }
}

@Composable
fun AdminBenefitsScreen(onBack: () -> Unit, snack: (String) -> Unit, vm: AdminBenefitsVm = hiltViewModel()) {
    val st by vm.rows.state.collectAsState()
    val names by vm.names.state.collectAsState()
    var edit by remember { mutableStateOf<BenefitsRow?>(null) }
    AdminPage("Beneficios", "Días y bolsas de horas por departamento", onBack, vm, snack) {
        LoadScreen(st, { vm.rows.refresh(); vm.names.refresh() }) { rows ->
            val all = (rows.map { it.department } + (names.data ?: emptyList())).distinct().sorted()
            ListScreenColumn {
                if (all.isEmpty()) item { EmptyState(Icons.Filled.CardGiftcard, "Sin departamentos") }
                items(all, key = { it }) { d ->
                    val b = rows.firstOrNull { it.department == d } ?: BenefitsRow(department = d)
                    GlassCard(Modifier.fillMaxWidth(), onClick = { edit = b }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d, style = MaterialTheme.typography.titleMedium)
                                Muted("Vacaciones ${b.vacationDays.toInt()} d · Horas extra ${b.overtimeHoursBank.toInt()} h")
                                Muted("Médicas ${b.sickLeaveHours.toInt()} h · Asuntos propios ${b.paidAbsenceHours.toInt()} h")
                            }
                            if (rows.none { it.department == d }) StatusChip("Por defecto", Tone.Neutral)
                            Icon(Icons.Filled.Edit, null)
                        }
                    }
                }
            }
        }
    }
    edit?.let { b ->
        var v by remember { mutableStateOf(b.vacationDays.toInt().toString()) }
        var o by remember { mutableStateOf(b.overtimeHoursBank.toInt().toString()) }
        var s by remember { mutableStateOf(b.sickLeaveHours.toInt().toString()) }
        var p by remember { mutableStateOf(b.paidAbsenceHours.toInt().toString()) }
        val ok = listOf(v, o, s, p).all { (it.toIntOrNull() ?: -1) in 0..9999 }
        val num = androidx.compose.ui.text.input.KeyboardType.Number
        FormDialog("Beneficios · ${b.department}", enabled = ok, onDismiss = { edit = null }, onConfirm = {
            vm.save(BenefitsRequest(b.department, v.toInt(), o.toInt(), s.toInt(), p.toInt())); edit = null
        }) {
            XField(v, { v = it }, "Días de vacaciones", keyboard = num)
            XField(o, { o = it }, "Bolsa horas extra (h)", keyboard = num)
            XField(s, { s = it }, "Horas médicas (h)", keyboard = num)
            XField(p, { p = it }, "Horas asuntos propios (h)", keyboard = num)
        }
    }
}

// ───────────────────────── News ─────────────────────────

@HiltViewModel
class AdminNewsVm @Inject constructor(private val api: AdminApi, lf: LoaderFactory) : AdminVm() {
    val news = lf.create<List<NewsItem>>(viewModelScope, "adm_news") { api.news() }
    fun save(id: String?, r: UpdateNewsRequest) =
        if (id == null) act("Noticia publicada", { api.createNews(CreateNewsRequest(r.title, r.summary, r.content, r.category, r.imageUrl)) }) { news.refresh() }
        else act("Noticia actualizada", { api.updateNews(id, r) }) { news.refresh() }
    fun delete(id: String) = act("Noticia eliminada", { api.deleteNews(id) }) { news.refresh() }
    fun upload(bytes: ByteArray, mime: String, done: (String) -> Unit) = viewModelScope.launch {
        safeCall { api.uploadNews(filePart("news", bytes, mime)) }
            .onSuccess { r -> (r.url ?: r.fileUrl ?: r.path)?.let { say("Imagen subida"); done(it) } ?: say("Respuesta sin URL") }
            .onFailure { say(it.userMessage()) }
    }
}

@Composable
fun AdminNewsScreen(onBack: () -> Unit, snack: (String) -> Unit, vm: AdminNewsVm = hiltViewModel()) {
    val ctx = LocalContext.current
    val st by vm.news.state.collectAsState()
    var form by remember { mutableStateOf<NewsItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<NewsItem?>(null) }
    AdminPage("Noticias", "Publicar y editar", onBack, vm, snack,
        actions = { IconAction(Icons.Filled.Add, "Nueva noticia") { creating = true } }) {
        LoadScreen(st, { vm.news.refresh() }) { list ->
            ListScreenColumn {
                if (list.isEmpty()) item { EmptyState(Icons.Filled.Newspaper, "Sin noticias") }
                items(list, key = { it.id }) { n ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        absUrl(n.imageUrl)?.let { AsyncImage(it, null, Modifier.fillMaxWidth().height(120.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(n.title, style = MaterialTheme.typography.titleMedium)
                                Muted("${fmtDate(n.date)} · ${n.category}")
                            }
                            IconAction(Icons.Filled.Edit, "Editar") { form = n }
                            IconAction(Icons.Filled.Delete, "Eliminar", danger = true) { del = n }
                        }
                    }
                }
            }
        }
    }
    if (creating || form != null) {
        val n = form
        var title by remember { mutableStateOf(n?.title ?: "") }; var summary by remember { mutableStateOf(n?.summary ?: "") }
        var content by remember { mutableStateOf(n?.content ?: "") }; var cat by remember { mutableStateOf(n?.category ?: "CORPORATE") }
        var img by remember { mutableStateOf(n?.imageUrl) }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { u -> readPicked(ctx, u)?.let { (b, m) -> vm.upload(b, m) { img = it } } ?: vm.say("No se pudo leer la imagen (máx. 10 MB)") }
        }
        val close = { creating = false; form = null }
        FormDialog(if (n == null) "Nueva noticia" else "Editar noticia", enabled = title.isNotBlank() && content.isNotBlank(), onDismiss = close, onConfirm = {
            vm.save(n?.id, UpdateNewsRequest(title.trim(), summary.trim(), content.trim(), cat, img)); close()
        }) {
            XField(title, { title = it }, "Título"); XField(summary, { summary = it }, "Resumen")
            XField(content, { content = it }, "Contenido", singleLine = false, minLines = 3)
            ChipsRow(listOf("CORPORATE" to "Corporativa", "EVENT" to "Evento", "URGENT" to "Urgente"), cat, { cat = it })
            absUrl(img)?.let { AsyncImage(it, null, Modifier.fillMaxWidth().height(100.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
            OutlinedButton({ pick.launch("image/*") }) { Icon(Icons.Filled.Image, null); Spacer(Modifier.width(8.dp)); Text(if (img == null) "Subir imagen" else "Cambiar imagen") }
        }
    }
    del?.let { ConfirmDialog("¿Eliminar la noticia ${it.title}?", onConfirm = { vm.delete(it.id) }, onDismiss = { del = null }) }
}

// ───────────────────────── Payroll upload ─────────────────────────

@HiltViewModel
class AdminPayrollVm @Inject constructor(private val api: AdminApi, lf: LoaderFactory) : AdminVm() {
    val payrolls = lf.create<List<Payroll>>(viewModelScope, "adm_payrolls") { api.payrolls() }
    val users = lf.create<List<User>>(viewModelScope, "adm_users") { api.users() }
    fun delete(id: String) = act("Nómina eliminada", { api.deletePayroll(id) }) { payrolls.refresh() }
    fun create(userId: String, month: String, year: Int, amount: Double, bytes: ByteArray) = act("Nómina subida y empleado notificado", {
        val up = api.uploadPayroll(filePart("payrolls", bytes, "application/pdf"))
        val url = up.url ?: up.fileUrl ?: up.path ?: error("Respuesta sin URL")
        api.createPayroll(CreatePayrollRequest(userId, month, year, amount, url))
    }) { payrolls.refresh() }
}

@Composable
fun AdminPayrollScreen(onBack: () -> Unit, snack: (String) -> Unit, vm: AdminPayrollVm = hiltViewModel()) {
    val ctx = LocalContext.current
    val st by vm.payrolls.state.collectAsState()
    val users by vm.users.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<Payroll?>(null) }
    var q by remember { mutableStateOf("") }
    AdminPage("Nóminas", "Subir y gestionar PDF por empleado", onBack, vm, snack,
        actions = { IconAction(Icons.Filled.UploadFile, "Subir nómina") { creating = true } }) {
        SearchField(q, { q = it }, "Buscar empleado")
        LoadScreen(st, { vm.payrolls.refresh() }) { all ->
            val list = all.filter { q.isBlank() || (it.user?.name ?: "").contains(q, true) }
            ListScreenColumn {
                if (list.isEmpty()) item { EmptyState(Icons.Filled.Payments, "Sin nóminas") }
                items(list, key = { it.id }) { p ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${p.user?.name ?: "—"} · ${p.month} ${p.year}", style = MaterialTheme.typography.titleMedium)
                                Muted(money(p.amount) + if (p.signedPdfUrl != null) " · firmada" else "")
                            }
                            IconAction(Icons.Filled.Delete, "Eliminar", danger = true) { del = p }
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        val now = LocalDate.now()
        var uid by remember { mutableStateOf("") }; var month by remember { mutableStateOf(MONTHS_ES[now.monthValue - 1]) }
        var year by remember { mutableStateOf(now.year.toString()) }; var amount by remember { mutableStateOf("") }
        var pdf by remember { mutableStateOf<ByteArray?>(null) }; var pdfName by remember { mutableStateOf("") }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { u -> readPicked(ctx, u)?.let { (b, m) -> if (m.contains("pdf")) { pdf = b; pdfName = "PDF seleccionado (${b.size / 1024} KB)" } else vm.say("Selecciona un PDF") } ?: vm.say("No se pudo leer el archivo (máx. 10 MB)") }
        }
        val amt = amount.replace(',', '.').toDoubleOrNull()
        val ok = uid.isNotBlank() && amt != null && amt >= 0 && (year.toIntOrNull() ?: 0) in 2000..2100 && pdf != null
        FormDialog("Subir nómina", "Subir", ok, { vm.create(uid, month, year.toInt(), amt!!, pdf!!); creating = false }, { creating = false }) {
            Dropdown("Empleado", uid, (users.data ?: emptyList()).sortedBy { it.name }.map { it.id to "${it.name} · ${it.department}" }, { uid = it })
            Dropdown("Mes", month, MONTHS_ES.map { it to it }, { month = it })
            XField(year, { year = it }, "Año", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            XField(amount, { amount = it }, "Importe (€)", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
            OutlinedButton({ pick.launch("application/pdf") }) { Icon(Icons.Filled.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text(if (pdf == null) "Elegir PDF" else pdfName) }
        }
    }
    del?.let { ConfirmDialog("¿Eliminar la nómina de ${it.user?.name ?: ""} (${it.month} ${it.year}) y su archivo?", onConfirm = { vm.delete(it.id) }, onDismiss = { del = null }) }
}

// ───────────────────────── Invites ─────────────────────────

@HiltViewModel
class AdminInvitesVm @Inject constructor(private val api: AdminApi, lf: LoaderFactory) : AdminVm() {
    val invites = lf.create<List<InvitationCode>>(viewModelScope, "adm_invites") { api.invites() }
    fun create() = act("Código generado", { api.createInvite() }) { invites.refresh() }
    fun revoke(id: String) = act("Código revocado", { api.deleteInvite(id) }) { invites.refresh() }
}

@Composable
fun AdminInvitesScreen(onBack: () -> Unit, snack: (String) -> Unit, vm: AdminInvitesVm = hiltViewModel()) {
    val ctx = LocalContext.current
    val st by vm.invites.state.collectAsState()
    var del by remember { mutableStateOf<InvitationCode?>(null) }
    AdminPage("Invitaciones", "Códigos de registro", onBack, vm, snack) {
        LoadScreen(st, { vm.invites.refresh() }) { list ->
            ListScreenColumn {
                item { GradientButton("Generar código", { vm.create() }, Modifier.fillMaxWidth()) }
                if (list.isEmpty()) item { EmptyState(Icons.Filled.ConfirmationNumber, "Sin códigos") }
                items(list, key = { it.id }) { c ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(c.code, style = MaterialTheme.typography.titleMedium); Muted(fmtDate(c.createdAt)) }
                            StatusChip(if (c.isUsed) "Usado" else "Disponible", if (c.isUsed) Tone.Neutral else Tone.Success)
                            if (!c.isUsed) {
                                IconAction(Icons.Filled.Share, "Compartir") {
                                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Tu código de invitación: ${c.code}"), "Compartir código"))
                                }
                                IconAction(Icons.Filled.ContentCopy, "Copiar") {
                                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("codigo", c.code)); vm.say("Código copiado")
                                }
                                IconAction(Icons.Filled.Close, "Revocar", danger = true) { del = c }
                            }
                        }
                    }
                }
            }
        }
    }
    del?.let { ConfirmDialog("¿Revocar el código ${it.code}?", "Revocar", onConfirm = { vm.revoke(it.id) }, onDismiss = { del = null }) }
}

// ───────────────────────── Company settings (+SMTP, email templates) ─────────────────────────

@HiltViewModel
class AdminSettingsVm @Inject constructor(private val api: AdminApi, lf: LoaderFactory) : AdminVm() {
    val settings = lf.create<Map<String, String>>(viewModelScope, "adm_settings") { api.settings() }
    val templates = lf.create<List<EmailTemplate>>(viewModelScope, "adm_templates") { api.emailTemplates() }
    fun save(values: Map<String, String>) = act("Configuración guardada", { api.updateSettings(values) }) { settings.refresh() }
    fun saveTemplate(id: String, r: EmailTemplateUpdate) = act("Plantilla guardada", { api.updateTemplate(id, r) }) { templates.refresh() }
    fun upload(kind: String, bytes: ByteArray, mime: String, done: (String) -> Unit) = viewModelScope.launch {
        safeCall { if (kind == "avatar") api.uploadAvatar(filePart("avatars", bytes, mime)) else api.uploadLogo(filePart("logos", bytes, mime)) }
            .onSuccess { r -> (r.url ?: r.fileUrl ?: r.path)?.let { say("Imagen subida"); done(it) } ?: say("Respuesta sin URL") }
            .onFailure { say(it.userMessage()) }
    }
}

@Composable
fun AdminSettingsScreen(onBack: () -> Unit, snack: (String) -> Unit, vm: AdminSettingsVm = hiltViewModel()) {
    var tab by remember { mutableStateOf("general") }
    val st by vm.settings.state.collectAsState()
    val tpl by vm.templates.state.collectAsState()
    AdminPage("Configuración", "Empresa, correo y plantillas", onBack, vm, snack) {
        ChipsRow(listOf("general" to "General", "smtp" to "Correo SMTP", "tpl" to "Plantillas"), tab, { tab = it })
        if (tab == "tpl") LoadScreen(tpl, { vm.templates.refresh() }) { list -> TemplatesList(list, vm) }
        else LoadScreen(st, { vm.settings.refresh() }) { s ->
            if (tab == "general") GeneralSettings(s, vm) else SmtpSettings(s, vm)
        }
    }
}

@Composable
private fun GeneralSettings(s: Map<String, String>, vm: AdminSettingsVm) {
    val ctx = LocalContext.current
    var name by remember(s) { mutableStateOf(s["companyName"] ?: "") }
    var logo by remember(s) { mutableStateOf(s["logoUrl"] ?: "") }
    var loginLogo by remember(s) { mutableStateOf(s["loginLogoUrl"] ?: "") }
    var avatar by remember(s) { mutableStateOf(s["defaultAvatarUrl"] ?: "") }
    var target by remember { mutableStateOf("logo") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> readPicked(ctx, u)?.let { (b, m) ->
            vm.upload(if (target == "avatar") "avatar" else "logo", b, m) { url -> when (target) { "logo" -> logo = url; "login" -> loginLogo = url; else -> avatar = url } }
        } ?: vm.say("No se pudo leer la imagen") }
    }
    @Composable fun imgRow(label: String, url: String, key: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            absUrl(url.ifBlank { null })?.let { AsyncImage(it, null, Modifier.size(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
            Text(label, Modifier.weight(1f))
            OutlinedButton({ target = key; pick.launch("image/*") }) { Text("Subir") }
        }
    }
    ListScreenColumn {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                XField(name, { name = it }, "Nombre de la empresa")
                Spacer(Modifier.height(12.dp))
                imgRow("Logo del portal", logo, "logo"); Spacer(Modifier.height(8.dp))
                imgRow("Logo del login", loginLogo, "login"); Spacer(Modifier.height(8.dp))
                imgRow("Avatar por defecto", avatar, "avatar")
            }
        }
        item {
            GradientButton("Guardar", {
                vm.save(buildMap {
                    put("companyName", name.trim())
                    if (logo.isNotBlank()) put("logoUrl", logo)
                    if (loginLogo.isNotBlank()) put("loginLogoUrl", loginLogo)
                    if (avatar.isNotBlank()) put("defaultAvatarUrl", avatar)
                })
            }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SmtpSettings(s: Map<String, String>, vm: AdminSettingsVm) {
    var host by remember(s) { mutableStateOf(s["smtpHost"] ?: "") }
    var port by remember(s) { mutableStateOf(s["smtpPort"] ?: "587") }
    var user by remember(s) { mutableStateOf(s["smtpUser"] ?: "") }
    var pass by remember(s) { mutableStateOf("") }
    var from by remember(s) { mutableStateOf(s["smtpFrom"] ?: "") }
    val configured = !s["smtpPass"].isNullOrBlank()
    val portOk = port.isBlank() || (port.toIntOrNull() ?: 0) in 1..65535
    ListScreenColumn {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    XField(host, { host = it }, "Servidor SMTP (host)")
                    XField(port, { port = it }, "Puerto", keyboard = androidx.compose.ui.text.input.KeyboardType.Number, error = if (!portOk) "Puerto no válido" else null)
                    XField(user, { user = it }, "Usuario SMTP")
                    XField(pass, { pass = it }, if (configured) "Contraseña SMTP (configurada: ********)" else "Contraseña SMTP", password = true)
                    Muted("Deja la contraseña vacía para mantener la actual. Nunca se muestra el valor guardado.")
                    XField(from, { from = it }, "Remitente (From)")
                }
            }
        }
        item {
            GradientButton("Guardar SMTP", {
                vm.save(buildMap {
                    put("smtpHost", host.trim()); put("smtpPort", port.trim()); put("smtpUser", user.trim()); put("smtpFrom", from.trim())
                    if (pass.isNotBlank()) put("smtpPass", pass)
                })
            }, Modifier.fillMaxWidth(), enabled = portOk)
        }
    }
}

@Composable
private fun TemplatesList(list: List<EmailTemplate>, vm: AdminSettingsVm) {
    var edit by remember { mutableStateOf<EmailTemplate?>(null) }
    ListScreenColumn {
        if (list.isEmpty()) item { EmptyState(Icons.Filled.Email, "Sin plantillas") }
        items(list, key = { it.id }) { t ->
            GlassCard(Modifier.fillMaxWidth(), onClick = { edit = t }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(t.name, style = MaterialTheme.typography.titleMedium); Muted(t.subject) }
                    StatusChip(if (t.isActive) "Activa" else "Inactiva", if (t.isActive) Tone.Success else Tone.Neutral)
                }
            }
        }
    }
    edit?.let { t ->
        var n by remember { mutableStateOf(t.name) }; var sub by remember { mutableStateOf(t.subject) }
        var body by remember { mutableStateOf(t.htmlBody) }; var act by remember { mutableStateOf(t.isActive) }
        FormDialog("Plantilla · ${t.type}", enabled = n.isNotBlank() && sub.isNotBlank() && body.isNotBlank(), onDismiss = { edit = null }, onConfirm = {
            vm.saveTemplate(t.id, EmailTemplateUpdate(n.trim(), sub.trim(), body, act)); edit = null
        }) {
            XField(n, { n = it }, "Nombre"); XField(sub, { sub = it }, "Asunto")
            XField(body, { body = it }, "Cuerpo HTML", singleLine = false, minLines = 5)
            t.variables?.takeIf { it.isNotBlank() }?.let { Muted("Variables: $it") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(act, { act = it }); Spacer(Modifier.width(8.dp)); Text("Plantilla activa") }
        }
    }
}

// ───────────────────────── Dashboard layouts ─────────────────────────

@HiltViewModel
class AdminLayoutsVm @Inject constructor(private val api: AdminApi, lf: LoaderFactory) : AdminVm() {
    val layouts = lf.create<List<DashLayout>>(viewModelScope, "adm_layouts") { api.layouts() }
    fun create(name: String, active: Boolean) = act("Diseño creado", { api.createLayout(CreateLayoutRequest(name, null, active)) }) { layouts.refresh() }
    fun setActive(id: String, on: Boolean) = act(if (on) "Diseño activado" else "Diseño desactivado", { api.updateLayout(id, UpdateLayoutRequest(isActive = on)) }) { layouts.refresh() }
    fun delete(id: String) = act("Diseño eliminado", { api.deleteLayout(id) }) { layouts.refresh() }
    fun addWidget(layoutId: String, r: AddWidgetRequest) = act("Widget añadido", { api.addWidget(layoutId, r) }) { layouts.refresh() }
    fun toggleWidget(w: DashWidget) = act("Widget actualizado", { api.updateWidget(w.id, UpdateWidgetRequest(isActive = !w.isActive)) }) { layouts.refresh() }
    fun deleteWidget(id: String) = act("Widget eliminado", { api.deleteWidget(id) }) { layouts.refresh() }
    fun move(l: DashLayout, index: Int, delta: Int) {
        val ws = l.widgets.sortedBy { it.order }.toMutableList()
        val j = index + delta
        if (j !in ws.indices) return
        val t = ws[index]; ws[index] = ws[j]; ws[j] = t
        act("Orden actualizado", { api.reorder(l.id, ReorderRequest(ws.mapIndexed { i, w -> WidgetOrder(w.id, i) })) }) { layouts.refresh() }
    }
}

private val WIDGET_TYPES = listOf("FICHAJE", "VACATIONS", "PAYROLL", "EVENTS", "NEWS", "BENEFITS", "ALERTS", "ADJUSTMENTS", "BANNER")
private val WIDGET_SCOPES = listOf("ALL" to "Todos", "EMPLOYEE" to "Empleados", "MANAGER" to "Responsables", "ADMIN" to "Admins")

@Composable
fun AdminLayoutsScreen(onBack: () -> Unit, snack: (String) -> Unit, vm: AdminLayoutsVm = hiltViewModel()) {
    val st by vm.layouts.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<DashLayout?>(null) }
    var delW by remember { mutableStateOf<DashWidget?>(null) }
    var addTo by remember { mutableStateOf<DashLayout?>(null) }
    AdminPage("Diseño del panel", "Widgets del inicio", onBack, vm, snack,
        actions = { IconAction(Icons.Filled.Add, "Nuevo diseño") { creating = true } }) {
        LoadScreen(st, { vm.layouts.refresh() }) { list ->
            ListScreenColumn {
                if (list.isEmpty()) item { EmptyState(Icons.Filled.Dashboard, "Sin diseños") }
                items(list, key = { it.id }) { l ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(l.name, style = MaterialTheme.typography.titleMedium); Muted("${l.widgets.size} widgets") }
                            Switch(l.isActive, { vm.setActive(l.id, it) })
                            IconAction(Icons.Filled.Delete, "Eliminar", danger = true) { del = l }
                        }
                        val ws = l.widgets.sortedBy { it.order }
                        ws.forEachIndexed { i, w ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(w.label.ifBlank { w.type }, style = MaterialTheme.typography.bodyLarge)
                                    Muted("${w.type} · ${WIDGET_SCOPES.firstOrNull { it.first == w.scope }?.second ?: w.scope}")
                                }
                                Switch(w.isActive, { vm.toggleWidget(w) })
                                IconAction(Icons.Filled.ArrowUpward, "Subir") { vm.move(l, i, -1) }
                                IconAction(Icons.Filled.ArrowDownward, "Bajar") { vm.move(l, i, 1) }
                                IconAction(Icons.Filled.Close, "Quitar", danger = true) { delW = w }
                            }
                        }
                        TextButton({ addTo = l }) { Text("Añadir widget") }
                    }
                }
            }
        }
    }
    if (creating) {
        var n by remember { mutableStateOf("") }; var a by remember { mutableStateOf(false) }
        FormDialog("Nuevo diseño", "Crear", n.isNotBlank(), { vm.create(n.trim(), a); creating = false }, { creating = false }) {
            XField(n, { n = it }, "Nombre")
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(a, { a = it }); Spacer(Modifier.width(8.dp)); Text("Activar ahora (desactiva los demás)") }
        }
    }
    addTo?.let { l ->
        var type by remember { mutableStateOf(WIDGET_TYPES.first()) }; var label by remember { mutableStateOf("") }; var scope by remember { mutableStateOf("ALL") }
        FormDialog("Añadir widget", "Añadir", true, { vm.addWidget(l.id, AddWidgetRequest(type, label.trim().ifBlank { type }, true, scope)); addTo = null }, { addTo = null }) {
            Dropdown("Tipo", type, WIDGET_TYPES.map { it to it }, { type = it })
            XField(label, { label = it }, "Etiqueta")
            Dropdown("Visible para", scope, WIDGET_SCOPES, { scope = it })
        }
    }
    del?.let { ConfirmDialog("¿Eliminar el diseño ${it.name} y sus widgets?", onConfirm = { vm.delete(it.id) }, onDismiss = { del = null }) }
    delW?.let { ConfirmDialog("¿Quitar el widget ${it.label.ifBlank { it.type }}?", "Quitar", onConfirm = { vm.deleteWidget(it.id) }, onDismiss = { delW = null }) }
}
