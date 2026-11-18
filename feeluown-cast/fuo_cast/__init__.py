"""
把 FeelUOwn 正在播放的歌曲通过 DLNA/UPnP 投放到电视自己播放（设备投放）。

安装后 FeelUOwn 播放控制栏会出现「投屏」按钮：刷新设备 -> 选中电视 -> 投放。
切歌会自动重新推送，暂停/继续会同步给电视；投放期间默认把本机音量调到 0，
避免电脑和电视同时出声。

原理：SSDP 发现 MediaRenderer -> 本机起带 token 的 HTTP 代理流，把带
cookie/签名且会过期的 Media.url 转成电视能裸 GET 的局域网地址 ->
SOAP SetAVTransportURI + DIDL-Lite 元数据交给电视，电视自己拉流解码。
不使用 FeelUOwn 的 ALLOW_LAN_CONNECT：那个开关会连带暴露 exec/eval 接口。

~/.fuorc 可配置项（写在 config.cast 下）::

   config.cast.HTTP_PORT = 8765        # 代理流端口
   config.cast.LAN_IP = ''             # 留空自动探测；多网卡/VPN 时手动填
   config.cast.BIND_ADDRESS = '0.0.0.0'
   config.cast.SSDP_TIMEOUT = 3.0
   config.cast.SSDP_BIND_IP = ''       # 多网卡时指定发 M-SEARCH 的网卡
   config.cast.SOAP_TIMEOUT = 5.0
   config.cast.MUTE_LOCAL = True

限制：电视与电脑需在同一局域网且开启「多屏互动 / 媒体共享」；PC 必须保持
开机在线（代理流在 PC 上），可锁屏但不能关机；需要本地解密的 DRM 流、
没有直连音频地址的媒体无法投放。仅实现标准 DLNA，不含 QPlay / AirPlay。
"""
from __future__ import annotations

import logging

__alias__ = "设备投放"
__desc__ = __doc__
__version__ = "0.1.0"
__author__ = "miaohongyun"
__dist_name__ = "fuo-cast"

logger = logging.getLogger(__name__)

_controller = None
_ui = None


def init_config(config):
    config.deffield("HTTP_PORT", type_=int, default=8765, desc="代理流监听端口")
    config.deffield("BIND_ADDRESS", type_=str, default="0.0.0.0", desc="代理流绑定地址")
    config.deffield("LAN_IP", type_=str, default="", desc="对外公布的本机局域网 IP，留空自动探测")
    config.deffield("SSDP_TIMEOUT", type_=float, default=3.0, desc="设备搜索等待秒数")
    config.deffield("SSDP_BIND_IP", type_=str, default="", desc="发送 M-SEARCH 使用的网卡 IP")
    config.deffield("SOAP_TIMEOUT", type_=float, default=5.0, desc="UPnP 指令超时秒数")
    config.deffield("MUTE_LOCAL", type_=bool, default=True, desc="投放时把本机音量调到 0")


def enable(app):
    global _controller, _ui
    from .controller import CastController

    config = getattr(app.config, "cast", None)
    if config is None:
        config = getattr(app.config, "fuo_cast", None)
    _controller = CastController(app, config)
    _controller.initialize()

    gui_mode = getattr(app, "GuiMode", 0)
    if getattr(app, "mode", 0) & gui_mode:
        try:
            from .ui import CastUI

            _ui = CastUI(app, _controller)
            _ui.initialize()
        except Exception:  # noqa: BLE001 - UI 失败不应拖垮插件核心
            logger.exception("投屏插件 UI 初始化失败")
    logger.info("设备投放插件已启用")


def disable(app):
    global _controller, _ui
    if _ui is not None:
        try:
            _ui.shutdown()
        except Exception:  # noqa: BLE001
            logger.exception("投屏插件 UI 关闭失败")
        _ui = None
    if _controller is not None:
        _controller.shutdown()
        _controller = None
