package org.feeluown.mobile.provider.lx

import org.feeluown.mobile.sourceupdate.CommunitySourceRegistry

/**
 * 阶段 F：候选级网络权限（最小授权）。
 *
 * 设计红线（与阶段 E 的空 allowedHosts fail-closed 一致，绝不放宽）：
 * - 一个候选只能访问静态审查 + 人工确认过的**具名域名**，禁止通配符、禁止裸 IP；
 * - 默认只允许 HTTPS + 443，禁止 userinfo / 百分号编码 host / 尾随点 / IPv6 字面量；
 * - SSRF 判定复用 [LxUrlSafety]（localhost / 私网 / 回环 / 链路本地 / 元数据地址）；
 * - 权限与候选版本 + SHA256 绑定，脚本一变审批自动失效（[isBoundTo] /
 *   [CandidateNetworkPolicyRegistry.validateAgainstRegistry]）；
 * - 不存在「继承另一个候选域名」的路径——每个 policy 自带独立 allowedHosts；
 * - credentialPolicy 只有 NONE：禁止把用户 Cookie/Token 写入候选网络权限；
 * - redirectPolicy 默认 DENY_ALL；本阶段不做安全的解析/连接绑定，重定向一律拒绝，
 *   DNS rebinding 的遗留风险继续保留，不在真实验证范围声明里抹去。
 */
enum class CandidateRedirectPolicy {
    /** 全部拒绝（本阶段默认，禁止把重定向当放行）。 */
    DENY_ALL,
    /** 每一跳都重新执行完整 URL + 主机策略校验（预留，当前不启用）。 */
    REVALIDATE_EACH_HOP,
}

/** 候选允许的凭据策略。只有 NONE：不得把任何用户凭据加入候选级网络权限。 */
enum class CandidateCredentialPolicy { NONE }

/**
 * 单个候选的网络权限描述。[boundSha256] 必须与隔离区脚本实际字节的 SHA256 完全一致才生效。
 */
data class CandidateNetworkPolicy(
    val candidateId: String,
    val boundSha256: String,
    val boundVersion: String? = null,
    /** 具名域名精确匹配，禁止通配符（*.x）、禁止 IP 字面量。 */
    val allowedHosts: Set<String>,
    val allowedSchemes: Set<String> = setOf("https"),
    val allowedPorts: Set<Int> = setOf(443),
    val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    val redirectPolicy: CandidateRedirectPolicy = CandidateRedirectPolicy.DENY_ALL,
    val credentialPolicy: CandidateCredentialPolicy = CandidateCredentialPolicy.NONE,
    /** 人工授权依据（审查日期 + 域名来源），写入台账可复核。 */
    val authorizationBasis: String,
    /** 该候选尚未获授权时的拒绝原因（allowedHosts 为空时必填）。 */
    val denialReason: String? = null,
) {
    init {
        require(credentialPolicy == CandidateCredentialPolicy.NONE) {
            "候选级网络权限禁止携带用户凭据"
        }
        require(allowedHosts.none { it.isBlank() || it.startsWith("*") }) {
            "候选级网络权限禁止通配符或空域名"
        }
        require(allowedHosts.isNotEmpty() || denialReason != null) {
            "无授权域名时必须给出 denialReason"
        }
    }

    /** 权限是否仍绑定该脚本字节；SHA 变化即失效。 */
    fun isBoundTo(actualSha256: String): Boolean =
        boundSha256.equals(actualSha256.trim(), ignoreCase = true)

    val isDenyAll: Boolean get() = allowedHosts.isEmpty()

    /**
     * 对一个 URL 做完整策略校验。返回 null 表示允许；非 null 为拒绝原因。
     * 与 [LxNetworkPolicy.rejectionReason] 语义一致：空名单 = 拒绝一切。
     */
    fun rejectionReason(rawUrl: String): String? {
        if (allowedHosts.isEmpty()) {
            return denialReason ?: "候选 ${candidateId} 无授权域名，拒绝全部网络请求"
        }
        val value = rawUrl.trim()
        if (value.isEmpty()) return "URL 为空"
        if (value.any { it.isISOControl() || it.isWhitespace() }) return "URL 含控制字符或空白"

        val lower = value.lowercase()
        val scheme = lower.substringBefore("://", "")
        if (scheme !in allowedSchemes) {
            return "协议 $scheme 未在候选授权范围（仅 ${allowedSchemes.joinToString()}）"
        }
        // 纯 SSRF 判定（此时候选已授权 https，复用 httpsOnly=true 的门面）。
        LxUrlSafety.dangerousTargetReason(value, httpsOnly = "http" !in allowedSchemes)?.let { return it }

        val afterScheme = value.substring(scheme.length + 3)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) return "URL 禁止包含 userinfo/凭据"
        if ('%' in authority) return "URL 的 host 禁止百分号编码"
        if (authority.endsWith(".")) return "URL 的 host 禁止尾随点"
        if (authority.startsWith("[")) return "禁止 IPv6 字面量地址"

        val hostPart = authority.substringBefore(':')
        val portPart = authority.substringAfter(':', "")
        val host = hostPart.lowercase()
        if (host.isEmpty() || host.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' }) {
            return "非法主机名"
        }
        // 裸 IP（含 0x/八进制等变体）不作为候选授权域名，避免不可审计的端点。
        if (host.all { it.isDigit() || it == '.' || it == 'x' || it in 'a'..'f' }) {
            return "禁止使用裸 IP 作为候选域名：$host"
        }
        val port = if (portPart.isEmpty()) {
            DEFAULT_HTTPS_PORT
        } else {
            portPart.toIntOrNull() ?: return "非法端口：$portPart"
        }
        if (port !in allowedPorts) return "端口 $port 未在候选授权范围（仅 ${allowedPorts.joinToString()}）"
        if (host !in allowedHosts) return "域名未在本候选授权列表：$host"
        return null
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 1 * 1024 * 1024
        const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000L
        const val DEFAULT_REQUEST_TIMEOUT_MS = 15_000L
        const val DEFAULT_HTTPS_PORT = 443
    }
}

/**
 * 候选网络权限登记。只登记 E2 静态无 BLOCK 且域名为具名 HTTPS 主机的候选；
 * 至尊（裸 IP + 明文 HTTP + 1314）与 Flower（混淆 + 裸 IP 初始化）为 deny-all 占位。
 */
object CandidateNetworkPolicyRegistry {

    /** 与登记表 SHA 绑定（写死常量，防止运行期被脚本内容影响）。 */
    val huibq = CandidateNetworkPolicy(
        candidateId = "huibq",
        boundSha256 = "a9adeb456690b523008038224091cee31977606302d79082eb06adb3c75c9146",
        boundVersion = "v1.2.0",
        allowedHosts = setOf("lxmusicapi.onrender.com"),
        authorizationBasis = "2026-11-20 E2 静态无 BLOCK；脚本唯一 API 域名 lxmusicapi.onrender.com（HTTPS/443）；SHA 绑定",
    )

    val gdStudio = CandidateNetworkPolicy(
        candidateId = "gd-studio",
        boundSha256 = "05631b29ff18a4b8688b4464abf0f38952474cd4d8d6c4149c365b0190c7fd28",
        boundVersion = "1.0.1",
        allowedHosts = setOf("music-api.gdstudio.xyz"),
        authorizationBasis = "2026-11-20 E2 静态无 BLOCK；脚本唯一 API 域名 music-api.gdstudio.xyz（HTTPS/443）；SHA 绑定",
    )

    val ikun = CandidateNetworkPolicy(
        candidateId = "ikun-v515",
        boundSha256 = "8db6c45bc225f1f15fc2e96f7d9ad3bf2e49dd1f2a998cdf23704185ea41fe89",
        allowedHosts = setOf("api.ikunshare.com"),
        authorizationBasis = "2026-11-20 E2 静态无 BLOCK；声明域名 api.ikunshare.com（HTTPS/443）；SHA 绑定",
    )

    val animemusic = CandidateNetworkPolicy(
        candidateId = "animemusic",
        boundSha256 = "cb26455f3d08be3d9fa034740a8d4bf186caf6c4200493c096026a6e2c3d0fa8",
        allowedHosts = setOf("animemusic.bzxhkj.com"),
        authorizationBasis = "2026-11-20 E2 静态无 BLOCK；声明域名 animemusic.bzxhkj.com（HTTPS/443）；SHA 绑定",
    )

    /** 聚合接口 juice：仅授权 init.conf 所在域名；响应体内动态二次 URL 不在授权范围。 */
    val juheInitOnly = CandidateNetworkPolicy(
        candidateId = "juhe",
        boundSha256 = "f7a661f01af7ea23035d5fac3b89d7c756372fc8a64bbfd649918f8a101cbf33",
        allowedHosts = setOf("api.music.lerd.dpdns.org"),
        authorizationBasis = "2026-11-20 仅授权 init.conf 初始化域名；303 响应体内二次 URL 为动态端点，未审不授权；SHA 绑定",
    )

    val zhizunDenied = CandidateNetworkPolicy(
        candidateId = "zhizun",
        boundSha256 = "ddc095aca3dc4df5a00ea6df3b84a31d17c1055fad3e1ee09edd6308ccc17210",
        allowedHosts = emptySet(),
        authorizationBasis = "2026-11-20 拒绝授权：唯一端点 110.42.36.53:1314 为裸 IP + 明文 HTTP + 非标端口，不满足 HTTPS/443/具名域名",
        denialReason = "至尊源端点为裸 IP 明文 HTTP:1314，未通过候选网络权限审查，拒绝全部请求",
    )

    val flowerDenied = CandidateNetworkPolicy(
        candidateId = "flower-pdone",
        boundSha256 = "15c166987edde41cf5917b3160c0ab253ff17e5e6bc6765c9eaa11a822c4aa97",
        allowedHosts = emptySet(),
        authorizationBasis = "2026-11-20 QUARANTINE 保持：重度混淆，初始化拼接裸 IP http://97.64.37.235 + 远程加载，无法静态审计",
        denialReason = "野花脚本混淆且初始化含裸 IP/远程加载，未通过审查，拒绝全部请求",
    )

    val all: List<CandidateNetworkPolicy> = listOf(
        huibq, gdStudio, ikun, animemusic, juheInitOnly, zhizunDenied, flowerDenied,
    )

    fun forCandidate(candidateId: String): CandidateNetworkPolicy? =
        all.firstOrNull { it.candidateId.equals(candidateId, ignoreCase = true) }

    /**
     * 校验某 policy 是否仍与登记表的 SHA 绑定。任何非 null 返回都表示审批已失效。
     * 登记无 SHA / 候选未登记 / SHA 变化 → 一律失效（fail closed）。
     */
    fun validateAgainstRegistry(policy: CandidateNetworkPolicy): String? {
        val record = CommunitySourceRegistry.find(policy.candidateId)
            ?: return "候选 ${policy.candidateId} 未在登记表"
        val registrySha = record.sha256 ?: return "登记表无 SHA，网络权限审批失效"
        if (!policy.isBoundTo(registrySha)) return "脚本 SHA 已变更，原网络权限审批自动失效"
        return null
    }
}
