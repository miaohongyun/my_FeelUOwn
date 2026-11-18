# -*- coding: utf-8 -*-
"""自定义播放地址的单元测试（不依赖 FeelUOwn / PyQt6）。

覆盖地址校验、标题猜测、JSON 持久化与播放标记；弹框流程涉及 Qt 事件循环，
这里只测不依赖 GUI 的纯逻辑。
"""
from __future__ import annotations

import importlib.util
import json
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]


def _load(name):
    full_name = "fuo_dl.%s" % name
    if full_name in sys.modules:
        return sys.modules[full_name]
    if "fuo_dl" not in sys.modules:
        package = types.ModuleType("fuo_dl")
        package.__path__ = [str(ROOT / "fuo_dl")]
        sys.modules["fuo_dl"] = package
    spec = importlib.util.spec_from_file_location(
        full_name, ROOT / "fuo_dl" / ("%s.py" % name)
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[full_name] = module
    spec.loader.exec_module(module)
    return module


CM = _load("custom_media")


def _ensure_media_module():
    """play_entry 里延迟导入 feeluown.media.Media，这里给个最小替身。"""
    if "feeluown.media" in sys.modules:
        return
    feeluown = types.ModuleType("feeluown")
    feeluown.__path__ = []
    media = types.ModuleType("feeluown.media")

    class Media:
        def __init__(self, obj, **kwargs):
            self.url = obj
            self.kwargs = kwargs

    media.Media = Media
    sys.modules["feeluown"] = feeluown
    sys.modules["feeluown.media"] = media


class NormalizeUrlTest(unittest.TestCase):
    def test_http_and_https_pass_through(self):
        self.assertEqual(
            CM.normalize_url(" https://example.com/a.flac "),
            "https://example.com/a.flac",
        )
        self.assertEqual(
            CM.normalize_url('"http://example.com/a.mp3"'),
            "http://example.com/a.mp3",
        )

    def test_empty_rejected(self):
        with self.assertRaises(ValueError):
            CM.normalize_url("   ")

    def test_unsupported_scheme_rejected(self):
        for url in ("ftp://example.com/a.mp3", "magnet:?xt=urn:btih:abc"):
            with self.assertRaises(ValueError):
                CM.normalize_url(url)

    def test_local_file_is_absolutized(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "song.flac"
            path.write_bytes(b"flac")
            self.assertEqual(CM.normalize_url(str(path)), str(path))

    def test_missing_local_file_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                CM.normalize_url(str(Path(tmp) / "missing.flac"))


class GuessTitleTest(unittest.TestCase):
    def test_url_with_query(self):
        self.assertEqual(
            CM.guess_title("https://cdn.example.com/music/Aria.flac?token=1"),
            "Aria",
        )

    def test_percent_encoded_name(self):
        self.assertEqual(
            CM.guess_title("https://example.com/%E5%A4%9C%E6%9B%B2.flac"),
            "夜曲",
        )

    def test_falls_back_to_url(self):
        self.assertEqual(CM.guess_title("https://example.com/"), "https://example.com/")


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = str(Path(self.tmp.name) / "custom_media.json")

    def tearDown(self):
        self.tmp.cleanup()

    def test_add_persists_and_reloads(self):
        store = CM.CustomMediaStore(self.path)
        store.add("https://example.com/a.flac")

        reloaded = CM.CustomMediaStore(self.path).list()
        self.assertEqual(len(reloaded), 1)
        self.assertEqual(reloaded[0].url, "https://example.com/a.flac")
        self.assertEqual(reloaded[0].title, "a")

    def test_add_same_url_moves_to_front(self):
        store = CM.CustomMediaStore(self.path)
        store.add("https://example.com/a.flac")
        store.add("https://example.com/b.flac")
        store.add("https://example.com/a.flac")

        urls = [item.url for item in store.list()]
        self.assertEqual(
            urls, ["https://example.com/a.flac", "https://example.com/b.flac"]
        )

    def test_remove(self):
        store = CM.CustomMediaStore(self.path)
        store.add("https://example.com/a.flac")
        self.assertTrue(store.remove("https://example.com/a.flac"))
        self.assertFalse(store.remove("https://example.com/a.flac"))
        self.assertEqual(store.list(), [])

    def test_corrupt_file_treated_as_empty(self):
        Path(self.path).write_text("not json", encoding="utf-8")
        self.assertEqual(CM.CustomMediaStore(self.path).list(), [])

    def test_non_dict_entries_are_ignored(self):
        Path(self.path).write_text(
            json.dumps([1, {"url": ""}, {"url": "https://example.com/x.mp3"}]),
            encoding="utf-8",
        )
        items = CM.CustomMediaStore(self.path).list()
        self.assertEqual([item.url for item in items],
                         ["https://example.com/x.mp3"])


class SeedDefaultsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = str(Path(self.tmp.name) / "custom_media.json")

    def tearDown(self):
        self.tmp.cleanup()

    def test_seeds_when_file_missing(self):
        store = CM.CustomMediaStore(self.path)
        store.seed_defaults()

        items = store.list()
        self.assertEqual(len(items), len(CM.DEFAULT_ENTRIES))
        self.assertTrue(os.path.isfile(self.path))
        self.assertIn("flac", items[0].url.lower())

    def test_does_not_resurrect_deleted_entries(self):
        store = CM.CustomMediaStore(self.path)
        store.seed_defaults()
        for item in store.list():
            store.remove(item.url)

        fresh = CM.CustomMediaStore(self.path)
        fresh.seed_defaults()
        self.assertEqual(fresh.list(), [])


class PlayEntryTest(unittest.TestCase):
    def test_play_marks_media_for_download_button(self):
        _ensure_media_module()
        played = []
        messages = []
        app = SimpleNamespace(
            player=SimpleNamespace(play=played.append),
            show_msg=messages.append,
        )
        entry = CM.CustomMedia(url="https://example.com/a.flac", title="Aria")

        CM.play_entry(app, entry)

        self.assertEqual(len(played), 1)
        self.assertEqual(played[0].url, entry.url)
        # ui._download_cur_media 靠这个属性识别自定义音源
        self.assertIs(played[0].custom_entry, entry)
        self.assertIn("Aria", messages[0])


if __name__ == "__main__":
    unittest.main()
