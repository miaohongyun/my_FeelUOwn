package org.feeluown.mobile.provider.musicfree

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.feeluown.mobile.provider.lx.CandidateNetworkPolicy

/**
 * ProviderNetworkGateway —— 所有 Provider 网络请求的唯一宿主出口（阶段 J0 重写）。
 *
 * J0 相对 H2 的实质整改（不是"调用 enforceRedirect 就宣称保护"）：
 * - [executor] 契约改为返回 [RawResponse]：底层 HTTP 客户端**必须关闭自动重定向**，
 *   3xx 原样上交给网关；网关自己决定是否逐跳跟随，每跳重新执行完整权限校验。
 * - body 是 [BodyStream]：**流式读取**，响应头 Content-Length 预检 + 读取中累计字节，
 *   超限立即 [BodyStream.cancel] 并释放，不把无限_body 缓存进内存。
 * - 权限 [ProviderNetworkPermission] 绑定 Provider ID / 实际版本 / 实际脚本字节 SHA /
 *   精确域名 / 精确端口 / 允许方法 / 体积 / 超时 / 重定向策略 / 跳转上限。
 * - SHA 由调用方传入"当前实际加载脚本字节"的 SHA（[get] 的 currentScriptBytesSha256），
 *   与权限登记值不一致即拒绝；不允许固定字符串冒充当前脚本。
 *
 * 仍如实保留：DNS rebinding（commonMain 无法做连接层 IP 绑定，见 J0 审查报告）。
 */
class ProviderNetworkGateway(
    private val executor: NetworkExecutor,
) {
    /** 底层执行器：必须返回未跟随重定向的原始响应，body 可流式读取与取消。 */
    fun interface NetworkExecutor {
        suspend fun execute(request: Request): RawResponse
    }

    /** 原始响应：3xx 不被底层跟随，交给网关逐跳决策。 */
    data class RawResponse(
        val statusCode: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: BodyStream,
    )

    /** 可取消的流式响应体。read 返回 -1 表示结束；cancel 立即释放底层资源。 */
    interface BodyStream {
        suspend fun read(buffer: ByteArray): Int
        fun cancel()
    }

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
        val contentType: String? = null,
    )

    sealed class GatewayResult {
        data class Ok(val response: Response) : GatewayResult()
        data class Denied(val reason: String) : GatewayResult()
        data class Failed(val reason: String) : GatewayResult()
    }

    /** 权限描述：身份绑定 + 网络策略（J0 增加方法/端口/超时/跳转上限，SHA 由实际字节校验）。 */
    data class ProviderNetworkPermission(
        val providerId: String,
        val providerVersion: String,
        /** 登记的脚本字节 SHA-256；必须与 get() 传入的实际字节 SHA 一致。 */
        val scriptSha256: String,
        val candidatePolicy: CandidateNetworkPolicy,
        val allowedMethods: Set<String> = setOf("GET"),
        val redirectPolicy: RedirectPolicy = RedirectPolicy.DENY_ALL,
        val maxRedirects: Int = 0,
        val credentialPolicy: CredentialPolicy = CredentialPolicy.NONE,
        val authorizationBasis: String,
    ) {
        init {
            require(candidatePolicy.candidateId == providerId) { "策略 candidateId 与 Provider 不一致" }
            require(credentialPolicy == CredentialPolicy.NONE) { "Provider 网络权限禁止携带凭据" }
            require(authorizationBasis.isNotBlank()) { "授权依据必填" }
            require(providerVersion.isNotBlank()) { "插件实际版本必填" }
            require(scriptSha256.length == 64) { "脚本 SHA-256 必须为 64 位十六进制" }
            require(allowedMethods.isNotEmpty() && allowedMethods.all { it.isNotBlank() }) { "允许方法必填" }
            require(maxRedirects in 0..8) { "跳转上限必须在 0..8" }
            require(redirectPolicy == RedirectPolicy.DENY_ALL || maxRedirects > 0) {
                "启用逐跳跟随时 maxRedirects 必须大于 0"
            }
        }

        fun validationProblem(actualScriptBytesSha256: String): String? = when {
            !candidatePolicy.isBoundTo(actualScriptBytesSha256) ->
                "脚本字节 SHA 已变更或与登记不一致（实际=$actualScriptBytesSha256，登记=${candidatePolicy.boundSha256}）"
            !candidatePolicy.boundSha256.equals(scriptSha256, ignoreCase = true) ->
                "权限绑定 SHA 与策略登记不一致"
            else -> null
        }
    }

    enum class RedirectPolicy { DENY_ALL, REVALIDATE_EACH_HOP }
    enum class CredentialPolicy { NONE }

    companion object {
        private val FORBIDDEN_HEADERS = setOf("cookie", "authorization", "x-api-key", "proxy-authorization")
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private const val BUFFER_SIZE = 8 * 1024

        /** 简单字符串执行器适配（仅测试/无流式需求场景；生产必须注入关闭重定向的客户端）。 */
        fun fromStringExecutor(handler: suspend (Request) -> Response): ProviderNetworkGateway =
            ProviderNetworkGateway(NetworkExecutor { request ->
                val r = handler(request)
                RawResponse(r.statusCode, r.headers, byteStreamOf(r.body.encodeToByteArray()))
            })

        /** 从字节构造测试用 BodyStream。 */
        fun byteStreamOf(bytes: ByteArray): BodyStream = object : BodyStream {
            private var offset = 0
            private var cancelled = false
            override suspend fun read(buffer: ByteArray): Int {
                if (cancelled || offset >= bytes.size) return -1
                val n = minOf(buffer.size, bytes.size - offset)
                bytes.copyInto(buffer, 0, offset, offset + n)
                offset += n
                return n
            }
            override fun cancel() { cancelled = true }
        }

        /** 构造期拒绝的危险 URL 模式，与 CandidateNetworkPolicy 叠加。 */
        fun staticUrlRejection(url: String): String? {
            val lower = url.trim().lowercase()
            if (lower.startsWith("//")) return "禁止协议相对 URL"
            if (lower.startsWith("http://")) return "禁止明文 HTTP"
            if ("@" in url.substringBefore("/").substringAfter("://")) return "禁止 userinfo/凭据于 URL"
            return null
        }

        /** 大小写不敏感读取响应头。 */
        fun header(headers: Map<String, String>, name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        /** 解析 Location：绝对 URL / 协议相对 //host/path / 相对路径，均基于当前 URL。 */
        fun resolveLocation(currentUrl: String, location: String): String? {
            val loc = location.trim()
            if (loc.isEmpty()) return null
            if (loc.startsWith("http://") || loc.startsWith("https://")) return loc
            val schemeEnd = currentUrl.indexOf("://")
            if (schemeEnd < 0) return null
            val scheme = currentUrl.substring(0, schemeEnd)
            if (loc.startsWith("//")) return "$scheme:$loc"
            val authorityEnd = currentUrl.indexOf('/', schemeEnd + 3)
            val base = if (authorityEnd < 0) currentUrl else currentUrl.substring(0, authorityEnd)
            return if (loc.startsWith("/")) "$base$loc" else "$base/$loc"
        }
    }

    /**
     * 单次请求：权限校验 → 执行 → 逐跳重定向控制 → 流式限额读取。
     * [actualScriptBytesSha256] 必须是"当前实际加载脚本字节"的 SHA-256（由调用方现场计算），
     * 不允许传入固定常量冒充——传错即被 [ProviderNetworkPermission.validationProblem] 拒绝。
     */
    suspend fun get(
        permission: ProviderNetworkPermission,
        request: Request,
        actualScriptBytesSha256: String,
    ): GatewayResult {
        permission.validationProblem(actualScriptBytesSha256)?.let { return GatewayResult.Denied(it) }
        val baseProblem = staticAndPolicyCheck(permission, request)
        if (baseProblem != null) return GatewayResult.Denied(baseProblem)

        var current = request
        var redirects = 0
        return try {
            withTimeout(permission.candidatePolicy.requestTimeoutMs) {
                var result: GatewayResult? = null
                while (result == null) {
                    val raw = executor.execute(current)
                    if (raw.statusCode in REDIRECT_STATUSES) {
                        // 3xx：先释放 body，绝不把重定向响应当作成功内容返回。
                        raw.body.cancel()
                        val location = header(raw.headers, "Location")
                        result = when {
                            location == null -> GatewayResult.Denied("3xx 响应缺少 Location，拒绝跟随")
                            permission.redirectPolicy == RedirectPolicy.DENY_ALL ->
                                GatewayResult.Denied("重定向策略为 DENY_ALL，拒绝跟随：$location")
                            redirects >= permission.maxRedirects ->
                                GatewayResult.Denied("重定向次数超过上限 ${permission.maxRedirects}：$location")
                            else -> {
                                val next = resolveLocation(current.url, location)
                                val hopProblem = if (next == null) "无法解析重定向 Location：$location"
                                else staticAndPolicyCheck(permission, current.copy(url = next))
                                    ?: permission.validationProblem(actualScriptBytesSha256)
                                if (hopProblem != null || next == null) {
                                    GatewayResult.Denied("重定向目标未通过完整策略校验：${hopProblem ?: "解析失败"}")
                                } else {
                                    current = current.copy(url = next)
                                    redirects++
                                    null // 继续下一跳
                                }
                            }
                        }
                        continue
                    }
                    // 非 3xx：响应头预检查 + 流式限额读取。
                    val contentType = header(raw.headers, "Content-Type")
                    val declaredLength = header(raw.headers, "Content-Length")?.toLongOrNull()
                    result = if (declaredLength != null && declaredLength > permission.candidatePolicy.maxResponseBytes) {
                        raw.body.cancel()
                        GatewayResult.Denied(
                            "响应 Content-Length=$declaredLength 超过上限 ${permission.candidatePolicy.maxResponseBytes}",
                        )
                    } else {
                        when (val read = readBounded(raw.body, permission.candidatePolicy.maxResponseBytes)) {
                            is BodyRead.Ok -> GatewayResult.Ok(
                                Response(raw.statusCode, read.text, raw.headers, contentType),
                            )
                            is BodyRead.TooLarge -> {
                                raw.body.cancel()
                                GatewayResult.Denied(
                                    "响应体流式读取超过上限 ${permission.candidatePolicy.maxResponseBytes}（已读 ${read.observed} 字节）",
                                )
                            }
                        }
                    }
                }
                result
            }
        } catch (_: TimeoutCancellationException) {
            GatewayResult.Failed("请求超时（${permission.candidatePolicy.requestTimeoutMs}ms）")
        } catch (e: CancellationException) {
            throw e // 用户/协程取消原样传播，不吞、不伪装成功
        } catch (e: Exception) {
            GatewayResult.Failed("网络执行失败：${e.message}")
        }
    }

    /** 对一条请求做静态 URL + 方法 + 凭据 + 候选策略校验；返回 null 表示通过。 */
    private fun staticAndPolicyCheck(permission: ProviderNetworkPermission, request: Request): String? {
        if (request.method.uppercase() !in permission.allowedMethods) {
            return "请求方法 ${request.method} 未在权限允许列表 ${permission.allowedMethods}"
        }
        staticUrlRejection(request.url)?.let { return it }
        val cred = request.headers.keys.firstOrNull { it.lowercase() in FORBIDDEN_HEADERS }
        if (cred != null) return "Provider 请求禁止携带凭据头：$cred"
        return CandidateNetworkPolicyRegistryProbe.reject(permission.candidatePolicy, request.url)
    }

    private sealed class BodyRead {
        data class Ok(val text: String, val bytes: Int) : BodyRead()
        data class TooLarge(val limit: Int, val observed: Int) : BodyRead()
    }

    /** 流式读取并累计字节，超限立即返回 TooLarge（由调用方 cancel body）。 */
    private suspend fun readBounded(body: BodyStream, maxBytes: Int): BodyRead {
        val buffer = ByteArray(BUFFER_SIZE)
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        while (true) {
            val n = body.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) return BodyRead.TooLarge(maxBytes, total)
            chunks += buffer.copyOfRange(0, n)
        }
        val all = ByteArray(total)
        var off = 0
        chunks.forEach { it.copyInto(all, off); off += it.size }
        return BodyRead.Ok(all.decodeToString(), total)
    }
}

internal object CandidateNetworkPolicyRegistryProbe {
    fun reject(policy: CandidateNetworkPolicy, url: String): String? = policy.rejectionReason(url)
}
