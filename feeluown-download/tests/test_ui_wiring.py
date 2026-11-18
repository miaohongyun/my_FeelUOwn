# -*- coding: utf-8 -*-
"""V1 下载 UI 的接线测试（无真实 Qt / feeluown 环境）。

用严格替身注入 PyQt6 与 feeluown 模块，加载真实的 ``fuo_dl/ui.py``，
模拟「点击下载按钮」「右键菜单多选下载」「取消选择」，验证：
  * 只弹一次音质选择框；
  * 选中的档位确实传到 prepare_media；
  * 指定档位下载的文件名带档位后缀；
  * 取消时不会产生任何下载任务。
"""
from __future__ import annotations

import asyncio
import importlib.util
import sys
import types
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
UI_PY = ROOT / "fuo_dl" / "ui.py"
MEDIA_PY = ROOT.parent / "FeelUOwn" / "feeluown" / "media.py"
if not MEDIA_PY.exists():
    MEDIA_PY = Path("E:/mypro/FeelUOwn/feeluown/media.py")


def _real_quality():
    spec = importlib.util.spec_from_file_location("_fu_media_ui_test", MEDIA_PY)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.Quality


Quality = _real_quality()


class FakeModel:
    """真实 FeelUOwn Model 实现了 __hash__/__eq__（models.py:204），替身保持一致。"""

    def __init__(self, identifier, model_type="song", **fields):
        self.identifier = identifier
        self.meta = SimpleNamespace(model_type=model_type)
        self.source = "netease"
        self.title = fields.get("title", identifier)
        self.title_display = self.title
        self.artists_name = fields.get("artists_name", "歌手")
        for key, value in fields.items():
            setattr(self, key, value)

    def __hash__(self):
        return hash((self.identifier, self.meta.model_type))

    def __eq__(self, other):
        return (
            isinstance(other, FakeModel)
            and self.identifier == other.identifier
            and self.meta.model_type == other.meta.model_type
        )

    def __repr__(self):
        return f"<FakeModel {self.identifier}>"


class FakeSignal:
    def __init__(self):
        self.slots = []

    def connect(self, slot, **_):
        self.slots.append(slot)

    def disconnect(self):
        self.slots.clear()


class FakeButton:
    def __init__(self):
        self.clicked = FakeSignal()
        self.enabled = None
        self.shown = False

    def disconnect(self):
        pass

    def show(self):
        self.shown = True

    def setEnabled(self, value):
        self.enabled = value

    def setChecked(self, _):
        pass


class FakeLayout:
    def __init__(self):
        self.calls = []

    def addSpacing(self, value):
        self.calls.append(("spacing", value))

    def addWidget(self, widget):
        self.calls.append(("widget", widget))


class FakeManager:
    def __init__(self):
        self.download_finished = FakeSignal()
        self.got = []
        self.checked = []
        self.download_dir = "C:/Music"

    def is_file_downloaded(self, filename):
        self.checked.append(filename)
        return False

    async def get(self, media, filename):
        self.got.append(filename)
        return "C:/Music/" + filename


class UiHarness:
    def __init__(
        self,
        chosen_quality=Quality.Audio.shq,
        cancelled=False,
        lossless=True,
        fallback_track=None,
    ):
        self.tasks = []
        self.choose_calls = []
        self.prepare_calls = []
        self.fallback_calls = []
        self.fallback_downloads = []
        self.chosen_quality = chosen_quality
        self.cancelled = cancelled

        def create_task(coro):
            # 真实的 aio.create_task 是同步函数，返回一个 Task。
            self.tasks.append(coro)
            return coro

        async def run_fn(fn, *args):
            return fn(*args)

        aio = types.ModuleType("feeluown.utils.aio")
        aio.create_task = create_task
        aio.run_fn = run_fn

        async def async_run(fn):
            return fn()

        async def choose_quality(app, songs, ask_fn=None):
            self.choose_calls.append(list(songs))
            return self.cancelled, self.chosen_quality

        def prepare_media(app, song, quality=None):
            self.prepare_calls.append((song, quality))
            return SimpleNamespace(url="http://example.com/song.mp3")

        quality_mod = types.ModuleType("fuo_dl.quality")
        quality_mod.choose_quality = choose_quality
        quality_mod.prepare_media = prepare_media

        async def choose_open_source(app, song):
            self.fallback_calls.append(song)
            return fallback_track

        async def download_open_track(app, track, dest_dir):
            self.fallback_downloads.append((track, dest_dir))
            return "C:/Music/" + track.title

        fallback_mod = types.ModuleType("fuo_dl.fallback")
        fallback_mod.choose_open_source = choose_open_source
        fallback_mod.download_open_track = download_open_track
        fallback_mod.is_lossless_media = lambda media: lossless

        custom_media_mod = types.ModuleType("fuo_dl.custom_media")
        custom_media_mod.add_menu_action = lambda app, ctx: None

        feeluown = types.ModuleType("feeluown")
        feeluown.__path__ = []
        utils = types.ModuleType("feeluown.utils")
        utils.aio = aio
        media = types.ModuleType("feeluown.media")
        media.Media = object
        media.Quality = Quality
        library = types.ModuleType("feeluown.library")
        library.ModelType = SimpleNamespace(song="song")
        library.ModelState = SimpleNamespace(cant_upgrade="cant_upgrade")
        library.MediaNotFound = type("MediaNotFound", (Exception,), {})
        gui = types.ModuleType("feeluown.gui")
        gui.__path__ = []
        gui_helpers = types.ModuleType("feeluown.gui.helpers")
        gui_helpers.async_run = async_run
        widgets = types.ModuleType("feeluown.gui.widgets")
        widgets.__path__ = []
        statusline = types.ModuleType("feeluown.gui.widgets.statusline")
        statusline.StatusLineItem = lambda *a, **kw: None

        qt = types.ModuleType("PyQt6")
        qt.__path__ = []
        qtw = types.ModuleType("PyQt6.QtWidgets")
        qtw.QPushButton = object

        fuo_dl = types.ModuleType("fuo_dl")
        fuo_dl.__path__ = [str(ROOT / "fuo_dl")]
        manager_mod = types.ModuleType("fuo_dl.manager")
        manager_mod.DownloadManager = object
        helpers_mod = types.ModuleType("fuo_dl.helpers")
        helpers_mod.cook_filename = lambda title, artists, ext: "song.mp3"
        helpers_mod.guess_media_url_ext = lambda url: "mp3"
        status_mod = types.ModuleType("fuo_dl.statusline")
        status_mod.DownloadLabel = lambda *a, **kw: None
        tag_mod = types.ModuleType("fuo_dl.tag_manager")
        tag_mod.TagManager = object

        self.patched = {
            "feeluown": feeluown,
            "feeluown.utils": utils,
            "feeluown.utils.aio": aio,
            "feeluown.media": media,
            "feeluown.library": library,
            "feeluown.gui": gui,
            "feeluown.gui.helpers": gui_helpers,
            "feeluown.gui.widgets": widgets,
            "feeluown.gui.widgets.statusline": statusline,
            "PyQt6": qt,
            "PyQt6.QtWidgets": qtw,
            "fuo_dl": fuo_dl,
            "fuo_dl.manager": manager_mod,
            "fuo_dl.helpers": helpers_mod,
            "fuo_dl.statusline": status_mod,
            "fuo_dl.tag_manager": tag_mod,
            "fuo_dl.quality": quality_mod,
            "fuo_dl.fallback": fallback_mod,
            "fuo_dl.custom_media": custom_media_mod,
        }

        self.button = FakeButton()
        self.layout = FakeLayout()
        self.menu_signal = FakeSignal()
        self.media_changed = FakeSignal()
        self.actions = {}
        self.song = FakeModel("song-1", title="歌名")
        self.other_songs = [
            FakeModel(f"song-{i}", title=f"歌名{i}") for i in (2, 3)
        ]
        fake_ui = SimpleNamespace(
            pc_panel=SimpleNamespace(
                download_btn=self.button, _sub_top_layout=self.layout
            ),
            bottom_panel=SimpleNamespace(
                status_line=SimpleNamespace(add_item=lambda *a, **kw: None)
            ),
            songs_table=SimpleNamespace(about_to_show_menu=self.menu_signal),
        )
        self.app = SimpleNamespace(
            player=SimpleNamespace(
                media_changed=self.media_changed, current_media=None
            ),
            playlist=SimpleNamespace(
                current_song=self.song, audio_select_policy="hq<>"
            ),
        )
        self.mgr = FakeManager()
        self.tag_mgr = SimpleNamespace(_name_fmts=[], write_tag=lambda *a: None)

        with mock.patch.dict(sys.modules, self.patched):
            spec = importlib.util.spec_from_file_location("fuo_dl.ui", UI_PY)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
        self.module = module
        self.download_ui = module.DownloadUi(
            self.mgr, self.tag_mgr, self.app, fake_ui
        )
        self.download_ui.initialize()

    async def drain(self):
        while self.tasks:
            batch = self.tasks[:]
            del self.tasks[:]
            await asyncio.gather(*batch)

    def run(self):
        asyncio.run(self.drain())


class UiWiringTest(unittest.TestCase):
    def test_button_click_asks_once_and_uses_selected_quality(self):
        h = UiHarness()
        self.assertEqual(len(h.button.clicked.slots), 1)
        h.button.clicked.slots[0](False)  # Qt clicked(bool)
        h.run()
        self.assertEqual(len(h.choose_calls), 1)
        self.assertEqual(h.choose_calls[0], [h.song])
        self.assertEqual(len(h.prepare_calls), 1)
        self.assertEqual(h.prepare_calls[0][1], Quality.Audio.shq)
        self.assertEqual(h.mgr.got, ["song_shq.mp3"])
        self.assertTrue(h.button.shown)

    def test_multi_select_asks_once_for_all_songs(self):
        h = UiHarness(chosen_quality=Quality.Audio.hq)
        ctx = {"add_action": lambda label, fn: h.actions.__setitem__(label, fn)}
        h.menu_signal.slots[0](ctx)
        self.assertIn("下载歌曲", h.actions)
        h.actions["下载歌曲"]([h.song, h.other_songs[0], h.other_songs[1]])
        h.run()
        self.assertEqual(len(h.choose_calls), 1)
        self.assertEqual(len(h.prepare_calls), 3)
        self.assertTrue(
            all(q == Quality.Audio.hq for _, q in h.prepare_calls)
        )
        self.assertEqual(len(h.mgr.got), 3)
        self.assertTrue(all(name.endswith("_hq.mp3") for name in h.mgr.got))

    def test_cancel_does_not_download(self):
        h = UiHarness(cancelled=True)
        h.button.clicked.slots[0](False)
        h.run()
        self.assertEqual(len(h.choose_calls), 1)
        self.assertEqual(h.prepare_calls, [])
        self.assertEqual(h.mgr.got, [])

    def test_default_quality_keeps_original_filename(self):
        h = UiHarness(chosen_quality=None)
        h.button.clicked.slots[0](False)
        h.run()
        self.assertIsNone(h.prepare_calls[0][1])
        self.assertEqual(h.mgr.got, ["song.mp3"])

    def test_non_song_models_are_ignored(self):
        h = UiHarness()
        ctx = {"add_action": lambda label, fn: h.actions.__setitem__(label, fn)}
        h.menu_signal.slots[0](ctx)
        album = FakeModel("album-1", model_type="album")
        h.actions["下载歌曲"]([album])
        h.run()
        self.assertEqual(h.choose_calls, [])
        self.assertEqual(h.mgr.got, [])

    def test_lossless_source_skips_fallback(self):
        h = UiHarness(lossless=True)
        h.button.clicked.slots[0](False)
        h.run()
        self.assertEqual(h.fallback_calls, [])
        self.assertEqual(h.mgr.got, ["song_shq.mp3"])

    def test_lossy_source_uses_picked_open_track(self):
        """音源不是无损时，选中开放源曲目就直接下它，不再走原 provider。"""
        track = SimpleNamespace(title="Aria")
        h = UiHarness(lossless=False, fallback_track=track)
        h.button.clicked.slots[0](False)
        h.run()
        self.assertEqual(len(h.fallback_calls), 1)
        self.assertEqual(len(h.fallback_downloads), 1)
        self.assertEqual(h.fallback_downloads[0][1], "C:/Music")
        self.assertEqual(h.mgr.got, [])

    def test_lossy_source_skip_falls_back_to_original(self):
        """用户在弹框里跳过时，仍然按原音质下载。"""
        h = UiHarness(lossless=False, fallback_track=None)
        h.button.clicked.slots[0](False)
        h.run()
        self.assertEqual(len(h.fallback_calls), 1)
        self.assertEqual(h.mgr.got, ["song_shq.mp3"])

    def test_custom_media_downloads_current_url(self):
        """自定义播放地址下载的是地址本身，不走歌曲取流。"""
        h = UiHarness()
        entry = SimpleNamespace(title="Aria")
        h.app.player.current_media = SimpleNamespace(
            url="https://example.com/a.flac", custom_entry=entry
        )
        h.button.clicked.slots[0](False)
        h.run()
        self.assertEqual(h.prepare_calls, [])
        self.assertEqual(h.choose_calls, [])
        self.assertEqual(h.mgr.got, ["song.mp3"])


if __name__ == "__main__":
    unittest.main()
