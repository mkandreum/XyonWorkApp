package com.xyon.empleados

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.xyon.empleados.ui.AppLock
import com.xyon.empleados.ui.DeepLinks
import com.xyon.empleados.ui.AppRoot
import com.xyon.empleados.ui.theme.XyonTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class XyonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.xyon.empleados.data.ServerConfig.init(this)
        // WorkManager initialises before Application.onCreate; this only enqueues
        // the periodic schedule refresh (no-op until the user logs in).
        runCatching { com.xyon.empleados.ui.Reminders.ensurePeriodicSync(this) }
    }
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DeepLinks.from(intent)
        publishShortcuts()
        setContent { XyonTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DeepLinks.from(intent)
    }

    /** Long-press launcher shortcuts. Dynamic so they work with the ".debug" application id too. */
    private fun publishShortcuts() {
        fun shortcut(id: String, label: String, icon: Int, route: String) =
            ShortcutInfoCompat.Builder(this, id)
                .setShortLabel(label)
                .setIcon(IconCompat.createWithResource(this, icon))
                .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("xyon://open/$route")))
                .build()
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(this, listOf(
                shortcut("fichar", "Fichar", R.drawable.ic_shortcut_fichar, "home"),
                shortcut("nominas", "Mis nóminas", R.drawable.ic_shortcut_payroll, "payroll"),
                shortcut("vacaciones", "Pedir vacaciones", R.drawable.ic_shortcut_vacation, "requests"),
            ))
        }
    }

    // Everything the app opens itself (file pickers via the Activity Result API, share sheets, PDF viewers)
    // goes through these; returning from them must not count as "left the app".
    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        AppLock.beginExternal()
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        AppLock.beginExternal()
        super.startActivity(intent, options)
    }

    override fun onResume() {
        super.onResume()
        AppLock.endExternal()
    }
}
