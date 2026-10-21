package com.xyon.empleados.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    private fun parse(vararg lines: String): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        val p = SseParser { out += it }
        lines.forEach { p.line(it) }
        return out
    }

    @Test fun `a notification event carries its id, name and data`() {
        val e = parse("id: n1", "event: notification", "data: {\"title\":\"Hola\"}", "").single()
        assertEquals("n1", e.id); assertEquals("notification", e.event); assertEquals("{\"title\":\"Hola\"}", e.data)
    }

    @Test fun `heartbeats and comments produce no event`() {
        assertTrue(parse(": ping", "").isEmpty())
        assertTrue(parse("retry: 5000", "").isEmpty())
    }

    @Test fun `data split over several lines is joined`() {
        assertEquals("a\nb", parse("data: a", "data: b", "").single().data)
    }

    @Test fun `the event name resets between events and the id persists`() {
        val list = parse("id: 1", "event: ready", "data: {}", "", "data: {}", "")
        assertEquals("ready", list[0].event)
        assertEquals("message", list[1].event)
        assertEquals("1", list[1].id)
    }
}
