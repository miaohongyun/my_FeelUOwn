import hashlib
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional


USER_AGENT = "my_FeelUOwn-SourceRouter/1.0"


class SourceRouterError(Exception):
    """路由器基础异常"""


class SourceUnavailableError(SourceRouterError):
    """节点级故障：超时、连接失败、5xx、429 等，计入熔断"""


class TrackNotFoundError(SourceRouterError):
    """曲目级空结果：源本身正常，只是没找到对应歌曲，不计入熔断"""


class AllSourcesDownError(SourceRouterError):
    """所有启用源及候选节点均不可用或处于冷却期"""


@dataclass
class TrackResult:
    source_id: str
    endpoint_id: str
    title: str
    artist: str
    stream_url: str
    codec: str = "unknown"
    bitrate_kbps: Optional[int] = None
    is_lossless: bool = False
    extra: Dict[str, Any] = field(default_factory=dict)


class CircuitBreakerStore:
    """持久化熔断状态：CLOSED -> OPEN(冷却) -> HALF_OPEN(半开试探)"""

    def __init__(self, state_path: Path, threshold: int = 3, cooldown_sec: int = 600):
        self.state_path = state_path
        self.threshold = threshold
        self.cooldown_sec = cooldown_sec
        self._lock = threading.Lock()
        self._state: Dict[str, Dict[str, Any]] = self._load()

    def _load(self) -> Dict[str, Dict[str, Any]]:
        if not self.state_path.exists():
            return {}
        try:
            return json.loads(self.state_path.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _save(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.state_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._state, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, self.state_path)

    def can_try(self, key: str) -> str:
        """返回 'CLOSED', 'HALF_OPEN', 或 'OPEN'"""
        with self._lock:
            item = self._state.get(key)
            if not item:
                return "CLOSED"
            cooldown_until = item.get("cooldown_until", 0.0)
            if cooldown_until <= 0:
                return "CLOSED"
            if time.time() >= cooldown_until:
                return "HALF_OPEN"
            return "OPEN"

    def record_success(self, key: str) -> None:
        with self._lock:
            if key in self._state:
                self._state[key] = {
                    "failures": 0,
                    "cooldown_until": 0.0,
                    "last_ok": time.time(),
                    "last_error": None,
                }
                self._save()

    def record_failure(self, key: str, reason: str) -> str:
        with self._lock:
            now = time.time()
            item = self._state.get(key, {"failures": 0, "cooldown_until": 0.0})
            failures = int(item.get("failures", 0)) + 1
            cooldown_until = 0.0
            state_name = "CLOSED"
            if failures >= self.threshold or item.get("cooldown_until", 0.0) > 0:
                cooldown_until = now + self.cooldown_sec
                state_name = "OPEN"
            self._state[key] = {
                "failures": failures,
                "cooldown_until": cooldown_until,
                "last_error": reason,
                "last_fail": now,
            }
            self._save()
            return state_name

    def reset_all(self) -> None:
        with self._lock:
            self._state = {}
            self._save()

    def snapshot(self) -> Dict[str, Dict[str, Any]]:
        with self._lock:
            return json.loads(json.dumps(self._state))


def _http_get_json(url: str, timeout: float = 5.0) -> Any:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status = getattr(resp, "status", 200)
            if status >= 500 or status == 429:
                raise SourceUnavailableError(f"HTTP {status} from {url}")
            raw = resp.read().decode("utf-8")
            return json.loads(raw)
    except urllib.error.HTTPError as e:
        if e.code >= 500 or e.code in (401, 403, 429):
            raise SourceUnavailableError(f"HTTP {e.code}: {e.reason}") from e
        if e.code == 404:
            raise TrackNotFoundError(f"HTTP 404 from {url}") from e
        raise SourceUnavailableError(f"HTTP {e.code}") from e
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as e:
        raise SourceUnavailableError(f"Network/Parse error: {e}") from e


def probe_stream_reachable(url: str, timeout: float = 5.0) -> Dict[str, Any]:
    """轻量探测音频流首字节（只读 2KB，绝不下载整首歌）"""
    req = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT, "Range": "bytes=0-2047"},
    )
    t0 = time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        chunk = resp.read(2048)
        elapsed_ms = int((time.monotonic() - t0) * 1000)
        return {
            "status": getattr(resp, "status", 200),
            "content_type": resp.headers.get("Content-Type", "unknown"),
            "bytes_read": len(chunk),
            "latency_ms": elapsed_ms,
        }


class MusicSourceRouter:
    def __init__(
        self,
        default_config_path: Path,
        cache_dir: Optional[Path] = None,
    ):
        self.default_config_path = default_config_path
        self.cache_dir = cache_dir or (Path.home() / ".my_feeluown")
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.cached_config_path = self.cache_dir / "sources_cache.json"
        self.health_state_path = self.cache_dir / "health_state.json"

        self._config_lock = threading.Lock()
        self.config = self._load_initial_config()
        policy = self.config.get("health_policy", {})
        self.timeout = float(policy.get("request_timeout_seconds", 5))
        self.breaker = CircuitBreakerStore(
            state_path=self.health_state_path,
            threshold=int(policy.get("failure_threshold", 3)),
            cooldown_sec=int(policy.get("cooldown_seconds", 600)),
        )

    def _load_initial_config(self) -> Dict[str, Any]:
        """缓存优先：先读本地缓存，没有才回退随包默认配置"""
        if self.cached_config_path.exists():
            try:
                return json.loads(self.cached_config_path.read_text(encoding="utf-8"))
            except Exception:
                pass
        data = json.loads(self.default_config_path.read_text(encoding="utf-8"))
        self._write_cached_config(data)
        return data

    def _write_cached_config(self, data: Dict[str, Any]) -> None:
        tmp = self.cached_config_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, self.cached_config_path)

    @staticmethod
    def _config_hash(data: Dict[str, Any]) -> str:
        canonical = json.dumps(data, sort_keys=True, ensure_ascii=False)
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    def refresh_remote_config_async(self, on_done: Optional[Callable[[str], None]] = None) -> threading.Thread:
        """后台异步拉取远程配置（不阻塞启动，失败静默用本地缓存）"""

        def _worker():
            msg = self.refresh_remote_config()
            if on_done:
                on_done(msg)

        t = threading.Thread(target=_worker, daemon=True)
        t.start()
        return t

    def refresh_remote_config(self) -> str:
        """同步拉取远端配置 + 刷新 Radio Browser 官方镜像列表"""
        updated_parts: List[str] = []

        # 1) 从你配置的可达静态 URL 拉取自定义 sources.json
        with self._config_lock:
            urls = list(self.config.get("remote_config_urls", []))
            current_hash = self._config_hash(self.config)

        for url in urls:
            if "example.com" in url:
                continue
            try:
                remote_data = _http_get_json(url, timeout=self.timeout)
                if isinstance(remote_data, dict) and "sources" in remote_data:
                    if self._config_hash(remote_data) != current_hash:
                        with self._config_lock:
                            self.config = remote_data
                            self._write_cached_config(remote_data)
                        updated_parts.append(f"远程配置已更新({remote_data.get('config_version', 'unknown')})")
                    else:
                        updated_parts.append("远程配置无变化")
                    break
            except Exception:
                continue

        # 2) 自动从 Radio Browser 官方接口刷新最新镜像节点列表
        rb_updated = self._refresh_radiobrowser_mirrors()
        if rb_updated:
            updated_parts.append(rb_updated)

        return "；".join(updated_parts) if updated_parts else "使用本地缓存配置"

    def _refresh_radiobrowser_mirrors(self) -> Optional[str]:
        with self._config_lock:
            sources = self.config.get("sources", [])
            rb_src = next((s for s in sources if s.get("type") == "radiobrowser" and s.get("enabled")), None)
            if not rb_src or not rb_src.get("mirror_discovery_url"):
                return None
            discovery_url = rb_src["mirror_discovery_url"]

        try:
            servers = _http_get_json(discovery_url, timeout=self.timeout)
            hosts = sorted({s["name"] for s in servers if isinstance(s, dict) and s.get("name")})
            if not hosts:
                return None
            new_endpoints = [{"id": f"rb_{h.split('.')[0]}", "api_base": f"https://{h}"} for h in hosts[:6]]
            with self._config_lock:
                for s in self.config.get("sources", []):
                    if s.get("type") == "radiobrowser":
                        s["endpoints"] = new_endpoints
                self._write_cached_config(self.config)
            return f"RadioBrowser镜像已自动刷新({len(new_endpoints)}个节点)"
        except Exception:
            return None

    def get_enabled_sources(self) -> List[Dict[str, Any]]:
        with self._config_lock:
            sources = [s for s in self.config.get("sources", []) if s.get("enabled", False)]
        return sorted(sources, key=lambda x: int(x.get("priority", 0)), reverse=True)

    def fetch_with_failover(
        self,
        source_filter: Optional[str] = None,
        query: str = "",
        prefer_lossless: bool = True,
    ) -> List[TrackResult]:
        """按优先级执行懒检查 + 失败驱动自动降级切换"""
        candidates = self.get_enabled_sources()
        if source_filter:
            candidates = [s for s in candidates if s["id"] == source_filter]
        if prefer_lossless:
            candidates = sorted(
                candidates,
                key=lambda x: (bool(x.get("lossless", False)), int(x.get("priority", 0))),
                reverse=True,
            )

        if not candidates:
            raise AllSourcesDownError("没有可用的启用音源")

        errors: List[str] = []
        found_empty_only = False

        for src in candidates:
            src_id = src["id"]
            src_type = src["type"]
            for ep in src.get("endpoints", []):
                ep_key = f"{src_id}:{ep['id']}"
                cb_state = self.breaker.can_try(ep_key)
                if cb_state == "OPEN":
                    errors.append(f"{ep_key} 冷却中")
                    continue

                try:
                    tracks = self._query_endpoint(src, ep, query=query)
                    self.breaker.record_success(ep_key)
                    if tracks:
                        return tracks
                    found_empty_only = True
                except TrackNotFoundError as e:
                    # 单曲未找到不算节点故障
                    self.breaker.record_success(ep_key)
                    found_empty_only = True
                    errors.append(f"{ep_key} 无匹配结果({e})")
                except SourceUnavailableError as e:
                    new_state = self.breaker.record_failure(ep_key, str(e))
                    errors.append(f"{ep_key} 失败[{new_state}]: {e}")

        if found_empty_only:
            return []
        raise AllSourcesDownError("所有候选音源节点均不可用，请检查网络或手动选源:\n  - " + "\n  - ".join(errors))

    def _query_endpoint(self, src: Dict[str, Any], ep: Dict[str, Any], query: str) -> List[TrackResult]:
        src_type = src["type"]
        src_id = src["id"]
        ep_id = ep["id"]
        api_base = ep["api_base"].rstrip("/")

        if src_type == "radioparadise":
            results: List[TrackResult] = []
            channels = ep.get("channels", [])
            if query.strip():
                q_low = query.strip().lower()
                matched = [c for c in channels if q_low in c["name"].lower()]
                if matched:
                    channels = matched
            for ch in channels:
                chan_id = ch["chan_id"]
                meta = _http_get_json(f"{api_base}/now_playing?chan={chan_id}", timeout=self.timeout)
                results.append(
                    TrackResult(
                        source_id=src_id,
                        endpoint_id=ep_id,
                        title=f"[{ch['name']}] {meta.get('title', 'Live Stream')}",
                        artist=meta.get("artist", "Radio Paradise"),
                        stream_url=ch["stream_url"],
                        codec="FLAC",
                        bitrate_kbps=900,
                        is_lossless=True,
                        extra={"album": meta.get("album", ""), "cover": meta.get("cover", "")},
                    )
                )
            return results

        if src_type == "radiobrowser":
            if query.strip():
                encoded = urllib.parse.quote(query.strip())
                url = f"{api_base}/json/stations/search?name={encoded}&limit=10"
            else:
                # 默认拉取中国地区电台，过滤音乐/流行相关
                url = f"{api_base}/json/stations/bycountryexact/China"
            stations = _http_get_json(url, timeout=self.timeout)
            if not isinstance(stations, list) or not stations:
                raise TrackNotFoundError("Radio Browser 未返回电台")

            # 如果没填搜索词，优先把带音乐标签、高码率、投票高的排前面
            def _score(st: Dict[str, Any]) -> tuple:
                tags = (st.get("tags") or "").lower()
                name = (st.get("name") or "").lower()
                is_music = any(k in tags or k in name for k in ("music", "音乐", "pop", "jazz", "classical", "fm"))
                return (is_music, int(st.get("bitrate") or 0), int(st.get("votes") or 0))

            stations = sorted(stations, key=_score, reverse=True)[:10]
            return [
                TrackResult(
                    source_id=src_id,
                    endpoint_id=ep_id,
                    title=st.get("name", "Unknown Radio").strip(),
                    artist=f"{st.get('country', 'CN')} | {st.get('tags', '')[:30]}",
                    stream_url=st.get("url_resolved") or st.get("url", ""),
                    codec=(st.get("codec") or "unknown").upper(),
                    bitrate_kbps=int(st.get("bitrate") or 0) or None,
                    is_lossless=((st.get("codec") or "").upper() == "FLAC"),
                )
                for st in stations
                if st.get("url_resolved") or st.get("url")
            ]

        if src_type == "jamendo":
            client_id = src.get("client_id", "")
            if not client_id or client_id.startswith("YOUR_"):
                raise SourceUnavailableError("Jamendo 未配置免费 client_id")
            q = urllib.parse.quote(query.strip() or "piano")
            url = f"{api_base}/tracks/?client_id={client_id}&format=json&limit=10&search={q}"
            data = _http_get_json(url, timeout=self.timeout)
            rows = data.get("results", [])
            if not rows:
                raise TrackNotFoundError(f"Jamendo 无 '{query}' 结果")
            return [
                TrackResult(
                    source_id=src_id,
                    endpoint_id=ep_id,
                    title=row.get("name", "Unknown"),
                    artist=row.get("artist_name", "Unknown Artist"),
                    stream_url=row.get("audio", ""),
                    codec="MP3",
                    bitrate_kbps=192,
                    is_lossless=False,
                    extra={"album": row.get("album_name", "")},
                )
                for row in rows
                if row.get("audio")
            ]

        raise SourceUnavailableError(f"不支持的源类型: {src_type}")
