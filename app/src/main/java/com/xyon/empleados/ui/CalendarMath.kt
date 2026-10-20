package com.xyon.empleados.ui

import com.xyon.empleados.data.DaySchedule
import com.xyon.empleados.data.Fichaje
import com.xyon.empleados.data.FichajeAdjustment
import com.xyon.empleados.data.FichajeDayStats
import java.time.Instant
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * An ENTRADA→SALIDA pair of a day. An entry with no exit yet is open (`salida == null`); an exit
 * with no entry (`entrada == null`) closes a night shift that started the previous day.
 */
data class EntryPair(val entrada: Fichaje?, val salida: Fichaje?, val hours: Double)

/**
 * Pure calendar figures (worked vs scheduled hours, live hours of an open entry, pairs, unresolved
 * incidents). Same rules as the web calendar (`utils/calendarDay.ts`).
 */
object CalendarMath {
    /** Full-time day, used when the server has no schedule for a day. */
    const val DEFAULT_DAILY_HOURS = 8.0

    /** Same margin as the server (OPEN_ENTRY_MAX_HOURS): a younger open entry is a shift in progress. */
    const val OPEN_ENTRY_MAX_HOURS = 16

    private fun minutes(hhmm: String?): Int? {
        val parts = hhmm?.split(":") ?: return null
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return h * 60 + m
    }

    private fun epoch(ts: String): Long? = runCatching { Instant.parse(ts).toEpochMilli() }.getOrNull()

    /** Hours between start and end of a (possibly split) day schedule; the lunch gap is excluded. */
    fun scheduleHours(s: DaySchedule?): Double {
        if (s == null) return 0.0
        val start = minutes(s.horaEntrada) ?: return 0.0
        val end = minutes(s.horaSalida) ?: return 0.0
        val lunchStart = minutes(s.horaSalidaManana)
        val lunchEnd = minutes(s.horaEntradaTarde)
        val gap = if (lunchStart != null && lunchEnd != null && lunchEnd > lunchStart) lunchEnd - lunchStart else 0
        val span = if (end >= start) end - start else end + 24 * 60 - start
        return max(0, span - gap) / 60.0
    }

    /** Hours expected on [day]: the schedule the server resolved, otherwise a full weekday. */
    fun expectedHours(day: LocalDate, stats: FichajeDayStats?): Double {
        stats?.daySchedule?.let { return scheduleHours(it) }
        return if (day.dayOfWeek.value >= 6) 0.0 else DEFAULT_DAILY_HOURS
    }

    /** The day's last fichaje when it is an entry still in progress (also a night shift started yesterday). */
    fun liveEntry(stats: FichajeDayStats?, nowMillis: Long): Fichaje? {
        val last = stats?.fichajes?.lastOrNull()?.takeIf { it.tipo == "ENTRADA" } ?: return null
        val age = nowMillis - (epoch(last.timestamp) ?: return null)
        return last.takeIf { age > 0 && age <= OPEN_ENTRY_MAX_HOURS * 3_600_000L }
    }

    /**
     * Worked hours of a day. The server only adds closed pairs, so a live open entry is counted up to
     * [nowMillis] (or to midnight when it started on an earlier day: the rest belongs to the next day).
     */
    fun workedHours(stats: FichajeDayStats?, nowMillis: Long, isToday: Boolean): Double {
        if (stats == null) return 0.0
        val open = liveEntry(stats, nowMillis) ?: return stats.horasTrabajadas
        val since = epoch(open.timestamp) ?: return stats.horasTrabajadas
        val startDay = Instant.ofEpochMilli(since).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val midnight = startDay.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = minOf(nowMillis, midnight)
        return stats.horasTrabajadas + max(0L, end - since) / 3_600_000.0
    }

    /** "8:34" (calendar cells). */
    fun formatHM(hours: Double): String {
        val total = max(0, (hours * 60).roundToInt())
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    /** "111h 52m" (summaries); whole hours drop the minutes. */
    fun formatHoursMinutes(hours: Double): String {
        val total = max(0, (hours * 60).roundToInt())
        val m = total % 60
        return if (m == 0) "${total / 60}h" else "${total / 60}h ${m.toString().padStart(2, '0')}m"
    }

    /** ENTRADA→SALIDA pairs in order; an entry without exit stays open and counts up to [nowMillis]. */
    fun pairs(fichajes: List<Fichaje>, nowMillis: Long): List<EntryPair> {
        val sorted = fichajes.sortedBy { epoch(it.timestamp) ?: Long.MAX_VALUE }
        val out = mutableListOf<EntryPair>()
        var i = 0
        while (i < sorted.size) {
            val f = sorted[i]
            if (f.tipo != "ENTRADA") {
                // Exit with no entry earlier the same day: it closes yesterday's night shift
                if (i == 0) {
                    val t = epoch(f.timestamp) ?: 0L
                    val midnight = Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    out += EntryPair(null, f, max(0L, t - midnight) / 3_600_000.0)
                }
                i++; continue
            }
            val next = sorted.getOrNull(i + 1)?.takeIf { it.tipo == "SALIDA" }
            val start = epoch(f.timestamp) ?: 0L
            val end = next?.let { epoch(it.timestamp) } ?: nowMillis
            out += EntryPair(f, next, max(0L, end - start) / 3_600_000.0)
            i += if (next != null) 2 else 1
        }
        return out
    }

    private fun covered(stats: FichajeDayStats, adjustments: List<FichajeAdjustment>) =
        stats.fichajes.any { f -> adjustments.any { it.fichajeId == f.id && (it.status == "PENDING" || it.status == "APPROVED") } }

    /**
     * A day needs action when it has a late arrival, an early departure or an entry left open on a
     * past day, and no adjustment request (pending or approved) already covers its records.
     */
    fun hasUnresolvedIssue(stats: FichajeDayStats?, adjustments: List<FichajeAdjustment>, isToday: Boolean, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (stats == null || stats.fichajes.isEmpty()) return false
        // A shift still in progress (e.g. started last night) is not an incident yet
        if (liveEntry(stats, nowMillis) != null) return false
        val incomplete = !isToday && !stats.isComplete
        if (!(stats.isLate || stats.isEarlyDeparture || incomplete)) return false
        return !covered(stats, adjustments)
    }

    /** More than 15 minutes over the scheduled hours. */
    fun isOvertime(worked: Double, expected: Double): Boolean = expected > 0 && worked - expected > 0.25
}
