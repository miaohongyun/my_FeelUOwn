from pathlib import Path
from source_router import (
    AllSourcesDownError,
    MusicSourceRouter,
    SourceUnavailableError,
    probe_stream_reachable,
)


def main():
    base_dir = Path(__file__).resolve().parent
    router = MusicSourceRouter(default_config_path=base_dir / "sources_default.json")

    print("=== 1. 启动完成（缓存优先，不阻塞） ===")
    print("当前已启用源:", [s["id"] for s in router.get_enabled_sources()])

    print("\n=== 2. 尝试更新配置与官方镜像列表 ===")
    sync_msg = router.refresh_remote_config()
    print("同步结果:", sync_msg)

    print("\n=== 3. 测试 Radio Paradise 官方真无损 FLAC 频道 + 流探测 ===")
    try:
        rp_tracks = router.fetch_with_failover(source_filter="radioparadise_flac")
        for t in rp_tracks:
            print(f"  - {t.title} | 歌手: {t.artist} | 编码: {t.codec} | 无损: {t.is_lossless}")
        if rp_tracks:
            first_stream = rp_tracks[0].stream_url
            print(f"  正在探测首条 FLAC 流可达性: {first_stream}")
            probe = probe_stream_reachable(first_stream, timeout=6.0)
            print("  流探测成功:", probe)
    except Exception as e:
        print("  Radio Paradise 当前网络不可达或超时:", e)

    print("\n=== 4. 测试 Radio Browser 国内电台拉取 + 首条流探测 ===")
    try:
        cn_radios = router.fetch_with_failover(source_filter="radiobrowser_cn", query="")
        for r in cn_radios[:5]:
            print(f"  - {r.title} ({r.codec} {r.bitrate_kbps or '?'}kbps) -> {r.stream_url}")
        for r in cn_radios[:3]:
            try:
                probe = probe_stream_reachable(r.stream_url, timeout=5.0)
                print(f"  国内电台流可播 [{r.title}]:", probe)
                break
            except Exception as stream_err:
                print(f"  电台流跳过 [{r.title}]: {stream_err}")
    except Exception as e:
        print("  Radio Browser 访问失败:", e)

    print("\n=== 5. 验证失败熔断与自动降级切换 ===")
    # 模拟某个节点连续失败 3 次，观察它是否自动进入冷却并切换候选源
    test_ep = "radiobrowser_cn:rb_de1"
    for i in range(3):
        state = router.breaker.record_failure(test_ep, f"模拟超时 #{i+1}")
        print(f"  模拟故障第 {i+1} 次 -> {test_ep} 状态: {state}")
    print("  当前熔断快照:", router.breaker.snapshot())

    # 测试完清理模拟故障记录，免得影响后续真实使用
    router.breaker.reset_all()
    print("\n测试完成，已重置模拟熔断记录。")


if __name__ == "__main__":
    main()
