"""投放编排：把 FeelUOwn 当前播放的 Media 推给 DLNA 设备，并跟随切歌/暂停。

本模块刻意不在顶层 import feeluown，只通过 app 的公开属性工作，
这样核心链路在没有安装 FeelUOwn / PyQt6 的环境里也能单测。
"""
from __future__ import annotations

import logging
import os
import threading
from concurrent.futures import ThreadPoolExecutor

from .discovery import discover_devices, guess_lan_ip
from .soap import Renderer, build_didl_item, guess_mime
from .stream import StreamServer

logger = logging.getLogger(__name__)


class CastError(Exception):
    pass


def _model_text(model, *names, default: str = "") -> str:
    """安全读取歌曲模型的展示字段，属性访问可能抛异常或是惰性对象。"""
    for name in names:
        try:
            value = getattr(model, name)
        except Exception:  # noqa: BLE001
            continue
        if isinstance(value, (str, int, float)) and value:
            return str(value)
    return default


class CastController:
    def __init__(self, app, config=None):
        self._app = app
        self._config = config
        self._server: StreamServer | None = None
        self._renderer: Renderer | None = None
        self._device = None
        self._current_song = None
        self._current_media = None
        self._saved_volume = None
        self._active_sid = ""
        self._listeners: list = []
        self._lock = threading.RLock()
        self._executor = ThreadPoolExecutor(
            max_workers=2, thread_name_prefix="fuo-cast"
        )
        self.status = "未投放"
        self.active = False
        self.last_error = ""
        self.devices: list = []

    # ---------- 配置 ----------

    def _cfg(self, name, default):
        if self._config is None:
            return default
        return getattr(self._config, name, default)

    # ---------- 生命周期 ----------

    def initialize(self) -> None:
        server = StreamServer(
            bind=self._cfg("BIND_ADDRESS", "0.0.0.0"),
            port=int(self._cfg("HTTP_PORT", 8765)),
            lan_ip=self._cfg("LAN_IP", "") or guess_lan_ip(),
        )
        server.start()
        self._server = server

        playlist = getattr(self._app, "playlist", None)
        player = getattr(self._app, "player", None)
        if playlist is not None:
            self._connect_signal(playlist, "song_changed_v2", self._on_song_changed)
        if player is not None:
            self._connect_signal(player, "state_changed", self._on_state_changed)
            self._current_media = getattr(player, "current_media", None)
        if playlist is not None:
            self._current_song = getattr(playlist, "current_song", None)
        self._notify("未投放", False)

    @staticmethod
    def _connect_signal(obj, name, callback):
        signal = getattr(obj, name, None)
        if signal is not None and hasattr(signal, "connect"):
            try:
                signal.connect(callback)
            except Exception:  # noqa: BLE001
                logger.exception("连接信号 %s 失败", name)
        return signal

    def shutdown(self) -> None:
        self.stop_cast()
        for obj, name, callback in (
            (getattr(self._app, "playlist", None), "song_changed_v2", self._on_song_changed),
            (getattr(self._app, "player", None), "state_changed", self._on_state_changed),
        ):
            signal = getattr(obj, name, None) if obj is not None else None
            if signal is not None and hasattr(signal, "disconnect"):
                try:
                    signal.disconnect(callback)
                except Exception:  # noqa: BLE001
                    pass
        if self._server is not None:
            self._server.stop()
            self._server = None
        self._executor.shutdown(wait=False, cancel_futures=True)

    def add_status_listener(self, callback) -> None:
        self._listeners.append(callback)

    def _notify(self, text: str, active: bool) -> None:
        self.status = text
        self.active = active
        for callback in list(self._listeners):
            try:
                callback(text, active)
            except Exception:  # noqa: BLE001
                logger.exception("状态回调执行失败")

    def _submit(self, fn, *args) -> None:
        self._executor.submit(self._guarded, fn, *args)

    def _guarded(self, fn, *args):
        try:
            fn(*args)
        except Exception as exc:  # noqa: BLE001
            self.last_error = str(exc)
            logger.warning("投放失败: %s", exc)
            # 首次推送就失败时，本机已被静音却什么都没播出来，必须回滚，
            # 否则用户会以为播放器坏了。
            if fn == self._push_current and not self._active_sid:
                self.stop_cast()
                self._notify(f"投放失败：{exc}（本机音量已恢复）", False)
            else:
                self._notify(f"投放失败：{exc}", self.active)

    # ---------- 设备 ----------

    def discover(self) -> list:
        devices = discover_devices(
            timeout=float(self._cfg("SSDP_TIMEOUT", 3.0)),
            mx=2,
            bind_ip=self._cfg("SSDP_BIND_IP", ""),
        )
        self.devices = devices
        return devices

    def cast_to(self, device) -> None:
        with self._lock:
            first_cast = self._renderer is None
            self._device = device
            self._renderer = Renderer(
                device, timeout=float(self._cfg("SOAP_TIMEOUT", 5.0))
            )
        if first_cast and self._cfg("MUTE_LOCAL", True):
            try:
                self._saved_volume = self._app.player.volume
                self._app.player.volume = 0  # 避免 PC 和电视同时出声
            except Exception:  # noqa: BLE001
                logger.exception("设置本机静音失败")
        self.last_error = ""
        self._notify(f"正在连接 {device.name}…", True)
        self._submit(self._push_current)

    def stop_cast(self) -> None:
        with self._lock:
            renderer = self._renderer
            self._renderer = None
            self._device = None
            self._active_sid = ""
            saved_volume = self._saved_volume
            self._saved_volume = None
        if renderer is not None:
            self._executor.submit(self._safe_stop, renderer)
        if saved_volume is not None:
            try:
                self._app.player.volume = saved_volume
            except Exception:  # noqa: BLE001
                logger.exception("恢复本机音量失败")
        if self._server is not None:
            self._server.registry.clear()
        self._notify("未投放", False)

    @staticmethod
    def _safe_stop(renderer) -> None:
        try:
            renderer.stop()
        except Exception as exc:  # noqa: BLE001
            logger.debug("停止投放指令失败(设备可能已离线): %s", exc)

    # ---------- 当前媒体 ----------

    def _make_entry(self, media, song):
        source = (getattr(media, "url", "") or "").strip()
        if not source:
            manifest = getattr(media, "_manifest", None)
            if manifest is None:
                try:
                    manifest = media.manifest
                except Exception:  # noqa: BLE001
                    manifest = None
            source = getattr(manifest, "audio_url", "") or ""
            if not source:
                raise CastError("当前媒体没有可推送的音频地址（DRM 或音视频分离流）")
        if getattr(media, "decryption_key", None):
            raise CastError("当前音源需要本地解密，电视无法直接播放")

        is_remote = source.startswith(("http://", "https://"))
        if not is_remote and not source.startswith("file://"):
            if not os.path.exists(source):
                raise CastError(f"找不到本地音频文件：{source}")

        props = getattr(media, "props", None)
        fmt = getattr(props, "format", "") or ""
        mime = guess_mime("audio." + fmt.lstrip(".") if fmt else source)
        return self._server.registry.register(
            source=source,
            mime=mime,
            headers=dict(getattr(media, "http_headers", None) or {}),
            proxy=getattr(media, "http_proxy", "") or "",
            title=_model_text(song, "title_display", "title", default="FeelUOwn"),
            artist=_model_text(song, "artists_name_display", "artists_name"),
            album=_model_text(song, "album_name_display", "album_name"),
            duration=getattr(props, "duration", 0) or 0,
        )

    def _push_current(self) -> None:
        media = self._current_media
        song = self._current_song
        if media is None:
            media = getattr(self._app.player, "current_media", None)
        if song is None:
            song = getattr(self._app.playlist, "current_song", None)
        if media is None:
            raise CastError("当前没有正在播放的歌曲，请先播放一首再投放")
        with self._lock:
            renderer = self._renderer
            server = self._server
        if renderer is None or server is None:
            return

        entry = self._make_entry(media, song)
        url = server.public_url(entry)
        metadata = build_didl_item(
            url,
            title=entry.title,
            artist=entry.artist,
            album=entry.album,
            duration=entry.duration,
            mime=entry.mime,
        )
        renderer.set_uri(url, metadata)
        renderer.play()

        old_sid = self._active_sid
        self._active_sid = entry.sid
        if old_sid:
            # 电视可能还在读上一条流，延迟撤销更稳
            timer = threading.Timer(3.0, server.registry.revoke, args=(old_sid,))
            timer.daemon = True
            timer.start()

        try:
            state = int(getattr(self._app.player, "state", 2))
        except (TypeError, ValueError):
            state = 2
        if state == 1:
            renderer.pause()
        self._notify(f"已投放到 {renderer.device.name}", True)

    # ---------- 信号回调 ----------

    def _on_song_changed(self, song, media) -> None:
        self._current_song = song
        self._current_media = media
        with self._lock:
            active = self._renderer is not None
        if active:
            self._submit(self._push_current)

    def _on_state_changed(self, state) -> None:
        with self._lock:
            renderer = self._renderer
        if renderer is None:
            return
        try:
            value = int(state)
        except (TypeError, ValueError):
            return
        self._submit(self._sync_state, renderer, value)

    @staticmethod
    def _sync_state(renderer, value) -> None:
        try:
            if value == 1:
                renderer.pause()
            elif value == 2:
                renderer.play()
            elif value == 0:
                renderer.stop()
        except Exception as exc:  # noqa: BLE001
            logger.debug("同步播放状态失败: %s", exc)
