# -*- coding: utf-8 -*-
"""
下载音质选择。

FeelUOwn 的播放/下载默认走 ``playlist.audio_select_policy``（默认 ``hq<>``，
即优先 ~320kbps 的 hq，而不是最高档 shq）。本模块在下载前列出这首歌实际
可用的音质档位，按品质由高到低排序让用户选，再按所选档位取流。

只使用 FeelUOwn 已有的公开接口，不需要改核心仓库::

    provider.song_list_quality(song)        -> [Quality.Audio]
    provider.song_get_media(song, quality)  -> Media
    library.song_prepare_media(song, policy) -> Media   # 回落

多音质 provider 需要实现 ``SupportsSongMultiQuality``；不支持的 provider
（或取流失败）会自动回落到原有播放策略，保证下载功能不退化。
"""
from __future__ import annotations

import inspect
import logging

from feeluown.media import Quality
from feeluown.utils import aio

logger = logging.getLogger(__name__)

#: ask_quality 的返回值，表示用户点了「取消」，与「选了默认音质」区分开。
CANCELLED = object()

#: None 表示不指定档位，沿用原来的 playlist.audio_select_policy。
DEFAULT_QUALITY = None

#: Quality.Audio 的定义顺序就是品质从高到低：shq > hq > sq > lq
QUALITY_ORDER = tuple(Quality.Audio)

QUALITY_LABELS = {
    Quality.Audio.shq: "无损 / 超高品质（>320kbps，通常需要会员）",
    Quality.Audio.hq: "高品质（约 320kbps）",
    Quality.Audio.sq: "标准品质（约 200kbps）",
    Quality.Audio.lq: "流畅品质（约 100kbps）",
}

DEFAULT_LABEL = "默认音质（跟随播放策略，不指定档位）"


def normalize_quality(value):
    """把 provider 返回的档位（枚举/字符串/带 value 的对象）归一化为 Quality.Audio。

    未知档位返回 None，避免弹窗里出现用户看不懂的原始值。
    """
    if isinstance(value, Quality.Audio):
        return value
    raw = getattr(value, "value", value)
    if not isinstance(raw, str):
        return None
    try:
        return Quality.Audio(raw.lower())
    except ValueError:
        return None


def sort_qualities(qualities):
    """按品质由高到低排序并去重，丢弃未知档位。"""
    present = set()
    for value in qualities or ():
        quality = normalize_quality(value)
        if quality is not None:
            present.add(quality)
    return [quality for quality in QUALITY_ORDER if quality in present]


def list_available_qualities(app, song):
    """列出某首歌实际可用的音质档位（同步，可能发网络请求，建议在线程池调用）。

    provider 不支持多音质、查询失败时返回空列表，由调用方回落到默认策略。
    """
    try:
        provider = app.library.get(song.source)
        fn = getattr(provider, "song_list_quality", None)
        if fn is None:
            return []
        return sort_qualities(fn(song))
    except Exception:  # noqa: BLE001 - 音质探测失败不能阻断下载
        logger.warning("获取可用音质失败，回落到默认策略", exc_info=True)
        return []


def build_items(qualities):
    """构造弹窗选项：[(显示文本, Quality.Audio 或 None), ...]。

    高品质排在最前面，最后固定附一个「默认音质」。
    """
    ordered = sort_qualities(qualities)
    items = [
        (f"{index}. {QUALITY_LABELS[quality]}  [{quality.value}]", quality)
        for index, quality in enumerate(ordered, 1)
    ]
    items.append((DEFAULT_LABEL, DEFAULT_QUALITY))
    return items


def parse_choice(label, items):
    """把弹窗返回的文本还原成 Quality.Audio（或 None 表示默认策略）。"""
    for text, quality in items:
        if text == label:
            return quality
    logger.warning("未识别的音质选项：%r，回落到默认策略", label)
    return DEFAULT_QUALITY


def _song_title(song):
    for attr in ("title_display", "title"):
        try:
            value = getattr(song, attr)
        except Exception:  # noqa: BLE001 - 模型字段可能是懒加载属性
            continue
        if isinstance(value, str) and value:
            return value
    return ""


def _notify(app, message):
    fn = getattr(app, "show_msg", None)
    if fn is None:
        return
    try:
        fn(message)
    except Exception:  # noqa: BLE001 - 状态栏提示失败不影响下载
        logger.debug("show_msg 调用失败", exc_info=True)


def ask_quality(items, song_title=""):
    """弹出模态选择框，返回选中的文本；用户取消返回 CANCELLED。

    PyQt6 只在真正弹窗时才导入，方便无 Qt 环境下对纯逻辑做单元测试。
    多首歌一起下载时只调用一次，用第一首歌的档位作为整批的选择。
    """
    from PyQt6.QtWidgets import QApplication, QInputDialog

    labels = [label for label, _ in items]
    parent = QApplication.activeWindow()
    if song_title:
        prompt = f"为《{song_title}》选择下载音质（品质由高到低）："
    else:
        prompt = "选择下载音质（品质由高到低）："
    chosen, ok = QInputDialog.getItem(
        parent, "选择下载音质", prompt, labels, 0, False
    )
    if not ok:
        return CANCELLED
    return chosen


async def choose_quality(app, songs, ask_fn=None):
    """下载前的音质选择流程。

    :return: (cancelled, quality)。quality 为 Quality.Audio 或 None（默认策略）。
    """
    config = getattr(app.config, "dl", None)
    if config is None:
        config = getattr(app.config, "fuo_dl", None)
    if config is not None and not getattr(config, "ASK_QUALITY", True):
        return False, DEFAULT_QUALITY

    songs = list(songs or ())
    if not songs:
        return True, DEFAULT_QUALITY

    song = songs[0]
    _notify(app, "正在获取可选下载音质…")
    qualities = await aio.run_fn(list_available_qualities, app, song)
    items = build_items(qualities)
    if len(items) < 2:
        # provider 没有提供任何可选档位，不打扰用户，直接走原逻辑。
        return False, DEFAULT_QUALITY

    ask = ask_fn or ask_quality
    try:
        chosen = ask(items, song_title=_song_title(song))
    except Exception:  # noqa: BLE001 - 弹窗失败（无 GUI 等）时仍然允许下载
        logger.warning("音质选择弹窗失败，回落到默认策略", exc_info=True)
        return False, DEFAULT_QUALITY
    if chosen is CANCELLED:
        return True, DEFAULT_QUALITY
    return False, parse_choice(chosen, items)


def unwrapped_prepare(app):
    """返回未经播放桥包装的取流函数。

    播放桥会把「开放曲库缓存文件」直接返回给播放器；下载时必须绕过它，
    否则会把缓存文件误判成「已经下载过」而跳过真正的下载。
    """
    fn = app.library.song_prepare_media
    return getattr(fn, "_fuo_dl_original", fn)


def prepare_media(app, song, quality=DEFAULT_QUALITY):
    """按指定档位取流；取不到时回落到「该档优先」的策略，再回落到原策略。

    同步函数，可能发网络请求，调用方应放在线程池里执行。
    """
    default_policy = app.playlist.audio_select_policy
    if quality is None:
        return unwrapped_prepare(app)(song, default_policy)

    getter = None
    try:
        provider = app.library.get(song.source)
        getter = getattr(provider, "song_get_media", None)
    except Exception:  # noqa: BLE001
        logger.warning("定位音源 provider 失败，回落到策略选流", exc_info=True)

    if getter is not None:
        try:
            media = getter(song, quality)
        except Exception:  # noqa: BLE001 - 例如该档需要会员
            logger.warning("按 %s 档取流失败", quality.value, exc_info=True)
        else:
            if media is not None and not inspect.isawaitable(media):
                return media
            if inspect.isawaitable(media):
                # 少数 provider 可能返回协程，这里同步接口无法消费，走回落。
                # 主动 close，避免产生「coroutine was never awaited」警告。
                logger.warning("provider 返回了异步 Media，回落到策略选流")
                close = getattr(media, "close", None)
                if close is not None:
                    try:
                        close()
                    except Exception:  # noqa: BLE001
                        logger.debug("关闭未消费的协程失败", exc_info=True)

    # '<>' 规则：以所选档为起点，先向更高档展开，再向更低档展开。
    policy = f"{quality.value}<>"
    try:
        return unwrapped_prepare(app)(song, policy)
    except Exception:  # noqa: BLE001
        logger.warning("回落策略 %s 取流失败，改用播放策略", policy, exc_info=True)
        return unwrapped_prepare(app)(song, default_policy)
