import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.request import urlopen
from xml.etree import ElementTree as ET

from fuo_cast.discovery import AVTRANSPORT, Device
from fuo_cast.soap import (
    Renderer,
    SoapError,
    build_didl_item,
    build_soap_xml,
    dlna_content_features,
    format_duration,
    guess_mime,
    soap_call,
)


def _local_text(root, name):
    for element in root.iter():
        if element.tag.rsplit("}", 1)[-1] == name:
            return element.text or ""
    return ""


class _DeviceHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8")
        self.server.calls.append(
            {
                "path": self.path,
                "soapaction": self.headers.get("SOAPACTION"),
                "content_type": self.headers.get("Content-Type"),
                "body": body,
            }
        )
        if self.server.fail_with_fault:
            status = 500
            payload = (
                '<?xml version="1.0"?>'
                '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">'
                "<s:Body><s:Fault><faultcode>s:Client</faultcode>"
                "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"
                "<errorCode>701</errorCode>"
                "<errorDescription>Transition not available</errorDescription>"
                "</UPnPError></detail></s:Fault></s:Body></s:Envelope>"
            ).encode()
        else:
            status = 200
            payload = (
                '<?xml version="1.0"?>'
                '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">'
                "<s:Body><u:PlayResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>"
                "</s:Body></s:Envelope>"
            ).encode()
        self.send_response(status)
        self.send_header("Content-Type", 'text/xml; charset="utf-8"')
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


class FakeDeviceServer:
    def __init__(self):
        self.httpd = HTTPServer(("127.0.0.1", 0), _DeviceHandler)
        self.httpd.calls = []
        self.httpd.fail_with_fault = False
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.httpd.server_address[1]}/AVTransport/ctrl"

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)


class MetadataTest(unittest.TestCase):
    def test_didl_escapes_special_chars_and_roundtrips(self):
        url = "http://192.168.1.9:8765/stream/tok/sid/audio.mp3?x=1&y=2"
        xml = build_didl_item(
            url,
            title='A & B <Live> "Remix"',
            artist="张三 & 李四",
            album="专辑 <1>",
            duration=225.5,
            mime="audio/mpeg",
        )
        root = ET.fromstring(xml)
        self.assertEqual(_local_text(root, "title"), 'A & B <Live> "Remix"')
        self.assertEqual(_local_text(root, "creator"), "张三 & 李四")
        self.assertEqual(_local_text(root, "class"), "object.item.audioItem.musicTrack")
        self.assertEqual(_local_text(root, "res"), url)
        res = next(e for e in root.iter() if e.tag.rsplit("}", 1)[-1] == "res")
        self.assertEqual(res.attrib["duration"], "00:03:45.500")
        self.assertIn("http-get:*:audio/mpeg:", res.attrib["protocolInfo"])
        self.assertIn("DLNA.ORG_OP=01", res.attrib["protocolInfo"])

    def test_format_duration(self):
        self.assertEqual(format_duration(0), "00:00:00.000")
        self.assertEqual(format_duration(None), "00:00:00.000")
        self.assertEqual(format_duration(225.5), "00:03:45.500")
        self.assertEqual(format_duration(3661), "01:01:01.000")
        self.assertEqual(format_duration("bad"), "00:00:00.000")

    def test_guess_mime(self):
        self.assertEqual(guess_mime("http://x/a.MP3?token=1&e=2"), "audio/mpeg")
        self.assertEqual(guess_mime("/music/song.flac"), "audio/flac")
        self.assertEqual(guess_mime("http://x/stream/abc"), "audio/mpeg")
        self.assertEqual(guess_mime("http://x/stream/abc", "audio/flac"), "audio/flac")

    def test_content_features(self):
        self.assertIn("DLNA.ORG_PN=MP3", dlna_content_features("audio/mpeg"))
        self.assertIn("DLNA.ORG_PN=FLAC", dlna_content_features("audio/flac"))
        self.assertIn("DLNA.ORG_PN=*", dlna_content_features("audio/unknown"))


class SoapXmlTest(unittest.TestCase):
    def test_soap_envelope_escapes_metadata(self):
        metadata = build_didl_item("http://x/a.mp3", title="A & B")
        data = build_soap_xml(
            "SetAVTransportURI",
            AVTRANSPORT,
            {"InstanceID": "0", "CurrentURI": "http://x/a.mp3", "CurrentURIMetaData": metadata},
        )
        text = data.decode("utf-8")
        self.assertIn("&lt;DIDL-Lite", text)
        self.assertIn("<u:SetAVTransportURI", text)
        root = ET.fromstring(text)
        params = {
            el.tag.rsplit("}", 1)[-1]: (el.text or "")
            for el in root.iter()
            if el.tag.rsplit("}", 1)[-1].startswith("Current")
        }
        self.assertEqual(params["CurrentURIMetaData"], metadata)
        self.assertEqual(params["CurrentURI"], "http://x/a.mp3")


class SoapTransportTest(unittest.TestCase):
    def setUp(self):
        self.server = FakeDeviceServer()

    def tearDown(self):
        self.server.close()

    def test_soap_call_sends_expected_headers(self):
        status, _ = soap_call(
            self.server.url,
            AVTRANSPORT,
            "Play",
            {"InstanceID": "0", "Speed": "1"},
            timeout=3,
        )
        self.assertEqual(status, 200)
        call = self.server.httpd.calls[-1]
        self.assertEqual(call["path"], "/AVTransport/ctrl")
        self.assertEqual(call["soapaction"], f'"{AVTRANSPORT}#Play"')
        self.assertIn("text/xml", call["content_type"])
        self.assertIn("<Speed>1</Speed>", call["body"])

    def test_soap_fault_raises_soap_error(self):
        self.server.httpd.fail_with_fault = True
        with self.assertRaises(SoapError) as ctx:
            soap_call(self.server.url, AVTRANSPORT, "Play", {"InstanceID": "0"}, timeout=3)
        self.assertEqual(ctx.exception.status, 500)
        self.assertEqual(ctx.exception.code, "701")
        self.assertEqual(ctx.exception.description, "Transition not available")

    def test_renderer_actions(self):
        device = Device(
            udn="uuid:test",
            name="测试电视",
            location="http://127.0.0.1/dmr.xml",
            avtransport_url=self.server.url,
            rendering_control_url=self.server.url,
        )
        renderer = Renderer(device, timeout=3)
        renderer.set_uri("http://pc/audio.mp3", build_didl_item("http://pc/audio.mp3"))
        renderer.play()
        renderer.pause()
        renderer.stop()
        actions = [
            call["soapaction"].split("#")[-1].rstrip('"')
            for call in self.server.httpd.calls
        ]
        self.assertEqual(
            actions, ["SetAVTransportURI", "Play", "Pause", "Stop"]
        )


if __name__ == "__main__":
    unittest.main()
