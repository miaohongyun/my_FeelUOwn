# -*- coding: utf-8 -*-
"""用户手动添加的播放地址。

开放曲库覆盖不到主流流行专辑，这时候最实用的办法就是自己贴一条音频直链：
网盘直链、自建服务、本地文件、别处的合法试听地址都可以。本模块负责：

* 校验地址（http/https 直链或本地存在的文件）；
* 把地址持久化到 FeelUOwn 的 data 目录，下次启动还能直接点播；
* 提供右键菜单入口：选已保存地址播放，或新增一条。

它只负责「播」和「记」，不做任何来源解析、会员绕过或盗版接口对接。
"""
from __future__ import annotations

import json
import logging
import os
import time
from dataclasses import asdict, dataclass
from typing import List, Optional
from urllib.parse import unquote, urlparse

logger = logging.getLogger(__name__)

#: 菜单与弹框里的固定项
ADD_LABEL = "＋ 新增音频地址…"
REMOVE_LABEL = "－ 删除已保存地址…"

#: 允许直链的协议。file 交给本地路径分支处理，不走 http 下载。
ALLOWED_SCHEMES = ("http", "https")

#: 首次使用时预置的公开授权测试音源，方便验证播放/下载链路。
#: 第一条是 CC0 的真无损 FLAC，后面三条是 incompetech 的 CC BY 4.0 曲目。
DEFAULT_ENTRIES = (
    (
        "https://gcore.jsdelivr.net/gh/ietf-wg-cellar/flac-test-files@main/"
        "subset/01%20-%20blocksize%204096.flac",
        "FLAC 真无损测试（CC0 / IETF CELLAR）",
    ),
    (
        "https://incompetech.com/music/royalty-free/mp3-royaltyfree/"
        "Mesmerizing%20Galaxy%20Loop.mp3",
        "Kevin MacLeod - Mesmerizing Galaxy（CC BY 4.0）",
    ),
    (
        "https://incompetech.com/music/royalty-free/mp3-royaltyfree/"
        "Lord%20of%20the%20Rangs.mp3",
        "Kevin MacLeod - Lord of the Rangs（CC BY 4.0）",
    ),
    (
        "https://incompetech.com/music/royalty-free/mp3-royaltyfree/"
        "Adeste%20Fideles%20Shorter.mp3",
        "Kevin MacLeod - Adeste Fideles（CC BY 4.0）",
    ),
)


@dataclass
class CustomMedia:
    """一条用户自建的播放地址。"""

    url: str
    title: str = ""
    added_at: float = 0.0


def normalize_url(text: str) -> str:
    """校验并归一化用户输入的地址。

    - ``http/https`` 直链原样返回；
    - 其他输入按本地路径处理，必须真实存在，返回绝对路径；
    - 其余协议（ftp、magnet…）直接拒绝。
    """
    value = (text or "").strip().strip('"').strip("'")
    if not value:
        raise ValueError("地址不能为空")

    if "://" in value:
        scheme = urlparse(value).scheme.lower()
        if scheme not in ALLOWED_SCHEMES:
            raise ValueError("只支持 http/https 直链或本地文件路径")
        return value

    path = os.path.abspath(os.path.expanduser(value))
    if not os.path.isfile(path):
        raise ValueError("本地文件不存在：%s" % path)
    return path


def guess_title(url: str) -> str:
    """从地址里猜一个标题，猜不出就用原地址。"""
    path = urlparse(url).path if "://" in url else url
    name = os.path.basename(unquote(path))
    stem = os.path.splitext(name)[0]
    return stem or url


def default_store_path() -> str:
    """默认持久化位置：FeelUOwn data 目录下的 custom_media.json。"""
    try:
        from feeluown.consts import DATA_DIR
    except Exception:  # noqa: BLE001 - 单测等非 FeelUOwn 环境的退路
        DATA_DIR = os.path.join(os.path.expanduser("~"), ".FeelUOwn", "data")
    return os.path.join(DATA_DIR, "fuo_dl", "custom_media.json")


class CustomMediaStore:
    """极简 JSON 列表存储。文件损坏时按空列表处理，不抛异常。"""

    def __init__(self, path: Optional[str] = None):
        self._path = path or default_store_path()
        self._items: List[CustomMedia] = []
        self._loaded = False

    def _ensure_loaded(self) -> None:
        if not self._loaded:
            self.load()

    def load(self) -> None:
        self._loaded = True
        try:
            with open(self._path, encoding="utf-8") as handle:
                raw = json.load(handle)
        except FileNotFoundError:
            return
        except Exception:  # noqa: BLE001 - 文件损坏不能阻断启动
            logger.warning("自定义音源文件解析失败，按空列表处理：%s", self._path,
                           exc_info=True)
            return

        items = []
        for entry in raw if isinstance(raw, list) else []:
            if not isinstance(entry, dict):
                continue
            url = entry.get("url")
            if not isinstance(url, str) or not url:
                continue
            title = entry.get("title")
            items.append(
                CustomMedia(
                    url=url,
                    title=title if isinstance(title, str) and title
                    else guess_title(url),
                    added_at=float(entry.get("added_at") or 0.0),
                )
            )
        self._items = items

    def save(self) -> None:
        directory = os.path.dirname(self._path)
        if directory:
            os.makedirs(directory, exist_ok=True)
        temp_path = self._path + ".tmp"
        with open(temp_path, "w", encoding="utf-8") as handle:
            json.dump(
                [asdict(item) for item in self._items],
                handle,
                ensure_ascii=False,
                indent=2,
            )
        os.replace(temp_path, self._path)

    def list(self) -> List[CustomMedia]:
        self._ensure_loaded()
        return list(self._items)

    def add(self, url: str, title: str = "") -> CustomMedia:
        """新增一条；同地址会覆盖旧标题并挪到最前面。"""
        self._ensure_loaded()
        entry = CustomMedia(
            url=url, title=title or guess_title(url), added_at=time.time()
        )
        self._items = [item for item in self._items if item.url != url]
        self._items.insert(0, entry)
        self.save()
        return entry

    def remove(self, url: str) -> bool:
        self._ensure_loaded()
        remaining = [item for item in self._items if item.url != url]
        if len(remaining) == len(self._items):
            return False
        self._items = remaining
        self.save()
        return True

    def seed_defaults(self) -> None:
        """配置文件不存在时写入内置测试音源。

        只在文件缺失时写一次：用户删掉的条目不会在下次启动时复活。
        """
        if os.path.exists(self._path):
            return
        self._loaded = True
        now = time.time()
        self._items = [
            CustomMedia(url=url, title=title, added_at=now)
            for url, title in DEFAULT_ENTRIES
        ]
        self.save()


_store: Optional[CustomMediaStore] = None


def get_store() -> CustomMediaStore:
    """进程级单例，避免每次弹框都读一遍文件。"""
    global _store
    if _store is None:
        _store = CustomMediaStore()
        _store.seed_defaults()
    return _store


def _notify(app, message: str) -> None:
    fn = getattr(app, "show_msg", None)
    if fn is None:
        return
    try:
        fn(message)
    except Exception:  # noqa: BLE001
        logger.debug("show_msg 调用失败", exc_info=True)


def play_entry(app, entry: CustomMedia) -> None:
    """直接交给播放器播放这条地址，并在 Media 上留标记给下载按钮识别。"""
    from feeluown.media import Media

    media = Media(entry.url)
    # ui._on_media_changed / _download_cur_media 靠这个属性区分自定义音源
    media.custom_entry = entry
    app.player.play(media)
    _notify(app, "正在播放自定义音源：%s" % entry.title)


def add_and_play(app) -> Optional[CustomMedia]:
    """弹框输入新地址 → 保存 → 播放。"""
    from PyQt6.QtWidgets import QApplication, QInputDialog, QMessageBox

    parent = QApplication.activeWindow()
    text, ok = QInputDialog.getText(
        parent,
        "添加音频地址",
        "粘贴音频直链（http/https）或本地文件路径：",
    )
    if not ok:
        return None
    try:
        url = normalize_url(text)
    except ValueError as exc:
        QMessageBox.warning(parent, "地址无效", str(exc))
        return None

    entry = get_store().add(url)
    play_entry(app, entry)
    return entry


def remove_saved() -> Optional[CustomMedia]:
    """从已保存地址里挑一条删除。"""
    from PyQt6.QtWidgets import QApplication, QInputDialog

    items = get_store().list()
    if not items:
        return None
    labels = ["%s  |  %s" % (item.title, item.url) for item in items]
    chosen, ok = QInputDialog.getItem(
        QApplication.activeWindow(), "删除播放地址", "选择要删除的地址：",
        labels, 0, False,
    )
    if not ok:
        return None
    for index, label in enumerate(labels):
        if label == chosen:
            get_store().remove(items[index].url)
            return items[index]
    return None


def play_custom_media(app) -> Optional[CustomMedia]:
    """右键菜单入口：选已保存地址播放，或新增 / 删除。"""
    from PyQt6.QtWidgets import QApplication, QInputDialog

    items = get_store().list()
    labels = ["%s  |  %s" % (item.title, item.url) for item in items]
    labels.append(ADD_LABEL)
    if items:
        labels.append(REMOVE_LABEL)

    chosen, ok = QInputDialog.getItem(
        QApplication.activeWindow(), "自定义播放地址",
        "选择已保存的地址，或新增一个：", labels, 0, False,
    )
    if not ok:
        return None
    if chosen == ADD_LABEL:
        return add_and_play(app)
    if chosen == REMOVE_LABEL:
        remove_saved()
        return None
    for index, label in enumerate(labels):
        if label == chosen and index < len(items):
            play_entry(app, items[index])
            return items[index]
    return None


def add_menu_action(app, ctx) -> None:
    """给歌曲右键菜单挂上入口。"""
    ctx["add_action"](
        "播放自定义音频地址…",
        lambda _models=None: play_custom_media(app),
    )


__all__ = [
    "ADD_LABEL",
    "ALLOWED_SCHEMES",
    "DEFAULT_ENTRIES",
    "CustomMedia",
    "CustomMediaStore",
    "REMOVE_LABEL",
    "add_and_play",
    "add_menu_action",
    "default_store_path",
    "get_store",
    "guess_title",
    "normalize_url",
    "play_custom_media",
    "play_entry",
    "remove_saved",
]
