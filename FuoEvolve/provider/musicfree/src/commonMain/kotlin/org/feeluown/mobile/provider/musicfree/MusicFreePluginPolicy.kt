package org.feeluown.mobile.provider.musicfree

import org.feeluown.mobile.provider.lx.CandidateNetworkPolicy

/**
 * 阶段 G2：把 MusicFree 插件映射成"候选级网络权限"，复用阶段 F 已实现的
 * [CandidateNetworkPolicy]（SSRF fail-closed + SHA 绑定 + 具名域名），不新增/放宽任何网络策略。
 *
 * 关键结论（诚实记录）：MusicFree 官方插件分两类，适配层对它们的处理不同——
 * 1. 固定具名 HTTPS 域名的插件（如 audiomack 的 api.audiomack.com）：可生成独立候选级 policy；
 * 2. 自托管/动态端点插件（Navidrome/WebDAV 用户自建服务器、YouTube 上游返回 URL）：
 *    端点由用户配置或响应体决定，静态不可审、常为私网/裸 IP。**不放宽 SSRF 去兼容它们**，
 *    一律 deny-all 占位，标记"需受控端点授权流程（本阶段未实现）"，不进入真实网络执行。
 *
 * 任何情况下插件正文 SHA 与登记不一致 → 审批失效。
 */
object MusicFreePluginPolicy {

    /** 为一个固定具名域名的插件生成候选级网络权限；不满足条件则 deny-all。 */
    fun forPlugin(record: MusicFreePluginRecord, actualSha256: String): PolicyDecision {
        val shaOk = record.sha256.equals(actualSha256.trim(), ignoreCase = true)
        if (!shaOk) return PolicyDecision.Denied("插件正文 SHA 与登记不一致，网络审批失效")
        if (!record.isStaticallyClean) {
            val hits = record.staticFindings.filter { it.value > 0 }
            return PolicyDecision.Denied("静态审查有风险命中：$hits")
        }
        if (record.declaredHosts.isEmpty()) {
            return PolicyDecision.Denied(
                "插件无静态可审的固定域名（自托管/动态端点），候选级网络权限不适用；" +
                    "不放宽 SSRF，需独立的受控端点授权流程（本阶段未实现）"
            )
        }
        val policy = CandidateNetworkPolicy(
            candidateId = record.pluginId,
            boundSha256 = record.sha256,
            boundVersion = record.version,
            allowedHosts = record.declaredHosts,
            authorizationBasis = "G2 官方插件静态域名授权：${record.origin} / ${record.license} / ${record.evidence}",
        )
        // 说明：CandidateNetworkPolicyRegistry.validateAgainstRegistry 针对 LX 候选登记表，
        // 这里用插件自身登记（上面 shaOk/isStaticallyClean 已等价完成 SHA + 静态审查校验），
        // 不调用它以避免因两套台账 ID 不同产生误判。
        // 用同一 rejectionReason 做一次自校验，确保生成的策略非空名单可用。
        val probe = "https://${record.declaredHosts.first()}/"
        if (policy.rejectionReason(probe) != null) {
            return PolicyDecision.Denied("生成的候选级策略无法通过自检（策略构造异常）")
        }
        return PolicyDecision.Authorized(policy)
    }

    /**
     * 对解析出的 URL 做最终策略校验。返回 null 表示允许；非 null 为拒绝原因。
     * 注意：policy.rejectionReason 本身已是"null=允许"语义，切勿反转。
     */
    fun rejectionReason(decision: PolicyDecision, url: String): String? = when (decision) {
        is PolicyDecision.Authorized -> decision.policy.rejectionReason(url)
        is PolicyDecision.Denied -> decision.reason
    }

    sealed class PolicyDecision {
        data class Authorized(val policy: CandidateNetworkPolicy) : PolicyDecision()
        data class Denied(val reason: String) : PolicyDecision()
    }
}
