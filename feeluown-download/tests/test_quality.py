# -*- coding: utf-8 -*-
"""下载音质选择的单元测试。

本机没有安装 PyQt6 / feeluown，因此这里用最小替身注入 ``feeluown.media``
与 ``feeluown.utils.aio``，再按文件路径加载真实的 ``fuo_dl/quality.py``。
被测逻辑是生产代码本身，弹窗通过 ask_fn 注入，不需要真实 Qt 事件循环。
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
QUALITY_PY = ROOT / "fuo_dl" / "quality.py"
MEDIA_PY = ROOT.parent / "FeelUOwn" / "feeluown" / "media.py"
if not MEDIA_PY.exists():
    MEDIA_PY = Path("E:/mypro/FeelUOwn/feeluown/media.py")


def _load_real_quality():
    """从 FeelUOwn 核心源码加载真实的 Quality（media.py 只依赖标准库）。"""
    spec = importlib.util.spec_from_file_location("_fu_media_for_test", MEDIA_PY)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.Quality


def _load_quality_module():
    Quality = _load_real_quality()

    feeluown = types.ModuleType("feeluown")
    feeluown.__path__ = []
    media = types.ModuleType("feeluown.media")
    media.Quality = Quality
    utils = types.ModuleType("feeluown.utils")
    aio = types.ModuleType("feeluown.utils.aio")

    async def run_fn(fn, *args, **kwargs):
        return fn(*args, **kwargs)

    aio.run_fn = run_fn
    utils.aio = aio

    patched = {
        "feeluown": feeluown,
        "feeluown.media": media,
        "feeluown.utils": utils,
        "feeluown.utils.aio": aio,
    }
    with mock.patch.dict(sys.modules, patched):
        spec = importlib.util.spec_from_file_location(
            "fuo_dl_quality_under_test", QUALITY_PY
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    module.Quality = Quality
    return module


Q = _load_quality_module()
Quality = Q.Quality


class FakeProvider:
    def __init__(self, qualities, medias=None, raise_on_get=False,
                 raise_on_list=False, async_get=False):
        self.qualities = qualities
        self.medias = medias or {}
        self.raise_on_get = raise_on_get
        self.raise_on_list = raise_on_list
        self.async_get = async_get
        self.get_calls = []

    def song_list_quality(self, song):
        if self.raise_on_list:
            raise RuntimeError("network down")
        return self.qualities

    def song_get_media(self, song, quality):
        self.get_calls.append(quality)
        if self.raise_on_get:
            raise RuntimeError("VIP required")
        media = self.medias.get(quality, "MEDIA-FOR-" + quality.value)
        if self.async_get:
            async def _later():
                return media
            return _later()
        return media


class FakeLibrary:
    def __init__(self, provider, default_media="MEDIA-FROM-POLICY"):
        self.provider = provider
        self.default_media = default_media
        self.prepare_calls = []

    def get(self, source):
        return self.provider

    def song_prepare_media(self, song, policy):
        self.prepare_calls.append(policy)
        return self.default_media


class FakeApp:
    def __init__(self, provider=None, ask_quality=True):
        self.library = FakeLibrary(provider)
        self.playlist = SimpleNamespace(audio_select_policy="hq<>")
        self.config = SimpleNamespace(dl=SimpleNamespace(ASK_QUALITY=ask_quality))
        self.messages = []

    def show_msg(self, msg):
        self.messages.append(msg)


SONG = SimpleNamespace(source="netease", title_display="测试歌曲")


class SortAndItemsTest(unittest.TestCase):
    def test_sort_high_to_low(self):
        result = Q.sort_qualities(["lq", "sq", "hq", "shq"])
        self.assertEqual([x.value for x in result], ["shq", "hq", "sq", "lq"])

    def test_sort_dedup_and_ignore_unknown(self):
        result = Q.sort_qualities(
            ["lq", Quality.Audio.shq, "SHQ", "flac", None, 123]
        )
        self.assertEqual([x.value for x in result], ["shq", "lq"])

    def test_items_high_quality_first_and_default_last(self):
        items = Q.build_items(["sq", "hq", "shq"])
        self.assertEqual(len(items), 4)
        self.assertIn("超高品质", items[0][0])
        self.assertEqual(items[0][1], Quality.Audio.shq)
        self.assertEqual([x[1].value for x in items[:3]], ["shq", "hq", "sq"])
        self.assertEqual(items[-1][0], Q.DEFAULT_LABEL)
        self.assertIsNone(items[-1][1])

    def test_parse_choice_roundtrip(self):
        items = Q.build_items(["hq", "shq"])
        self.assertEqual(Q.parse_choice(items[0][0], items), Quality.Audio.shq)
        self.assertEqual(Q.parse_choice(items[1][0], items), Quality.Audio.hq)
        self.assertIsNone(Q.parse_choice(Q.DEFAULT_LABEL, items))
        self.assertIsNone(Q.parse_choice("unknown", items))

    def test_fallback_policy_starts_with_selected_quality(self):
        for value in ("shq", "hq", "sq", "lq"):
            policy = f"{value}<>"
            ordered = Quality.SortPolicy.apply(
                policy, [x.value for x in Quality.Audio]
            )
            self.assertEqual(ordered[0], value, policy)


class ListQualitiesTest(unittest.TestCase):
    def test_ok(self):
        app = FakeApp(FakeProvider(["lq", "hq", "shq"]))
        self.assertEqual(
            [x.value for x in Q.list_available_qualities(app, SONG)],
            ["shq", "hq", "lq"],
        )

    def test_provider_without_multi_quality(self):
        app = FakeApp(object())
        self.assertEqual(Q.list_available_qualities(app, SONG), [])

    def test_provider_raises(self):
        app = FakeApp(FakeProvider([], raise_on_list=True))
        self.assertEqual(Q.list_available_qualities(app, SONG), [])

    def test_missing_provider(self):
        app = FakeApp(None)
        self.assertEqual(Q.list_available_qualities(app, SONG), [])


class PrepareMediaTest(unittest.TestCase):
    def test_default_uses_playlist_policy(self):
        app = FakeApp(FakeProvider(["hq"]))
        media = Q.prepare_media(app, SONG, None)
        self.assertEqual(media, "MEDIA-FROM-POLICY")
        self.assertEqual(app.library.prepare_calls, ["hq<>"])

    def test_selected_quality_uses_provider_get_media(self):
        provider = FakeProvider(["shq", "hq"])
        app = FakeApp(provider)
        media = Q.prepare_media(app, SONG, Quality.Audio.shq)
        self.assertEqual(media, "MEDIA-FOR-shq")
        self.assertEqual(provider.get_calls, [Quality.Audio.shq])
        self.assertEqual(app.library.prepare_calls, [])

    def test_fallback_when_quality_not_available(self):
        provider = FakeProvider(["hq"], medias={Quality.Audio.shq: None})
        app = FakeApp(provider)
        media = Q.prepare_media(app, SONG, Quality.Audio.shq)
        self.assertEqual(media, "MEDIA-FROM-POLICY")
        self.assertEqual(app.library.prepare_calls, ["shq<>"])

    def test_fallback_when_get_media_raises(self):
        provider = FakeProvider(["hq"], raise_on_get=True)
        app = FakeApp(provider)
        self.assertEqual(
            Q.prepare_media(app, SONG, Quality.Audio.hq),
            "MEDIA-FROM-POLICY",
        )
        self.assertEqual(app.library.prepare_calls, ["hq<>"])

    def test_fallback_when_get_media_returns_coroutine(self):
        provider = FakeProvider(["hq"], async_get=True)
        app = FakeApp(provider)
        self.assertEqual(
            Q.prepare_media(app, SONG, Quality.Audio.hq),
            "MEDIA-FROM-POLICY",
        )
        self.assertEqual(app.library.prepare_calls, ["hq<>"])

    def test_fallback_policy_failure_uses_playlist_policy(self):
        provider = FakeProvider(["hq"], medias={Quality.Audio.shq: None})
        app = FakeApp(provider)
        calls = []

        def prepare(song, policy):
            calls.append(policy)
            if policy == "shq<>":
                raise RuntimeError("no standby")
            return "MEDIA-FROM-POLICY"

        app.library.song_prepare_media = prepare
        self.assertEqual(
            Q.prepare_media(app, SONG, Quality.Audio.shq),
            "MEDIA-FROM-POLICY",
        )
        self.assertEqual(calls, ["shq<>", "hq<>"])


class ChooseQualityTest(unittest.TestCase):
    def test_ask_disabled_does_not_pop_dialog(self):
        app = FakeApp(FakeProvider(["shq", "hq"]), ask_quality=False)
        asked = []

        def ask(items, song_title=""):
            asked.append(items)
            return items[0][0]

        cancelled, quality = asyncio.run(Q.choose_quality(app, [SONG], ask_fn=ask))
        self.assertFalse(cancelled)
        self.assertIsNone(quality)
        self.assertEqual(asked, [])
        self.assertEqual(app.library.prepare_calls, [])

    def test_dialog_items_are_high_to_low(self):
        app = FakeApp(FakeProvider(["lq", "sq", "hq", "shq"]))
        captured = {}

        def ask(items, song_title=""):
            captured["items"] = items
            captured["title"] = song_title
            return items[0][0]

        cancelled, quality = asyncio.run(Q.choose_quality(app, [SONG], ask_fn=ask))
        self.assertFalse(cancelled)
        self.assertEqual(quality, Quality.Audio.shq)
        self.assertEqual(captured["title"], "测试歌曲")
        self.assertEqual(
            [x[1].value for x in captured["items"][:4]],
            ["shq", "hq", "sq", "lq"],
        )
        self.assertTrue(app.messages)  # 有「正在获取可选下载音质」提示

    def test_choose_lower_quality(self):
        app = FakeApp(FakeProvider(["shq", "hq", "lq"]))
        cancelled, quality = asyncio.run(
            Q.choose_quality(
                app, [SONG], ask_fn=lambda items, song_title="": items[2][0]
            )
        )
        self.assertFalse(cancelled)
        self.assertEqual(quality, Quality.Audio.lq)

    def test_choose_default_item(self):
        app = FakeApp(FakeProvider(["shq"]))
        cancelled, quality = asyncio.run(
            Q.choose_quality(
                app, [SONG], ask_fn=lambda items, song_title="": Q.DEFAULT_LABEL
            )
        )
        self.assertFalse(cancelled)
        self.assertIsNone(quality)

    def test_cancel(self):
        app = FakeApp(FakeProvider(["shq", "hq"]))
        cancelled, quality = asyncio.run(
            Q.choose_quality(
                app, [SONG], ask_fn=lambda items, song_title="": Q.CANCELLED
            )
        )
        self.assertTrue(cancelled)
        self.assertIsNone(quality)

    def test_no_available_quality_does_not_pop_dialog(self):
        app = FakeApp(FakeProvider([]))
        asked = []
        cancelled, quality = asyncio.run(
            Q.choose_quality(
                app, [SONG],
                ask_fn=lambda items, song_title="": asked.append(items) or items[0][0],
            )
        )
        self.assertFalse(cancelled)
        self.assertIsNone(quality)
        self.assertEqual(asked, [])

    def test_dialog_failure_falls_back(self):
        app = FakeApp(FakeProvider(["shq", "hq"]))

        def broken(items, song_title=""):
            raise RuntimeError("no display")

        cancelled, quality = asyncio.run(
            Q.choose_quality(app, [SONG], ask_fn=broken)
        )
        self.assertFalse(cancelled)
        self.assertIsNone(quality)

    def test_empty_songs_cancels(self):
        app = FakeApp(FakeProvider(["shq"]))
        cancelled, quality = asyncio.run(Q.choose_quality(app, []))
        self.assertTrue(cancelled)
        self.assertIsNone(quality)

    def test_config_namespace_fuo_dl(self):
        app = FakeApp(FakeProvider(["shq"]), ask_quality=False)
        app.config = SimpleNamespace(fuo_dl=SimpleNamespace(ASK_QUALITY=False))
        cancelled, quality = asyncio.run(Q.choose_quality(app, [SONG]))
        self.assertFalse(cancelled)
        self.assertIsNone(quality)


class WiringContractTest(unittest.TestCase):
    """保证 V1/V2 的入口确实接到了选档逻辑，避免只测工具函数。"""

    def _read(self, relative):
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_v1_ui_wiring(self):
        source = self._read("fuo_dl/ui.py")
        self.assertIn("from .quality import choose_quality, prepare_media", source)
        self.assertIn("async def _download_songs(self, songs)", source)
        self.assertIn("await choose_quality(self._app, songs)", source)
        self.assertIn("self.download_song(song, quality=quality)", source)
        self.assertIn("prepare_media,", source)
        self.assertNotIn(
            "self._app.library.song_prepare_media,", source,
            "V1 不应再直接按播放策略取流",
        )

    def test_v2_wiring(self):
        manager = self._read("fuo_dl/v2/manager.py")
        self.assertIn("from fuo_dl.quality import choose_quality, prepare_media", manager)
        self.assertIn("async def download_many(self, songs)", manager)
        self.assertIn("await choose_quality(self._app, songs)", manager)
        self.assertIn("prepare_media,", manager)
        ui = self._read("fuo_dl/v2/ui.py")
        self.assertIn("download_many", ui)
        self.assertNotIn("self.download_mgr.download, model", ui)

    def test_config_field(self):
        self.assertIn('"ASK_QUALITY"', self._read("fuo_dl/__init__.py"))


if __name__ == "__main__":
    unittest.main()
