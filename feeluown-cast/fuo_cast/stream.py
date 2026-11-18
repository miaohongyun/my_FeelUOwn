"""本机 HTTP 代理流：把带鉴权、会过期的 Media 变成电视可直接 GET 的 URL。

FeelUOwn 的 Media.url 通常带 cookie/签名，且每次切歌实时申请；DLNA 电视
只会裸 GET 一个地址，拿不到这些 header。这里在 PC 上起一个带 token 的
专用 HTTP 服务，由它带 header 拉上游并流式转给电视，同时支持 Range，
否则电视拖动进度条/预读会失败。不复用 FeelUOwn 自带的 webserver：
那个端口允许局域网连接时会连带暴露 exec/eval 接口，风险太高。
"""
from __future__ import annotations

import hmac
import logging
import os
import re
import secrets
import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urlparse
from urllib.request import ProxyHandler, Request, build_opener, urlopen

from .soap import EXT_BY_MIME, dlna_content_features

logger = logging.getLogger(__name__)

CHUNK_SIZE = 64 * 1024


@dataclass
class StreamEntry:
    sid: str
    source: str  # http(s) URL 或本机文件路径
    mime: str
    headers: dict = field(default_factory=dict)
    proxy: str = ""
    title: str = ""
    artist: str = ""
    album: str = ""
    duration: float = 0
    created_at: float = field(default_factory=time.time)

    @property
    def is_local(self) -> bool:
        return not self.source.lower().startswith(("http://", "https://"))


class StreamRegistry:
    """当前有效的流。切歌时注册新 id、撤销旧 id，避免旧 URL 被长期复用。"""

    def __init__(self, ttl: float = 3600, max_entries: int = 32):
        self.ttl = ttl
        self.max_entries = max_entries
        self._entries: dict[str, StreamEntry] = {}
        self._lock = threading.Lock()

    def register(self, source: str, mime: str = "audio/mpeg", **kwargs) -> StreamEntry:
        with self._lock:
            self._purge()
            if len(self._entries) >= self.max_entries:
                oldest = min(self._entries.values(), key=lambda e: e.created_at)
                self._entries.pop(oldest.sid, None)
            entry = StreamEntry(
                sid=secrets.token_urlsafe(9), source=source, mime=mime, **kwargs
            )
            self._entries[entry.sid] = entry
            return entry

    def get(self, sid: str) -> StreamEntry | None:
        with self._lock:
            entry = self._entries.get(sid)
            if entry and self.ttl > 0 and time.time() - entry.created_at > self.ttl:
                self._entries.pop(sid, None)
                return None
            return entry

    def revoke(self, sid: str) -> None:
        with self._lock:
            self._entries.pop(sid, None)

    def clear(self) -> None:
        with self._lock:
            self._entries.clear()

    def _purge(self) -> None:
        if self.ttl <= 0:
            return
        now = time.time()
        for sid, entry in list(self._entries.items()):
            if now - entry.created_at > self.ttl:
                self._entries.pop(sid, None)


def parse_byte_range(value: str | None, size: int) -> tuple[int, int] | None:
    """把 Range 头解析成闭区间；返回 None 表示请求无法满足(416)。"""
    if size <= 0:
        return None if value else (0, -1)
    if not value:
        return (0, size - 1)
    match = re.match(r"bytes=(\d*)-(\d*)\s*$", value.strip(), re.IGNORECASE)
    if not match:
        return None
    start_text, end_text = match.group(1), match.group(2)
    if not start_text:
        if not end_text:
            return None
        suffix = int(end_text)
        return (max(0, size - suffix), size - 1) if suffix > 0 else None
    start = int(start_text)
    if start >= size:
        return None
    end = int(end_text) if end_text else size - 1
    return (start, min(end, size - 1))


def _header_int(value) -> int | None:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _local_path(source: str) -> str:
    """把 file:// URL 或普通路径统一成本机可读路径（兼容 Windows 盘符）。"""
    if not source.startswith("file://"):
        return source
    parsed = urlparse(source)
    path = unquote(parsed.path)
    if re.match(r"^/[A-Za-z]:", path):
        path = path[1:]
    if parsed.netloc and parsed.netloc.lower() not in ("", "localhost"):
        path = "//" + parsed.netloc + path
    return path


class _StreamHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "FeelUOwnCast/0.1"

    def log_message(self, fmt, *args):
        logger.debug("%s %s", self.address_string(), fmt % args)

    def do_GET(self):
        self._serve(head_only=False)

    def do_HEAD(self):
        self._serve(head_only=True)

    def _reject(self, status: int, message: str = "") -> None:
        body = message.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        if body:
            self.wfile.write(body)
        self.close_connection = True

    def _emit(self, status, mime, length=None, content_range=None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", mime)
        if length is not None:
            self.send_header("Content-Length", str(length))
        if content_range:
            self.send_header("Content-Range", content_range)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("contentFeatures.dlna.org", dlna_content_features(mime))
        self.send_header("transferMode.dlna.org", "Streaming")
        # 始终关闭连接：上游可能是 chunked，不去猜长度，电视靠 close 定界。
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

    def _serve(self, head_only: bool) -> None:
        parts = [p for p in urlparse(self.path).path.split("/") if p]
        if len(parts) < 3 or parts[0] != "stream":
            self._reject(404, "not found")
            return
        if not hmac.compare_digest(parts[1], self.server.token):
            self._reject(403, "forbidden")
            return
        entry = self.server.registry.get(parts[2])
        if entry is None:
            self._reject(404, "stream expired")
            return
        if entry.is_local:
            self._serve_local(entry, head_only)
        else:
            self._serve_remote(entry, head_only)

    def _serve_remote(self, entry: StreamEntry, head_only: bool) -> None:
        # 上游 header 来自 FeelUOwn provider，客户端无法注入自己的 header。
        headers = {
            key: value
            for key, value in (entry.headers or {}).items()
            if key.lower()
            not in ("host", "content-length", "connection", "accept-encoding", "range")
        }
        for name in ("Range", "If-Range"):
            value = self.headers.get(name)
            if value:
                headers[name] = value
        if not any(k.lower() == "user-agent" for k in headers):
            headers["User-Agent"] = self.headers.get(
                "User-Agent", "FeelUOwn-Cast/0.1"
            )
        request = Request(entry.source, headers=headers, method="GET")
        try:
            if entry.proxy:
                opener = build_opener(
                    ProxyHandler({"http": entry.proxy, "https": entry.proxy})
                )
                resp = opener.open(request, timeout=15)
            else:
                resp = urlopen(request, timeout=15)
        except HTTPError as exc:
            if exc.code == 416:
                self._emit(
                    416, entry.mime, 0, exc.headers.get("Content-Range", "bytes */0")
                )
            else:
                logger.warning("上游返回 %s: %s", exc.code, entry.source)
                self._reject(502, f"upstream {exc.code}")
            return
        except (URLError, OSError, TimeoutError) as exc:
            logger.warning("拉上游失败: %s", exc)
            self._reject(502, "upstream unavailable")
            return

        with resp:
            status = getattr(resp, "status", 200) or 200
            self._emit(
                status,
                entry.mime,
                _header_int(resp.headers.get("Content-Length")),
                resp.headers.get("Content-Range"),
            )
            if head_only:
                return
            try:
                while True:
                    chunk = resp.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError, OSError):
                # 电视切歌/停止时会直接断开，属正常现象
                logger.debug("电视端提前断开连接")

    def _serve_local(self, entry: StreamEntry, head_only: bool) -> None:
        path = _local_path(entry.source)
        try:
            size = os.path.getsize(path)
        except OSError:
            self._reject(404, "file not found")
            return
        range_header = self.headers.get("Range")
        parsed = parse_byte_range(range_header, size)
        if parsed is None:
            self._emit(416, entry.mime, 0, f"bytes */{size}")
            return
        start, end = parsed
        length = max(0, end - start + 1)
        status = 206 if range_header else 200
        self._emit(
            status,
            entry.mime,
            length,
            f"bytes {start}-{end}/{size}" if status == 206 else None,
        )
        if head_only or length == 0:
            return
        try:
            with open(path, "rb") as source:
                source.seek(start)
                remaining = length
                while remaining > 0:
                    chunk = source.read(min(CHUNK_SIZE, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError, OSError) as exc:
            logger.debug("本地文件传输中断: %s", exc)


class _CastHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, *args, token: str = "", registry: StreamRegistry | None = None,
                 **kwargs):
        super().__init__(*args, **kwargs)
        self.token = token
        self.registry = registry or StreamRegistry()


class StreamServer:
    def __init__(
        self,
        bind: str = "0.0.0.0",
        port: int = 8765,
        token: str = "",
        lan_ip: str = "",
    ):
        self.bind = bind
        self.port = port
        self.token = token or secrets.token_urlsafe(18)
        self.lan_ip = lan_ip
        self.registry = StreamRegistry()
        self._httpd: _CastHTTPServer | None = None
        self._thread: threading.Thread | None = None

    @property
    def bound_port(self) -> int:
        if self._httpd is not None:
            return self._httpd.server_address[1]
        return self.port

    def start(self) -> None:
        if self._httpd is not None:
            return
        self._httpd = _CastHTTPServer(
            (self.bind, self.port),
            _StreamHandler,
            token=self.token,
            registry=self.registry,
        )
        self._thread = threading.Thread(
            target=self._httpd.serve_forever,
            name="fuo-cast-stream",
            daemon=True,
        )
        self._thread.start()
        logger.info(
            "投屏代理流已启动: %s:%s (token 已启用)", self.bind, self.bound_port
        )

    def public_url(self, entry: StreamEntry) -> str:
        ext = EXT_BY_MIME.get(entry.mime, "mp3")
        return (
            f"http://{self.lan_ip}:{self.bound_port}/stream/"
            f"{self.token}/{entry.sid}/audio.{ext}"
        )

    def stop(self) -> None:
        if self._httpd is not None:
            self._httpd.shutdown()
            self._httpd.server_close()
            self._httpd = None
        self.registry.clear()
        self._thread = None
