package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SsdpProtocolTest {
    @Test
    fun searchRequestCarriesTheStandardHeaders() {
        val request = SsdpProtocol.buildSearchRequest("ssdp:all", mx = 3)

        assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(request.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(request.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(request.contains("MX: 3\r\n"))
        assertTrue(request.contains("ST: ssdp:all\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test
    fun everySearchTargetIsQueried() {
        assertTrue(SsdpProtocol.SEARCH_TARGETS.contains("ssdp:all"))
        assertTrue(SsdpProtocol.SEARCH_TARGETS.contains(DlnaProtocol.MEDIA_RENDERER_DEVICE_TYPE))
        assertTrue(SsdpProtocol.SEARCH_TARGETS.contains(DlnaProtocol.AVTRANSPORT_SERVICE))
    }

    @Test
    fun locationIsExtractedFromTheResponse() {
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=1800\r\n")
            append("LOCATION: http://192.168.1.5:8200/desc/device.xml\r\n")
            append("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
            append("\r\n")
        }

        assertEquals("http://192.168.1.5:8200/desc/device.xml", SsdpProtocol.parseLocation(response))
    }

    @Test
    fun locationHeaderIsCaseInsensitive() {
        val response = "HTTP/1.1 200 OK\r\nlocation: http://host/desc.xml\r\n\r\n"

        assertEquals("http://host/desc.xml", SsdpProtocol.parseLocation(response))
    }

    @Test
    fun nonResponsePacketsAreIgnored() {
        assertNull(SsdpProtocol.parseLocation("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n\r\n"))
        assertNull(SsdpProtocol.parseLocation("HTTP/1.1 200 OK\r\nST: ssdp:all\r\n\r\n"))
        assertNull(SsdpProtocol.parseLocation(""))
    }
}
