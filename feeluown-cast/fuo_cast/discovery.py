"""SSDP/UPnP MediaRenderer 发现，只依赖 Python 标准库。

流程：UDP 多播 M-SEARCH -> 收集 LOCATION -> GET 设备描述 XML
      -> 抽出 AVTransport / RenderingControl 的 controlURL。
"""
from __future__ import annotations

import logging
import re
import socket
import time
from dataclasses import dataclass
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen
from xml.etree import ElementTree as ET

logger = logging.getLogger(__name__)

SSDP_MULTICAST = ("239.255.255.250", 1900)
MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

# 不同电视对 ST 的响应不一致：TCL 等 Android TV 通常回应 ssdp:all，
# 部分只回应 MediaRenderer 或 AVTransport，所以三个都发一遍再去重。
SEARCH_TARGETS = ("ssdp:all", MEDIA_RENDERER, AVTRANSPORT)

MSEARCH_TEMPLATE = (
    "M-SEARCH * HTTP/1.1\r\n"
    "HOST: 239.255.255.250:1900\r\n"
    'MAN: "ssdp:discover"\r\n'
    "MX: {mx}\r\n"
    "ST: {st}\r\n"
    "\r\n"
)


@dataclass
class Device:
    """一台可被投放的 DLNA 电视/音箱。"""

    udn: str
    name: str
    location: str
    avtransport_url: str
    rendering_control_url: str = ""
    manufacturer: str = ""
    model_name: str = ""

    @property
    def key(self) -> str:
        return self.udn or self.location

    @property
    def label(self) -> str:
        extra = " ".join(x for x in (self.manufacturer, self.model_name) if x)
        return f"{self.name} ({extra})" if extra else self.name


def guess_lan_ip(probe: str = "223.5.5.5") -> str:
    """取本机在局域网中的出口 IP（UDP connect 不产生实际流量）。"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect((probe, 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def parse_ssdp_packet(data: bytes) -> str | None:
    """从 SSDP 响应里取出 LOCATION，非响应包返回 None。"""
    text = data.decode("utf-8", "replace")
    head = text.split("\r\n\r\n", 1)[0]
    lines = head.splitlines()
    if not lines:
        return None
    first = lines[0].strip()
    if not (first.startswith("HTTP/1.") or first.startswith("NOTIFY ")):
        return None
    for line in lines[1:]:
        name, sep, value = line.partition(":")
        if sep and name.strip().lower() == "location":
            return value.strip() or None
    return None


def ssdp_search(
    timeout: float = 3.0,
    mx: int = 2,
    targets=SEARCH_TARGETS,
    bind_ip: str = "",
) -> list[str]:
    """发送 M-SEARCH 并收集 LOCATION 列表（去重、保持发现顺序）。"""
    locations: list[str] = []
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
        except OSError:
            pass
        try:
            sock.bind((bind_ip or "", 0))
        except OSError:
            # 配置了不可用的网卡地址时退回自动选择
            sock.bind(("", 0))
        for st in targets:
            packet = MSEARCH_TEMPLATE.format(mx=mx, st=st).encode("ascii")
            try:
                sock.sendto(packet, SSDP_MULTICAST)
            except OSError as exc:
                logger.warning("SSDP 发送失败(%s): %s", st, exc)
        deadline = time.monotonic() + max(timeout, 0.1)
        while True:
            remain = deadline - time.monotonic()
            if remain <= 0:
                break
            sock.settimeout(min(remain, 0.5))
            try:
                data, _ = sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            location = parse_ssdp_packet(data)
            if location and location not in locations:
                locations.append(location)
    finally:
        sock.close()
    return locations


def fetch_description(location: str, timeout: float = 3.0) -> str:
    """GET 设备描述 XML，兼容常见的非 UTF-8 编码声明。"""
    req = Request(
        location,
        headers={"User-Agent": "FeelUOwn-Cast/0.1 UPnP/1.0", "Connection": "close"},
    )
    with urlopen(req, timeout=timeout) as resp:
        raw = resp.read(512 * 1024)
    match = re.match(rb"<\?xml[^>]*encoding=[\"']([\w-]+)[\"']", raw[:200])
    encoding = match.group(1).decode("ascii") if match else "utf-8"
    try:
        return raw.decode(encoding, "replace")
    except LookupError:
        return raw.decode("utf-8", "replace")


def _local_tag(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _child_text(node, name: str, default: str = "") -> str:
    for child in node:
        if _local_tag(child.tag) == name:
            return (child.text or "").strip()
    return default


def _resolve_url(base: str, url: str) -> str:
    url = (url or "").strip()
    if not url:
        return ""
    if url.startswith(("http://", "https://")):
        return url
    if url.startswith("/"):
        parsed = urlparse(base)
        return f"{parsed.scheme}://{parsed.netloc}{url}"
    return urljoin(base, url)


def _iter_devices(root):
    """遍历 root device 以及所有 embedded device。"""
    pending = [child for child in root if _local_tag(child.tag) == "device"]
    seen = []
    while pending:
        dev = pending.pop(0)
        seen.append(dev)
        for child in dev:
            if _local_tag(child.tag) == "deviceList":
                pending.extend(x for x in child if _local_tag(x.tag) == "device")
    return seen


def _collect_services(devices) -> dict[str, dict[str, str]]:
    services: dict[str, dict[str, str]] = {}
    for dev in devices:
        for child in dev:
            if _local_tag(child.tag) != "serviceList":
                continue
            for service in child:
                if _local_tag(service.tag) != "service":
                    continue
                service_type = _child_text(service, "serviceType")
                if not service_type:
                    continue
                services[service_type] = {
                    "controlURL": _child_text(service, "controlURL"),
                    "eventSubURL": _child_text(service, "eventSubURL"),
                    "SCPDURL": _child_text(service, "SCPDURL"),
                }
    return services


def parse_device_description(xml_text: str, location: str) -> Device | None:
    """解析设备描述，只有含 AVTransport 的 MediaRenderer 才返回。"""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        logger.debug("设备描述 XML 解析失败 %s: %s", location, exc)
        return None

    url_base = _child_text(root, "URLBase")
    if not url_base:
        # 相对 controlURL 应以描述文件所在目录为基准
        url_base = location.rsplit("/", 1)[0] + "/"
    elif not url_base.endswith("/"):
        url_base += "/"

    devices = _iter_devices(root)
    if not devices:
        return None

    renderer = next(
        (d for d in devices if _child_text(d, "deviceType") == MEDIA_RENDERER),
        None,
    )
    if renderer is None:
        return None

    services = _collect_services(devices)
    avt = services.get(AVTRANSPORT, {})
    control_url = _resolve_url(url_base, avt.get("controlURL", ""))
    if not control_url:
        return None
    rcs = services.get(RENDERING_CONTROL, {})

    root_device = devices[0]
    name = _child_text(renderer, "friendlyName") or _child_text(
        root_device, "friendlyName", "DLNA 设备"
    )
    return Device(
        udn=_child_text(renderer, "UDN") or _child_text(root_device, "UDN") or location,
        name=name,
        location=location,
        avtransport_url=control_url,
        rendering_control_url=_resolve_url(url_base, rcs.get("controlURL", "")),
        manufacturer=_child_text(root_device, "manufacturer"),
        model_name=_child_text(root_device, "modelName"),
    )


def discover_devices(
    timeout: float = 3.0,
    mx: int = 2,
    bind_ip: str = "",
    description_timeout: float = 3.0,
) -> list[Device]:
    """发现局域网内可投放设备（同步阻塞，建议放到线程里调用）。"""
    locations = ssdp_search(timeout=timeout, mx=mx, bind_ip=bind_ip)
    devices: list[Device] = []
    seen: set[str] = set()
    for location in locations:
        try:
            xml_text = fetch_description(location, timeout=description_timeout)
        except Exception as exc:  # noqa: BLE001 - 局域网设备千奇百怪，单个失败不影响其它
            logger.debug("读取设备描述失败 %s: %s", location, exc)
            continue
        device = parse_device_description(xml_text, location)
        if device and device.key not in seen:
            seen.add(device.key)
            devices.append(device)
    return devices
