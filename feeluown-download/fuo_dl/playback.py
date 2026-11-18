# -*- coding: utf-8 -*-
"""播放时自动换用开放曲库的无损音源。

FeelUOwn 的播放流程是 ``playlist._prepare_media`` → ``library.song_prepare_media``
→ provider 按音质策略取流。这里包装 ``library.song_prepare_media``：

1. 这首歌之前缓存过无损文件 → 直接播放本地文件；
2. 原 provider 给的流本身是无损 → 原样返回，不做任何事；
3. 不是无损 → 去开放曲库找同名曲目，下载并做真无损校验。
   **第一次播放会等下载完成再播**，默认最多等 10 秒
   （``PLAY_WAIT_TIMEOUT``）；超时则先按原音质播放，后台继续下载，
   下次播放自动用缓存的无损文件。
4. 当前歌曲开始播放时，后台顺手为**下一首**预取无损
   （``PLAY_PREFETCH_NEXT``），这样切歌时通常已经命中缓存，不用再等。

等这么久是因为一首 FLAC 动辄几十 MB。不想等可以把 ``PLAY_WAIT_TIMEOUT``
设为 0，退化成「后台准备、下次播放生效」。

边界：只接 Internet Archive / Jamendo 这类公开授权曲库，不碰会员音源，
也不做任何会员绕过。
"""
from __future__ import annotations

import json
import logging
import os
import threading
from typing import Callable, Dict, List, Optional, Tuple

from .fallback import find_candidates, is_lossless_media
from .open_sources import OpenTrack, download

logger = logging.getLogger(__name__)

#: 后台检索时单个 HTTP 请求的超时（秒）。检索不在播放线程里，不阻塞点播放。
DEFAULT_SEARCH_TIMEOUT = 3.0

#: 第一次播放最多等多久（秒）让无损音源就绪；0 表示不等，后台准备。
DEFAULT_WAIT_TIMEOUT = 10.0

#: 下载后校验结论落在这个集合里才允许缓存。
#: suspicious 是「老录音自然滚降」，unreadable 是没装 ffmpeg，都不代表转码造假。
ACCEPTABLE_VERDICTS = frozenset({"lossless", "suspicious", "unreadable"})

#: 无损缓存目录（相对 FeelUOwn 的 CACHE_DIR）
CACHE_SUBDIR = os.path.join("fuo_dl", "lossless")

#: 缓存索引文件名，内容形如 {"source|identifier": "/path/to/file.flac"}
INDEX_NAME = "index.json"

SongKey = Tuple[str, str]


def cache_dir() -> str:
    """默认的无损缓存目录，跟随 FeelUOwn 的 CACHE_DIR。"""
    try:
        from feeluown.consts import CACHE_DIR
    except Exception:  # noqa: BLE001 - 单测等非 FeelUOwn 环境下的退路
        CACHE_DIR = os.path.join(os.path.expanduser("~"), ".FeelUOwn", "cache")
    return os.path.join(CACHE_DIR, CACHE_SUBDIR)


def song_key(song) -> SongKey:
    """缓存键：source + identifier，避免不同音源的同名 id 串味。"""
    return (
        getattr(song, "source", "") or "",
        str(getattr(song, "identifier", "") or ""),
    )


def song_title(song) -> str:
    """取展示用标题，懒加载字段异常时返回空串。"""
    for attr in ("title_display", "title"):
        try:
            value = getattr(song, attr)
        except Exception:  # noqa: BLE001
            continue
        if isinstance(value, str) and value:
            return value
    return ""


def pick_best(
    candidates: List[OpenTrack], title: str = ""
) -> Optional[OpenTrack]:
    """从候选里挑一条：标题长度最接近的优先，其次 Internet Archive。

    Archive 的条目是上传者原始的 FLAC 文件；Jamendo 即使指定 flac，
    也可能拿到上传者的 mp3，所以要靠下载后的校验兜底。
    """
    if not candidates:
        return None

    def rank(item) -> tuple:
        index, track = item
        diff = abs(len(track.title) - len(title)) if title else 0
        source_rank = 0 if track.source == "archive" else 1
        return (diff, source_rank, index)

    return min(enumerate(candidates), key=rank)[1]


def _config(app):
    """兼容 dl / fuo_dl 两种配置命名空间。"""
    config = getattr(app.config, "dl", None)
    if config is None:
        config = getattr(app.config, "fuo_dl", None)
    return config


def _notify(app, message: str) -> None:
    fn = getattr(app, "show_msg", None)
    if fn is None:
        return
    try:
        fn(message)
    except Exception:  # noqa: BLE001 - 状态栏提示失败不影响播放
        logger.debug("show_msg 调用失败", exc_info=True)


class _Job:
    """一次后台准备任务，播放线程可以等它完成。"""

    __slots__ = ("done", "path")

    def __init__(self):
        self.done = threading.Event()
        self.path: Optional[str] = None


class PlaybackBridge:
    """把 ``library.song_prepare_media`` 换成带无损缓存的版本。"""

    def __init__(
        self,
        app,
        cache_path: Optional[str] = None,
        search_timeout: float = DEFAULT_SEARCH_TIMEOUT,
        wait_timeout: float = DEFAULT_WAIT_TIMEOUT,
        search_fn: Optional[Callable] = None,
        download_fn: Optional[Callable] = None,
    ):
        self._app = app
        self._cache_path = cache_path or cache_dir()
        self._search_timeout = search_timeout
        self._wait_timeout = wait_timeout
        self._search_fn = search_fn or find_candidates
        self._download_fn = download_fn or download
        #: (source, identifier) -> 本地无损文件路径
        self._cache: Dict[SongKey, str] = self._load_index()
        #: 正在后台准备的歌曲，避免同一首重复起线程
        self._jobs: Dict[SongKey, _Job] = {}
        #: 保护 _jobs 的注册与清理（播放线程和预取线程都可能并发）
        self._jobs_lock = threading.Lock()
        #: 多首歌同时准备时，串行写索引
        self._index_lock = threading.Lock()
        self._original = None
        self._installed = False
        #: 下一首预取依赖的 playlist 与信号槽（无 GUI 环境下为 None）
        self._playlist = None
        self._song_changed_slot = None

    # -- 安装 / 卸载 -------------------------------------------------------

    def install(self) -> None:
        if self._installed:
            return
        self._original = self._app.library.song_prepare_media
        bridge = self

        def prepare_media(song, policy=None):
            """替代 ``library.song_prepare_media`` 的取流入口。"""
            return bridge.prepare_media(song, policy)

        # 下载流程通过这个属性拿到原始取流函数，避免把缓存文件当成「已下载」
        prepare_media._fuo_dl_original = self._original  # type: ignore[attr-defined]
        self._app.library.song_prepare_media = prepare_media
        self._connect_prefetch()
        self._installed = True
        logger.info("fuo-dl 播放桥已挂载，缓存目录：%s", self._cache_path)

    def uninstall(self) -> None:
        if not self._installed:
            return
        self._disconnect_prefetch()
        self._app.library.song_prepare_media = self._original
        self._original = None
        self._installed = False

    # -- 下一首预取 --------------------------------------------------------

    def _connect_prefetch(self) -> None:
        """挂上 song_changed，当前歌一开始播就为下一首做准备。

        注意：``song_changed`` 是在 playlist 持锁时 emit 的（见 FeelUOwn
        playlist.py 的注释），回调里不能直接读 ``next_song``，否则死锁。
        这里只起线程，让线程等锁释放后再算下一首。
        """
        playlist = getattr(self._app, "playlist", None)
        signal = getattr(playlist, "song_changed", None)
        connect = getattr(signal, "connect", None)
        if connect is None:
            return
        self._playlist = playlist
        self._song_changed_slot = self._on_song_changed
        connect(self._song_changed_slot)

    def _disconnect_prefetch(self) -> None:
        if self._playlist is None or self._song_changed_slot is None:
            return
        try:
            self._playlist.song_changed.disconnect(self._song_changed_slot)
        except (TypeError, RuntimeError):
            logger.debug("断开 song_changed 失败", exc_info=True)
        self._playlist = None
        self._song_changed_slot = None

    def _on_song_changed(self, song=None) -> None:
        """信号回调：只起后台线程，不在这里做网络请求或读 playlist。"""
        if song is None or not self._prefetch_enabled():
            return
        threading.Thread(
            target=self._prefetch_next,
            args=(song,),
            name="fuo-dl-prefetch",
            daemon=True,
        ).start()

    def _prefetch_next(self, song) -> None:
        """算出下一首并预取；等 playlist 锁释放后再读，避免死锁。"""
        playlist = self._playlist
        if playlist is None:
            return
        try:
            next_song = playlist.next_song
        except Exception:  # noqa: BLE001 - 队列变动时读下一首可能失败
            logger.debug("获取下一首失败，跳过预取", exc_info=True)
            return
        if next_song is None:
            return
        if song_key(next_song) == song_key(song):
            return
        self._prefetch(next_song)

    def _prefetch(self, song) -> None:
        """为这首歌起一个预取任务；缓存已有或任务已在跑时直接跳过。"""
        key = song_key(song)
        if not key[1] or key in self._cache:
            return
        with self._jobs_lock:
            if key in self._jobs:
                return
            job = _Job()
            self._jobs[key] = job
        threading.Thread(
            target=self._prefetch_worker,
            args=(song, key, job),
            name="fuo-dl-prefetch",
            daemon=True,
        ).start()

    def _prefetch_worker(self, song, key: SongKey, job: _Job) -> None:
        """原音源有损时才去开放曲库找，成功后写缓存供切歌时直接命中。"""
        title = song_title(song)
        try:
            if not self._installed:
                return
            # 原音源本身就是无损的话没必要多下一份
            media = None
            if self._original is not None:
                try:
                    media = self._original(song, None)
                except Exception:  # noqa: BLE001 - 预取失败不能影响当前播放
                    logger.debug("预取时取流失败：%s", title, exc_info=True)
            if media is not None and is_lossless_media(media):
                return

            config = _config(self._app)
            jamendo_id = getattr(config, "JAMENDO_CLIENT_ID", "") if config else ""
            candidates = self._search_fn(
                song, jamendo_id, limit=5, timeout=self._search_timeout
            )
            track = pick_best(candidates, title)
            if track is None:
                logger.info("下一首没有可用的无损版本：%s", title)
                return

            outcome = self._download_fn(track, self._cache_path)
            if not self._keep(outcome):
                return

            self._cache[key] = outcome.path
            job.path = outcome.path
            self._save_index()
            logger.info("下一首无损音源已预取：%s", title)
        except Exception:  # noqa: BLE001 - 预取失败留给播放时再试
            logger.warning("预取下一首无损音源失败：%s", title, exc_info=True)
        finally:
            with self._jobs_lock:
                self._jobs.pop(key, None)
            job.done.set()

    # -- 缓存索引 ----------------------------------------------------------

    def _index_file(self) -> str:
        return os.path.join(self._cache_path, INDEX_NAME)

    def _load_index(self) -> Dict[SongKey, str]:
        """读回上次会话的缓存；文件已不存在的条目直接丢弃。"""
        try:
            with open(self._index_file(), encoding="utf-8") as handle:
                raw = json.load(handle)
        except FileNotFoundError:
            return {}
        except Exception:  # noqa: BLE001 - 索引损坏不能阻断播放
            logger.warning("无损缓存索引解析失败，按空索引处理", exc_info=True)
            return {}

        cache: Dict[SongKey, str] = {}
        for key, path in (raw.items() if isinstance(raw, dict) else []):
            if not isinstance(key, str) or not isinstance(path, str):
                continue
            if not os.path.isfile(path):
                continue
            source, _, identifier = key.partition("|")
            cache[(source, identifier)] = path
        return cache

    def _save_index(self) -> None:
        """索引只是加速手段，写失败不影响播放。"""
        with self._index_lock:
            try:
                os.makedirs(self._cache_path, exist_ok=True)
                temp_path = self._index_file() + ".tmp"
                with open(temp_path, "w", encoding="utf-8") as handle:
                    json.dump(
                        {"%s|%s" % key: path for key, path in self._cache.items()},
                        handle,
                        ensure_ascii=False,
                    )
                os.replace(temp_path, self._index_file())
            except OSError:
                logger.debug("写入无损缓存索引失败", exc_info=True)

    # -- 播放取流 ----------------------------------------------------------

    def prepare_media(self, song, policy=None):
        """替代 ``library.song_prepare_media`` 的取流入口（同步，跑在线程池里）。

        无损可用时返回本地文件；否则最多等 ``PLAY_WAIT_TIMEOUT`` 秒准备，
        仍不就绪就先按原音质播放，后台任务继续跑。
        """
        cached = self._cached_media(song)
        if cached is not None:
            logger.info("使用缓存的无损音源：%s", song_key(song))
            return cached

        if self._original is None:  # pragma: no cover - install 之后不会发生
            raise RuntimeError("PlaybackBridge 未安装")
        media = self._original(song, policy)

        if not self._fallback_enabled():
            return media
        if media is not None and is_lossless_media(media):
            return media

        job = self._start_job(song, song_key(song))
        if job is None:
            return media

        wait = self._wait_timeout_value()
        if wait > 0:
            _notify(self._app, "正在准备无损音源，最多等待 %d 秒…" % wait)
            if not job.done.wait(wait):
                _notify(
                    self._app,
                    "无损音源还没准备好，先按原音质播放，后台会继续缓存",
                )
                return media

        prepared = self._media_for_path(job.path)
        return prepared if prepared is not None else media

    def _media_for_path(self, path):
        """本地无损文件 -> Media；路径为空或文件不存在时返回 None。"""
        if not isinstance(path, str) or not os.path.isfile(path):
            return None
        from feeluown.media import Media

        ext = os.path.splitext(path)[1].lstrip(".") or "flac"
        return Media(path, format=ext)

    def _cached_media(self, song):
        """命中缓存且文件还在时，返回指向本地文件的 Media。"""
        key = song_key(song)
        path = self._cache.get(key)
        if not path:
            return None
        media = self._media_for_path(path)
        if media is None:
            self._cache.pop(key, None)
            self._save_index()
        return media

    def _fallback_enabled(self) -> bool:
        config = _config(self._app)
        if config is None:
            return True
        return bool(getattr(config, "PLAY_OPEN_SOURCE_FALLBACK", True))

    def _prefetch_enabled(self) -> bool:
        """是否在播放当前歌时顺手预取下一首。"""
        config = _config(self._app)
        if config is None:
            return True
        return bool(getattr(config, "PLAY_PREFETCH_NEXT", True))

    def _wait_timeout_value(self) -> float:
        """第一次播放的等待时长，允许用配置覆盖。"""
        config = _config(self._app)
        value = (
            getattr(config, "PLAY_WAIT_TIMEOUT", self._wait_timeout)
            if config is not None
            else self._wait_timeout
        )
        try:
            return max(0.0, float(value))
        except (TypeError, ValueError):
            return self._wait_timeout

    # -- 后台准备 ----------------------------------------------------------

    def _start_job(self, song, key: SongKey) -> Optional[_Job]:
        """启动（或复用）这首歌的准备任务。"""
        if not key[1]:
            return None
        with self._jobs_lock:
            job = self._jobs.get(key)
            if job is not None:
                return job
            # 预取任务可能刚好完成并写入缓存，这时无需再起线程
            if key in self._cache:
                return None
            job = _Job()
            self._jobs[key] = job
        threading.Thread(
            target=self._prepare_worker,
            args=(song, key, job),
            name="fuo-dl-lossless",
            daemon=True,
        ).start()
        return job

    def _prepare_worker(
        self, song, key: SongKey, job: Optional[_Job] = None
    ) -> None:
        """检索 → 挑一条 → 下载 → 校验 → 写缓存，完成后唤醒等待中的播放线程。"""
        job = job or _Job()
        title = song_title(song)
        try:
            config = _config(self._app)
            jamendo_id = getattr(config, "JAMENDO_CLIENT_ID", "") if config else ""
            candidates = self._search_fn(
                song, jamendo_id, limit=5, timeout=self._search_timeout
            )
            track = pick_best(candidates, title)
            if track is None:
                logger.info("开放曲库没有匹配的无损版本：%s", title)
                return

            _notify(
                self._app,
                "开放曲库找到无损版本，正在下载：%s" % track.describe(),
            )
            outcome = self._download_fn(track, self._cache_path)
            if not self._keep(outcome):
                return

            self._cache[key] = outcome.path
            self._save_index()
            job.path = outcome.path
            _notify(
                self._app,
                "无损音源已就绪：%s" % title,
            )
        except Exception:  # noqa: BLE001 - 后台任务失败不能影响播放
            logger.warning("后台准备无损音源失败：%s", title, exc_info=True)
        finally:
            with self._jobs_lock:
                self._jobs.pop(key, None)
            job.done.set()

    @staticmethod
    def _keep(outcome) -> bool:
        """校验下载结果，假无损 / 有损源直接删掉。"""
        path = getattr(outcome, "path", "") if outcome is not None else ""
        if not path or not os.path.isfile(path):
            return False

        result = getattr(outcome, "result", None)
        verdict = getattr(result, "verdict", "") if result is not None else ""
        if result is None or verdict in ACCEPTABLE_VERDICTS:
            return True

        logger.info("开放源文件未通过真无损校验（%s），丢弃：%s", verdict, path)
        try:
            os.remove(path)
        except OSError:
            logger.debug("删除未通过校验的文件失败", exc_info=True)
        return False


def install(app, **kwargs) -> PlaybackBridge:
    """创建并挂载播放桥，返回实例供 disable 时卸载。"""
    bridge = PlaybackBridge(app, **kwargs)
    bridge.install()
    return bridge


__all__ = [
    "ACCEPTABLE_VERDICTS",
    "DEFAULT_SEARCH_TIMEOUT",
    "DEFAULT_WAIT_TIMEOUT",
    "PlaybackBridge",
    "cache_dir",
    "install",
    "pick_best",
    "song_key",
    "song_title",
]
