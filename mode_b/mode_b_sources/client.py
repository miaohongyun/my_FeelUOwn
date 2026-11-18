import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

from .health import CircuitBreaker
from .models import ConfigValidationError, ModeBConfig, SourceEntry
from .storage import atomic_write_json, canonical_hash, safe_read_json


class SourceConfigError(Exception):
    """manualOverride 指向不存在的源等配置异常"""


class TrackNotFoundError(Exception):
    """单曲不存在/无版权/404，不触发源冷却"""


class SourceUnavailableError(Exception):
    """网络超时、5xx、401/403、429 或响应结构异常，计入源冷却"""


class AllSourcesDownError(Exception):
    """全部启用源不可用，必须明确报错并提供手动切源入口"""


@dataclass(frozen=True)
class ModeBSearchHit:
    """一条搜索命中（对齐 Kotlin 版 ModeBRouter.ModeBSearchHit）。"""
    sourceId: str
    trackId: str
    title: str
    artist: str = ""


def _default_cache_dir() -> Path:
    """统一持久化到 FeelUOwn 配置目录，不污染插件源码。"""
    return Path.home() / ".FeelUOwn" / "mode_b"


def _extract_by_path(data: Any, path: str) -> Any:
    """支持 'url'、'data.url'、'results.0.audio' 等点分路径提取"""
    cur = data
    for part in path.split("."):
        if isinstance(cur, dict):
            if part not in cur:
                return None
            cur = cur[part]
        elif isinstance(cur, list):
            if not part.isdigit():
                return None
            idx = int(part)
            if idx < 0 or idx >= len(cur):
                return None
            cur = cur[idx]
        else:
            return None
    return cur


def _build_url(api_base: str, url_path: str, extra_params: Dict[str, str]) -> str:
    base = api_base.rstrip("/")
    # 支持路径参数占位符 {id}：id 来自 extra_params（runtimeParams 已 merge 进来）。
    # 含 {id} 时用 id 替换并从 query 移除 id，避免路径与查询串同时出现；无占位符时行为不变。
    id_for_path = extra_params.get("id")
    has_path_id = "{id}" in url_path
    effective_path = url_path.replace("{id}", id_for_path) if (id_for_path and has_path_id) else url_path
    path = f"/{effective_path.lstrip('/')}" if effective_path else ""
    raw_url = f"{base}{path}"
    parsed = urllib.parse.urlsplit(raw_url)
    existing = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
    merged = dict(existing)
    merged.update(extra_params)
    if id_for_path and has_path_id:
        merged.pop("id", None)
    new_query = urllib.parse.urlencode(merged)
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path, new_query, parsed.fragment))


def _urlopen(req: urllib.request.Request, timeout: float):
    """对 127.0.0.1 / localhost 自动绕过系统 HTTP_PROXY，避免国内开发机测试走代理失败"""
    host = urllib.parse.urlsplit(req.full_url).hostname or ""
    if host in ("127.0.0.1", "localhost", "::1"):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        return opener.open(req, timeout=timeout)
    return urllib.request.urlopen(req, timeout=timeout)


class ModeBRouter:
    def __init__(
        self,
        default_config_path: Path,
        cache_dir: Optional[Path] = None,
        token_env_var: str = "MODE_B_CONFIG_TOKEN",
        clock=time.time,
    ):
        self.default_config_path = Path(default_config_path)
        self.cache_dir = Path(cache_dir) if cache_dir else _default_cache_dir()
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.cached_config_path = self.cache_dir / "sources_cache.json"
        self.health_state_path = self.cache_dir / "health_state.json"
        self.token_env_var = token_env_var
        self._clock = clock

        self._config_lock = threading.Lock()
        self.config: ModeBConfig = self._load_initial_config()
        self.breaker = CircuitBreaker(
            state_path=self.health_state_path,
            failure_threshold=self.config.healthPolicy.failureThreshold,
            cooldown_seconds=self.config.healthPolicy.cooldownSeconds,
            clock=self._clock,
        )

    def _load_initial_config(self) -> ModeBConfig:
        """缓存优先：先读本地缓存；若缓存损坏或首次启动，则回退默认内置配置"""
        cached_raw = safe_read_json(self.cached_config_path)
        if cached_raw is not None:
            try:
                return ModeBConfig.from_dict(cached_raw)
            except ConfigValidationError:
                pass

        default_raw = json.loads(self.default_config_path.read_text(encoding="utf-8"))
        cfg = ModeBConfig.from_dict(default_raw)
        atomic_write_json(self.cached_config_path, default_raw)
        return cfg

    def startup_sync_async(
        self,
        on_finished: Optional[Callable[[bool, str], None]] = None,
    ) -> threading.Thread:
        """启动后在后台拉取远程配置（3~5秒超时），绝不阻塞启动"""

        def _job():
            ok, msg = self.sync_remote_config()
            if on_finished:
                on_finished(ok, msg)

        t = threading.Thread(target=_job, daemon=True)
        t.start()
        return t

    def sync_remote_config(self) -> Tuple[bool, str]:
        """手动刷新或启动时单次拉取：Hash/版本号一致不写盘；校验失败直接丢弃保底"""
        with self._config_lock:
            urls = list(self.config.remoteConfigUrls)
            timeout = self.config.healthPolicy.requestTimeoutSeconds
            cur_hash = canonical_hash(self.config.rawDict)
            cur_ver = self.config.configVersion

        if not urls:
            return False, "未配置 remoteConfigUrls，继续使用本地缓存"

        token = os.environ.get(self.token_env_var, "").strip()
        last_err = "无可用配置地址"

        for url in urls:
            if "example.com" in url:
                continue
            headers = {"User-Agent": "my_FeelUOwn-ModeB/1.0", "Accept": "application/json"}
            if token:
                headers["Authorization"] = f"Bearer {token}"
            req = urllib.request.Request(url, headers=headers)
            try:
                with _urlopen(req, timeout=timeout) as resp:
                    if getattr(resp, "status", 200) >= 400:
                        last_err = f"HTTP {resp.status}"
                        continue
                    raw_text = resp.read().decode("utf-8")
                candidate_dict = json.loads(raw_text)
                candidate_cfg = ModeBConfig.from_dict(candidate_dict)
            except (ConfigValidationError, json.JSONDecodeError) as e:
                last_err = f"远程配置校验失败，已丢弃并保留旧配置: {e}"
                continue
            except Exception as e:
                last_err = f"拉取远程配置失败: {e}"
                continue

            new_hash = canonical_hash(candidate_dict)
            if new_hash == cur_hash and candidate_cfg.configVersion == cur_ver:
                return True, f"远程配置无变化 ({cur_ver})"

            with self._config_lock:
                self.config = candidate_cfg
                self.breaker.update_policy(
                    candidate_cfg.healthPolicy.failureThreshold,
                    candidate_cfg.healthPolicy.cooldownSeconds,
                )
                atomic_write_json(self.cached_config_path, candidate_dict)
            return True, f"远程配置已更新至 {candidate_cfg.configVersion}"

        return False, last_err

    def set_manual_override(self, source_id: Optional[str]) -> None:
        """支持在运行期手动锁定/解除锁定某个音乐源"""
        with self._config_lock:
            if source_id is not None:
                if not any(s.id == source_id for s in self.config.sources):
                    raise SourceConfigError(f"未知的 source_id: {source_id}")
            self.config.manualOverride = source_id
            self.config.rawDict["manualOverride"] = source_id
            atomic_write_json(self.cached_config_path, self.config.rawDict)

    def get_candidates(self) -> List[SourceEntry]:
        with self._config_lock:
            enabled = [s for s in self.config.sources if s.enabled]
        return sorted(enabled, key=lambda s: s.priority, reverse=True)

    def resolve_play_url(self, runtime_params: Optional[Dict[str, str]] = None) -> Dict[str, Any]:
        """
        核心模式 B 调度入口：
        1. 若设置了 manualOverride，则直接强制走指定源（方便调试与手工切源）；
        2. 否则按 priority 从高到低走懒检查（跳过冷却中源；冷却到期放行 1 个半开探针）；
        3. 全部挂掉时抛出 AllSourcesDownError。
        """
        extra = dict(runtime_params or {})
        with self._config_lock:
            override_id = self.config.manualOverride
            sources_by_id = {s.id: s for s in self.config.sources}
            policy = self.config.healthPolicy

        if override_id:
            if override_id not in sources_by_id:
                raise SourceConfigError(f"manualOverride 指向不存在的源: {override_id}")
            target = sources_by_id[override_id]
            try:
                result = self._request_source(target, extra, policy.requestTimeoutSeconds)
                self.breaker.record_success(target.id)
                return result
            except (TrackNotFoundError, SourceUnavailableError) as e:
                raise AllSourcesDownError(f"手动指定的源 [{target.id}] 请求失败: {e}") from e

        candidates = self.get_candidates()
        if not candidates:
            raise AllSourcesDownError("当前没有任何启用的音乐源")

        errors: List[str] = []
        only_track_not_found = False

        for idx, entry in enumerate(candidates):
            perm = self.breaker.acquire_permission(entry.id)
            if perm == "OPEN":
                errors.append(f"[{entry.id}] 处于冷却期，已跳过")
                continue

            try:
                result = self._request_source(entry, extra, policy.requestTimeoutSeconds)
                self.breaker.record_success(entry.id)
                return result
            except TrackNotFoundError as e:
                # 单曲 404/无版权属于曲目级失败，不扣源的健康分
                self.breaker.record_success(entry.id)
                only_track_not_found = True
                errors.append(f"[{entry.id}] 未找到该曲目: {e}")
            except SourceUnavailableError as e:
                tripped_now = self.breaker.record_failure(entry.id, str(e))
                errors.append(f"[{entry.id}] 请求失败: {e}")
                # 仅在主源刚失效的瞬间、且开启 probeOnTrip 时主动对下一候选做一次最低音质探针
                if tripped_now and policy.probeOnTrip and idx + 1 < len(candidates):
                    next_entry = candidates[idx + 1]
                    self._probe_next_candidate(next_entry, policy.probeParams, policy.requestTimeoutSeconds)

        if only_track_not_found:
            raise TrackNotFoundError("所有可用源均未找到该曲目:\n" + "\n".join(errors))
        raise AllSourcesDownError("所有音乐源均不可用，请检查网络或手动切源:\n" + "\n".join(errors))

    def search_tracks(self, keyword: str) -> List[ModeBSearchHit]:
        """在配置了 searchPath 的启用源中按 priority 降序搜索，首个非空结果即返回。

        没有任何可搜索源时直接返回空列表（零网络请求），不干扰其它音乐源。
        """
        with self._config_lock:
            searchable = [
                s for s in self.config.sources if s.enabled and s.searchable
            ]
            timeout = self.config.healthPolicy.requestTimeoutSeconds
        searchable.sort(key=lambda s: s.priority, reverse=True)

        for entry in searchable:
            if self.breaker.acquire_permission(entry.id) == "OPEN":
                continue
            try:
                hits = self._request_search(entry, keyword, timeout)
                self.breaker.record_success(entry.id)
                if hits:
                    return hits
            except TrackNotFoundError:
                self.breaker.record_success(entry.id)
            except SourceUnavailableError:
                self.breaker.record_failure(entry.id, "search unavailable")
        return []

    def _request_search(
        self, entry: SourceEntry, keyword: str, timeout: float
    ) -> List[ModeBSearchHit]:
        search_path = entry.searchPath
        if not search_path:
            return []
        params = dict(entry.params)
        params[entry.searchKeywordParam] = keyword
        headers = {"User-Agent": "my_FeelUOwn-ModeB/1.0", "Accept": "application/json"}
        headers.update(entry.headers)
        url = _build_url(entry.apiBase, search_path, params)
        req = urllib.request.Request(url, headers=headers, method="GET")
        try:
            with _urlopen(req, timeout=timeout) as resp:
                status = getattr(resp, "status", 200)
                if status >= 500 or status == 429:
                    raise SourceUnavailableError(f"HTTP {status}")
                payload = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raise SourceUnavailableError(f"HTTP {e.code}: {e.reason}") from e
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as e:
            raise SourceUnavailableError(f"网络或JSON异常: {e}") from e

        arr = _extract_by_path(payload, entry.searchListField)
        if not isinstance(arr, list):
            return []
        hits: List[ModeBSearchHit] = []
        for item in arr:
            track_id = _extract_by_path(item, entry.searchIdField)
            title = _extract_by_path(item, entry.searchTitleField)
            artist = _extract_by_path(item, entry.searchArtistField)
            # 搜索结果的 ID 可能是整数（如 iTunes trackId=1441133277）。
            # 与 Kotlin 侧 el.contentOrNull ?: el.content 对齐：数字也要转字符串，
            # 否则整数 ID 会被当成空串丢弃，导致整个源搜索返回 0 结果。
            if isinstance(track_id, (int, float)) and not isinstance(track_id, bool):
                track_id = str(track_id)
            track_id = track_id.strip() if isinstance(track_id, str) else ""
            title = title.strip() if isinstance(title, str) else ""
            artist = artist.strip() if isinstance(artist, str) else ""
            if track_id and title:
                hits.append(ModeBSearchHit(
                    sourceId=entry.id, trackId=track_id, title=title, artist=artist,
                ))
        return hits

    def _probe_next_candidate(self, entry: SourceEntry, probe_params: Dict[str, str], timeout: float) -> None:
        if self.breaker.acquire_permission(entry.id) == "OPEN":
            return
        try:
            self._request_source(entry, probe_params, timeout)
            self.breaker.record_success(entry.id)
        except TrackNotFoundError:
            self.breaker.record_success(entry.id)
        except SourceUnavailableError as e:
            self.breaker.record_failure(entry.id, f"故障切换探针失败: {e}")

    def _request_source(
        self,
        entry: SourceEntry,
        runtime_params: Dict[str, str],
        timeout: float,
    ) -> Dict[str, Any]:
        merged_params = dict(entry.params)
        merged_params.update(runtime_params)
        headers = {"User-Agent": "my_FeelUOwn-ModeB/1.0", "Accept": "application/json"}
        headers.update(entry.headers)

        if entry.method == "POST":
            url = _build_url(entry.apiBase, entry.urlPath, {})
            body = json.dumps(merged_params).encode("utf-8")
            headers.setdefault("Content-Type", "application/json")
            req = urllib.request.Request(url, data=body, headers=headers, method="POST")
        else:
            url = _build_url(entry.apiBase, entry.urlPath, merged_params)
            req = urllib.request.Request(url, headers=headers, method="GET")

        try:
            with _urlopen(req, timeout=timeout) as resp:
                status = getattr(resp, "status", 200)
                if status >= 500 or status == 429:
                    raise SourceUnavailableError(f"HTTP {status}")
                payload = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise TrackNotFoundError("HTTP 404") from e
            raise SourceUnavailableError(f"HTTP {e.code}: {e.reason}") from e
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as e:
            raise SourceUnavailableError(f"网络或JSON异常: {e}") from e

        play_url = _extract_by_path(payload, entry.urlField)
        if not isinstance(play_url, str) or not play_url.strip():
            # 某些聚合接口返回 HTTP 200 但业务字段明确表示没这首歌，可按需区分；空 url 视为无资源
            raise TrackNotFoundError(f"响应中未提取到有效播放地址字段 '{entry.urlField}'")

        # 过滤 hop-by-hop / 内容协商头，只保留拉流真正需要的
        # （Referer / User-Agent / Origin / Cookie / Authorization 等）。
        stream_headers = {
            k: v for k, v in entry.headers.items()
            if k.lower() not in ("accept", "content-type", "accept-encoding", "connection")
        }
        return {
            "sourceId": entry.id,
            "sourceName": entry.name,
            "url": play_url.strip(),
            "headers": stream_headers,
            "raw": payload,
        }
