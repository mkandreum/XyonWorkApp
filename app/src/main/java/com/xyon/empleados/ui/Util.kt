package com.xyon.empleados.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val ES: Locale = Locale("es", "ES")
private val fmtDay = DateTimeFormatter.ofPattern("d MMM yyyy", ES)
private val fmtTime = DateTimeFormatter.ofPattern("HH:mm", ES)

fun parseDate(iso: String?): LocalDate? = iso?.let {
    runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(it.take(10)) }.getOrNull()
}

fun fmtDate(iso: String?): String = parseDate(iso)?.format(fmtDay) ?: ""
fun fmtTimeOf(iso: String?): String =
    runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).format(fmtTime) }.getOrDefault("--:--")
fun monthName(m: Int) = java.time.Month.of(m).getDisplayName(TextStyle.FULL, ES).replaceFirstChar { it.uppercase() }
fun money(v: Double) = java.text.NumberFormat.getCurrencyInstance(ES).format(v)

fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Buenos días"; in 12..19 -> "Buenas tardes"; else -> "Buenas noches"
}

fun hasLocationPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Best last-known location (no Play Services dependency). Null when permission/providers are unavailable. */
@SuppressLint("MissingPermission")
fun lastLocation(ctx: Context): Location? {
    if (!hasLocationPermission(ctx)) return null
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
}

/** Legal pages of the company server (privacy, legal notice, account deletion), opened in the browser. */
fun openLegalPage(ctx: Context, path: String) {
    val url = com.xyon.empleados.data.ServerConfig.origin + path.removePrefix("/")
    runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Safe file name for a PDF saved on the device. */
fun pdfFileName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf" }

/** Android 8/9 need the legacy storage permission to write to the public Downloads folder. */
fun needsLegacyStoragePermission(ctx: Context): Boolean =
    android.os.Build.VERSION.SDK_INT < 29 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

/**
 * Saves a PDF into the public Downloads folder. Uses MediaStore on Android 10+ (no permission needed)
 * and the legacy public directory on Android 8/9. Returns the user-visible location or throws.
 */
fun savePdfToDownloads(ctx: Context, bytes: ByteArray, name: String): String =
    saveToDownloads(ctx, bytes, pdfFileName(name), "application/pdf")

/** Saves any file (CSV, JSON…) into the public Downloads folder; same rules as [savePdfToDownloads]. */
fun saveToDownloads(ctx: Context, bytes: ByteArray, fileName: String, mime: String): String {
    val file = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("No se pudo crear el archivo")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("No se pudo escribir el archivo")
        values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } else {
        @Suppress("DEPRECATION")
        val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        java.io.File(dir, file).writeBytes(bytes)
    }
    return "Descargas/$file"
}
