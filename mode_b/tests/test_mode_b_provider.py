"""fuo_mode_b 插件逻辑测试。

本机没有安装 feeluown GUI 环境，这里用最小 stub 注入 sys.modules，验证
ModeBProvider 的搜索兜底、多音质三方法、异常映射与 headers 透传逻辑。
"""
import sys
import types
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
for p in (str(ROOT), str(ROOT / "mode_b_sources")):
    if p not in sys.path:
        sys.path.insert(0, p)


# -------------------- FeelUOwn 最小 stub --------------------
def _install_stubs():
    if "feeluown.excs" in sys.modules:
        return

    class MediaNotFound(Exception):
        pass

    class ProviderIOError(Exception):
        def __init__(self, message="", provider=None):
            super().__init__(message)
            self.provider = provider

    excs = types.ModuleType("feeluown.excs")
    excs.MediaNotFound = MediaNotFound
    excs.ProviderIOError = ProviderIOError
    excs.NoUserLoggedIn = type("NoUserLoggedIn", (Exception,), {})

    fu = types.ModuleType("feeluown")
    fu.excs = excs

    class _Enum:
        def __init__(self, v):
            self.value = v

        def __eq__(self, o):
            return isinstance(o, _Enum) and o.value == self.value

        def __hash__(self):
            return hash(self.value)

        def __repr__(self):
            return f"<Q {self.value}>"

    class _Audio:
        shq = _Enum("shq")
        hq = _Enum("hq")
        sq = _Enum("sq")
        lq = _Enum("lq")

    class Quality:
        Audio = _Audio

    class Media:
        def __init__(self, url, type_="audio", http_headers=None, **_):
            self.url = url
            self.http_headers = http_headers or {}
            self.type_ = type_
            self.manifest = None

    media_mod = types.ModuleType("feeluown.media")
    media_mod.Quality = Quality
    media_mod.Media = Media
    media_mod.MediaType = types.SimpleNamespace(audio="audio")

    class SearchType:
        so = "song"
        al = "album"
        ar = "artist"
        vi = "video"
        pl = "playlist"

        @classmethod
        def parse(cls, obj):
            return obj if obj in (cls.so, cls.al, cls.ar, cls.vi, cls.pl) else cls.so

    class ModelType:
        song = "song"

    class AbstractProvider:
        pass

    class ProviderV2:
        pass

    class BriefSongModel:
        def __init__(self, identifier="", source="", title="", artists_name=""):
            self.identifier = identifier
            self.source = source
            self.title = title
            self.artists_name = artists_name

    class SimpleSearchResult:
        def __init__(self, q="", source="", songs=None, **_):
            self.q = q
            self.source = source
            self.songs = songs or []

    library = types.ModuleType("feeluown.library")
    for k, v in dict(
        AbstractProvider=AbstractProvider, ProviderV2=ProviderV2,
        BriefSongModel=BriefSongModel, SimpleSearchResult=SimpleSearchResult,
        SearchType=SearchType, ModelType=ModelType,
    ).items():
        setattr(library, k, v)

    for name, mod in [
        ("feeluown", fu), ("feeluown.media", media_mod),
        ("feeluown.library", library), ("feeluown.excs", excs),
    ]:
        sys.modules[name] = mod


_install_stubs()

import fuo_mode_b  # noqa: E402
from mode_b_sources import ModeBSearchHit  # noqa: E402


class FakeRouter:
    def __init__(self, hits=None, play_result=None, play_exc=None):
        self.hits = hits or []
        self.play_result = play_result
        self.play_exc = play_exc
        self.calls = []

    def search_tracks(self, keyword):
        self.calls.append(("search", keyword))
        return self.hits

    def resolve_play_url(self, params=None):
        self.calls.append(("resolve", params))
        if self.play_exc:
            raise self.play_exc
        return self.play_result

    def get_candidates(self):
        return getattr(self, "_candidates", [])


class ProviderTest(unittest.TestCase):
    def test_search_plain_forwards_to_router(self):
        router = FakeRouter(hits=[ModeBSearchHit("s1", "t1", "Title", "Artist")])
        p = fuo_mode_b.ModeBProvider(router)
        result = p.search("周杰伦")
        self.assertEqual(result.source, "modeb")
        self.assertEqual(len(result.songs), 1)
        self.assertEqual(result.songs[0].identifier, "s1::t1")
        self.assertEqual(result.songs[0].title, "Title")
        self.assertEqual(result.songs[0].artists_name, "Artist")
        self.assertEqual(router.calls, [("search", "周杰伦")])

    def test_search_no_hits_returns_empty_no_error(self):
        p = fuo_mode_b.ModeBProvider(FakeRouter(hits=[]))
        result = p.search("nonexistent-song-name")
        self.assertEqual(result.songs, [])

    def test_search_fallback_keywords(self):
        router = FakeRouter(hits=[])
        p = fuo_mode_b.ModeBProvider(router)
        # 兜底关键词不应触发 router.search_tracks
        for kw in ("flac", "电台", "Radio", "modeb"):
            result = p.search(kw)
            self.assertGreaterEqual(len(result.songs), 0)
        self.assertEqual(router.calls, [])

    def test_fallback_builds_direct_entries(self):
        class Entry:
            def __init__(self, sid, name):
                self.id, self.name = sid, name

        router = FakeRouter()
        router.get_candidates = lambda: [Entry("radioparadise_now", "无损电台")]
        p = fuo_mode_b.ModeBProvider(router)
        result = p.search("flac")
        self.assertEqual(len(result.songs), 1)
        self.assertEqual(result.songs[0].identifier, "radioparadise_now::live::radioparadise_now")
        self.assertIn("无损电台", result.songs[0].title)

    def test_multi_quality_three_methods(self):
        p = fuo_mode_b.ModeBProvider(FakeRouter())
        song = fuo_mode_b.BriefSongModel(identifier="s1::t1", source="modeb")
        # song_list_quality
        self.assertEqual(p.song_list_quality(song), [fuo_mode_b.Quality.Audio.sq])
        # song_get_media + headers 透传
        router = FakeRouter(play_result={
            "url": "https://audio.test/x.flac",
            "headers": {"Referer": "https://example.com", "User-Agent": "UA"},
            "sourceName": "src",
        })
        p2 = fuo_mode_b.ModeBProvider(router)
        media = p2.song_get_media(song, fuo_mode_b.Quality.Audio.sq)
        self.assertEqual(media.url, "https://audio.test/x.flac")
        self.assertEqual(media.http_headers["Referer"], "https://example.com")
        # song_select_media
        media2, q = p2.song_select_media(song)
        self.assertEqual(q, fuo_mode_b.Quality.Audio.sq)
        self.assertEqual(media2.url, "https://audio.test/x.flac")

    def test_exception_mapping(self):
        from feeluown.excs import MediaNotFound, ProviderIOError
        from mode_b_sources import AllSourcesDownError, TrackNotFoundError

        song = fuo_mode_b.BriefSongModel(identifier="s1::t1", source="modeb")
        p_tnf = fuo_mode_b.ModeBProvider(FakeRouter(play_exc=TrackNotFoundError("404")))
        with self.assertRaises(MediaNotFound):
            p_tnf.song_get_media(song, fuo_mode_b.Quality.Audio.sq)

        p_down = fuo_mode_b.ModeBProvider(FakeRouter(play_exc=AllSourcesDownError("all down")))
        with self.assertRaises(ProviderIOError):
            p_down.song_get_media(song, fuo_mode_b.Quality.Audio.sq)

    def test_identifier_split(self):
        self.assertEqual(fuo_mode_b._split_identifier("s1::t1"), ("s1", "t1"))
        self.assertEqual(fuo_mode_b._split_identifier("plain"), ("", "plain"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
