"""端到端模拟：controller -> SOAP SetAVTransportURI -> 电视裸 GET 代理流 -> 上游音频。

不依赖真实电视和 SSDP 多播，但把「电视只认 URL、拿不到 cookie」这条
最容易断的链路完整跑通，并验证切歌、暂停的联动。
"""
import re
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from types import SimpleNamespace
from urllib.request import Request, urlopen
from xml.etree import ElementTree as ET

from fuo_cast.controller import CastController
from fuo_cast.discovery import Device

DATA = b"0123456789abcdef" * 8  # 128 bytes
SONG = SimpleNamespace(
    title_display="测试曲目",
    artists_name_display="测试歌手",
    album_name_display="测试专辑",
)


class _UpstreamHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.server.requests.append(dict(self.headers))
        data = DATA
        status = 200
        content_range = None
        match = re.match(r"bytes=(\d+)-(\d*)", self.headers.get("Range", ""))
        if match:
            start = int(match.group(1))
            end = int(match.group(2)) if match.group(2) else len(DATA) - 1
            data = DATA[start : end + 1]
            status = 206
            content_range = f"bytes {start}-{end}/{len(DATA)}"
        self.send_response(status)
        self.send_header("Content-Type", "audio/mpeg")
        self.send_header("Content-Length", str(len(data)))
        if content_range:
            self.send_header("Content-Range", content_range)
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass


class _TvHandler(BaseHTTPRequestHandler):
    """模拟一台 DLNA 电视：收到 Play 后，用裸 GET 去拉 CurrentURI。"""

    protocol_version = "HTTP/1.0"

    def log_message(self, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8")
        state = self.server
        state.soap_calls.append(body)
        if "SetAVTransportURI" in body:
            root = ET.fromstring(body)
            for element in root.iter():
                name = element.tag.rsplit("}", 1)[-1]
                if name == "CurrentURI":
                    state.current_uri = element.text or ""
                elif name == "CurrentURIMetaData":
                    state.metadata = element.text or ""
        elif "Play" in body and state.current_uri:
            try:
                request = Request(
                    state.current_uri, headers={"Range": "bytes=0-31"}
                )
                with urlopen(request, timeout=5) as resp:
                    state.stream_status = resp.status
                    state.streamed = resp.read()
            except Exception as exc:  # noqa: BLE001
                state.stream_error = str(exc)
        payload = (
            '<?xml version="1.0"?>'
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">'
            "<s:Body/></s:Envelope>"
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", 'text/xml; charset="utf-8"')
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


class _FakeServer:
    def __init__(self, handler):
        self.httpd = HTTPServer(("127.0.0.1", 0), handler)
        self.httpd.requests = []
        self.httpd.soap_calls = []
        self.httpd.current_uri = ""
        self.httpd.metadata = ""
        self.httpd.streamed = None
        self.httpd.stream_status = None
        self.httpd.stream_error = ""
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.httpd.server_address[1]}"

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)


class EndToEndCastTest(unittest.TestCase):
    def setUp(self):
        self.upstream = _FakeServer(_UpstreamHandler)
        self.tv = _FakeServer(_TvHandler)
        config = SimpleNamespace(
            HTTP_PORT=0,
            BIND_ADDRESS="127.0.0.1",
            LAN_IP="127.0.0.1",
            SSDP_TIMEOUT=0.1,
            SSDP_BIND_IP="",
            SOAP_TIMEOUT=3.0,
            MUTE_LOCAL=True,
        )
        self.media = SimpleNamespace(
            url=self.upstream.url + "/a.mp3?sign=xyz",
            http_headers={"Cookie": "sid=abc"},
            http_proxy="",
            decryption_key=None,
            props=SimpleNamespace(format="mp3", duration=180),
        )
        self.app = SimpleNamespace(
            config=SimpleNamespace(cast=config),
            playlist=SimpleNamespace(current_song=SONG),
            player=SimpleNamespace(current_media=self.media, volume=70, state=2),
            mode=0,
            GuiMode=1,
        )
        self.ctrl = CastController(self.app, config)
        self.ctrl.initialize()

    def tearDown(self):
        self.ctrl.shutdown()
        self.tv.close()
        self.upstream.close()

    def _wait(self, predicate, timeout=8.0):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return True
            time.sleep(0.05)
        return False

    def _cast(self):
        device = Device(
            udn="uuid:fake-tcl-t7l",
            name="客厅 TCL T7L",
            location=self.tv.url + "/dmr.xml",
            avtransport_url=self.tv.url + "/AVTransport/ctrl",
        )
        self.ctrl.cast_to(device)

    def test_tv_fetches_audio_from_proxy_url(self):
        self._cast()
        ok = self._wait(
            lambda: self.tv.httpd.streamed is not None or self.tv.httpd.stream_error
        )
        self.assertTrue(ok, "电视未在超时内拉流")
        self.assertEqual(
            self.tv.httpd.stream_error, "", "模拟电视拉流报错"
        )
        self.assertEqual(self.tv.httpd.stream_status, 206)
        self.assertEqual(self.tv.httpd.streamed, DATA[:32])

        calls = self.tv.httpd.soap_calls
        self.assertTrue(any("SetAVTransportURI" in c for c in calls))
        self.assertTrue(any("<u:Play" in c for c in calls))
        self.assertIn("object.item.audioItem.musicTrack", self.tv.httpd.metadata)
        self.assertIn(self.tv.httpd.current_uri, self.tv.httpd.metadata)
        # 电视只发了裸 GET，cookie 由 PC 侧代理补上
        self.assertEqual(
            self.upstream.httpd.requests[-1].get("Cookie"), "sid=abc"
        )
        self.assertEqual(self.app.player.volume, 0)

    def test_song_change_pushes_new_uri(self):
        self._cast()
        self.assertTrue(
            self._wait(lambda: self.tv.httpd.streamed is not None),
            self.tv.httpd.stream_error,
        )
        old_uri = self.tv.httpd.current_uri
        old_count = len(self.tv.httpd.soap_calls)
        new_media = SimpleNamespace(
            url=self.upstream.url + "/b.mp3?sign=new",
            http_headers={},
            http_proxy="",
            decryption_key=None,
            props=SimpleNamespace(format="mp3", duration=200),
        )
        self.ctrl._on_song_changed(SONG, new_media)
        self.assertTrue(
            self._wait(lambda: len(self.tv.httpd.soap_calls) >= old_count + 2)
        )
        self.assertNotEqual(self.tv.httpd.current_uri, old_uri)

    def test_pause_is_forwarded(self):
        self._cast()
        self.assertTrue(
            self._wait(lambda: self.tv.httpd.streamed is not None),
            self.tv.httpd.stream_error,
        )
        self.ctrl._on_state_changed(1)
        self.assertTrue(
            self._wait(
                lambda: any("<u:Pause" in c for c in self.tv.httpd.soap_calls)
            )
        )


if __name__ == "__main__":
    unittest.main()
