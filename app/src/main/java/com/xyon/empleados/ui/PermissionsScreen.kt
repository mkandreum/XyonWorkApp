package com.xyon.empleados.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xyon.empleados.ui.theme.*

private data class PermItem(val title: String, val why: String, val icon: ImageVector, val color: Color, val perms: List<String>)

/** Permissions this device actually needs (depends on the Android version). */
private fun neededItems(): List<PermItem> = buildList {
    if (Build.VERSION.SDK_INT >= 33) add(PermItem(
        "Notificaciones", "Avisos de solicitudes aprobadas, recordatorios de fichaje y comunicados.",
        Icons.Filled.Notifications, Lilac, listOf(Manifest.permission.POST_NOTIFICATIONS),
    ))
    add(PermItem(
        "Ubicación", "Se usa solo al fichar, para validar que estás en tu centro de trabajo.",
        Icons.Filled.LocationOn, Lime, listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    ))
    if (Build.VERSION.SDK_INT < 29) add(PermItem(
        "Archivos", "Para guardar tus nóminas en la carpeta Descargas.",
        Icons.Filled.Folder, Sun, listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
    ))
}

/**
 * First-run walkthrough after the first login on a device: explains and requests the Android
 * permissions the app uses, and offers the biometric lock. Shown once; everything can be changed later.
 */
@Composable
fun PermissionsScreen(session: SessionViewModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as FragmentActivity
    val x = Xyon.colors
    val items = remember { neededItems() }
    val bio by session.biometricEnabled.collectAsState(initial = false)
    var tick by remember { mutableIntStateOf(0) }   // re-read grants after returning from dialogs/settings

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    fun granted(item: PermItem) = item.perms.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    BlueField {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Antes de\nempezar.", color = Color.White, fontWeight = FontWeight.Black, fontSize = 48.sp, lineHeight = 46.sp, letterSpacing = (-2.4).sp,
                modifier = Modifier.padding(top = 12.dp))
            Text("Xyon necesita algunos permisos para funcionar bien. Puedes cambiarlos cuando quieras en Ajustes.",
                color = Color.White.copy(alpha = .85f), fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))

            key(tick) {
                items.forEachIndexed { i, item ->
                    val ok = granted(item)
                    PermCard(item.title, item.why, item.icon, item.color, ok, Modifier.staggered(i)) {
                        launcher.launch(item.perms.toTypedArray())
                    }
                }
            }

            // Exact alarms are a special access on Android 12+, not a runtime permission.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarms = ctx.getSystemService(AlarmManager::class.java)
                if (alarms?.canScheduleExactAlarms() != true) {
                    PermCard(
                        "Recordatorios exactos",
                        "Así el aviso de fichaje llega justo 5 minutos antes, aunque el móvil esté en reposo.",
                        Icons.Filled.Alarm, Sun, false, Modifier.staggered(items.size),
                        actionLabel = "Permitir",
                    ) {
                        val packageUri = Uri.parse("package:${ctx.packageName}")
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri))
                        }.onFailure {
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
                        }
                    }
                }
            }

            PermCard(
                "Bloqueo con huella", "Pide tu huella, rostro o PIN al volver a la app y al abrir tus nóminas.",
                Icons.Filled.Fingerprint, Coral, bio, Modifier.staggered(items.size),
                actionLabel = "Activar",
            ) {
                if (canAuthenticate(act)) promptBiometric(act, "Activar bloqueo", { session.setBiometric(true) })
            }

            Spacer(Modifier.height(8.dp))
            val pending = remember(tick) { items.filterNot { granted(it) } }
            if (pending.isNotEmpty()) {
                XyonButton("Permitir todo", { launcher.launch(pending.flatMap { it.perms }.toTypedArray()) }, Modifier.fillMaxWidth(), style = XyonButtonStyle.Lime)
            }
            XyonButton(if (pending.isEmpty()) "Empezar" else "Continuar", onDone, Modifier.fillMaxWidth(), style = XyonButtonStyle.Ink)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PermCard(
    title: String, why: String, icon: ImageVector, color: Color, granted: Boolean, modifier: Modifier = Modifier,
    actionLabel: String = "Permitir", onAsk: () -> Unit,
) {
    val x = Xyon.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(x.card).border(1.5.dp, x.outline, RoundedCornerShape(28.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BlobIcon(icon, color, 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = x.ink)
            Text(why, color = x.inkSoft, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(8.dp))
        if (granted) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Lime), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, "Concedido", tint = InkBlack)
            }
        } else {
            Text(actionLabel, color = x.bg, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                modifier = Modifier.clip(CircleShape).background(x.ink).clickable(onClick = onAsk).padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}
