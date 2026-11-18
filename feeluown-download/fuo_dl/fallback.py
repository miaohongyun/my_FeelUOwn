# -*- coding: utf-8 -*-
"""原音源拿不到无损时，回退到开放曲库找真无损。

触发条件只有一种：当前取到的音频不是无损格式（mp3/aac/opus…），或者原音源
根本取不到流。命中后**弹框让用户挑**，不自动决定——开放曲库的元数据质量
参差不齐，自动匹配很容易下到翻奏版、现场版或同名不同曲。

开放曲库只覆盖公共领域和 CC 授权作品（Internet Archive / Jamendo），
主流流行专辑搜不到。这是版权边界，不是实现问题。

用法::

    from fuo_dl.fallback import choose_open_source, download_open_track

    track = await choose_open_source(app, song)
    if track is not None:
        await download_open_track(app, track, download_dir)
"""
from __future__ import annotations

import logging
import re
import unicodedata
from typing import List, Optional

from feeluown.utils import aio

from .helpers import guess_media_url_ext
from .lossless import format_report
from .open_sources import OpenTrack, download, search_all
from .quality import _notify, _song_title

logger = logging.getLogger(__name__)

#: 这些格式才算无损，具体档位判定交给 lossless 模块
LOSSLESS_FORMATS = frozenset(
    {"flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff"}
)

#: 弹框里最后一项，表示不换源
SKIP_LABEL = "跳过，按原音质下载"

#: 标题太短时不做包含匹配，否则 "Aria" 会匹配到一堆曲目
MIN_CONTAIN_LENGTH = 6


def is_lossless_media(media) -> bool:
    """判断取到的 Media 是不是无损格式。

    provider 未必填了 ``props.format``，此时退回按 URL 后缀猜。
    """
    props = getattr(media, "props", None)
    fmt = (getattr(props, "format", "") or "") if props is not None else ""
    if not fmt:
        fmt = guess_media_url_ext(getattr(media, "url", "") or "")
    return (fmt or "").lower() in LOSSLESS_FORMATS


def _normalize(text) -> str:
    """归一化标题：全角转半角、去括号里的版本标记、去标点。"""
    if not isinstance(text, str):
        return ""
    text = unicodedata.normalize("NFKC", text).lower()
    text = re.sub(r"[\(（\[【].*?[\)）\]】]", " ", text)
    text = re.sub(r"[^\w\s]", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def is_title_match(song_title: str, track_title: str) -> bool:
    """标题是否指向同一首曲子。

    相等直接通过；包含关系要求较短一方至少 6 个字符，避免短标题误配。
    """
    left, right = _normalize(song_title), _normalize(track_title)
    if not left or not right:
        return False
    if left == right:
        return True
    shorter, longer = (left, right) if len(left) <= len(right) else (right, left)
    return len(shorter) >= MIN_CONTAIN_LENGTH and shorter in longer


def is_artist_match(song_artist: str, track_artist: str) -> bool:
    """艺人是否有交集。开放曲库经常缺艺人字段，此时不排除。"""
    left, right = _normalize(song_artist), _normalize(track_artist)
    if not left or not right:
        return True
    return left in right or right in left


def _song_meta(song) -> tuple:
    """从 song 模型里取标题与艺人名。"""
    title = ""
    for attr in ("title_display", "title"):
        value = getattr(song, attr, "")
        if isinstance(value, str) and value:
            title = value
            break
    artist = getattr(song, "artists_name", "") or ""
    return title, artist if isinstance(artist, str) else ""


def find_candidates(
    song, jamendo_client_id: str = "", limit: int = 8, timeout: float = 20
) -> List[OpenTrack]:
    """在开放曲库里找标题匹配的候选。

    网络失败、无匹配都返回空列表，由调用方按原音质继续下载。
    ``timeout`` 是单个 HTTP 请求的超时，播放链路会传更小的值。
    """
    title, artist = _song_meta(song)
    if not title:
        return []

    query = ("%s %s" % (title, artist)).strip()
    try:
        tracks = search_all(
            query, jamendo_client_id, limit=max(limit * 2, 8), timeout=timeout
        )
    except Exception:  # noqa: BLE001 - 检索失败不能阻断下载
        logger.warning("开放曲库检索失败：%s", query, exc_info=True)
        return []

    matched = [track for track in tracks if is_title_match(title, track.title)]
    if artist:
        strong = [
            track for track in matched if is_artist_match(artist, track.artist)
        ]
        if strong:
            matched = strong
    return matched[:limit]


def format_candidate(index: int, track: OpenTrack) -> str:
    """弹框里的一行文本。"""
    size = "%.1fMB" % (track.size / 1048576.0) if track.size else "大小未知"
    return "%d. %s - %s  [%s | %s]" % (
        index, track.artist or "未知艺人", track.title, track.source, size
    )


def ask_open_source(
    candidates: List[OpenTrack], song_title: str = ""
) -> Optional[OpenTrack]:
    """弹出候选列表，返回选中的曲目；取消或选「跳过」返回 None。

    PyQt6 延迟导入，方便无 Qt 环境下对纯逻辑做单测。
    """
    from PyQt6.QtWidgets import QApplication, QInputDialog

    labels = [
        format_candidate(index, track)
        for index, track in enumerate(candidates, 1)
    ]
    labels.append(SKIP_LABEL)
    parent = QApplication.activeWindow()
    if song_title:
        prompt = "《%s》不是无损，开放曲库找到这些版本：" % song_title
    else:
        prompt = "当前音源不是无损，开放曲库找到这些版本："
    chosen, ok = QInputDialog.getItem(
        parent, "选择无损音源", prompt, labels, 0, False
    )
    if not ok or chosen == SKIP_LABEL:
        return None
    for index, label in enumerate(labels):
        if label == chosen and index < len(candidates):
            return candidates[index]
    return None


def _config(app):
    """兼容 dl / fuo_dl 两种配置命名空间。"""
    config = getattr(app.config, "dl", None)
    if config is None:
        config = getattr(app.config, "fuo_dl", None)
    return config


async def choose_open_source(app, song) -> Optional[OpenTrack]:
    """完整的回退决策流程：检索 → 过滤 → 让用户挑。"""
    config = _config(app)
    if config is not None and not getattr(config, "OPEN_SOURCE_FALLBACK", True):
        return None
    jamendo_id = getattr(config, "JAMENDO_CLIENT_ID", "") if config else ""

    _notify(app, "当前音源不是无损，正在检索开放曲库…")
    candidates = await aio.run_fn(find_candidates, song, jamendo_id)
    if not candidates:
        _notify(app, "开放曲库没有匹配的无损版本，按原音质下载")
        return None

    try:
        return ask_open_source(candidates, _song_title(song))
    except Exception:  # noqa: BLE001 - 无 GUI 时不能阻断下载
        logger.warning("无损音源选择弹框失败，按原音质下载", exc_info=True)
        return None


async def download_open_track(app, track: OpenTrack, dest_dir: str) -> Optional[str]:
    """下载开放源曲目并自动校验，返回落地路径；失败返回 None。"""
    _notify(app, "正在下载无损：%s" % track.describe())
    try:
        outcome = await aio.run_fn(download, track, dest_dir)
    except Exception:  # noqa: BLE001
        logger.exception("开放源下载失败：%s", track.url)
        _notify(app, "开放源下载失败：%s" % track.title)
        return None

    if outcome.result is not None:
        _notify(app, "下载完成｜%s" % format_report(outcome.result))
        if not outcome.result.ok:
            _notify(app, "该文件未通过真无损校验，建议不要当作无损收藏")
    else:
        _notify(app, "下载完成：%s" % outcome.path)
    return outcome.path


__all__ = [
    "LOSSLESS_FORMATS",
    "SKIP_LABEL",
    "ask_open_source",
    "choose_open_source",
    "download_open_track",
    "find_candidates",
    "format_candidate",
    "is_artist_match",
    "is_lossless_media",
    "is_title_match",
]
