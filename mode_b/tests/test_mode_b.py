import json
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

# 确保从项目根目录或 tests/ 目录运行都能导入 mode_b_sources
ROOT_DIR = Path(__file__).resolve().parent.parent
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from mode_b_sources import (  # noqa: E402
    AllSourcesDownError,
    ModeBRouter,
    ModeBSearchHit,
    SourceConfigError,
    TrackNotFoundError,
)


class MockState:
    def __init__(self):
        self.remote_config_payload = None
        self.remote_raw_text = None
        self.remote_status = 200
        self.remote_delay = 0.0
        self.source_behavior = {
            "src_a": {"status": 200, "url": "https://audio.test/a.flac", "calls": 0, "delay": 0.0},
            "src_b": {"status": 200, "url": "https://audio.test/b.flac", "calls": 0, "delay": 0.0},
            "src_c": {"status": 200, "url": "https://audio.test/c.flac", "calls": 0, "delay": 0.0},
        }


class QuietHTTPServer(ThreadingHTTPServer):
    """忽略客户端主动超时断开导致的 ConnectionAbortedError / BrokenPipeError"""
    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (BrokenPipeError, ConnectionAbortedError, ConnectionResetError)):
            return
        super().handle_error(request, client_address)


def make_handler(state: MockState):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            return

        def do_GET(self):
            parsed = urlparse(self.path)
            if parsed.path == "/config.json":
                if state.remote_delay > 0:
                    time.sleep(state.remote_delay)
                self.send_response(state.remote_status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                if state.remote_raw_text is not None:
                    self.wfile.write(state.remote_raw_text.encode("utf-8"))
                else:
                    self.wfile.write(json.dumps(state.remote_config_payload or {}).encode("utf-8"))
                return

            for src_key in ("src_a", "src_b", "src_c"):
                if parsed.path == f"/{src_key}/url":
                    cfg = state.source_behavior[src_key]
                    cfg["calls"] += 1
                    if cfg["delay"] > 0:
                        time.sleep(cfg["delay"])
                    status = cfg["status"]
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    if status == 200:
                        qs = parse_qs(parsed.query)
                        song_id = qs.get("id", ["default"])[0]
                        body = {"code": 0, "data": {"url": f"{cfg['url']}?id={song_id}"}}
                    else:
                        body = {"code": status, "error": "mock error"}
                    self.wfile.write(json.dumps(body).encode("utf-8"))
                    return

            self.send_response(404)
            self.end_headers()

    return Handler


class ModeBRouterTestSuite(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp_dir.name)
        self.cache_dir = self.root / "cache"
        self.default_cfg_path = self.root / "default.json"

        self.mock_state = MockState()
        self.server = QuietHTTPServer(("127.0.0.1", 0), make_handler(self.mock_state))
        self.port = self.server.server_address[1]
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()

        self.fake_now = 1000.0
        self._write_default_config()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp_dir.cleanup()

    def _clock(self):
        return self.fake_now

    def _base_url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def _write_default_config(self, remote_urls=None):
        cfg = {
            "schemaVersion": 1,
            "configVersion": "1.0.0",
            "manualOverride": None,
            "remoteConfigUrls": remote_urls if remote_urls is not None else [f"{self._base_url()}/config.json"],
            "healthPolicy": {
                "failureThreshold": 3,
                "cooldownSeconds": 600,
                "requestTimeoutSeconds": 1.0,
                "probeOnTrip": False,
            },
            "sources": [
                {
                    "id": "src_a",
                    "name": "主源 A",
                    "apiBase": f"{self._base_url()}/src_a",
                    "urlPath": "/url",
                    "params": {"quality": "lossless"},
                    "urlField": "data.url",
                    "priority": 100,
                    "enabled": True,
                },
                {
                    "id": "src_b",
                    "name": "备用源 B",
                    "apiBase": f"{self._base_url()}/src_b",
                    "urlPath": "/url",
                    "params": {"quality": "lossless"},
                    "urlField": "data.url",
                    "priority": 80,
                    "enabled": True,
                },
                {
                    "id": "src_c",
                    "name": "禁用源 C",
                    "apiBase": f"{self._base_url()}/src_c",
                    "urlPath": "/url",
                    "params": {},
                    "urlField": "data.url",
                    "priority": 50,
                    "enabled": False,
                },
            ],
        }
        self.default_cfg_path.write_text(json.dumps(cfg), encoding="utf-8")
        return cfg

    def test_1_cache_first_startup_never_blocked_by_slow_remote(self):
        """1. 远程配置慢/挂掉时，启动先读本地缓存秒开，绝不阻塞"""
        self.mock_state.remote_delay = 2.0  # 大于 1.0s 超时
        t0 = time.monotonic()
        router = ModeBRouter(self.default_cfg_path, self.cache_dir, clock=self._clock)
        bg = router.startup_sync_async()
        elapsed = time.monotonic() - t0
        self.assertLess(elapsed, 0.3)
        # 启动瞬间即可使用本地缓存源
        res = router.resolve_play_url({"id": "101"})
        self.assertEqual(res["sourceId"], "src_a")
        bg.join(timeout=3.0)

    def test_2_remote_sync_updates_cache_and_rejects_broken_json(self):
        """2. 远程合法配置按 Hash 更新；手滑推错 JSON 会校验丢弃并保底旧配置"""
        router = ModeBRouter(self.default_cfg_path, self.cache_dir, clock=self._clock)

        # 推送一份损坏的 JSON（缺少 apiBase）
        self.mock_state.remote_config_payload = {
            "schemaVersion": 1,
            "configVersion": "1.0.1-broken",
            "sources": [{"id": "bad", "name": "坏配置"}],
        }
        ok, msg = router.sync_remote_config()
        self.assertFalse(ok)
        self.assertIn("校验失败", msg)
        self.assertEqual(router.config.configVersion, "1.0.0")

        # 推送合法更新版本
        new_cfg = self._write_default_config()
        new_cfg["configVersion"] = "1.0.2"
        new_cfg["sources"][1]["priority"] = 200  # 把 src_b 提到最高优先级
        self.mock_state.remote_config_payload = new_cfg
        ok, _ = router.sync_remote_config()
        self.assertTrue(ok)
        self.assertEqual(router.config.configVersion, "1.0.2")
        res = router.resolve_play_url({"id": "202"})
        self.assertEqual(res["sourceId"], "src_b")

    def test_3_lazy_check_no_startup_ping(self):
        """3. 懒检查验证：启动和正常播放只打最高优先级源，绝不并发探测备用源"""
        router = ModeBRouter(self.default_cfg_path, self.cache_dir, clock=self._clock)
        router.resolve_play_url({"id": "303"})
        self.assertEqual(self.mock_state.source_behavior["src_a"]["calls"], 1)
        self.assertEqual(self.mock_state.source_behavior["src_b"]["calls"], 0)
        self.assertEqual(self.mock_state.source_behavior["src_c"]["calls"], 0)

    def test_4_single_track_404_does_not_trip_source(self):
        """4. 单曲 404（没版权）不影响主源健康分，不会把主源误杀冷却"""
        router = ModeBRouter(self.default_cfg_path, self.cache_dir, clock=self._clock)
        self.mock_state.source_behavior["src_a"]["status"] = 404

        for _ in range(4):
            res = router.resolve_play_url({"id": "404song"})
            self.assertEqual(res["sourceId"], "src_b")

        # 确认 src_a 没有被熔断冷却，恢复 200 后立刻直接命中 src_a
        self.assertEqual(router.breaker.snapshot(), {})
        self.mock_state.source_behavior["src_a"]["status"] = 200
        res = router.resolve_play_url({"id": "oksong"})
        self.assertEqual(res["sourceId"], "src_a")

    def test_5_circuit_breaker_persistence_and_half_open_recovery(self):
        """5. 连续失败 3 次触发冷却、重启不重置、冷却到期半开试探恢复"""
        router = ModeBRouter(self.default_cfg_path, self.cache_dir, clock=self._clock)
        self.mock_state.source_behavior["src_a"]["status"] = 500

        # 前 3 次失败每次都会打到 src_a，并自动 fallback 到 src_b
        for i in range(3):
            res = router.resolve_play_url({"id": f"s{i}"})
            self.assertEqual(res["sourceId"], "src_b")
        self.assertEqual(self.mock_state.source_behavior["src_a"]["calls"], 3)

        # 第 4 次直接跳过冷却中的 src_a
        router.resolve_play_url({"id": "s3"})
        self.assertEqual(self.mock_state.source_behavior["src_a"]["calls"], 3)

        # 模拟重启 App：新建一个 ModeBRouter，依然记得 src_a 在冷却中，不撞死源
        restarted_router = ModeBRouter(self.default_cfg_path, self.cache_dir, clock=self._clock)
        restarted_router.resolve_play_url({"id": "s4"})
        self.assertEqual(self.mock_state.source_behavior["src_a"]["calls"], 3)

        # 推进时间超过 600s 冷却期，且后端恢复正常 -> 半开试探成功恢复 CLOSED
        self.fake_now += 601.0
        self.mock_state.source_behavior["src_a"]["status"] = 200
        res = restarted_router.resolve_play_url({"id": "s5"})
        self.assertEqual(res["sourceId"], "src_a")
        self.assertEqual(self.mock_state.source_behavior["src_a"]["calls"], 4)

    def test_6_all_sources_down_raises_explicit_error_and_manual_override(self):
        """6. 全部源都挂时明确报错；manualOverride 可强制指定禁用/任意源调试"""
        router = ModeBRouter(self.default_cfg_path, self.cache_dir, clock=self._clock)
        self.mock_state.source_behavior["src_a"]["status"] = 503
        self.mock_state.source_behavior["src_b"]["status"] = 503

        with self.assertRaises(AllSourcesDownError):
            router.resolve_play_url({"id": "999"})

        # 手动指定平时 disabled 的 src_c 调试
        router.set_manual_override("src_c")
        res = router.resolve_play_url({"id": "debug1"})
        self.assertEqual(res["sourceId"], "src_c")

        with self.assertRaises(SourceConfigError):
            router.set_manual_override("not_exists")


class SearchState:
    def __init__(self):
        # search_calls 记录每个源的搜索请求次数；hits 为返回的条目列表
        self.search_calls = {"searchable_a": 0, "searchable_b": 0, "plain": 0}
        self.hits = {
            "searchable_a": [],
            "searchable_b": [
                {"id": "t-b-1", "title": "Fallback Radio", "artist": "Brain Radio"},
            ],
        }


def make_search_handler(state: SearchState):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            return

        def do_GET(self):
            parsed = urlparse(self.path)
            qs = parse_qs(parsed.query)
            keyword = qs.get("q", [""])[0]
            for src_key in ("searchable_a", "searchable_b", "plain"):
                if parsed.path == f"/{src_key}/search":
                    state.search_calls[src_key] += 1
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    payload = {
                        "code": 0,
                        "data": [
                            dict(item, searched=keyword)
                            for item in state.hits.get(src_key, [])
                        ],
                    }
                    self.wfile.write(json.dumps(payload).encode("utf-8"))
                    return
            self.send_response(404)
            self.end_headers()

    return Handler


class ModeBSearchTestSuite(unittest.TestCase):
    """搜索扩展：无 searchPath 源零请求；有 searchPath 按优先级降级。"""

    def setUp(self):
        self.tmp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp_dir.name)
        self.cache_dir = self.root / "cache"
        self.default_cfg_path = self.root / "default.json"
        self.state = SearchState()
        self.server = QuietHTTPServer(("127.0.0.1", 0), make_search_handler(self.state))
        self.port = self.server.server_address[1]
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()
        self.fake_now = 1000.0

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp_dir.cleanup()

    def _clock(self):
        return self.fake_now

    def _base_url(self):
        return f"http://127.0.0.1:{self.port}"

    def _source(self, sid, priority, search_path=None, enabled=True):
        src = {
            "id": sid,
            "name": sid,
            "apiBase": self._base_url(),
            "urlPath": "/url",
            "params": {},
            "urlField": "data.url",
            "priority": priority,
            "enabled": enabled,
        }
        if search_path is not None:
            src["searchPath"] = search_path
        return src

    def _write_config(self, sources):
        cfg = {
            "schemaVersion": 1,
            "configVersion": "search-1.0",
            "manualOverride": None,
            "remoteConfigUrls": [],
            "healthPolicy": {"failureThreshold": 3, "cooldownSeconds": 600, "requestTimeoutSeconds": 1.0},
            "sources": sources,
        }
        self.default_cfg_path.write_text(json.dumps(cfg), encoding="utf-8")

    def test_no_searchable_source_makes_zero_requests(self):
        """没有任何 searchPath 源时，搜索零网络请求并返回空（零污染）。"""
        self._write_config([
            self._source("plain", 100),
            self._source("plain_disabled", 90, search_path="/plain_disabled/search", enabled=False),
        ])
        router = ModeBRouter(self.default_cfg_path, self.cache_dir, clock=self._clock)
        self.assertEqual(router.search_tracks("flac"), [])
        self.assertEqual(self.state.search_calls, {"searchable_a": 0, "searchable_b": 0, "plain": 0})

    def test_search_uses_searchpath_fields_and_falls_back(self):
        """按 priority 降序搜索，高优先级空结果时降级到下一源；字段映射对齐 Kotlin。"""
        self._write_config([
            self._source("searchable_a", 100, search_path="/searchable_a/search"),
            self._source("searchable_b", 80, search_path="/searchable_b/search"),
            self._source("plain", 60),
        ])
        router = ModeBRouter(self.default_cfg_path, self.cache_dir, clock=self._clock)
        hits = router.search_tracks("radio")
        self.assertEqual(len(hits), 1)
        self.assertIsInstance(hits[0], ModeBSearchHit)
        self.assertEqual(hits[0].sourceId, "searchable_b")
        self.assertEqual(hits[0].trackId, "t-b-1")
        self.assertEqual(hits[0].title, "Fallback Radio")
        self.assertEqual(hits[0].artist, "Brain Radio")
        # 高优先级搜索过、降级的也搜索过，plain 无 searchPath 从不请求
        self.assertEqual(self.state.search_calls["searchable_a"], 1)
        self.assertEqual(self.state.search_calls["searchable_b"], 1)
        self.assertEqual(self.state.search_calls["plain"], 0)

    def test_search_custom_field_names(self):
        """自定义 searchKeywordParam/searchIdField 等 6 字段均生效。"""
        src = self._source("searchable_a", 100, search_path="/searchable_a/search")
        src.update({
            "searchKeywordParam": "q",
            "searchListField": "data",
            "searchIdField": "id",
            "searchTitleField": "title",
            "searchArtistField": "artist",
        })
        self._write_config([src])
        self.state.hits["searchable_a"] = [{"id": "x1", "title": "Custom FLAC", "artist": "A"}]
        router = ModeBRouter(self.default_cfg_path, self.cache_dir, clock=self._clock)
        hits = router.search_tracks("flac")
        self.assertEqual(len(hits), 1)
        self.assertEqual(hits[0].trackId, "x1")
        self.assertEqual(hits[0].title, "Custom FLAC")

    def test_search_defaults_applied_when_fields_absent(self):
        """6 个搜索字段缺失时套用与 Kotlin 版一致的第默认值。"""
        self._write_config([self._source("searchable_b", 100, search_path="/searchable_b/search")])
        router = ModeBRouter(self.default_cfg_path, self.cache_dir, clock=self._clock)
        entry = router.config.sources[0]
        self.assertEqual(entry.searchKeywordParam, "q")
        self.assertEqual(entry.searchListField, "data")
        self.assertEqual(entry.searchIdField, "id")
        self.assertEqual(entry.searchTitleField, "title")
        self.assertEqual(entry.searchArtistField, "artist")

    def test_search_integer_track_id_is_kept(self):
        """iTunes 等源返回的 trackId 是整数，必须转字符串保留（对齐 Kotlin contentOrNull ?: content）。"""
        self._write_config([self._source("searchable_a", 100, search_path="/searchable_a/search")])
        # iTunes 真实响应里 trackId 是 int；这里同时覆盖数字 title 不应被误当有效值
        self.state.hits["searchable_a"] = [
            {"id": 1441133277, "title": "Hey Jude", "artist": "The Beatles"},
            {"id": 1427344, "title": "Trifle", "artist": "Vladimir"},
        ]
        router = ModeBRouter(self.default_cfg_path, self.cache_dir, clock=self._clock)
        hits = router.search_tracks("piano")
        self.assertEqual(len(hits), 2, "整数 ID 不应被当成空串丢弃")
        self.assertEqual(hits[0].trackId, "1441133277")
        self.assertIsInstance(hits[0].trackId, str)
        self.assertEqual(hits[1].trackId, "1427344")


class BuildUrlPlaceholderTest(unittest.TestCase):
    """_build_url 的 {id} 路径占位符支持：无占位符时行为不变。"""

    def test_path_id_placeholder_replaced_and_removed_from_query(self):
        from mode_b_sources.client import _build_url
        url = _build_url(
            "https://api.openverse.org/v1/audio/",
            "{id}/",
            {"id": "abc123", "app_name": "FuoEvolve"},
        )
        self.assertIn("/v1/audio/abc123/", url)
        self.assertNotIn("id=", url)
        self.assertIn("app_name=FuoEvolve", url)

    def test_no_placeholder_keeps_id_in_query(self):
        """没有 {id} 占位符时 id 仍在 query（现有 ?id= 源不受影响）。"""
        from mode_b_sources.client import _build_url
        url = _build_url("https://itunes.apple.com", "/lookup", {"id": "1441133277", "country": "CN"})
        self.assertIn("id=1441133277", url)
        self.assertIn("country=CN", url)
        self.assertNotIn("{id}", url)


if __name__ == "__main__":
    unittest.main(verbosity=2)
