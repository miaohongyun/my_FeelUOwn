import tempfile
import time
import urllib.request
from pathlib import Path

from mode_b_sources import ModeBRouter


def main():
    root = Path(__file__).resolve().parent
    default_cfg = root / "mode_b_sources" / "sources_default.json"

    with tempfile.TemporaryDirectory() as tmp:
        router = ModeBRouter(default_config_path=default_cfg, cache_dir=Path(tmp))
        candidates = router.get_candidates()
        print("已启用候选源:", [c.id for c in candidates])

        for c in candidates:
            print(f"\n--- 实测真实源: {c.id} ({c.name}) ---")
            router.set_manual_override(c.id)
            t0 = time.monotonic()
            try:
                res = router.resolve_play_url()
                ms = int((time.monotonic() - t0) * 1000)
                print(f"API 成功 ({ms}ms) -> 提取流 URL: {res['url']}")

                # 顺便只读前 2KB 验证音频流本机可达性
                req = urllib.request.Request(
                    res["url"],
                    headers={"User-Agent": "my_FeelUOwn-ModeB/1.0", "Range": "bytes=0-2047"},
                )
                t1 = time.monotonic()
                with urllib.request.urlopen(req, timeout=6.0) as resp:
                    chunk = resp.read(2048)
                    stream_ms = int((time.monotonic() - t1) * 1000)
                    print(
                        f"流探测成功 ({stream_ms}ms) | HTTP {getattr(resp, 'status', 200)} "
                        f"| Content-Type={resp.headers.get('Content-Type')} | bytes={len(chunk)}"
                    )
            except Exception as e:
                ms = int((time.monotonic() - t0) * 1000)
                print(f"失败 ({ms}ms): {e}")


if __name__ == "__main__":
    main()
