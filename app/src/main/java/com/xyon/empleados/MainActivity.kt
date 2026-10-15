package com.xyon.empleados

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.xyon.empleados.ui.AppLock
import com.xyon.empleados.ui.AppRoot
import com.xyon.empleados.ui.theme.XyonTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class XyonApp : Application()

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XyonTheme { AppRoot() } }
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
