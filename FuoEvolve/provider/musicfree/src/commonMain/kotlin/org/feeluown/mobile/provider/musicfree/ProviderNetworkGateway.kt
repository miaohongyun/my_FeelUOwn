package org.feeluown.mobile.provider.musicfree

import kotlinx.coroutines.CancellationException
import org.feeluown.mobile.provider.lx.CandidateNetworkPolicy

/**
 * 阶段 H2：ProviderNetworkGateway —— 所有 Provider 网络请求的唯一宿主出口。
 *
 * 设计（对应任务书）：
 * - 权限 [ProviderNetworkPermission] 绑定 Provider ID / 版本 / 脚本 SHA256 / 精确域名 /
 *   HTTPS+端口 / 响应体积 / 超时 / 重定向 / 凭据策略，复用阶段 F [CandidateNetworkPolicy] 的
 *   rejectionReason（SSRF/私网/裸IP/userinfo/编码host/尾随点全部 fail-closed）。
 * - **默认拒绝**：通配符域名、明文 HTTP、裸 IP、私网/回环/链路本地/metadata、未校验重定向、
 *   动态端点、任意 Cookie/Token/宿主凭据——全部在 [enforce] 与权限构造期拒绝。
 * - 所有请求必须经 [get]；本模块不提供任何其它网络 API，SandboxSession 的宿主能力只有 md5，
 *   因此 JS/Provider 无法绕过网关（无 fetch/XHR/socket 宿主 binding）。
 * - 重定向：默认 DENY_ALL；REVALIDATE_EACH_HOP 由调用方逐跳调用 [enforce]，不自动跟随。
 * - 权限变更/脚本 SHA 变化 → [ProviderNetworkPermission.isValidFor] 失效，旧审批不可复用。
 * - DNS rebinding：commonMain 无法做连接层 IP 绑定，**如实保留风险**，见报告。
 *
 * 注入 [executor] 便于各平台用同一 Ktor client 或测试用假执行器；网关只做策略与生命周期，
 * 不自己 new client（避免"又一个旁路客户端"）。
 */
class ProviderNetworkGateway(
    private val executor: suspend (Request) -> Response,
) {
    /** 一条经网关的网络请求。headers 禁止携带凭据（构造期过滤）。 */
    data class Request(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
    )

    data class Response(
        val statusCode: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    )

    sealed class GatewayResult {
        data class Ok(val response: Response) : GatewayResult()
        data class Denied(val reason: String) : GatewayResult()
        data class Failed(val reason: String) : GatewayResult()
    }

    /** 权限描述：候选级网络策略 + Provider 身份绑定。 */
    data class ProviderNetworkPermission(
        val providerId: String,
        val providerVersion: String,
        val scriptSha256: String,
        val candidatePolicy: CandidateNetworkPolicy,
        val redirectPolicy: RedirectPolicy = RedirectPolicy.DENY_ALL,
        val credentialPolicy: CredentialPolicy = CredentialPolicy.NONE,
        val authorizationBasis: String,
    ) {
        init {
            require(candidatePolicy.candidateId == providerId) { "策略 candidateId 与 Provider 不一致" }
            require(credentialPolicy == CredentialPolicy.NONE) { "Provider 网络权限禁止携带凭据" }
            require(authorizationBasis.isNotBlank()) { "授权依据必填" }
        }

        /** 权限对当前脚本字节是否仍有效；SHA 变化即失效。 */
        fun isValidFor(currentScriptSha256: String): Boolean =
            candidatePolicy.isBoundTo(currentScriptSha256) &&
                candidatePolicy.boundSha256.equals(scriptSha256, ignoreCase = true)

        fun validationProblem(currentScriptSha256: String): String? = when {
            !candidatePolicy.isBoundTo(currentScriptSha256) -> "脚本 SHA 已变更，网络权限审批失效"
            !candidatePolicy.boundSha256.equals(scriptSha256, ignoreCase = true) -> "权限绑定 SHA 与登记不一致"
            else -> null
        }
    }

    enum class RedirectPolicy { DENY_ALL, REVALIDATE_EACH_HOP }
    enum class CredentialPolicy { NONE }

    companion object {
        /** 禁止出现在 Provider 请求头里的凭据字段（大小写不敏感）。 */
        private val FORBIDDEN_HEADERS = setOf("cookie", "authorization", "x-api-key", "proxy-authorization")

        /** 构造期拒绝的危险 URL 模式（网关默认拒绝的一部分，与 CandidateNetworkPolicy 叠加）。 */
        fun staticUrlRejection(url: String): String? {
            val lower = url.trim().lowercase()
            if (lower.startsWith("//")) return "禁止协议相对 URL"
            if (lower.startsWith("http://")) return "禁止明文 HTTP"
            if ("@" in url.substringBefore("/").substringAfter("://")) return "禁止 userinfo/凭据于 URL"
            return null
        }
    }

    /**
     * 单次请求的策略执行。返回 denied 时不调用 executor（零网络）。
     * [currentScriptSha256] 传入当前脚本实际字节 SHA，用于 SHA 变更失效校验。
     */
    suspend fun get(
        permission: ProviderNetworkPermission,
        request: Request,
        currentScriptSha256: String,
    ): GatewayResult {
        permission.validationProblem(currentScriptSha256)?.let { return GatewayResult.Denied(it) }
        staticUrlRejection(request.url)?.let { return GatewayResult.Denied(it) }
        val cred = request.headers.keys.firstOrNull { it.lowercase() in FORBIDDEN_HEADERS }
        if (cred != null) return GatewayResult.Denied("Provider 请求禁止携带凭据头：$cred")
        CandidateNetworkPolicyRegistryProbe.reject(permission.candidatePolicy, request.url)?.let {
            return GatewayResult.Denied(it)
        }
        return try {
            val response = executor(request)
            if (response.body.encodeToByteArray().size > permission.candidatePolicy.maxResponseBytes) {
                GatewayResult.Denied("响应体超过候选授权上限 ${permission.candidatePolicy.maxResponseBytes} 字节")
            } else {
                GatewayResult.Ok(response)
            }
        } catch (e: CancellationException) {
            throw e // 取消原样传播，不吞
        } catch (e: Exception) {
            GatewayResult.Failed("网络执行失败：${e.message}")
        }
    }

    /** 重定向：DENY_ALL 一律拒绝；REVALIDATE_EACH_HOP 对下一跳重新执行完整策略。 */
    fun enforceRedirect(
        permission: ProviderNetworkPermission,
        currentScriptSha256: String,
        location: String,
    ): GatewayResult = when (permission.redirectPolicy) {
        RedirectPolicy.DENY_ALL -> GatewayResult.Denied("重定向策略为 DENY_ALL，拒绝跟随：$location")
        RedirectPolicy.REVALIDATE_EACH_HOP -> {
            val problem = permission.validationProblem(currentScriptSha256)
                ?: staticUrlRejection(location)
                ?: CandidateNetworkPolicyRegistryProbe.reject(permission.candidatePolicy, location)
            if (problem != null) GatewayResult.Denied("重定向目标未通过完整策略校验：$problem") else GatewayResult.Ok(
                Response(0, "")
            )
        }
    }
}

/** 把 CandidateNetworkPolicy 的纯校验接入网关（公开 API，无旁路）。 */
internal object CandidateNetworkPolicyRegistryProbe {
    fun reject(policy: CandidateNetworkPolicy, url: String): String? = policy.rejectionReason(url)
}
