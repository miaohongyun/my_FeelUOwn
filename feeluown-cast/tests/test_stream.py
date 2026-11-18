import os
import re
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from fuo_cast.stream import (
    StreamRegistry,
    StreamServer,
    _local_path,
    parse_byte_range,
)

DATA = b"0123456789abcdef" * 8  # 128 bytes


class _UpstreamHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.server.requests.append(
            {"path": self.path, "headers": dict(self.headers)}
        )
        data = DATA
        status = 200
        content_range = None
        match = re.match(r"bytes=(\d+)-(\d*)", self.headers.get("Range", ""))
        if match:
            start = int(match.group(1))
            end = int(match.group(2)) if match.group(2) else len(DATA) - 1
            if start >= len(DATA):
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{len(DATA)}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
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


class FakeUpstream:
    def __init__(self):
        self.httpd = HTTPServer(("127.0.0.1", 0), _UpstreamHandler)
        self.httpd.requests = []
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.httpd.server_address[1]}/audio.mp3?sign=abc"

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)


def request(url, method="GET", headers=None):
    req = Request(url, method=method, headers=headers or {})
    try:
        with urlopen(req, timeout=5) as resp:
            return resp.status, resp.headers, resp.read()
    except HTTPError as exc:
        return exc.code, exc.headers, exc.read()


class RangeHelperTest(unittest.TestCase):
    def test_full_and_open_ended(self):
        self.assertEqual(parse_byte_range(None, 100), (0, 99))
        self.assertEqual(parse_byte_range("bytes=0-", 100), (0, 99))
        self.assertEqual(parse_byte_range("bytes=10-19", 100), (10, 19))
        self.assertEqual(parse_byte_range("bytes=90-999", 100), (90, 99))

    def test_suffix_and_invalid(self):
        self.assertEqual(parse_byte_range("bytes=-5", 100), (95, 99))
        self.assertIsNone(parse_byte_range("bytes=100-", 100))
        self.assertIsNone(parse_byte_range("bytes=abc-def", 100))
        self.assertIsNone(parse_byte_range("bytes=-", 100))
        self.assertEqual(parse_byte_range(None, 0), (0, -1))
        self.assertIsNone(parse_byte_range("bytes=0-1", 0))

    def test_local_path_file_uri(self):
        self.assertEqual(_local_path("file:///tmp/a.mp3"), "/tmp/a.mp3")
        self.assertEqual(_local_path("C:/music/a.mp3"), "C:/music/a.mp3")


class RegistryTest(unittest.TestCase):
    def test_register_revoke_and_clear(self):
        registry = StreamRegistry()
        entry = registry.register("http://x/a.mp3", title="song")
        self.assertIs(registry.get(entry.sid), entry)
        registry.revoke(entry.sid)
        self.assertIsNone(registry.get(entry.sid))

    def test_ttl_expires(self):
        # ttl <= 0 表示永不过期；手动把创建时间往前挪来模拟超时
        registry = StreamRegistry(ttl=1)
        entry = registry.register("http://x/a.mp3")
        entry.created_at -= 10
        self.assertIsNone(registry.get(entry.sid))

    def test_max_entries_evicts_oldest(self):
        registry = StreamRegistry(ttl=0, max_entries=2)
        first = registry.register("http://x/1.mp3")
        registry.register("http://x/2.mp3")
        registry.register("http://x/3.mp3")
        self.assertIsNone(registry.get(first.sid))


class ProxyStreamTest(unittest.TestCase):
    def setUp(self):
        self.upstream = FakeUpstream()
        self.server = StreamServer(
            bind="127.0.0.1", port=0, token="test-token", lan_ip="127.0.0.1"
        )
        self.server.start()
        self.temp_files = []

    def tearDown(self):
        for path in self.temp_files:
            try:
                os.unlink(path)
            except OSError:
                pass
        self.server.stop()
        self.upstream.close()

    def _url(self, entry):
        return self.server.public_url(entry)

    def test_token_is_required(self):
        entry = self.server.registry.register(self.upstream.url)
        url = self._url(entry).replace("/test-token/", "/wrong-token/")
        status, _, _ = request(url)
        self.assertEqual(status, 403)

    def test_unknown_stream_id(self):
        url = (
            f"http://127.0.0.1:{self.server.bound_port}"
            "/stream/test-token/does-not-exist/audio.mp3"
        )
        status, _, _ = request(url)
        self.assertEqual(status, 404)

    def test_remote_get_with_dlna_headers(self):
        entry = self.server.registry.register(self.upstream.url)
        status, headers, body = request(self._url(entry))
        self.assertEqual(status, 200)
        self.assertEqual(body, DATA)
        self.assertEqual(headers.get("Content-Type"), "audio/mpeg")
        self.assertEqual(headers.get("transferMode.dlna.org"), "Streaming")
        self.assertIn("DLNA.ORG_PN=MP3", headers.get("contentFeatures.dlna.org", ""))
        self.assertEqual(headers.get("Accept-Ranges"), "bytes")

    def test_head_returns_headers_only(self):
        entry = self.server.registry.register(self.upstream.url)
        status, headers, body = request(self._url(entry), method="HEAD")
        self.assertEqual(status, 200)
        self.assertEqual(body, b"")
        self.assertEqual(headers.get("transferMode.dlna.org"), "Streaming")

    def test_range_is_forwarded_upstream(self):
        entry = self.server.registry.register(self.upstream.url)
        status, headers, body = request(
            self._url(entry), headers={"Range": "bytes=16-31"}
        )
        self.assertEqual(status, 206)
        self.assertEqual(body, DATA[16:32])
        self.assertEqual(headers.get("Content-Range"), f"bytes 16-31/{len(DATA)}")
        self.assertEqual(
            self.upstream.httpd.requests[-1]["headers"].get("Range"), "bytes=16-31"
        )

    def test_media_headers_are_forwarded(self):
        entry = self.server.registry.register(
            self.upstream.url,
            headers={"X-Auth": "secret-123", "Cookie": "sid=abc", "User-Agent": "fuo"},
        )
        request(self._url(entry))
        upstream_headers = self.upstream.httpd.requests[-1]["headers"]
        self.assertEqual(upstream_headers.get("X-Auth"), "secret-123")
        self.assertEqual(upstream_headers.get("Cookie"), "sid=abc")
        self.assertEqual(upstream_headers.get("User-Agent"), "fuo")

    def test_client_cannot_inject_upstream_headers(self):
        """客户端传的 Range 之外的自定义头不应出现在上游请求里。"""
        entry = self.server.registry.register(self.upstream.url)
        request(self._url(entry), headers={"X-Evil": "injected"})
        upstream_headers = self.upstream.httpd.requests[-1]["headers"]
        self.assertNotIn("X-Evil", upstream_headers)

    def test_local_file_full_and_range(self):
        with tempfile.NamedTemporaryFile(delete=False, suffix=".flac") as handle:
            handle.write(DATA)
            path = handle.name
        self.temp_files.append(path)
        entry = self.server.registry.register(path, mime="audio/flac")

        status, headers, body = request(self._url(entry))
        self.assertEqual(status, 200)
        self.assertEqual(body, DATA)
        self.assertEqual(headers.get("Content-Type"), "audio/flac")
        self.assertIn("DLNA.ORG_PN=FLAC", headers.get("contentFeatures.dlna.org", ""))

        status, headers, body = request(
            self._url(entry), headers={"Range": "bytes=10-19"}
        )
        self.assertEqual(status, 206)
        self.assertEqual(body, DATA[10:20])
        self.assertEqual(headers.get("Content-Range"), f"bytes 10-19/{len(DATA)}")

        status, _, body = request(self._url(entry), headers={"Range": "bytes=-5"})
        self.assertEqual(status, 206)
        self.assertEqual(body, DATA[-5:])

        status, _, _ = request(self._url(entry), headers={"Range": "bytes=999-"})
        self.assertEqual(status, 416)

    def test_local_file_head(self):
        with tempfile.NamedTemporaryFile(delete=False, suffix=".mp3") as handle:
            handle.write(DATA)
            path = handle.name
        self.temp_files.append(path)
        entry = self.server.registry.register(path)
        status, _, body = request(self._url(entry), method="HEAD")
        self.assertEqual(status, 200)
        self.assertEqual(body, b"")

    def test_public_url_contains_token_and_sid(self):
        entry = self.server.registry.register(self.upstream.url)
        url = self._url(entry)
        self.assertTrue(url.startswith("http://127.0.0.1:"))
        self.assertIn(f"/stream/test-token/{entry.sid}/audio.mp3", url)


if __name__ == "__main__":
    unittest.main()
