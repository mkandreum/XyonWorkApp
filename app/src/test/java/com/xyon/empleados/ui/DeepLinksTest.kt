package com.xyon.empleados.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinksTest {

    @Test
    fun readsTokenAndEmailFromTheResetLink() {
        val r = DeepLinks.parseReset("https://work.xyoncloud.win/reset-password?token=abc123&email=ana%40empresa.com")
        assertEquals("abc123", r?.token)
        assertEquals("ana@empresa.com", r?.email)
    }

    @Test
    fun decodesTheEmailPlusTagAndToleratesTrailingSlash() {
        val r = DeepLinks.parseReset("https://work.xyoncloud.win/reset-password/?email=ana%2Brrhh%40empresa.com&token=deadbeef")
        assertEquals("ana+rrhh@empresa.com", r?.email)
        assertEquals("deadbeef", r?.token)
    }

    @Test
    fun ignoresLinksThatAreNotTheResetPage() {
        assertNull(DeepLinks.parseReset("https://work.xyoncloud.win/login?token=abc&email=a%40b.com"))
        assertNull(DeepLinks.parseReset("https://work.xyoncloud.win/payroll?token=abc&email=a%40b.com"))
        assertNull(DeepLinks.parseReset("https://work.xyoncloud.win/"))
        assertNull(DeepLinks.parseReset(null))
        assertNull(DeepLinks.parseReset("no es una url"))
    }

    @Test
    fun rejectsInsecureLinksAndMissingFields() {
        assertNull(DeepLinks.parseReset("http://work.xyoncloud.win/reset-password?token=abc&email=a%40b.com"))
        assertNull(DeepLinks.parseReset("https://work.xyoncloud.win/reset-password?email=a%40b.com"))
        assertNull(DeepLinks.parseReset("https://work.xyoncloud.win/reset-password?token=abc"))
        assertNull(DeepLinks.parseReset("https://work.xyoncloud.win/reset-password?token=abc&email=sin-arroba"))
    }
}
