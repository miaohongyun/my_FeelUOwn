# -*- coding: utf-8 -*-
"""真无损校验与开放音源的单元测试。

本机没有安装 feeluown / PyQt6，也不需要网络和 ffmpeg：
* ``fuo_dl/lossless.py`` 只依赖标准库，直接按路径加载；
* ffprobe / ffmpeg 的调用点全部用 mock 替身；
* 判定逻辑（``decide_verdict`` / ``_summarize``）用合成频谱覆盖。
"""
from __future__ import annotations

import asyncio
import importlib.util
import json
import sys
import types
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]


def _load_package_module(name, filename):
    """按文件路径加载模块，并登记到 sys.modules 以便相对导入生效。"""
    package = types.ModuleType("fuo_dl")
    package.__path__ = [str(ROOT / "fuo_dl")]
    sys.modules.setdefault("fuo_dl", package)

    full_name = "fuo_dl.%s" % name
    spec = importlib.util.spec_from_file_location(full_name, ROOT / "fuo_dl" / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[full_name] = module
    spec.loader.exec_module(module)
    return module


LL = _load_package_module("lossless", "lossless.py")
OS_ = _load_package_module("open_sources", "open_sources.py")


# ---------------------------------------------------------------------------
# 合成频谱：更接近真实文件的形状，避免用理想阶跃信号测出假结论
# ---------------------------------------------------------------------------


def synth_spectrum(cutoff_hz, transition_hz=200.0, floor_db=-85.0,
                   nyquist=22050.0, step=10.77):
    """构造一段平均功率谱。

    :param cutoff_hz: 高频内容结束的位置
    :param transition_hz: 从 0dB 衰减到 floor_db 需要多宽，越窄越像转码断崖
    :param floor_db: 高频噪底。真实录音通常 -80 ~ -100dB
    """
    freqs, power = [], []
    freq = 0.0
    while freq <= nyquist:
        freqs.append(freq)
        if freq <= cutoff_hz:
            db = 0.0
        elif freq <= cutoff_hz + transition_hz:
            db = floor_db * (freq - cutoff_hz) / transition_hz
        else:
            db = floor_db
        power.append(10.0 ** (db / 10.0))
        freq += step
    return freqs, power


def make_profile(**kwargs):
    """构造 AudioProfile，默认是 44.1kHz/16bit 的 FLAC。"""
    params = dict(
        path="/tmp/test.flac", codec="flac", sample_rate=44100,
        bits_per_sample=16, bit_rate=900000, duration=240.0, channels=2,
    )
    params.update(kwargs)
    return LL.AudioProfile(**params)


class CodecTest(unittest.TestCase):
    def test_lossless_codecs(self):
        for name in ("flac", "alac", "ape", "wavpack", "pcm_s24le", "dsd_lsbf"):
            self.assertTrue(LL.is_lossless_codec(name), name)

    def test_lossy_codecs(self):
        for name in ("mp3", "aac", "opus", "vorbis", "wmav2", ""):
            self.assertFalse(LL.is_lossless_codec(name), name)


class SpectrumSummarizeTest(unittest.TestCase):
    def test_natural_rolloff_is_lossless(self):
        """高频连续延伸到 ~21.5kHz，即使滚降较快也是真无损。"""
        freqs, power = synth_spectrum(21500, transition_hz=500)
        spectrum = LL._summarize(freqs, power, 22050.0)
        result = LL.decide_verdict(make_profile(), spectrum)
        self.assertEqual(result.verdict, LL.VERDICT_LOSSLESS, result.reasons)
        self.assertGreater(spectrum.cutoff_ratio, 0.94)

    def test_codec_cliff_is_fake(self):
        """20kHz 处 70dB 陡崖 = 有损转码的典型特征（高频仍有噪底，不是数字静音）。"""
        freqs, power = synth_spectrum(20000, transition_hz=50, floor_db=-70)
        spectrum = LL._summarize(freqs, power, 22050.0)
        self.assertFalse(spectrum.digital_silence_top)
        self.assertGreaterEqual(spectrum.cliff_drop_db, LL.CLIFF_DB)
        result = LL.decide_verdict(make_profile(), spectrum)
        self.assertEqual(result.verdict, LL.VERDICT_FAKE_LOSSLESS, result.reasons)

    def test_digital_silence_top_is_fake(self):
        """断崖到底后是精确的数字静音，真实录音不会有这种特征。"""
        freqs, power = synth_spectrum(20000, transition_hz=50, floor_db=-300)
        spectrum = LL._summarize(freqs, power, 22050.0)
        self.assertTrue(spectrum.digital_silence_top)
        result = LL.decide_verdict(make_profile(), spectrum)
        self.assertEqual(result.verdict, LL.VERDICT_FAKE_LOSSLESS, result.reasons)

    def test_low_cutoff_without_cliff_is_suspicious(self):
        """截止很低但滚降平缓，多半是老录音，判可疑而不是假无损。"""
        freqs, power = synth_spectrum(13000, transition_hz=4000, floor_db=-90)
        spectrum = LL._summarize(freqs, power, 22050.0)
        result = LL.decide_verdict(make_profile(), spectrum)
        self.assertEqual(result.verdict, LL.VERDICT_SUSPICIOUS, result.reasons)
        self.assertLess(spectrum.cliff_drop_db, LL.CLIFF_DB)

    def test_hi_res_192k_true(self):
        """96kHz 真 Hi-Res：能量能延伸到 40kHz 以上。"""
        freqs, power = synth_spectrum(42000, transition_hz=3000, nyquist=48000.0)
        spectrum = LL._summarize(freqs, power, 48000.0)
        result = LL.decide_verdict(
            make_profile(sample_rate=96000, bits_per_sample=24), spectrum
        )
        self.assertEqual(result.verdict, LL.VERDICT_LOSSLESS, result.reasons)

    def test_upsampled_44k_disguised_as_96k(self):
        """44.1k 有损源升频成 96k：22kHz 以上全是数字静音。"""
        freqs, power = synth_spectrum(
            20000, transition_hz=50, floor_db=-300, nyquist=48000.0
        )
        spectrum = LL._summarize(freqs, power, 48000.0)
        result = LL.decide_verdict(
            make_profile(sample_rate=96000, bits_per_sample=24), spectrum
        )
        self.assertEqual(result.verdict, LL.VERDICT_FAKE_LOSSLESS, result.reasons)

    def test_empty_spectrum(self):
        spectrum = LL._summarize([], [], 22050.0)
        self.assertEqual(spectrum.bins, 0)


class VerdictTest(unittest.TestCase):
    def test_lossy_container(self):
        result = LL.decide_verdict(make_profile(codec="mp3", bits_per_sample=0), None)
        self.assertEqual(result.verdict, LL.VERDICT_LOSSY_SOURCE)

    def test_unreadable_on_error(self):
        profile = LL.AudioProfile(path="/tmp/x.flac", error="没有音频流")
        result = LL.decide_verdict(profile, None)
        self.assertEqual(result.verdict, LL.VERDICT_UNREADABLE)
        self.assertIn("没有音频流", result.reasons)

    def test_no_spectrum_falls_back_to_container(self):
        result = LL.decide_verdict(make_profile(), None)
        self.assertEqual(result.verdict, LL.VERDICT_LOSSLESS)
        self.assertTrue(any("频谱未分析" in reason for reason in result.reasons))

    def test_fake_24bit(self):
        """声称 24bit，实测只有 16bit 有效精度。"""
        freqs, power = synth_spectrum(21500, transition_hz=500)
        spectrum = LL._summarize(freqs, power, 22050.0)
        result = LL.decide_verdict(
            make_profile(bits_per_sample=24), spectrum, effective_bits=16
        )
        self.assertTrue(result.fake_bit_depth)
        self.assertEqual(result.effective_bits, 16)

    def test_genuine_24bit(self):
        freqs, power = synth_spectrum(21500, transition_hz=500)
        spectrum = LL._summarize(freqs, power, 22050.0)
        result = LL.decide_verdict(
            make_profile(bits_per_sample=24), spectrum, effective_bits=24
        )
        self.assertFalse(result.fake_bit_depth)


class BitDepthTest(unittest.TestCase):
    def test_16bit_shifted_into_24bit(self):
        """16bit 源转 24bit 容器：s32 里低 16 位恒为 0。"""
        raw = (0x1234 << 16).to_bytes(4, "little", signed=True)
        self.assertEqual(LL._effective_bits_from_s32(raw * 64), 16)

    def test_genuine_24bit(self):
        """真 24bit：s32 里只有低 8 位是填充零。"""
        raw = (0x123456 << 8).to_bytes(4, "little", signed=True)
        self.assertEqual(LL._effective_bits_from_s32(raw * 64), 24)

    def test_genuine_32bit(self):
        """32bit 源不左移，低 8 位也有内容。"""
        raw = 0x12345678.to_bytes(4, "little", signed=True)
        self.assertEqual(LL._effective_bits_from_s32(raw * 64), 32)

    def test_all_zero_returns_none(self):
        """整段静音判断不了位深，不能误判成 16bit。"""
        self.assertIsNone(LL._effective_bits_from_s32(b"\x00" * 64))

    def test_empty_returns_none(self):
        self.assertIsNone(LL._effective_bits_from_s32(b""))

    def test_measure_skips_16bit_source(self):
        """16bit 及以下不需要测有效位深，直接返回 None。"""
        self.assertIsNone(LL.measure_effective_bits("/tmp/x.flac", make_profile()))


class ProbeTest(unittest.TestCase):
    @mock.patch.object(LL, "_resolve_tools", return_value=("ffmpeg", "ffprobe"))
    @mock.patch.object(LL, "_run")
    def test_probe_flac(self, run_mock, _tools):
        run_mock.return_value = json.dumps({
            "streams": [{
                "codec_name": "flac", "sample_rate": "96000", "channels": 2,
                "bits_per_raw_sample": "24", "bit_rate": "2304000",
            }],
            "format": {"duration": "301.5", "bit_rate": "2310000"},
        }).encode()
        profile = LL.probe("/tmp/a.flac")
        self.assertEqual(profile.codec, "flac")
        self.assertEqual(profile.sample_rate, 96000)
        self.assertEqual(profile.bits_per_sample, 24)
        self.assertAlmostEqual(profile.duration, 301.5)
        self.assertTrue(profile.is_lossless_codec)

    @mock.patch.object(LL, "_resolve_tools", return_value=("ffmpeg", "ffprobe"))
    @mock.patch.object(LL, "_run", side_effect=RuntimeError("boom"))
    def test_probe_failure_is_soft(self, *_):
        profile = LL.probe("/tmp/a.flac")
        self.assertTrue(profile.error)
        self.assertEqual(LL.decide_verdict(profile, None).verdict,
                         LL.VERDICT_UNREADABLE)

    @mock.patch.object(LL, "_resolve_tools", return_value=(None, None))
    def test_probe_without_tools(self, *_):
        profile = LL.probe("/tmp/a.flac")
        self.assertIn("ffprobe", profile.error)

    def test_verify_missing_file(self):
        result = LL.verify("/definitely/not/here.flac")
        self.assertEqual(result.verdict, LL.VERDICT_UNREADABLE)


class ReportTest(unittest.TestCase):
    def test_report_contains_key_facts(self):
        freqs, power = synth_spectrum(21500, transition_hz=500)
        spectrum = LL._summarize(freqs, power, 22050.0)
        result = LL.decide_verdict(make_profile(), spectrum)
        text = LL.format_report(result)
        self.assertIn("真无损", text)
        self.assertIn("FLAC", text)
        self.assertIn("44.1kHz/16bit", text)

    def test_pick_start_prefers_middle(self):
        starts = LL._pick_start(300.0, 20.0)
        self.assertAlmostEqual(starts[0], 140.0)

    def test_pick_start_short_file(self):
        self.assertEqual(LL._pick_start(10.0, 20.0), [0.0])
        self.assertEqual(LL._pick_start(0.0, 20.0), [0.0])


# ---------------------------------------------------------------------------
# 开放音源
# ---------------------------------------------------------------------------


class FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self._payload


class FakeSession:
    """按 URL 片段路由的假 Session。"""

    def __init__(self, routes):
        self.routes = routes
        self.calls = []

    def get(self, url, params=None, timeout=None, **kwargs):
        self.calls.append((url, params))
        for fragment, payload in self.routes.items():
            if fragment in url:
                return FakeResponse(payload)
        raise AssertionError("unexpected url: %s" % url)


class ArchiveTest(unittest.TestCase):
    def test_query_becomes_phrase_and_audio_only(self):
        query = OS_._archive_query("beethoven moonlight")
        self.assertIn('"beethoven moonlight"', query)
        self.assertIn("mediatype:(audio)", query)

    def test_query_keeps_advanced_syntax(self):
        query = OS_._archive_query("creator:(gould)")
        self.assertIn("creator:(gould)", query)

    def test_search_archive_picks_flac_only(self):
        session = FakeSession({
            "advancedsearch.php": {
                "response": {"docs": [{"identifier": "item-1"}]}
            },
            "/metadata/item-1": {
                "metadata": {"title": "Goldberg Variations", "creator": "Glenn Gould"},
                "files": [
                    {"name": "cover.jpg", "format": "JPEG"},
                    {"name": "01 aria.mp3", "format": "VBR MP3"},
                    {
                        "name": "01 aria.flac", "format": "24bit Flac",
                        "size": "52428800", "length": "201.4", "title": "Aria",
                    },
                ],
            },
        })
        tracks = OS_.search_archive("goldberg", limit=3, session=session)
        self.assertEqual(len(tracks), 1)
        track = tracks[0]
        self.assertEqual(track.source, "archive")
        self.assertEqual(track.title, "Aria")
        self.assertEqual(track.artist, "Glenn Gould")
        self.assertEqual(track.fmt, "flac")
        self.assertEqual(track.size, 52428800)
        self.assertIn("archive.org/download/item-1/", track.url)
        self.assertIn("01%20aria.flac", track.url)
        self.assertIn("metadata", session.calls[1][0])

    def test_search_archive_handles_failure(self):
        session = FakeSession({"advancedsearch.php": {"response": {"docs": []}}})
        self.assertEqual(OS_.search_archive("x", session=session), [])

    def test_first_text_accepts_list(self):
        self.assertEqual(OS_._first_text(["a", "b"]), "a")
        self.assertEqual(OS_._first_text(None), "")
        self.assertEqual(OS_._first_text(123), "123")


class JamendoTest(unittest.TestCase):
    def test_without_client_id_returns_empty(self):
        self.assertEqual(OS_.search_jamendo("x", ""), [])

    def test_search_jamendo_maps_fields(self):
        session = FakeSession({
            "api.jamendo.com": {"results": [{
                "id": 42, "name": "Track A", "artist_name": "Artist",
                "album_name": "Album", "duration": 180,
                "audiodownload": "https://prod.jamendo.com/dl/42",
                "audiodownload_allowed": True,
                "license_ccurl": "https://creativecommons.org/licenses/by/4.0/",
            }]},
        })
        tracks = OS_.search_jamendo("track", "cid", session=session)
        self.assertEqual(len(tracks), 1)
        self.assertEqual(tracks[0].source, "jamendo")
        self.assertEqual(tracks[0].identifier, "42")
        self.assertEqual(tracks[0].duration, 180.0)
        self.assertIn("jamendo", tracks[0].url)


class FilenameTest(unittest.TestCase):
    def test_illegal_chars_are_replaced(self):
        track = OS_.OpenTrack(
            source="archive", identifier="x",
            title='AC/DC: Live? <1979>', artist="Band", fmt="flac",
        )
        name = track.filename()
        self.assertNotIn("/", name)
        self.assertNotIn("?", name)
        self.assertNotIn("<", name)
        self.assertTrue(name.endswith(".flac"))

    def test_describe(self):
        track = OS_.OpenTrack(
            source="archive", identifier="x", title="Aria", artist="Gould",
            fmt="flac", size=1048576,
        )
        text = track.describe()
        self.assertIn("Gould - Aria", text)
        self.assertIn("FLAC", text)
        self.assertIn("1.0MB", text)


# ---------------------------------------------------------------------------
# 接线契约：确保校验真的接到了两版下载流程里
# ---------------------------------------------------------------------------


class WiringTest(unittest.TestCase):
    def _read(self, relative):
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_v1_manager_calls_verify(self):
        source = self._read("fuo_dl/manager.py")
        self.assertIn("from .lossless import format_report, verify", source)
        self.assertIn("async def verify_download", source)
        self.assertIn("aio.run_fn(verify, path)", source)

    def test_v2_manager_calls_verify(self):
        source = self._read("fuo_dl/v2/manager.py")
        self.assertIn("from fuo_dl.lossless import format_report, verify", source)
        self.assertIn("async def _verify", source)
        self.assertIn("task.verify_result = await run_fn(verify, task.filepath)", source)

    def test_config_field(self):
        self.assertIn("VERIFY_LOSSLESS", self._read("fuo_dl/__init__.py"))


# ---------------------------------------------------------------------------
# 开放源回退：匹配与降级
# ---------------------------------------------------------------------------


def _load_fallback_module():
    """加载 fallback.py，替身掉 feeluown 与 quality 依赖。"""
    fake_feeluown = types.ModuleType("feeluown")
    fake_utils = types.ModuleType("feeluown.utils")
    fake_aio = types.ModuleType("feeluown.utils.aio")

    async def _run_fn(func, *args, **kwargs):
        return func(*args, **kwargs)

    fake_aio.run_fn = _run_fn
    fake_utils.aio = fake_aio
    fake_feeluown.utils = fake_utils
    sys.modules["feeluown"] = fake_feeluown
    sys.modules["feeluown.utils"] = fake_utils
    sys.modules["feeluown.utils.aio"] = fake_aio

    fake_helpers = types.ModuleType("fuo_dl.helpers")
    fake_helpers.guess_media_url_ext = lambda url: (
        url.rsplit(".", 1)[-1].split("?")[0].lower() if url and "." in url else ""
    )
    sys.modules["fuo_dl.helpers"] = fake_helpers

    fake_quality = types.ModuleType("fuo_dl.quality")
    fake_quality._notify = lambda app, msg: None
    fake_quality._song_title = lambda song: getattr(song, "title", "")
    sys.modules["fuo_dl.quality"] = fake_quality

    return _load_package_module("fallback", "fallback.py")


FB = _load_fallback_module()


def _media(fmt="", url="http://example.com/a.mp3"):
    return types.SimpleNamespace(
        props=types.SimpleNamespace(format=fmt), url=url
    )


def _song(title="Goldberg Variations", artists_name="Glenn Gould"):
    return types.SimpleNamespace(title=title, artists_name=artists_name)


def _app(fallback=True, jamendo_id=""):
    dl = types.SimpleNamespace(
        OPEN_SOURCE_FALLBACK=fallback, JAMENDO_CLIENT_ID=jamendo_id
    )
    return types.SimpleNamespace(config=types.SimpleNamespace(dl=dl))


class LosslessMediaTest(unittest.TestCase):
    def test_flac_and_alac_are_lossless(self):
        self.assertTrue(FB.is_lossless_media(_media("flac")))
        self.assertTrue(FB.is_lossless_media(_media("ALAC")))
        self.assertTrue(FB.is_lossless_media(_media("wav")))

    def test_lossy_formats_rejected(self):
        for name in ("mp3", "aac", "opus", "vorbis", "m4a"):
            self.assertFalse(FB.is_lossless_media(_media(name)), name)

    def test_falls_back_to_url_suffix(self):
        self.assertTrue(FB.is_lossless_media(_media("", "http://x/a.flac?t=1")))
        self.assertFalse(FB.is_lossless_media(_media("", "http://x/a.mp3")))

    def test_media_without_props(self):
        media = types.SimpleNamespace(url="http://x/a.flac")
        self.assertTrue(FB.is_lossless_media(media))


class TitleMatchTest(unittest.TestCase):
    def test_case_insensitive(self):
        self.assertTrue(FB.is_title_match("Goldberg Variations",
                                          "goldberg variations"))

    def test_version_suffix_ignored(self):
        self.assertTrue(FB.is_title_match("Aria (Remastered 2015)", "Aria"))

    def test_short_title_not_contain_matched(self):
        """4 个字符的标题走包含匹配会误伤，必须要求相等。"""
        self.assertFalse(FB.is_title_match("Aria", "Aria and Variations"))

    def test_long_title_contain_matched(self):
        self.assertTrue(FB.is_title_match(
            "Goldberg Variations BWV 988",
            "Goldberg Variations BWV 988 Aria",
        ))

    def test_unrelated_titles(self):
        self.assertFalse(FB.is_title_match("Moonlight Sonata",
                                           "Goldberg Variations"))

    def test_empty_title(self):
        self.assertFalse(FB.is_title_match("", "Aria"))

    def test_artist_match(self):
        self.assertTrue(FB.is_artist_match("Gould", "Glenn Gould"))
        self.assertFalse(FB.is_artist_match("Glenn Gould", "Lang Lang"))

    def test_missing_artist_is_tolerated(self):
        self.assertTrue(FB.is_artist_match("Glenn Gould", ""))
        self.assertTrue(FB.is_artist_match("", "Anyone"))


class FindCandidatesTest(unittest.TestCase):
    def test_filters_by_title_and_artist(self):
        tracks = [
            OS_.OpenTrack("archive", "hit", "Goldberg Variations BWV 988",
                          "Glenn Gould", fmt="flac"),
            OS_.OpenTrack("archive", "miss", "Moonlight Sonata",
                          "Beethoven", fmt="flac"),
        ]
        with mock.patch.object(FB, "search_all", return_value=tracks):
            result = FB.find_candidates(_song())
        self.assertEqual([t.identifier for t in result], ["hit"])

    def test_artist_filter_keeps_better_match(self):
        """有艺人强匹配时，把不同演奏者的版本排除掉。"""
        tracks = [
            OS_.OpenTrack("archive", "other", "Goldberg Variations", "Lang Lang"),
            OS_.OpenTrack("archive", "good", "Goldberg Variations", "Glenn Gould"),
        ]
        with mock.patch.object(FB, "search_all", return_value=tracks):
            result = FB.find_candidates(_song())
        self.assertEqual([t.identifier for t in result], ["good"])

    def test_empty_title_skips_search(self):
        with mock.patch.object(FB, "search_all") as search:
            self.assertEqual(FB.find_candidates(_song(title="")), [])
            search.assert_not_called()

    def test_network_failure_is_soft(self):
        with mock.patch.object(FB, "search_all", side_effect=RuntimeError("net")):
            self.assertEqual(FB.find_candidates(_song()), [])


class FormatCandidateTest(unittest.TestCase):
    def test_layout(self):
        track = OS_.OpenTrack(
            "archive", "a", "Aria", "Gould", size=1048576, fmt="flac"
        )
        text = FB.format_candidate(1, track)
        self.assertIn("1. Gould - Aria", text)
        self.assertIn("archive", text)
        self.assertIn("1.0MB", text)

    def test_unknown_size(self):
        track = OS_.OpenTrack("jamendo", "a", "Aria", "", fmt="flac")
        self.assertIn("大小未知", FB.format_candidate(2, track))


class ChooseOpenSourceTest(unittest.TestCase):
    def test_disabled_by_config(self):
        with mock.patch.object(FB, "find_candidates") as search:
            result = asyncio.run(
                FB.choose_open_source(_app(fallback=False), _song())
            )
            search.assert_not_called()
        self.assertIsNone(result)

    def test_no_candidates_returns_none(self):
        with mock.patch.object(FB, "find_candidates", return_value=[]):
            self.assertIsNone(
                asyncio.run(FB.choose_open_source(_app(), _song()))
            )

    def test_user_pick_returned(self):
        track = OS_.OpenTrack("archive", "a", "Goldberg Variations",
                              "Glenn Gould", fmt="flac")
        with mock.patch.object(FB, "find_candidates", return_value=[track]), \
                mock.patch.object(FB, "ask_open_source", return_value=track):
            result = asyncio.run(FB.choose_open_source(_app(), _song()))
        self.assertIs(result, track)

    def test_user_skip_returns_none(self):
        track = OS_.OpenTrack("archive", "a", "Goldberg Variations",
                              "Glenn Gould", fmt="flac")
        with mock.patch.object(FB, "find_candidates", return_value=[track]), \
                mock.patch.object(FB, "ask_open_source", return_value=None):
            self.assertIsNone(
                asyncio.run(FB.choose_open_source(_app(), _song()))
            )

    def test_dialog_failure_falls_back_silently(self):
        """无 GUI 环境（弹框抛异常）时不能阻断下载。"""
        track = OS_.OpenTrack("archive", "a", "Goldberg Variations",
                              "Glenn Gould", fmt="flac")
        with mock.patch.object(FB, "find_candidates", return_value=[track]), \
                mock.patch.object(FB, "ask_open_source", side_effect=RuntimeError):
            self.assertIsNone(
                asyncio.run(FB.choose_open_source(_app(), _song()))
            )


class DownloadOpenTrackTest(unittest.TestCase):
    def test_success_returns_path(self):
        track = OS_.OpenTrack("archive", "a", "Aria", "Gould", fmt="flac")
        outcome = OS_.DownloadOutcome(path="/tmp/a.flac", bytes_written=100)
        with mock.patch.object(FB, "download", return_value=outcome):
            path = asyncio.run(FB.download_open_track(_app(), track, "/tmp"))
        self.assertEqual(path, "/tmp/a.flac")

    def test_failure_returns_none(self):
        track = OS_.OpenTrack("archive", "a", "Aria", "Gould", fmt="flac")
        with mock.patch.object(FB, "download", side_effect=RuntimeError("404")):
            self.assertIsNone(
                asyncio.run(FB.download_open_track(_app(), track, "/tmp"))
            )


class FallbackWiringTest(unittest.TestCase):
    def _read(self, relative):
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_v2_download_has_fallback(self):
        source = self._read("fuo_dl/v2/manager.py")
        self.assertIn("from fuo_dl.fallback import", source)
        self.assertIn("await choose_open_source(self._app, song)", source)
        self.assertIn("await download_open_track(", source)

    def test_v1_download_has_fallback(self):
        source = self._read("fuo_dl/ui.py")
        self.assertIn("from .fallback import", source)
        self.assertIn("await choose_open_source(self._app, song)", source)

    def test_v1_manager_exposes_download_dir(self):
        self.assertIn("def download_dir", self._read("fuo_dl/manager.py"))

    def test_config_fields(self):
        source = self._read("fuo_dl/__init__.py")
        self.assertIn("OPEN_SOURCE_FALLBACK", source)
        self.assertIn("JAMENDO_CLIENT_ID", source)
        self.assertIn("PLAY_OPEN_SOURCE_FALLBACK", source)
        self.assertIn("PLAY_PREFETCH_NEXT", source)


if __name__ == "__main__":
    unittest.main()
