# -*- coding: utf-8 -*-
"""合法可用的无损音源检索与下载。

这个模块只接入「明确授权、无需任何会员体系即可公开获取」的曲库：

* **Internet Archive** — 公共领域 / CC 授权的现场录音与古典音乐，
  有大量 24bit FLAC。官方 advancedsearch / metadata 接口完全开放，不需要 key。
* **Jamendo** — CC 授权的独立音乐，官方 API 需要免费申请的 ``client_id``。

它不碰 QQ 音乐、网易云之类的付费曲库，也不做任何会员绕过。
检索到的每个文件都可以再过一遍 :mod:`fuo_dl.lossless`，
把「320kbps 转码成 FLAC」的伪无损挡在外面。

命令行用法::

    python -m fuo_dl.open_sources search "beethoven moonlight"
    python -m fuo_dl.open_sources download "beethoven moonlight" --index 1

也可以在代码里用::

    from fuo_dl.open_sources import search_all, download

    for track in search_all("beethoven moonlight"):
        print(track.describe())
"""
from __future__ import annotations

import argparse
import logging
import os
import re
import sys
from dataclasses import dataclass, field
from typing import Callable, List, Optional
from urllib.parse import quote

import requests

from .lossless import VerifyResult, format_report, verify

logger = logging.getLogger(__name__)

ARCHIVE_SEARCH_URL = "https://archive.org/advancedsearch.php"
ARCHIVE_METADATA_URL = "https://archive.org/metadata/%s"
ARCHIVE_DOWNLOAD_URL = "https://archive.org/download/%s/%s"
JAMENDO_API_URL = "https://api.jamendo.com/v3.0/tracks/"

#: 只收真无损格式，wav/aiff 体积太大，不在候选里
WANTED_FORMATS = ("flac",)

DEFAULT_TIMEOUT = 20
DOWNLOAD_CHUNK = 1024 * 64
#: Windows 文件名非法字符
_ILLEGAL_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


@dataclass
class OpenTrack:
    """一条可下载的无损曲目。"""

    source: str
    identifier: str
    title: str
    artist: str = ""
    album: str = ""
    url: str = ""
    fmt: str = "flac"
    size: int = 0
    duration: float = 0.0
    license_url: str = ""
    notes: str = ""

    def describe(self) -> str:
        """一行摘要，给 CLI 列表用。"""
        parts = ["[%s]" % self.source, "%s - %s" % (self.artist or "未知", self.title)]
        if self.fmt:
            parts.append(self.fmt.upper())
        if self.size:
            parts.append("%.1fMB" % (self.size / 1024.0 / 1024.0))
        if self.license_url:
            parts.append(self.license_url)
        return " | ".join(parts)

    def filename(self) -> str:
        """生成安全的落盘文件名。"""
        base = "%s - %s" % (self.artist or "未知", self.title)
        base = _ILLEGAL_CHARS.sub("_", base).strip(" ._")
        base = re.sub(r"\s+", " ", base)[:120] or self.identifier or "track"
        return "%s.%s" % (base, self.fmt or "flac")


@dataclass
class DownloadOutcome:
    """下载结果，含真无损校验结论。"""

    path: str
    bytes_written: int = 0
    result: Optional[VerifyResult] = None

    @property
    def ok(self) -> bool:
        return os.path.isfile(self.path)

    def report(self) -> str:
        text = "已保存 %s" % self.path
        if self.result is not None:
            text += "\n" + format_report(self.result)
        return text


# ---------------------------------------------------------------------------
# Internet Archive
# ---------------------------------------------------------------------------


def _archive_query(query: str) -> str:
    """把用户输入包成 Solr 表达式，并限定为音频。"""
    query = query.strip()
    if not query:
        return "mediatype:(audio)"
    # 不含字段语法时整体当短语，避免检索词被拆散
    if ":" not in query and "(" not in query:
        query = '"%s"' % query.replace('"', "")
    return "(%s) AND mediatype:(audio)" % query


def search_archive(
    query: str,
    limit: int = 8,
    timeout: int = DEFAULT_TIMEOUT,
    session: Optional[requests.Session] = None,
) -> List[OpenTrack]:
    """检索 Internet Archive 的 FLAC 资源。

    先搜条目，再逐个查 metadata 找 flac 文件。任何一步失败都跳过该条目，
    不向外抛异常。
    """
    http = session or requests
    params = [
        ("q", _archive_query(query)),
        ("fl[]", "identifier"),
        ("fl[]", "title"),
        ("fl[]", "creator"),
        ("rows", str(max(limit, 1))),
        ("page", "1"),
        ("output", "json"),
    ]
    try:
        resp = http.get(ARCHIVE_SEARCH_URL, params=params, timeout=timeout)
        resp.raise_for_status()
        docs = ((resp.json() or {}).get("response") or {}).get("docs") or []
    except Exception as exc:  # noqa: BLE001 - 单源失败不影响整体检索
        logger.warning("Internet Archive 检索失败: %s", exc)
        return []

    tracks: List[OpenTrack] = []
    for doc in docs:
        identifier = doc.get("identifier")
        if not identifier:
            continue
        tracks.extend(_archive_item_tracks(identifier, http, timeout, limit - len(tracks)))
        if len(tracks) >= limit:
            break
    return tracks[:limit]


def _archive_item_tracks(
    identifier: str, http, timeout: int, remain: int
) -> List[OpenTrack]:
    """查单个条目的 metadata，挑出其中的 FLAC 文件。"""
    if remain <= 0:
        return []
    try:
        resp = http.get(ARCHIVE_METADATA_URL % quote(identifier), timeout=timeout)
        resp.raise_for_status()
        data = resp.json() or {}
    except Exception as exc:  # noqa: BLE001
        logger.debug("读取条目 %s 失败: %s", identifier, exc)
        return []

    meta = data.get("metadata") or {}
    item_title = _first_text(meta.get("title")) or identifier
    item_artist = _first_text(meta.get("creator")) or ""
    license_url = _first_text(meta.get("licenseurl")) or ""

    tracks: List[OpenTrack] = []
    for entry in data.get("files") or []:
        name = entry.get("name") or ""
        fmt = (entry.get("format") or "").lower()
        is_flac = name.lower().endswith(".flac") or "flac" in fmt
        if not is_flac:
            continue
        tracks.append(
            OpenTrack(
                source="archive",
                identifier=identifier,
                title=_first_text(entry.get("title")) or item_title,
                artist=_first_text(entry.get("artist")) or item_artist,
                album=_first_text(entry.get("album")) or item_title,
                url=ARCHIVE_DOWNLOAD_URL % (identifier, quote(name, safe="/")),
                fmt="flac",
                size=_to_int(entry.get("size")),
                duration=_to_float(entry.get("length")),
                license_url=license_url,
                notes=fmt,
            )
        )
        if len(tracks) >= remain:
            break
    return tracks


def _first_text(value) -> str:
    """metadata 字段可能是 str 也可能是 list，统一取第一个。"""
    if isinstance(value, (list, tuple)):
        value = value[0] if value else ""
    if isinstance(value, str):
        return value.strip()
    return "" if value is None else str(value).strip()


def _to_int(value) -> int:
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return 0


def _to_float(value) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


# ---------------------------------------------------------------------------
# Jamendo
# ---------------------------------------------------------------------------


def search_jamendo(
    query: str,
    client_id: str,
    limit: int = 8,
    timeout: int = DEFAULT_TIMEOUT,
    session: Optional[requests.Session] = None,
) -> List[OpenTrack]:
    """检索 Jamendo 的 CC 授权曲目。

    ``client_id`` 需要在 https://devportal.jamendo.com 免费申请。
    返回的下载地址是艺人上传的原始格式，可能是 mp3，所以下载后必须校验。
    """
    if not client_id:
        return []
    http = session or requests
    params = {
        "client_id": client_id,
        "format": "json",
        "limit": str(max(limit, 1)),
        "search": query,
        "audioformat": "flac",
        "include": "musicinfo",
        "order": "relevance",
    }
    try:
        resp = http.get(JAMENDO_API_URL, params=params, timeout=timeout)
        resp.raise_for_status()
        results = (resp.json() or {}).get("results") or []
    except Exception as exc:  # noqa: BLE001
        logger.warning("Jamendo 检索失败: %s", exc)
        return []

    tracks: List[OpenTrack] = []
    for item in results:
        if not item.get("audiodownload_allowed", True):
            continue
        url = item.get("audiodownload") or ""
        if not url:
            continue
        tracks.append(
            OpenTrack(
                source="jamendo",
                identifier=str(item.get("id") or ""),
                title=item.get("name") or "",
                artist=item.get("artist_name") or "",
                album=item.get("album_name") or "",
                url=url,
                fmt="flac",
                duration=_to_float(item.get("duration")),
                license_url=item.get("license_ccurl") or "",
                notes="原始上传格式，需校验",
            )
        )
    return tracks[:limit]


# ---------------------------------------------------------------------------
# 聚合检索与下载
# ---------------------------------------------------------------------------


def search_all(
    query: str,
    jamendo_client_id: str = "",
    limit: int = 8,
    timeout: int = DEFAULT_TIMEOUT,
) -> List[OpenTrack]:
    """检索所有可用源。两个源串行请求，单个源失败不影响另一个。"""
    tracks: List[OpenTrack] = []
    with requests.Session() as session:
        tracks.extend(
            search_jamendo(query, jamendo_client_id, limit, timeout, session)
        )
        tracks.extend(search_archive(query, limit, timeout, session))
    return tracks


def download(
    track: OpenTrack,
    dest_dir: str,
    progress_cb: Optional[Callable[[int, int], None]] = None,
    timeout: int = 60,
    check: bool = True,
) -> DownloadOutcome:
    """流式下载到目标目录，先写 ``.part`` 再原子改名，避免半截文件。

    ``check=True`` 时下载完立刻跑真无损校验，结果放在 ``outcome.result``。
    """
    if not track.url:
        raise ValueError("曲目没有下载地址: %s" % track.identifier)

    os.makedirs(dest_dir, exist_ok=True)
    filepath = os.path.join(dest_dir, track.filename())
    temp_path = filepath + ".part"

    written = 0
    try:
        with requests.get(track.url, stream=True, timeout=timeout) as resp:
            resp.raise_for_status()
            total = _to_int(resp.headers.get("content-length"))
            with open(temp_path, "wb") as handle:
                for chunk in resp.iter_content(DOWNLOAD_CHUNK):
                    if not chunk:
                        continue
                    handle.write(chunk)
                    written += len(chunk)
                    if progress_cb is not None:
                        try:
                            progress_cb(written, total)
                        except Exception:  # noqa: BLE001 - 进度回调失败不影响下载
                            logger.debug("进度回调失败", exc_info=True)
        os.replace(temp_path, filepath)
    except Exception:
        if os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except OSError:
                logger.debug("清理临时文件失败: %s", temp_path)
        raise

    outcome = DownloadOutcome(path=filepath, bytes_written=written)
    if check:
        outcome.result = verify(filepath)
        logger.info("下载校验结果: %s", format_report(outcome.result))
    return outcome


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _cli_search(args) -> int:
    tracks = search_all(args.query, args.jamendo_id, limit=args.limit)
    if not tracks:
        print("没有找到无损资源。")
        return 1
    for index, track in enumerate(tracks, 1):
        print("%2d. %s" % (index, track.describe()))
    print("\n用 download 子命令加 --index 下载，例如：")
    print('  python -m fuo_dl.open_sources download "%s" --index 1' % args.query)
    return 0


def _cli_download(args) -> int:
    tracks = search_all(args.query, args.jamendo_id, limit=max(args.index, 10))
    if not tracks:
        print("没有找到无损资源。")
        return 1
    if args.index < 1 or args.index > len(tracks):
        print("索引超出范围，共有 %d 条结果。" % len(tracks))
        return 1

    track = tracks[args.index - 1]
    print("开始下载：%s" % track.describe())

    def on_progress(done: int, total: int) -> None:
        if total:
            sys.stdout.write("\r  %.1f%%  %.1fMB/%.1fMB" % (
                done * 100.0 / total, done / 1048576.0, total / 1048576.0
            ))
        else:
            sys.stdout.write("\r  %.1fMB" % (done / 1048576.0))
        sys.stdout.flush()

    outcome = download(track, args.dest, progress_cb=on_progress)
    print("\n" + outcome.report())
    if outcome.result is not None and not outcome.result.ok:
        print("注意：该文件未通过真无损校验，建议不要当作无损收藏。")
        return 2
    return 0


def build_parser() -> argparse.ArgumentParser:
    """构造 CLI 参数解析器。"""
    parser = argparse.ArgumentParser(
        prog="python -m fuo_dl.open_sources",
        description="检索并下载 CC 授权 / 公共领域的无损音乐",
    )
    parser.add_argument(
        "--jamendo-id",
        default=os.environ.get("JAMENDO_CLIENT_ID", ""),
        help="Jamendo client_id，也可用环境变量 JAMENDO_CLIENT_ID",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    search = sub.add_parser("search", help="检索无损曲目")
    search.add_argument("query", help="关键词，例如 beethoven moonlight")
    search.add_argument("--limit", type=int, default=8, help="返回条数")
    search.set_defaults(func=_cli_search)

    fetch = sub.add_parser("download", help="按检索结果的序号下载")
    fetch.add_argument("query", help="关键词，需与检索时一致")
    fetch.add_argument("--index", type=int, default=1, help="结果序号，从 1 开始")
    fetch.add_argument("--dest", default=".", help="保存目录")
    fetch.set_defaults(func=_cli_download)
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    """CLI 入口，返回进程退出码。"""
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(message)s")
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())


__all__ = [
    "DownloadOutcome",
    "OpenTrack",
    "download",
    "search_all",
    "search_archive",
    "search_jamendo",
]
