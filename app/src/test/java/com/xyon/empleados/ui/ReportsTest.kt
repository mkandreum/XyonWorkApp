package com.xyon.empleados.ui

import com.xyon.empleados.data.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReportsTest {
    private val wed = LocalDate.of(2026, 10, 21) // miércoles

    @Test fun thisWeekStartsMonday() = assertEquals(LocalDate.of(2026, 10, 19) to LocalDate.of(2026, 10, 25), presetRange("this-week", wed))
    @Test fun lastWeek() = assertEquals(LocalDate.of(2026, 10, 12) to LocalDate.of(2026, 10, 18), presetRange("last-week", wed))
    @Test fun thisMonth() = assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 31), presetRange("this-month", wed))
    @Test fun lastMonthFebruaryLeap() = assertEquals(LocalDate.of(2028, 2, 1) to LocalDate.of(2028, 2, 29), presetRange("last-month", LocalDate.of(2028, 3, 15)))

    @Test fun reportHtmlEscapesUserContent() {
        val data = AttendanceReportData(
            period = ReportPeriod(label = "01/10/2026 - 31/10/2026"),
            reports = listOf(UserReport(
                user = ReportUser(name = "<script>alert(1)</script>", department = "Ventas"),
                days = listOf(ReportDay(date = "2026-10-01", dayName = "jueves", status = "Correcto", kind = "ok", observations = "<b>x</b>", workMode = "REMOTE", hours = 8.0)),
                summary = ReportSummary(daysWorked = 1, totalHours = 8.0, daysRemote = 1),
            )),
        )
        val html = reportHtml(data, "Acme & Co")
        assertFalse(html.contains("<script>alert"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("Acme &amp; Co"))
        assertTrue(html.contains("01/10/2026"))
        assertTrue(html.contains("Casa"))
    }
}
