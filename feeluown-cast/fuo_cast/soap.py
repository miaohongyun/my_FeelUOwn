"""UPnP AVTransport SOAP 控制与 DIDL-Lite 元数据生成，只依赖标准库。"""
from __future__ import annotations

import logging
import math
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape

from .discovery import AVTRANSPORT, RENDERING_CONTROL, Device

logger = logging.getLogger(__name__)

SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/"
SOAP_ENC = "http://schemas.xmlsoap.org/soap/encoding/"
DLNA_FLAGS = "01700000000000000000000000000000"

MIME_BY_EXT = {
    ".mp3": "audio/mpeg",
    ".m4a": "audio/mp4",
    ".mp4": "audio/mp4",
    ".aac": "audio/aac",
    ".flac": "audio/flac",
    ".wav": "audio/wav",
    ".ogg": "audio/ogg",
    ".oga": "audio/ogg",
    ".opus": "audio/ogg",
    ".wma": "audio/x-ms-wma",
    ".ape": "audio/x-ape",
}
DLNA_PN_BY_MIME = {
    "audio/mpeg": "MP3",
    "audio/mp4": "AAC_ISO_320",
    "audio/aac": "AAC_ISO",
    "audio/flac": "FLAC",
    "audio/wav": "LPCM",
    "audio/x-ms-wma": "WMABASE",
}
EXT_BY_MIME = {
    "audio/mpeg": "mp3",
    "audio/mp4": "m4a",
    "audio/aac": "aac",
    "audio/flac": "flac",
    "audio/wav": "wav",
    "audio/ogg": "ogg",
    "audio/x-ms-wma": "wma",
}


class SoapError(Exception):
    """UPnP 设备返回 SOAP Fault。"""

    def __init__(self, status: int, code: str, description: str, body: str = ""):
        super().__init__(f"UPnP 错误 {status} [{code}] {description}")
        self.status = status
        self.code = code
        self.description = description
        self.body = body


def guess_mime(url: str, default: str = "audio/mpeg") -> str:
    """从 URL/路径的扩展名猜 mime，忽略查询串。"""
    path = url.split("?", 1)[0].split("#", 1)[0].lower()
    for ext, mime in MIME_BY_EXT.items():
        if path.endswith(ext):
            return mime
    return default


def dlna_content_features(mime: str) -> str:
    pn = DLNA_PN_BY_MIME.get(mime, "*")
    return (
        f"DLNA.ORG_PN={pn};DLNA.ORG_OP=01;DLNA.ORG_CI=0;"
        f"DLNA.ORG_FLAGS={DLNA_FLAGS}"
    )


def format_duration(seconds) -> str:
    """转成 DIDL-Lite 需要的 HH:MM:SS.mmm。"""
    try:
        total = float(seconds or 0)
    except (TypeError, ValueError):
        total = 0.0
    if not math.isfinite(total) or total <= 0:
        return "00:00:00.000"
    hours, rest = divmod(int(total), 3600)
    minutes, secs = divmod(rest, 60)
    millis = int((total - int(total)) * 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}.{millis:03d}"


def build_didl_item(
    url: str,
    title: str = "",
    artist: str = "",
    album: str = "",
    duration=0,
    mime: str = "audio/mpeg",
    item_id: str = "fuo-cast-0",
) -> str:
    """生成 CurrentURIMetaData。

    很多电视在 metadata 为空或字段不全时会「接受指令但不出声」，
    所以这里把 title/artist/class/res/protocolInfo 全部给全。
    """
    protocol_info = f"http-get:*:{mime}:{dlna_content_features(mime)}"
    return (
        '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
        'xmlns:dc="http://purl.org/dc/elements/1.1/" '
        'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
        f'<item id="{escape(item_id, {chr(34): "&quot;"})}" '
        'parentID="-1" restricted="1">'
        f"<dc:title>{escape(title or 'FeelUOwn')}</dc:title>"
        f"<dc:creator>{escape(artist or '')}</dc:creator>"
        f"<upnp:artist>{escape(artist or '')}</upnp:artist>"
        f"<upnp:album>{escape(album or '')}</upnp:album>"
        "<upnp:class>object.item.audioItem.musicTrack</upnp:class>"
        f'<res duration="{format_duration(duration)}" '
        f'protocolInfo="{escape(protocol_info, {chr(34): "&quot;"})}">'
        f"{escape(url)}</res>"
        "</item></DIDL-Lite>"
    )


def build_soap_xml(action: str, service: str, params: dict) -> bytes:
    inner = "".join(
        f"<{key}>{escape(str(value))}</{key}>" for key, value in params.items()
    )
    envelope = (
        '<?xml version="1.0" encoding="utf-8"?>'
        f'<s:Envelope xmlns:s="{SOAP_ENV}" s:encodingStyle="{SOAP_ENC}">'
        f'<s:Body><u:{action} xmlns:u="{service}">{inner}</u:{action}>'
        "</s:Body></s:Envelope>"
    )
    return envelope.encode("utf-8")


def soap_call(
    control_url: str,
    service: str,
    action: str,
    params: dict,
    timeout: float = 5.0,
) -> tuple[int, str]:
    data = build_soap_xml(action, service, params)
    request = Request(
        control_url,
        data=data,
        method="POST",
        headers={
            "Content-Type": 'text/xml; charset="utf-8"',
            "SOAPACTION": f'"{service}#{action}"',
            "Content-Length": str(len(data)),
            "User-Agent": "FeelUOwn-Cast/0.1 UPnP/1.0",
            "Connection": "close",
        },
    )
    try:
        with urlopen(request, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        code, description = "", body.strip()[:200]
        try:
            root = ET.fromstring(body)
            text = {el.tag.rsplit("}", 1)[-1]: (el.text or "") for el in root.iter()}
            code = text.get("errorCode", "")
            description = text.get("errorDescription", "") or description
        except ET.ParseError:
            pass
        raise SoapError(exc.code, code, description, body) from None


class Renderer:
    """对一台 DLNA 设备执行 AVTransport / RenderingControl 动作。"""

    def __init__(self, device: Device, timeout: float = 5.0):
        self.device = device
        self.timeout = timeout

    def _call(self, action: str, params: dict, service: str = AVTRANSPORT):
        control_url = (
            self.device.avtransport_url
            if service == AVTRANSPORT
            else self.device.rendering_control_url
        )
        if not control_url:
            raise SoapError(0, "", f"设备未提供 {service} 控制地址")
        return soap_call(control_url, service, action, params, self.timeout)

    def set_uri(self, uri: str, metadata: str) -> None:
        self._call(
            "SetAVTransportURI",
            {
                "InstanceID": "0",
                "CurrentURI": uri,
                "CurrentURIMetaData": metadata,
            },
        )

    def play(self) -> None:
        self._call("Play", {"InstanceID": "0", "Speed": "1"})

    def pause(self) -> None:
        self._call("Pause", {"InstanceID": "0"})

    def stop(self) -> None:
        self._call("Stop", {"InstanceID": "0"})

    def set_volume(self, volume: int) -> None:
        volume = max(0, min(100, int(volume)))
        self._call(
            "SetVolume",
            {
                "InstanceID": "0",
                "Channel": "Master",
                "DesiredVolume": str(volume),
            },
            service=RENDERING_CONTROL,
        )

    def get_transport_state(self) -> str:
        _, body = self._call("GetTransportInfo", {"InstanceID": "0"})
        try:
            root = ET.fromstring(body)
            for el in root.iter():
                if el.tag.rsplit("}", 1)[-1] == "CurrentTransportState":
                    return (el.text or "").strip()
        except ET.ParseError:
            pass
        return ""
