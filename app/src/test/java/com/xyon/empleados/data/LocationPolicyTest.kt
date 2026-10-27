package com.xyon.empleados.data

import com.xyon.empleados.ui.FichajeWidgets
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPolicyTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `the company's policy decides per work mode`() {
        val s = json.decodeFromString<FichajeStatus>("""{"hasActiveEntry":false,"location":{"office":false,"remote":true}}""")
        assertFalse(s.wantsLocation("OFFICE"))
        assertTrue(s.wantsLocation("REMOTE"))
    }

    @Test fun `an older server without policy keeps the previous behaviour`() {
        val s = json.decodeFromString<FichajeStatus>("""{"hasActiveEntry":false}""")
        assertTrue(s.wantsLocation("OFFICE"))
        assertFalse(s.wantsLocation("REMOTE"))
    }

    @Test fun `widget hours read as hours and minutes`() {
        assertEquals("7h 30m", FichajeWidgets.formatHours(7.5))
        assertEquals("0h 00m", FichajeWidgets.formatHours(-1.0))
        assertEquals("38h 05m", FichajeWidgets.formatHours(38 + 5 / 60.0))
    }
}
