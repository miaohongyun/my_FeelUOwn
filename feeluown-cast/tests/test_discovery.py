import unittest

from fuo_cast.discovery import (
    _resolve_url,
    guess_lan_ip,
    parse_device_description,
    parse_ssdp_packet,
)

LOCATION = "http://192.168.1.50:8200/description.xml"

SAMPLE_XML = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <URLBase>http://192.168.1.50:8200/</URLBase>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>客厅 TCL T7L</friendlyName>
    <manufacturer>TCL</manufacturer>
    <modelName>T7L</modelName>
    <UDN>uuid:TCL-T7L-001</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <controlURL>/AVTransport/ctrl</controlURL>
        <eventSubURL>/AVTransport/evt</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <controlURL>/RenderingControl/ctrl</controlURL>
      </service>
    </serviceList>
  </device>
</root>
"""

EMBEDDED_XML = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
    <friendlyName>组合设备</friendlyName>
    <UDN>uuid:root-001</UDN>
    <deviceList>
      <device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>内建播放器</friendlyName>
        <serviceList>
          <service>
            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>AVTransport/ctrl</controlURL>
          </service>
        </serviceList>
      </device>
    </deviceList>
  </device>
</root>
"""


class ParseSsdpTest(unittest.TestCase):
    def test_http_response_with_lowercase_header(self):
        packet = (
            b"HTTP/1.1 200 OK\r\n"
            b"CACHE-CONTROL: max-age=1800\r\n"
            b"location: http://192.168.1.50:8200/description.xml\r\n"
            b"ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        )
        self.assertEqual(
            parse_ssdp_packet(packet), "http://192.168.1.50:8200/description.xml"
        )

    def test_notify_and_invalid_packets(self):
        notify = b"NOTIFY * HTTP/1.1\r\nLOCATION: http://1.2.3.4/dmr.xml\r\n\r\n"
        self.assertEqual(parse_ssdp_packet(notify), "http://1.2.3.4/dmr.xml")
        self.assertIsNone(parse_ssdp_packet(b"HTTP/1.1 200 OK\r\nSERVER: foo\r\n\r\n"))
        self.assertIsNone(parse_ssdp_packet(b"M-SEARCH * HTTP/1.1\r\n\r\n"))
        self.assertIsNone(parse_ssdp_packet(b""))


class ParseDescriptionTest(unittest.TestCase):
    def test_full_description(self):
        device = parse_device_description(SAMPLE_XML, LOCATION)
        self.assertIsNotNone(device)
        self.assertEqual(device.name, "客厅 TCL T7L")
        self.assertEqual(device.udn, "uuid:TCL-T7L-001")
        self.assertEqual(
            device.avtransport_url, "http://192.168.1.50:8200/AVTransport/ctrl"
        )
        self.assertEqual(
            device.rendering_control_url,
            "http://192.168.1.50:8200/RenderingControl/ctrl",
        )
        self.assertEqual(device.manufacturer, "TCL")
        self.assertIn("TCL", device.label)
        self.assertEqual(device.key, "uuid:TCL-T7L-001")

    def test_relative_control_url_without_urlbase(self):
        device = parse_device_description(EMBEDDED_XML, LOCATION)
        self.assertIsNotNone(device)
        self.assertEqual(device.name, "内建播放器")
        self.assertEqual(
            device.avtransport_url, "http://192.168.1.50:8200/AVTransport/ctrl"
        )
        self.assertEqual(device.rendering_control_url, "")

    def test_non_renderer_is_ignored(self):
        xml = SAMPLE_XML.replace(
            "device:MediaRenderer:1", "device:MediaServer:1"
        )
        self.assertIsNone(parse_device_description(xml, LOCATION))

    def test_missing_avtransport_is_ignored(self):
        xml = SAMPLE_XML.replace(
            "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>",
            "<serviceType>urn:schemas-upnp-org:service:Foo:1</serviceType>",
        )
        self.assertIsNone(parse_device_description(xml, LOCATION))

    def test_broken_xml_returns_none(self):
        self.assertIsNone(parse_device_description("<root><broken", LOCATION))


class UrlHelperTest(unittest.TestCase):
    def test_resolve_url(self):
        base = "http://192.168.1.50:8200/"
        self.assertEqual(
            _resolve_url(base, "/AVTransport/ctrl"),
            "http://192.168.1.50:8200/AVTransport/ctrl",
        )
        self.assertEqual(
            _resolve_url(base, "AVTransport/ctrl"),
            "http://192.168.1.50:8200/AVTransport/ctrl",
        )
        self.assertEqual(
            _resolve_url(base, "http://other:1234/ctrl"), "http://other:1234/ctrl"
        )
        self.assertEqual(_resolve_url(base, ""), "")

    def test_guess_lan_ip_returns_ipv4(self):
        ip = guess_lan_ip()
        self.assertEqual(len(ip.split(".")), 4)


if __name__ == "__main__":
    unittest.main()
