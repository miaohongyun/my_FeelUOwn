package org.feeluown.mobile.provider.musicfree

/**
 * 阶段 H3：Audiomack 播放域名审查结论（2026-11-20）。
 *
 * 任务书要求：不能直接把解析出的域名加入白名单；必须记录真实解析域名、每跳重定向、归属与使用条件、
 * 目标地区可访问性、DNS/SSRF、URL 有效期/签名/请求头、下载 vs 流媒体，证据充分才建精确域名策略；
 * 否则保持拒绝并记录原因，不用全局白名单，不绕过访问限制。
 */
object MusicFreePlaybackDomainAudit {

    enum class Decision { AUTHORIZED, DENIED }

    data class DomainEvidence(
        val hostname: String,
        val role: String,
        val dnsAddresses: List<String>,
        val ownership: String,
        val evidence: String,
    )

    /** 真实解析域名（G3 实测 signedUrl host）与候选授权域名的对比。 */
    val declaredApiHost = "api.audiomack.com"
    val resolvedPlaybackHost = "music.audiomack.com"

    val evidence = listOf(
        DomainEvidence(
            hostname = declaredApiHost,
            role = "候选级授权域名（搜索/musicUrl API）",
            dnsAddresses = listOf("2a03:2880:f127:83:face:b00c:0:25de", "98.159.108.71"),
            ownership = "Audiomack Inc. 基础设施（Meta/Facebook IP 段 2a03:2880::/32 / 98.159.108.*）",
            evidence = "G3 真实 OAuth 搜索/解析均在此域名，HTTP 200；插件源码静态可见的唯一 API 域名",
        ),
        DomainEvidence(
            hostname = resolvedPlaybackHost,
            role = "signedUrl 实际播放域名（由 API 响应体返回，非插件静态声明）",
            dnsAddresses = listOf("2a03:2880:f10d:183:face:b00c:0:25de", "108.160.170.43"),
            ownership = "同属 Meta/Facebook IP 段，但为不同主机名；运营方归属与授权关系未在 API 条款中明示",
            evidence = "G3 实测 signedUrl scheme=https、host=music.audiomack.com；Range 0-0 → 206 audio/mp4、Content-Range bytes 0-0/4364494",
        ),
    )

    /** H3 决策：证据不足 → 保持拒绝。 */
    data class AuditDecision(
        val decision: Decision,
        val hostname: String,
        val reasons: List<String>,
        val followUpRequired: List<String>,
    )

    val playbackHostDecision = AuditDecision(
        decision = Decision.DENIED,
        hostname = resolvedPlaybackHost,
        reasons = listOf(
            "解析域名 $resolvedPlaybackHost ≠ 候选静态授权域名 $declaredApiHost，且为响应体返回的动态端点",
            "Audiomack 服务条款禁止'Alter or modify any part of the Services'、禁止非公共搜索引擎的抓取/索引，第三方客户端用签名 URL 播放未被明确授权",
            "条款禁止分发他人版权作品副本；无法确认本项目用例属于条款允许范围",
            "API 文档(audiomack.com/data-api/docs)描述登录态数据 API，未授权匿名签名 URL 供第三方 App 流媒体消费",
            "本轮本地 HTTPS 代理失效，无法新增每跳重定向/URL 有效期签名参数的实时取证（G3 已记录 host/scheme/206，但重定向跳数与过期时间未复核）",
            "目标地区可访问性与使用方式未经确认",
            "DNS rebinding 仍未在 commonMain 解决；两个域名解析到 Meta IP 段但主机名不同，不能据此推断授权",
        ),
        followUpRequired = listOf(
            "取得 Audiomack 官方对第三方流媒体播放的书面授权或明确 API 条款",
            "代理恢复后复核 signedUrl 每跳重定向、Expires/X-Amz-Expires 等有效期参数与请求头要求",
            "确认目标地区可访问性与使用方式、下载 vs 仅流媒体",
            "人工审核通过后才可建立精确域名策略（非通配符），并绑定脚本 SHA",
        ),
    )

    /** 是否允许把某播放域名加入候选授权：本阶段对 Audiomack 播放域名一律 false。 */
    fun mayAllowPlaybackHost(hostname: String): Boolean =
        hostname.equals(resolvedPlaybackHost, ignoreCase = true) && playbackHostDecision.decision == Decision.AUTHORIZED

    /** 网关是否应拒绝该播放 URL（未授权域名 → 拒绝）。 */
    fun playbackUrlRejectionReason(url: String): String? {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').lowercase()
        if (host == resolvedPlaybackHost) {
            return "播放域名 $resolvedPlaybackHost 未获 H3 授权（证据不足/条款未明确），保持拒绝；不放宽白名单"
        }
        return "播放域名 <$host> 不在 Audiomack 审查范围，默认拒绝"
    }
}
