package com.xyon.empleados.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    private fun ok(input: String) = ServerAddress.normalize(input) as ServerAddress.Result.Ok
    private fun isError(input: String) = ServerAddress.normalize(input) is ServerAddress.Result.Error

    @Test fun shortCodeBuildsSubdomain() {
        val r = ok("acme")
        assertEquals("https://acme.xyonwork.com/api/", r.baseUrl)
        assertEquals(false, r.ownDomain)
    }

    @Test fun trimsAndLowercases() = assertEquals("https://mi-empresa.xyonwork.com/api/", ok("  Mi-Empresa ").baseUrl)

    @Test fun fullSubdomainIsKept() = assertEquals("https://acme.xyonwork.com/api/", ok("acme.xyonwork.com").baseUrl)

    @Test fun ownDomainWithPathFromQr() {
        val r = ok("https://rrhh.acme.com/conectar?x=1")
        assertEquals("https://rrhh.acme.com/api/", r.baseUrl)
        assertEquals("rrhh.acme.com", r.host)
        assertTrue(r.ownDomain)
    }

    @Test fun apiSuffixIsNormalized() = assertEquals("https://rrhh.acme.com/api/", ok("https://rrhh.acme.com/api/").baseUrl)

    @Test fun trailingDotIsIgnored() = assertEquals("https://acme.xyonwork.com/api/", ok("acme.xyonwork.com.").baseUrl)

    @Test fun rejectsHttp() = assertTrue(isError("http://acme.xyonwork.com"))
    @Test fun rejectsEmpty() = assertTrue(isError("   "))
    @Test fun rejectsPorts() = assertTrue(isError("acme.com:8443"))
    @Test fun rejectsCredentials() = assertTrue(isError("user@acme.com"))
    @Test fun rejectsIp() = assertTrue(isError("192.168.1.10"))
    @Test fun rejectsSpaces() = assertTrue(isError("mi empresa"))
    @Test fun rejectsBadLabels() {
        assertTrue(isError("-acme"))
        assertTrue(isError("acme-"))
        assertTrue(isError("ac_me"))
        assertTrue(isError("acme..com"))
    }
    @Test fun rejectsReservedSubdomains() {
        assertTrue(isError("admin"))
        assertTrue(isError("api.xyonwork.com"))
    }
    @Test fun rejectsNestedSubdomainOfRoot() = assertTrue(isError("a.b.xyonwork.com"))
    @Test fun rejectsOtherSchemes() = assertTrue(isError("ftp://acme.com"))
}
