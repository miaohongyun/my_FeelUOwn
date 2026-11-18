package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceDescriptionParserTest {
    @Test
    fun rendererWithAvTransportBecomesACastDevice() {
        val device = DeviceDescriptionParser.parse(
            xml = DESCRIPTION,
            location = "http://192.168.1.5:8200/desc/device.xml",
        )

        assertNotNull(device)
        assertEquals("TCL Living Room", device.name)
        assertEquals("192.168.1.5", device.host)
        assertEquals(8200, device.port)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", device.rendererType)
        assertEquals("http://192.168.1.5:8200/AVTransport/Control", device.controlUrl)
        assertEquals("TCL", device.manufacturer)
    }

    @Test
    fun relativeControlUrlIsResolvedAgainstUrlBase() {
        val device = DeviceDescriptionParser.parse(
            xml = DESCRIPTION.replace(
                "<controlURL>/AVTransport/Control</controlURL>",
                "<controlURL>AVTransport/Control</controlURL>",
            ).replace(
                "<URLBase>http://192.168.1.5:8200/</URLBase>",
                "",
            ),
            location = "http://192.168.1.5:8200/desc/device.xml",
        )

        assertNotNull(device)
        assertEquals("http://192.168.1.5:8200/desc/AVTransport/Control", device.controlUrl)
    }

    @Test
    fun staleUrlBaseIsRejectedInFavourOfTheLocationAddress() {
        val xml = DESCRIPTION.replace(
            "http://192.168.1.5:8200/",
            "http://192.168.1.99:8200/",
        )

        val device = DeviceDescriptionParser.parse(xml, "http://192.168.1.5:8200/desc/device.xml")

        assertNotNull(device)
        assertEquals("192.168.1.5", device.host)
        assertEquals("http://192.168.1.5:8200/AVTransport/Control", device.controlUrl)
    }

    @Test
    fun controlUrlHostIsRewrittenToTheReachableAddress() {
        val xml = DESCRIPTION.replace(
            "<controlURL>/AVTransport/Control</controlURL>",
            "<controlURL>http://192.168.1.99:8200/AVTransport/Control</controlURL>",
        )

        val device = DeviceDescriptionParser.parse(xml, "http://192.168.1.5:8200/desc/device.xml")

        assertNotNull(device)
        assertEquals("http://192.168.1.5:8200/AVTransport/Control", device.controlUrl)
    }

    @Test
    fun loopbackControlUrlFromWindowsMediaPlayerIsRewritten() {
        // Windows Media Player advertises ip6-localhost in its description; it is only valid on
        // the machine that published it, never on the phone that found it over SSDP.
        val xml = DESCRIPTION.replace(
            "<controlURL>/AVTransport/Control</controlURL>",
            "<controlURL>http://ip6-localhost/upnphost/udhisapi.dll?control=uuid:renderer</controlURL>",
        )

        val device = DeviceDescriptionParser.parse(xml, "http://192.168.5.126:2869/desc/device.xml")

        assertNotNull(device)
        assertEquals(
            "http://192.168.5.126:2869/upnphost/udhisapi.dll?control=uuid:renderer",
            device.controlUrl,
        )
    }

    @Test
    fun devicesWithoutAvTransportAreRejected() {
        val xml = DESCRIPTION.replace(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1",
        )

        assertNull(DeviceDescriptionParser.parse(xml, "http://192.168.1.5:8200/desc/device.xml"))
    }

    @Test
    fun brokenXmlIsRejected() {
        assertNull(DeviceDescriptionParser.parse("not xml at all", "http://host/desc.xml"))
        assertNull(DeviceDescriptionParser.parse("", "http://host/desc.xml"))
    }

    @Test
    fun urlResolutionHandlesAbsoluteAndRootedPaths() {
        assertEquals(
            "http://host/x",
            DeviceDescriptionParser.resolveUrl("http://host/base/", "http://host/x"),
        )
        assertEquals(
            "http://host/x",
            DeviceDescriptionParser.resolveUrl("http://host/base/", "/x"),
        )
        assertEquals(
            "http://host/base/x",
            DeviceDescriptionParser.resolveUrl("http://host/base/", "x"),
        )
        assertEquals("", DeviceDescriptionParser.resolveUrl("http://host/base/", ""))
    }

    @Test
    fun embeddedDevicesAreVisited() {
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Embedded Renderer</friendlyName>
                <UDN>uuid:embedded</UDN>
                <deviceList>
                  <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                    <friendlyName>Inner</friendlyName>
                    <UDN>uuid:inner</UDN>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                        <controlURL>/ctl</controlURL>
                      </service>
                    </serviceList>
                  </device>
                </deviceList>
              </device>
            </root>
        """.trimIndent()

        val device = DeviceDescriptionParser.parse(xml, "http://host/desc.xml")
        assertNotNull(device)
        assertTrue(device.id.isNotEmpty())
    }

    private companion object {
        val DESCRIPTION = """
            <?xml version="1.0" encoding="utf-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <URLBase>http://192.168.1.5:8200/</URLBase>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>TCL Living Room</friendlyName>
                <manufacturer>TCL</manufacturer>
                <modelName>T7L</modelName>
                <UDN>uuid:tcl-living-room</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/RenderingControl/Control</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/AVTransport/Control</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
    }
}
