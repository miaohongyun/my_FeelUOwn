from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


class ConfigValidationError(ValueError):
    """远程或本地配置格式非法时抛出，触发保底回退"""


def _is_real_int(val: Any) -> bool:
    return isinstance(val, int) and not isinstance(val, bool)


@dataclass
class SourceEntry:
    id: str
    name: str
    apiBase: str
    urlPath: str
    params: Dict[str, str] = field(default_factory=dict)
    priority: int = 0
    enabled: bool = True
    urlField: str = "url"
    method: str = "GET"
    headers: Dict[str, str] = field(default_factory=dict)
    # 可选搜索端点配置（对齐 Kotlin 版 Models.kt / 接入 Provider.search）
    searchPath: Optional[str] = None
    searchKeywordParam: str = "q"
    searchListField: str = "data"
    searchIdField: str = "id"
    searchTitleField: str = "title"
    searchArtistField: str = "artist"

    @property
    def searchable(self) -> bool:
        """是否配置了可用的搜索端点（searchPath 非空）。"""
        return bool(self.searchPath and self.searchPath.strip())

    @classmethod
    def from_dict(cls, raw: Any) -> "SourceEntry":
        if not isinstance(raw, dict):
            raise ConfigValidationError("source entry 必须是对象")

        for req_key in ("id", "name", "apiBase", "urlPath"):
            val = raw.get(req_key)
            if not isinstance(val, str) or (req_key != "urlPath" and not val.strip()):
                raise ConfigValidationError(f"source 字段 '{req_key}' 必须是非空字符串")

        api_base = raw["apiBase"].strip()
        if not (api_base.startswith("http://") or api_base.startswith("https://")):
            raise ConfigValidationError(f"source '{raw['id']}' 的 apiBase 必须是 http(s) URL")

        params = raw.get("params", {})
        if not isinstance(params, dict) or not all(
            isinstance(k, str) and isinstance(v, str) for k, v in params.items()
        ):
            raise ConfigValidationError(f"source '{raw['id']}' 的 params 必须是 Record<string, string>")

        priority = raw.get("priority", 0)
        if not _is_real_int(priority):
            raise ConfigValidationError(f"source '{raw['id']}' 的 priority 必须是整数")

        enabled = raw.get("enabled", True)
        if not isinstance(enabled, bool):
            raise ConfigValidationError(f"source '{raw['id']}' 的 enabled 必须是布尔值")

        url_field = raw.get("urlField", "url")
        if not isinstance(url_field, str) or not url_field.strip():
            raise ConfigValidationError(f"source '{raw['id']}' 的 urlField 必须是非空字符串")

        method = raw.get("method", "GET").upper()
        if method not in ("GET", "POST"):
            raise ConfigValidationError(f"source '{raw['id']}' 的 method 仅支持 GET/POST")

        headers = raw.get("headers", {})
        if not isinstance(headers, dict) or not all(
            isinstance(k, str) and isinstance(v, str) for k, v in headers.items()
        ):
            raise ConfigValidationError(f"source '{raw['id']}' 的 headers 必须是字符串字典")

        # 可选搜索字段：与 Kotlin 版 Models.kt 同名同默认值。
        search_path = raw.get("searchPath")
        if search_path is not None and (not isinstance(search_path, str) or not search_path.strip()):
            raise ConfigValidationError(f"source '{raw['id']}' 的 searchPath 必须是 null 或非空字符串")

        text_fields = {
            "searchKeywordParam": "q",
            "searchListField": "data",
            "searchIdField": "id",
            "searchTitleField": "title",
            "searchArtistField": "artist",
        }
        parsed_text = {}
        for key, default in text_fields.items():
            val = raw.get(key, default)
            if not isinstance(val, str) or not val.strip():
                raise ConfigValidationError(f"source '{raw['id']}' 的 {key} 必须是非空字符串")
            parsed_text[key] = val.strip()

        return cls(
            id=raw["id"].strip(),
            name=raw["name"].strip(),
            apiBase=api_base,
            urlPath=raw["urlPath"].strip(),
            params=dict(params),
            priority=priority,
            enabled=enabled,
            urlField=url_field.strip(),
            method=method,
            headers=dict(headers),
            searchPath=search_path.strip() if search_path else None,
            searchKeywordParam=parsed_text["searchKeywordParam"],
            searchListField=parsed_text["searchListField"],
            searchIdField=parsed_text["searchIdField"],
            searchTitleField=parsed_text["searchTitleField"],
            searchArtistField=parsed_text["searchArtistField"],
        )


@dataclass
class HealthPolicy:
    failureThreshold: int = 3
    cooldownSeconds: int = 600
    requestTimeoutSeconds: float = 5.0
    probeOnTrip: bool = False
    probeParams: Dict[str, str] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, raw: Any) -> "HealthPolicy":
        if raw is None:
            return cls()
        if not isinstance(raw, dict):
            raise ConfigValidationError("healthPolicy 必须是对象")

        threshold = raw.get("failureThreshold", 3)
        cooldown = raw.get("cooldownSeconds", 600)
        timeout = raw.get("requestTimeoutSeconds", 5.0)
        probe_on_trip = raw.get("probeOnTrip", False)
        probe_params = raw.get("probeParams", {})

        if not _is_real_int(threshold) or threshold < 1:
            raise ConfigValidationError("failureThreshold 必须是 >= 1 的整数")
        if not _is_real_int(cooldown) or cooldown < 1:
            raise ConfigValidationError("cooldownSeconds 必须是 >= 1 的整数")
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or timeout <= 0:
            raise ConfigValidationError("requestTimeoutSeconds 必须是正数")
        if not isinstance(probe_on_trip, bool):
            raise ConfigValidationError("probeOnTrip 必须是布尔值")
        if not isinstance(probe_params, dict) or not all(
            isinstance(k, str) and isinstance(v, str) for k, v in probe_params.items()
        ):
            raise ConfigValidationError("probeParams 必须是字符串字典")

        return cls(
            failureThreshold=threshold,
            cooldownSeconds=cooldown,
            requestTimeoutSeconds=float(timeout),
            probeOnTrip=probe_on_trip,
            probeParams=dict(probe_params),
        )


@dataclass
class ModeBConfig:
    schemaVersion: int
    configVersion: str
    sources: List[SourceEntry]
    remoteConfigUrls: List[str] = field(default_factory=list)
    manualOverride: Optional[str] = None
    healthPolicy: HealthPolicy = field(default_factory=HealthPolicy)
    rawDict: Dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, raw: Any) -> "ModeBConfig":
        if not isinstance(raw, dict):
            raise ConfigValidationError("根配置必须是 JSON 对象")

        schema_ver = raw.get("schemaVersion")
        if not _is_real_int(schema_ver) or schema_ver < 1:
            raise ConfigValidationError("schemaVersion 必须是 >= 1 的整数")

        cfg_ver = raw.get("configVersion")
        if not isinstance(cfg_ver, str) or not cfg_ver.strip():
            raise ConfigValidationError("configVersion 必须是非空字符串")

        remote_urls = raw.get("remoteConfigUrls", [])
        if not isinstance(remote_urls, list) or not all(isinstance(u, str) for u in remote_urls):
            raise ConfigValidationError("remoteConfigUrls 必须是字符串数组")

        manual_override = raw.get("manualOverride")
        if manual_override is not None and (not isinstance(manual_override, str) or not manual_override.strip()):
            raise ConfigValidationError("manualOverride 必须是 null 或非空字符串")

        raw_sources = raw.get("sources")
        if not isinstance(raw_sources, list) or len(raw_sources) == 0:
            raise ConfigValidationError("sources 必须是非空数组")

        seen_ids = set()
        entries: List[SourceEntry] = []
        for item in raw_sources:
            entry = SourceEntry.from_dict(item)
            if entry.id in seen_ids:
                raise ConfigValidationError(f"存在重复的 source id: {entry.id}")
            seen_ids.add(entry.id)
            entries.append(entry)

        policy = HealthPolicy.from_dict(raw.get("healthPolicy"))

        return cls(
            schemaVersion=schema_ver,
            configVersion=cfg_ver.strip(),
            sources=entries,
            remoteConfigUrls=[u.strip() for u in remote_urls if u.strip()],
            manualOverride=manual_override.strip() if manual_override else None,
            healthPolicy=policy,
            rawDict=raw,
        )
