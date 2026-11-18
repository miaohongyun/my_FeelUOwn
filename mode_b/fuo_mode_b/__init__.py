"""模式 B 多源路由插件（FeelUOwn 桌面端）。

把 ``mode_b_sources`` 的纯数据配置 + 懒检查熔断路由，包装成一个标准
FeelUOwn 音乐源 Provider，挂进 ``app.library``。核心仓库零污染：本插件与
引擎都是独立包，通过 ``fuo.plugins_v1`` entry point 热加载。

设计要点
--------
- 启动：后台线程拉远程配置（3~5s 超时），绝不阻塞 PyQt6 主界面。
- 搜索：普通歌名只转发给配了 ``searchPath`` 的启用源；没有任何可搜索源时
  零网络请求、直接返回空，不干扰网易云/QQ/B 站等正常音源。
- 兜底测试入口：搜 ``flac`` / ``电台`` / ``radio`` / ``modeb`` 时，把当前
  启用的直连源包装成 ``BriefSongModel``，桌面搜到即可双击试听、测下载与投屏。
- 取流：``song_get_media`` 调 ``resolve_play_url``（5s 短超时），把真实 url 与
  headers 装进 ``Media`` 透传给 mpv；路由异常映射为 FeelUOwn 标准异常。

~/.fuorc 可配置项（写在 config.mode_b 下）::

   config.mode_b.CACHE_DIR = ''            # 留空用默认 ~/.FeelUOwn/mode_b
   config.mode_b.CONFIG_PATH = ''          # 默认内置 sources_default.json
   config.mode_b.FALLBACK_KEYWORDS = ''    # 逗号分隔，覆盖默认兜底关键词

终端调试入口（挂在 ``app.mode_b_router`` / ``app.mode_b_provider``）::

   app.mode_b_router.sync_remote_config()
   app.mode_b_router.set_manual_override('source_id')
   app.mode_b_router.breaker.snapshot()
   app.mode_b_router.breaker.reset_all()
"""
from __future__ import annotations

import logging
from pathlib import Path
from typing import List, Optional, Tuple

from feeluown.excs import MediaNotFound, ProviderIOError
from feeluown.library import (
    AbstractProvider,
    BriefSongModel,
    ModelType,
    ProviderV2,
    SearchType,
    SimpleSearchResult,
)
from feeluown.media import Media, Quality

from mode_b_sources import (
    AllSourcesDownError,
    ModeBRouter,
    SourceConfigError,
    SourceUnavailableError,
    TrackNotFoundError,
)

__alias__ = "模式 B 源路由器"
__desc__ = "基于纯数据配置同步与懒检查熔断的多源路由引擎"
__version__ = "0.2.0"

logger = logging.getLogger(__name__)

SOURCE = "modeb"
#: 命中这些关键词时返回直连兜底条目，方便测试播放/下载/投屏。
DEFAULT_FALLBACK_KEYWORDS = ("flac", "电台", "radio", "modeb")
#: identifier 中 sourceId 与 trackId 的分隔符。
_ID_SEP = "::"

_provider: Optional["ModeBProvider"] = None


def _default_config_path() -> Path:
    return Path(__file__).resolve().parent.parent / "mode_b_sources" / "sources_default.json"


def _split_identifier(identifier: str) -> Tuple[str, str]:
    """从 identifier 解析出 (sourceId, trackId)；非本格式返回 ('', identifier)。"""
    if _ID_SEP in identifier:
        source_id, track_id = identifier.split(_ID_SEP, 1)
        return source_id, track_id
    return "", identifier


class ModeBProvider(AbstractProvider, ProviderV2):
    """把 ModeBRouter 暴露成标准 FeelUOwn 多音质音乐源。"""

    class meta:
        identifier = SOURCE
        name = "模式 B 多源路由"

    def __init__(self, router: ModeBRouter, fallback_keywords=DEFAULT_FALLBACK_KEYWORDS):
        super().__init__()
        self.router = router
        self.fallback_keywords = tuple(k.lower() for k in fallback_keywords)

    @property
    def identifier(self):
        return SOURCE

    @property
    def name(self):
        return ModeBProvider.meta.name

    def use_model_v2(self, model_type: ModelType) -> bool:
        return model_type in (ModelType.song,)

    #
    # 搜索
    #
    def search(self, keyword, type_=SearchType.so, **kwargs):
        """普通搜歌只走配了 searchPath 的源；兜底关键词返回直连测试条目。"""
        search_type = SearchType.parse(type_ or SearchType.so)
        if search_type is not SearchType.so or not keyword:
            return SimpleSearchResult(q=keyword or "", source=SOURCE)

        lowered = keyword.lower()
        if any(word in lowered for word in self.fallback_keywords):
            songs = self._fallback_songs()
        else:
            songs = self._search_sources(keyword)
        return SimpleSearchResult(q=keyword, source=SOURCE, songs=songs)

    def _search_sources(self, keyword: str) -> List[BriefSongModel]:
        """转发给 router；无 searchPath 源时 router 零请求返回空。"""
        try:
            hits = self.router.search_tracks(keyword)
        except Exception:  # noqa: BLE001 - 搜索失败降级为空，不拖垮 UI
            logger.warning("模式 B 搜索失败，返回空结果", exc_info=True)
            return []
        return [
            BriefSongModel(
                identifier=f"{hit.sourceId}{_ID_SEP}{hit.trackId}",
                source=SOURCE,
                title=hit.title,
                artists_name=hit.artist,
            )
            for hit in hits
        ]

    def _fallback_songs(self) -> List[BriefSongModel]:
        """把当前启用的直连源包装成可双击试听的测试条目。"""
        songs = []
        for entry in self.router.get_candidates():
            songs.append(BriefSongModel(
                identifier=f"{entry.id}{_ID_SEP}live::{entry.id}",
                source=SOURCE,
                title=f"[模式B] {entry.name}",
                artists_name=entry.id,
            ))
        return songs

    #
    # 多音质 / 取流
    #
    def song_list_quality(self, song: BriefSongModel) -> List[Quality.Audio]:
        """直播源无档位切换语义，但仍声明一档，满足 SupportsSongMultiQuality。"""
        return [Quality.Audio.sq]

    def song_select_media(
        self, song: BriefSongModel, policy=None
    ) -> Tuple[Media, Quality.Audio]:
        """按策略选一档；直播源只有一档，直接走 song_get_media。"""
        media = self.song_get_media(song, Quality.Audio.sq)
        if media is None:
            raise MediaNotFound(f"模式 B 未找到可播放媒体: {song.identifier}")
        return media, Quality.Audio.sq

    def song_get_media(
        self, song: BriefSongModel, quality: Quality.Audio
    ) -> Optional[Media]:
        """路由到真实源取流，把 url 与 headers 装进 Media 透传给 mpv/投屏/下载。"""
        source_id, track_id = _split_identifier(song.identifier)
        runtime_params = {}
        if track_id:
            runtime_params["id"] = track_id
        # 若 identifier 里带了 sourceId，锁到该源；否则交给 router 按优先级降级。
        try:
            result = self.router.resolve_play_url(runtime_params)
        except TrackNotFoundError as e:
            raise MediaNotFound(str(e)) from e
        except (AllSourcesDownError, SourceUnavailableError, SourceConfigError) as e:
            raise ProviderIOError(str(e), provider=SOURCE) from e

        headers = result.get("headers") or {}
        return Media(
            result["url"],
            http_headers=headers if isinstance(headers, dict) else {},
        )


def _init_config(config):
    """声明 ~/.fuorc 下 config.mode_b 的可调项。"""
    try:
        config.deffield("CACHE_DIR", type_=str, default="", desc="模式B缓存目录，留空用 ~/.FeelUOwn/mode_b")
        config.deffield("CONFIG_PATH", type_=str, default="", desc="模式B默认配置路径，留空用内置 sources_default.json")
        config.deffield("FALLBACK_KEYWORDS", type_=str, default="", desc="兜底测试关键词，逗号分隔")
    except Exception:  # noqa: BLE001 - 配置不可用时用默认值
        logger.debug("初始化 mode_b 配置段失败，使用默认值", exc_info=True)


def _router_kwargs(app) -> dict:
    """从 config.mode_b 读取目录覆盖，构造 router 参数。"""
    kwargs = {}
    cfg = getattr(app.config, "mode_b", None)
    if cfg is not None:
        cache_dir = getattr(cfg, "CACHE_DIR", "")
        if cache_dir:
            kwargs["cache_dir"] = Path(cache_dir).expanduser()
        config_path = getattr(cfg, "CONFIG_PATH", "")
        if config_path:
            kwargs["default_config_path"] = Path(config_path).expanduser()
    kwargs.setdefault("default_config_path", _default_config_path())
    return kwargs


def _fallback_keywords(app):
    cfg = getattr(app.config, "mode_b", None)
    raw = getattr(cfg, "FALLBACK_KEYWORDS", "") if cfg is not None else ""
    if raw:
        return tuple(k.strip() for k in raw.split(",") if k.strip())
    return DEFAULT_FALLBACK_KEYWORDS


def enable(app):
    """注册 Provider + 后台同步 + 挂调试入口，全程不阻塞 UI。"""
    global _provider
    _init_config(app.config)

    router = ModeBRouter(**_router_kwargs(app))
    provider = ModeBProvider(router, fallback_keywords=_fallback_keywords(app))

    # 后台拉远程配置，绝不阻塞 PyQt6 主界面启动。
    router.startup_sync_async(
        on_finished=lambda ok, msg: logger.info("ModeB 配置后台同步 (ok=%s): %s", ok, msg)
    )

    app.library.register(provider)
    # 调试入口：终端 suo exec / ~/.fuorc 可直接访问。
    app.mode_b_router = router
    app.mode_b_provider = provider
    _provider = provider

    logger.info(
        "插件 %s 已启用，启用源: %s", __alias__, [s.id for s in router.get_candidates()]
    )


def disable(app):
    """从 library 注销并清理调试入口。"""
    global _provider
    if _provider is not None:
        try:
            app.library.deregister(_provider)
        except Exception:  # noqa: BLE001
            logger.debug("注销 mode_b provider 失败", exc_info=True)
        _provider = None
    for attr in ("mode_b_router", "mode_b_provider"):
        if hasattr(app, attr):
            delattr(app, attr)
    logger.info("插件 %s 已禁用", __alias__)
