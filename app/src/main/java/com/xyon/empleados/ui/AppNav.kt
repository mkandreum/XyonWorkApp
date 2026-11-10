package com.xyon.empleados.ui

import io.github.mkandreum.liquidbar.LiquidBarItem
import io.github.mkandreum.liquidbar.LiquidGlassStyle
import io.github.mkandreum.liquidbar.LiquidPillBar
import io.github.mkandreum.liquidbar.LiquidPillDefaults
import io.github.mkandreum.liquidbar.glass.GlassBackdrop
import io.github.mkandreum.liquidbar.glass.glassSource
import io.github.mkandreum.liquidbar.glass.rememberGlassBackdrop
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xyon.empleados.data.User
import com.xyon.empleados.ui.admin.adminRoutes
import com.xyon.empleados.ui.theme.*
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val EMPLOYEE_TABS = listOf(
    Tab("home", "Inicio", Icons.Filled.Home),
    Tab("calendar", "Calendario", Icons.Filled.CalendarMonth),
    Tab("requests", "Solicitudes", Icons.Filled.BeachAccess),
    Tab("payroll", "Nóminas", Icons.Filled.Receipt),
    Tab("news", "Noticias", Icons.Filled.Newspaper),
    Tab("profile", "Perfil", Icons.Filled.Person),
)
/** Admins manage, they don't clock in or get payslips here: their bar is the management menu. */
private val ADMIN_TABS = listOf(
    Tab("admin", "Panel", Icons.Filled.Dashboard),
    Tab("adm/all", "Solicitudes", Icons.Filled.Inbox),
    Tab("adm/fichajes", "Fichajes", Icons.Filled.AccessTime),
    Tab("adm/users", "Usuarios", Icons.Filled.People),
    Tab("profile", "Perfil", Icons.Filled.Person),
)
private fun tabsFor(user: User) = if (user.isAdmin) ADMIN_TABS else EMPLOYEE_TABS

@Composable
fun AppRoot(vm: SessionViewModel = hiltViewModel()) {
    val auth by vm.auth.collectAsState()
    val locked by vm.locked.collectAsState()
    val bioEnabled by vm.biometricEnabled.collectAsState(initial = false)
    // initial=true so the walkthrough never flashes before the stored flag is read
    val onboarded by vm.permissionsOnboarded.collectAsState(initial = true)
    val snack = remember { SnackbarHostState() }
    val activity = LocalContext.current as FragmentActivity
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { vm.message.collect { snack.showSnackbar(it) } }
    // Reset link while already logged in: the screen lives in the logged-out graph, so keep the
    // link pending (it will open after logout) and explain it instead of doing nothing.
    val resetLink by DeepLinks.pendingReset.collectAsState()
    LaunchedEffect(resetLink, auth) {
        if (resetLink != null && auth is AuthState.LoggedIn) {
            snack.showSnackbar("Enlace para cambiar la contraseña recibido: cierra sesión para usarlo")
        }
    }

    // Leaving the app (home, app switcher, screen off) locks it; trips the app itself started
    // (PIN screen, pickers, share) and configuration changes (rotation) do not.
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && !activity.isChangingConfigurations && !AppLock.externalActive) vm.onAppBackground()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // With the lock on, hide the app's content in the recent-apps preview (Android 13+).
    LaunchedEffect(bioEnabled) {
        if (android.os.Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!bioEnabled)
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            auth::class, label = "auth",
            transitionSpec = { (fadeIn(tween(350)) + scaleIn(initialScale = .96f)) togetherWith fadeOut(tween(200)) },
        ) { kind ->
            when (kind) {
                AuthState.Loading::class -> AuroraBackground {}
                AuthState.LoggedOut::class -> AuthGraph(vm)
                AuthState.Locked::class -> LockScreen(vm)
                else -> (auth as? AuthState.LoggedIn)?.let { MainShell(it.user, vm, snack) }
            }
        }
        // First login on this device: permissions walkthrough
        AnimatedVisibility(
            !onboarded && auth is AuthState.LoggedIn && !locked,
            enter = fadeIn(tween(250)) + slideInVertically { it / 6 }, exit = fadeOut(tween(250)),
        ) {
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) {}) {
                PermissionsScreen(vm) { vm.finishPermissionsOnboarding() }
            }
        }
        // Resume lock sits on top of the live session so the user returns to exactly where they were.
        AnimatedVisibility(
            locked && auth is AuthState.LoggedIn,
            enter = fadeIn(tween(150)), exit = fadeOut(tween(250)) + scaleOut(targetScale = 1.04f),
        ) {
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) {}) { LockScreen(vm) }
        }
        SnackbarHost(snack, Modifier.align(Alignment.TopCenter).statusBarsPadding())
    }
}

@Composable
private fun AuthGraph(vm: SessionViewModel) {
    val nav = rememberNavController()
    // No company chosen yet: start at "Conecta con tu empresa" (it also offers the build's default
    // server, so installs from before multi-company keep working with one tap).
    val start = remember { if (com.xyon.empleados.data.ServerConfig.isConfigured) "login" else "connect" }
    // App Link del correo de reset: abre "Nueva contraseña" con token y email ya puestos.
    val resetLink by DeepLinks.pendingReset.collectAsState()
    LaunchedEffect(start, resetLink) {
        val link = resetLink ?: return@LaunchedEffect
        DeepLinks.pendingReset.value = null
        val token = android.net.Uri.encode(link.token)
        val email = android.net.Uri.encode(link.email)
        nav.navigate("reset?token=$token&email=$email") { launchSingleTop = true }
    }
    NavHost(nav, start, enterTransition = { slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it / 3 } + fadeIn() },
        exitTransition = { fadeOut() }, popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 3 } + fadeOut() }) {
        composable("login") { LoginScreen(vm, { nav.navigate("register") }, { nav.navigate("forgot") }, { nav.navigate("connect") }) }
        composable("connect") {
            ConnectScreen(
                onDone = { nav.navigate("login") { popUpTo(nav.graph.id) { inclusive = true } } },
                onBack = if (nav.previousBackStackEntry != null) ({ nav.popBackStack() }) else null,
            )
        }
        composable("register") { RegisterScreen(vm) { nav.popBackStack() } }
        composable("forgot") { ForgotScreen(vm, { nav.popBackStack() }, { nav.navigate("reset") }) }
        composable(
            "reset?token={token}&email={email}",
            arguments = listOf(
                navArgument("token") { defaultValue = "" },
                navArgument("email") { defaultValue = "" },
            ),
        ) { entry ->
            ResetScreen(
                vm,
                onBack = { nav.popBackStack() },
                onDone = { nav.navigate("login") { popUpTo("login") { inclusive = true } } },
                initialToken = entry.arguments?.getString("token").orEmpty(),
                initialEmail = entry.arguments?.getString("email").orEmpty(),
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MainShell(user: User, session: SessionViewModel, snack: SnackbarHostState) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val act = LocalContext.current as FragmentActivity
    val newsVm: NewsViewModel = hiltViewModel()
    val newsState by newsVm.news.state.collectAsState()
    val say: (String) -> Unit = { m ->
        // A failure buzzes harder than a confirmation
        if (Regex("(?i)no se pudo|error|inv[aá]lid|fall|incorrect|denegad|sin conexi[oó]n").containsMatchIn(m)) Haptics.reject() else Haptics.tick()
        scope.launch { snack.showSnackbar(m) }
    }
    // Admins must enable two-step verification first (the server blocks everything else)
    if (user.isAdmin && user.mfaSetupRequired) {
        Box(Modifier.fillMaxSize().background(Xyon.colors.bg).statusBarsPadding()) {
            Column {
                Text("Protege tu cuenta de administración: activa la verificación en dos pasos para continuar.",
                    color = Xyon.colors.ink, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
                SecurityScreen(session, { session.signOut() }, say)
            }
        }
        return
    }
    val route by nav.currentBackStackEntryAsState()
    val current = route?.destination?.route
    val planVm: PlanViewModel = hiltViewModel(act)
    val features by planVm.features.collectAsState()
    LaunchedEffect(user.id) { planVm.load() }
    val tabs = remember(user.role, features) { tabsFor(user).filter { it.route != "payroll" || features.allows("payroll") } }
    val start = tabs.first().route
    val showBar = tabs.any { it.route == current }

    fun push(r: String) = nav.navigate(r) { launchSingleTop = true }
    fun tab(r: String) = nav.navigate(r) { popUpTo(start) { saveState = true }; launchSingleTop = true; restoreState = true }

    // Launcher shortcuts land here once the session is open
    val deepLink by DeepLinks.pending.collectAsState()
    LaunchedEffect(deepLink) {
        val r = deepLink ?: return@LaunchedEffect
        DeepLinks.pending.value = null
        // Only whitelisted tabs: any app can send an intent to the exported launcher activity
        if (tabs.any { it.route == r }) tab(r)
    }

    val backdrop = rememberGlassBackdrop()
    Box(Modifier.fillMaxSize()) {
    // Everything the glass bar refracts; the bar is a sibling, never a child (RenderNode cycle)
    AuroraBackground(Modifier.glassSource(backdrop)) {
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedScope provides this) {
                NavHost(
                    nav, start,
                    enterTransition = { slideInHorizontally(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { it / 4 } + fadeIn(tween(250)) },
                    exitTransition = { fadeOut(tween(150)) },
                    popEnterTransition = { fadeIn(tween(250)) },
                    popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut(tween(200)) },
                ) {
                    composable("home") { Scoped(this) {
                        HomeScreen(user, { push("notifications") }, { tab("calendar") }, { tab("payroll") }, { tab("news") }, { push("team") }, { push("admin") }, snack = say)
                    } }
                    composable("calendar") { Scoped(this) { CalendarScreen(user, snack = say, onMyRecord = { push("myreport") }, onGoHome = { tab("home") }) } }
                    composable("myreport") { Scoped(this) { ReportsScreen(user, self = true, { nav.popBackStack() }, say) } }
                    composable("requests") { Scoped(this) { RequestsScreen(snack = say) } }
                    composable("news") { Scoped(this) { NewsScreen(newsVm) { n -> push("news/${n.id}") } } }
                    composable("news/{id}") { e -> Scoped(this) {
                        val id = e.arguments?.getString("id")
                        NewsDetailScreen(newsState.data?.firstOrNull { it.id == id }) { nav.popBackStack() }
                    } }
                    composable("profile") { Scoped(this) {
                        ProfileScreen(user, session, { push("notifications") }, { tab("payroll") }, { tab("calendar") }, { push("team") }, { if (tabs.any { it.route == "admin" }) tab("admin") else push("admin") }, onSecurity = { push("security") }) { on ->
                            if (!on) session.setBiometric(false)
                            else if (canAuthenticate(act)) promptBiometric(act, "Activar desbloqueo", { session.setBiometric(true) }, { say(it) })
                            else say("Este dispositivo no tiene biometría o PIN configurado")
                        }
                    } }
                    composable("security") { Scoped(this) { SecurityScreen(session, { nav.popBackStack() }, say) } }
                    composable("notifications") { Scoped(this) {
                        NotificationsScreen({ nav.popBackStack() }, user) { r ->
                            if (tabs.any { it.route == r }) tab(r) else push(r)
                        }
                    } }
                    composable("payroll") { Scoped(this) {
                        BiometricGate("Tus nóminas", "Confirma que eres tú para verlas", { tab("home") }) {
                            PayrollScreen(null, snack = say)
                        }
                    } }
                    adminRoutes(nav, user, say, tabRoots = tabs.map { it.route }.toSet())
                }
            }
        }
    }
    AnimatedVisibility(
        showBar, Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(spring(Spring.DampingRatioMediumBouncy)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) { BottomBar(backdrop, tabs, current ?: start) { tab(it) } }
    }
}

@Composable
private fun Scoped(scope: AnimatedVisibilityScope, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAnimScope provides scope) { Box(Modifier.fillMaxSize()) { content() } }
}

/** Floating liquid-glass pill (LiquidBar): the active tab is a lime lens that springs between tabs and can be dragged. */
@Composable
private fun BottomBar(backdrop: GlassBackdrop, tabs: List<Tab>, selected: String, onSelect: (String) -> Unit) {
    val x = Xyon.colors
    val idx = tabs.indexOfFirst { it.route == selected }.coerceAtLeast(0)
    val items = remember(tabs) { tabs.map { LiquidBarItem(it.label, it.icon) } }
    // The active tab shows its name when the pill still fits; otherwise it is icon-only with a brief hint
    val showLabels = LocalConfiguration.current.screenWidthDp >= tabs.size * 48 + 137
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) { if (hint != null) { kotlinx.coroutines.delay(1500); hint = null } }
    Column(Modifier.navigationBarsPadding().padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(hint != null, enter = fadeIn() + scaleIn(spring(Spring.DampingRatioMediumBouncy)), exit = fadeOut()) {
            Box(Modifier.padding(bottom = 8.dp).clip(CircleShape).background(Lime).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(hint.orEmpty(), color = InkBlack, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
            }
        }
        LiquidPillBar(
            backdrop = backdrop,
            items = items,
            selectedIndex = idx,
            onSelect = { i ->
                if (i != idx) { Haptics.click(); if (!showLabels) hint = tabs[i].label; onSelect(tabs[i].route) }
            },
            colors = LiquidPillDefaults.colors(accent = Lime, onAccent = InkBlack, content = (if (x.dark) Color.White else InkBlack).copy(alpha = .72f)),
            glass = if (x.dark) LiquidGlassStyle.Dark else LiquidGlassStyle.Light,
            showLabels = showLabels,
        )
    }
}
