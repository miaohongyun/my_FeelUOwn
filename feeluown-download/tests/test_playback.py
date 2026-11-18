# -*- coding: utf-8 -*-
"""播放桥的单元测试（不依赖 FeelUOwn / PyQt6 / 网络 / ffmpeg）。

被测对象是生产代码 ``fuo_dl/playback.py``：用替身注入 ``feeluown.media`` 与
``feeluown.utils.aio``，检索和下载函数通过构造参数注入，避免真实网络请求。
"""
from __future__ import annotations

import importlib.util
import os
import sys
import tempfile
import threading
import time
import types
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
MEDIA_PY = ROOT.parent / "FeelUOwn" / "feeluown" / "media.py"
if not MEDIA_PY.exists():
    MEDIA_PY = Path("E:/mypro/FeelUOwn/feeluown/media.py")


def _install_feeluown_stubs():
    """fallback 需要 feeluown.utils.aio；缓存命中时要用真实的 Media。"""
    if getattr(sys.modules.get("feeluown"), "_fuo_dl_stub", False):
        return
    feeluown = types.ModuleType("feeluown")
    feeluown.__path__ = []
    feeluown._fuo_dl_stub = True

    utils = types.ModuleType("feeluown.utils")
    utils.__path__ = []
    aio = types.ModuleType("feeluown.utils.aio")

    async def run_fn(fn, *args, **kwargs):
        return fn(*args, **kwargs)

    aio.run_fn = run_fn
    utils.aio = aio

    spec = importlib.util.spec_from_file_location("feeluown.media", MEDIA_PY)
    media = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(media)

    sys.modules["feeluown"] = feeluown
    sys.modules["feeluown.utils"] = utils
    sys.modules["feeluown.utils.aio"] = aio
    sys.modules["feeluown.media"] = media


def _load(name):
    """按路径加载 fuo_dl 下的模块，复用已注册的同名模块。"""
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
    try:
        spec.loader.exec_module(module)
    except Exception:
        sys.modules.pop(full_name, None)
        raise
    return module


_install_feeluown_stubs()
PB = _load("playback")


class FakeLibrary:
    """只实现桥需要的两个行为：取流与还原。"""

    def __init__(self, media):
        self.media = media
        self.calls = []

    def song_prepare_media(self, song, policy=None):
        self.calls.append(policy)
        if isinstance(self.media, Exception):
            raise self.media
        return self.media


def make_app(media, enabled=True, jamendo_id=""):
    config = SimpleNamespace(
        PLAY_OPEN_SOURCE_FALLBACK=enabled,
        JAMENDO_CLIENT_ID=jamendo_id,
    )
    return SimpleNamespace(
        library=FakeLibrary(media),
        config=SimpleNamespace(dl=config),
        show_msg=lambda message: None,
    )


def make_song(identifier="song-1", title="Aria", source="netease"):
    return SimpleNamespace(
        identifier=identifier,
        source=source,
        title=title,
        title_display=title,
        artists_name="Artist",
    )


def lossy_media(url="http://example.com/song.mp3"):
    return SimpleNamespace(url=url, props=SimpleNamespace(format="mp3"))


def lossless_media(url="http://example.com/song.flac"):
    return SimpleNamespace(url=url, props=SimpleNamespace(format="flac"))


def make_track(source="archive", title="Aria", artist="Artist"):
    return SimpleNamespace(
        source=source, title=title, artist=artist, url="http://example.com/a.flac",
        describe=lambda: "%s - %s" % (artist, title),
    )


class PickBestTest(unittest.TestCase):
    def test_empty(self):
        self.assertIsNone(PB.pick_best([], "Aria"))

    def test_prefers_archive_over_jamendo(self):
        tracks = [make_track("jamendo"), make_track("archive")]
        self.assertIs(PB.pick_best(tracks, "Aria"), tracks[1])

    def test_prefers_closest_title_length(self):
        long_track = make_track("archive", title="Aria (Live at Home)")
        exact = make_track("archive", title="Aria")
        self.assertIs(PB.pick_best([long_track, exact], "Aria"), exact)


class InstallTest(unittest.TestCase):
    def test_install_and_uninstall(self):
        library = FakeLibrary(lossless_media())
        app = make_app(lossless_media())
        app.library = library
        original = library.song_prepare_media

        bridge = PB.PlaybackBridge(app)
        bridge.install()
        self.assertIsNot(library.song_prepare_media, original)
        # 下载流程依赖这个属性绕过播放桥
        self.assertEqual(
            library.song_prepare_media._fuo_dl_original, original
        )
        # 重复安装不应叠加包装
        bridge.install()
        self.assertEqual(library.song_prepare_media._fuo_dl_original, original)

        bridge.uninstall()
        self.assertEqual(library.song_prepare_media, original)

    def test_uninstall_without_install_is_safe(self):
        app = make_app(lossless_media())
        PB.PlaybackBridge(app).uninstall()


class PrepareMediaTest(unittest.TestCase):
    def test_lossless_source_is_returned_as_is(self):
        media = lossless_media()
        searched = []
        bridge = PB.PlaybackBridge(
            make_app(media),
            search_fn=lambda *a, **kw: searched.append(a) or [],
        )
        bridge.install()

        self.assertIs(bridge.prepare_media(make_song(), "hq<>"), media)
        self.assertEqual(searched, [])
        self.assertEqual(bridge._jobs, {})

    def test_lossy_source_returns_original_and_starts_worker(self):
        media = lossy_media()
        bridge = PB.PlaybackBridge(make_app(media), wait_timeout=0)
        bridge.install()
        with mock.patch.object(bridge, "_start_job", return_value=None) as start:
            self.assertIs(bridge.prepare_media(make_song(), "hq<>"), media)
        start.assert_called_once()

    def test_media_not_found_propagates(self):
        bridge = PB.PlaybackBridge(make_app(RuntimeError("not found")))
        bridge.install()
        with self.assertRaises(RuntimeError):
            bridge.prepare_media(make_song(), "hq<>")

    def test_disabled_fallback_does_not_search(self):
        bridge = PB.PlaybackBridge(make_app(lossy_media(), enabled=False))
        bridge.install()
        with mock.patch.object(bridge, "_start_job") as start:
            bridge.prepare_media(make_song(), "hq<>")
        start.assert_not_called()

    def test_cached_file_skips_original_provider(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "aria.flac")
            Path(path).write_bytes(b"flac")
            app = make_app(lossy_media())
            bridge = PB.PlaybackBridge(app, cache_path=tmp)
            bridge._cache[PB.song_key(make_song())] = path

            media = bridge.prepare_media(make_song(), "hq<>")
            self.assertEqual(media.url, path)
            self.assertEqual(media.props.format, "flac")
            self.assertEqual(app.library.calls, [])

    def test_stale_cache_entry_is_dropped(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = make_app(lossy_media())
            bridge = PB.PlaybackBridge(app, cache_path=tmp)
            bridge.install()
            key = PB.song_key(make_song())
            bridge._cache[key] = os.path.join(tmp, "gone.flac")

            with mock.patch.object(bridge, "_start_job", return_value=None):
                media = bridge.prepare_media(make_song(), "hq<>")
            self.assertEqual(media.url, "http://example.com/song.mp3")
            self.assertNotIn(key, bridge._cache)


class CacheIndexTest(unittest.TestCase):
    def test_index_survives_restart(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "aria.flac")
            Path(path).write_bytes(b"flac")
            app = make_app(lossy_media())
            first = PB.PlaybackBridge(app, cache_path=tmp)
            key = PB.song_key(make_song())
            first._cache[key] = path
            first._save_index()

            second = PB.PlaybackBridge(app, cache_path=tmp)
            self.assertEqual(second._cache[key], path)
            media = second.prepare_media(make_song(), "hq<>")
            self.assertEqual(media.url, path)

    def test_stale_entries_are_pruned_on_load(self):
        with tempfile.TemporaryDirectory() as tmp:
            index_path = os.path.join(tmp, PB.INDEX_NAME)
            Path(index_path).write_text(
                '{"netease|gone": "%s"}' % os.path.join(tmp, "gone.flac"),
                encoding="utf-8",
            )
            bridge = PB.PlaybackBridge(make_app(lossy_media()), cache_path=tmp)
            self.assertEqual(bridge._cache, {})

    def test_corrupt_index_is_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(os.path.join(tmp, PB.INDEX_NAME)).write_text(
                "not json", encoding="utf-8"
            )
            bridge = PB.PlaybackBridge(make_app(lossy_media()), cache_path=tmp)
            self.assertEqual(bridge._cache, {})


class BackgroundTest(unittest.TestCase):
    def _bridge(self, media, search_result, download_result):
        app = make_app(media)
        searched = []
        downloaded = []

        def search_fn(song, jamendo_id, limit, timeout):
            searched.append((song, jamendo_id, limit, timeout))
            return search_result

        def download_fn(track, dest_dir):
            downloaded.append((track, dest_dir))
            return download_result

        bridge = PB.PlaybackBridge(
            app, cache_path=tempfile.gettempdir(),
            search_fn=search_fn, download_fn=download_fn,
        )
        return app, bridge, searched, downloaded

    def test_worker_caches_verified_track(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "aria.flac")
            Path(path).write_bytes(b"flac")
            track = make_track()
            download_result = SimpleNamespace(
                path=path,
                result=SimpleNamespace(verdict="lossless", ok=True),
            )
            app, bridge, searched, downloaded = self._bridge(
                lossy_media(), [track], download_result
            )
            bridge._cache_path = tmp
            song = make_song()
            bridge._prepare_worker(song, PB.song_key(song))

            self.assertEqual(len(searched), 1)
            # 播放链路用更短的检索超时，不阻塞点播放
            self.assertEqual(searched[0][3], PB.DEFAULT_SEARCH_TIMEOUT)
            self.assertEqual(downloaded[0][1], tmp)
            self.assertEqual(bridge._cache[PB.song_key(song)], path)

    def test_worker_discards_fake_lossless(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "fake.flac")
            Path(path).write_bytes(b"flac")
            download_result = SimpleNamespace(
                path=path,
                result=SimpleNamespace(verdict="fake_lossless", ok=False),
            )
            _, bridge, _, _ = self._bridge(lossy_media(), [make_track()],
                                           download_result)
            bridge._cache_path = tmp
            song = make_song()
            bridge._prepare_worker(song, PB.song_key(song))

            self.assertNotIn(PB.song_key(song), bridge._cache)
            self.assertFalse(os.path.exists(path))

    def test_worker_without_match_does_nothing(self):
        _, bridge, _, downloaded = self._bridge(lossy_media(), [], None)
        song = make_song()
        bridge._prepare_worker(song, PB.song_key(song))
        self.assertEqual(downloaded, [])
        self.assertEqual(bridge._jobs, {})

    def test_background_thread_is_started_once_per_song(self):
        class FakeThread:
            created = []

            def __init__(self, target=None, args=(), name="", daemon=False):
                self.target = target
                self.args = args
                self.daemon = daemon
                FakeThread.created.append(self)

            def start(self):
                pass

        bridge = PB.PlaybackBridge(make_app(lossy_media()))
        song = make_song()
        with mock.patch.object(PB.threading, "Thread", FakeThread):
            bridge._start_job(song, PB.song_key(song))
            bridge._start_job(song, PB.song_key(song))

        self.assertEqual(len(FakeThread.created), 1)
        self.assertEqual(
            FakeThread.created[0].args[:2], (song, PB.song_key(song))
        )
        self.assertTrue(FakeThread.created[0].daemon)

    def test_song_without_identifier_skips_background(self):
        class FakeThread:
            created = []

            def __init__(self, **kwargs):
                FakeThread.created.append(kwargs)

            def start(self):
                pass

        bridge = PB.PlaybackBridge(make_app(lossy_media()))
        with mock.patch.object(PB.threading, "Thread", FakeThread):
            bridge._start_job(
                SimpleNamespace(identifier="", source="x"), ("x", "")
            )
        self.assertEqual(FakeThread.created, [])


class FirstPlayTest(unittest.TestCase):
    """第一次播放就返回无损文件的同步等待逻辑。"""

    def _make_bridge(self, tmp, wait_timeout, gate=None):
        path = os.path.join(tmp, "aria.flac")
        Path(path).write_bytes(b"flac")

        def search_fn(song, jamendo_id, limit, timeout):
            return [make_track()]

        def download_fn(track, dest_dir):
            if gate is not None:
                gate.wait(5)
            return SimpleNamespace(
                path=path,
                result=SimpleNamespace(verdict="lossless", ok=True),
            )

        app = make_app(lossy_media())
        bridge = PB.PlaybackBridge(
            app,
            cache_path=tmp,
            wait_timeout=wait_timeout,
            search_fn=search_fn,
            download_fn=download_fn,
        )
        bridge.install()
        return bridge, path

    def test_first_play_returns_lossless_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            bridge, path = self._make_bridge(tmp, wait_timeout=5)
            media = bridge.prepare_media(make_song(), "hq<>")
            self.assertEqual(media.url, path)
            self.assertEqual(media.props.format, "flac")
            self.assertEqual(bridge._cache[PB.song_key(make_song())], path)

    def test_timeout_falls_back_then_cache_is_ready(self):
        with tempfile.TemporaryDirectory() as tmp:
            gate = threading.Event()
            bridge, path = self._make_bridge(
                tmp, wait_timeout=0.05, gate=gate
            )
            media = bridge.prepare_media(make_song(), "hq<>")
            self.assertEqual(media.url, "http://example.com/song.mp3")

            gate.set()
            key = PB.song_key(make_song())
            deadline = time.time() + 3
            while time.time() < deadline and key not in bridge._cache:
                time.sleep(0.01)

            self.assertEqual(bridge._cache.get(key), path)
            self.assertEqual(
                bridge.prepare_media(make_song(), "hq<>").url, path
            )

    def test_zero_wait_returns_original_immediately(self):
        with tempfile.TemporaryDirectory() as tmp:
            gate = threading.Event()
            bridge, _ = self._make_bridge(tmp, wait_timeout=0, gate=gate)
            try:
                media = bridge.prepare_media(make_song(), "hq<>")
                self.assertEqual(media.url, "http://example.com/song.mp3")
            finally:
                gate.set()

    def test_config_overrides_wait_timeout(self):
        with tempfile.TemporaryDirectory() as tmp:
            bridge, _ = self._make_bridge(tmp, wait_timeout=30)
            bridge._app.config.dl.PLAY_WAIT_TIMEOUT = 0
            self.assertEqual(bridge._wait_timeout_value(), 0.0)
            bridge._app.config.dl.PLAY_WAIT_TIMEOUT = "bad"
            self.assertEqual(bridge._wait_timeout_value(), 30.0)


class PrefetchTest(unittest.TestCase):
    """下一首预取：当前歌一开始播，就在后台准备下一首。"""

    def _bridge(self, media, search_result, download_result, tmp):
        app = make_app(media)
        searched = []
        downloaded = []

        def search_fn(song, jamendo_id, limit, timeout):
            searched.append(song)
            return search_result

        def download_fn(track, dest_dir):
            downloaded.append(track)
            return download_result

        bridge = PB.PlaybackBridge(
            app, cache_path=tmp,
            search_fn=search_fn, download_fn=download_fn,
        )
        bridge.install()
        return bridge, searched, downloaded

    def test_prefetch_next_song_caches_lossless(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "next.flac")
            Path(path).write_bytes(b"flac")
            download_result = SimpleNamespace(
                path=path,
                result=SimpleNamespace(verdict="lossless", ok=True),
            )
            bridge, searched, downloaded = self._bridge(
                lossy_media(), [make_track()], download_result, tmp
            )
            next_song = make_song("song-2", "Next")
            bridge._playlist = SimpleNamespace(next_song=next_song)

            bridge._prefetch_next(make_song())
            key = PB.song_key(next_song)
            deadline = time.time() + 3
            while time.time() < deadline and key not in bridge._cache:
                time.sleep(0.01)

            self.assertEqual(len(searched), 1)
            self.assertEqual(len(downloaded), 1)
            self.assertEqual(bridge._cache.get(key), path)

    def test_prefetch_skips_when_next_is_same_song(self):
        with tempfile.TemporaryDirectory() as tmp:
            bridge, searched, _ = self._bridge(
                lossy_media(), [make_track()], None, tmp
            )
            song = make_song()
            # 单曲循环时 next_song 就是当前歌
            bridge._playlist = SimpleNamespace(next_song=song)
            bridge._prefetch_next(song)
            self.assertEqual(searched, [])

    def test_prefetch_skips_when_source_is_lossless(self):
        with tempfile.TemporaryDirectory() as tmp:
            bridge, searched, downloaded = self._bridge(
                lossless_media(), [make_track()],
                SimpleNamespace(path="", result=None), tmp
            )
            song = make_song("song-2", "Next")
            key = PB.song_key(song)
            bridge._prefetch_worker(song, key, PB._Job())

            self.assertEqual(searched, [])
            self.assertEqual(downloaded, [])

    def test_prefetch_worker_cleans_job(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "next.flac")
            Path(path).write_bytes(b"flac")
            bridge, _, _ = self._bridge(
                lossy_media(), [make_track()],
                SimpleNamespace(
                    path=path,
                    result=SimpleNamespace(verdict="lossless", ok=True),
                ),
                tmp,
            )
            song = make_song("song-2", "Next")
            key = PB.song_key(song)
            job = PB._Job()
            bridge._jobs[key] = job
            bridge._prefetch_worker(song, key, job)

            self.assertEqual(job.path, path)
            self.assertNotIn(key, bridge._jobs)
            self.assertTrue(job.done.is_set())

    def test_prefetch_disabled_does_not_start_thread(self):
        class FakeThread:
            created = []

            def __init__(self, **kwargs):
                FakeThread.created.append(kwargs)

            def start(self):
                pass

        with tempfile.TemporaryDirectory() as tmp:
            bridge, _, _ = self._bridge(
                lossy_media(), [make_track()], None, tmp
            )
            bridge._app.config.dl.PLAY_PREFETCH_NEXT = False
            bridge._playlist = SimpleNamespace(next_song=make_song("song-2"))
            with mock.patch.object(PB.threading, "Thread", FakeThread):
                bridge._on_song_changed(make_song())
            self.assertEqual(FakeThread.created, [])


class WiringContractTest(unittest.TestCase):
    def test_plugin_mounts_and_unmounts_bridge(self):
        source = (ROOT / "fuo_dl" / "__init__.py").read_text(encoding="utf-8")
        self.assertIn("install_playback(app)", source)
        self.assertIn("playback_bridge.uninstall()", source)


if __name__ == "__main__":
    unittest.main()
