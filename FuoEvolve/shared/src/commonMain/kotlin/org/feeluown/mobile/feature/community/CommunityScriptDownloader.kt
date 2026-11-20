package org.feeluown.mobile.feature.community

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.withTimeoutOrNull
import org.feeluown.mobile.provider.lx.LxUrlSafety
import org.feeluown.mobile.sourceupdate.BoundedBodyRead
import org.feeluown.mobile.sourceupdate.HttpBodyLimits
import org.feeluown.mobile.sourceupdate.readBodyBounded

/**
 * 社区脚本的**受控下载器**（阶段 D 修复 P1-2）。
 *
 * 只把脚本文本下载到内存，交给 Repository 做哈希核验与隔离区写入；本类不写文件、
 * 不执行脚本、不做任何安装。安全约束：
 * - 仅 HTTPS；localhost/私网/IPv6 字面量/云元数据地址一律拒绝（复用 LX 的 SSRF 判定）；
 * - 禁止跨域重定向：客户端 followRedirects=false，任何 3xx 都当成拒绝（重定向目标未经校验）；
 * - 响应体大小：Content-Length 预判 + 流式累计双保险，超过 [maxBytes] 立即中止；
 * - 总超时 [timeoutMillis]；异常分类返回，不把网络失败伪装成成功。
 */
class CommunityScriptDownloader(
    private val client: HttpClient,
    private val maxBytes: Int = HttpBodyLimits.SCRIPT_DOWNLOAD_BYTES,
    private val timeoutMillis: Long = 15_000L,
) {
    sealed interface DownloadResult {
        data class Success(val text: String, val bytes: Int, val finalUrl: String) : DownloadResult
        /** SSRF / 协议 / 重定向 / HTTP 状态等安全拒绝。 */
        data class Rejected(val reason: String) : DownloadResult
        data object Timeout : DownloadResult
        data class TooLarge(val limit: Int, val observed: Long) : DownloadResult
        data class Failed(val reason: String) : DownloadResult
    }

    suspend fun download(url: String): DownloadResult {
        // 1) URL 安全校验（HTTPS-only + SSRF + 严格语法），在发请求之前。
        val rejection = validateDownloadUrl(url)
        if (rejection != null) return DownloadResult.Rejected(rejection)

        // 2) 带超时的请求。客户端必须配置 followRedirects=false（由工厂保证）。
        val response: HttpResponse = try {
            withTimeoutOrNull(timeoutMillis) { client.get(url) }
                ?: return DownloadResult.Timeout
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消信号必须上抛，不能吞掉。
        } catch (e: Exception) {
            return DownloadResult.Failed("网络失败: ${e.message.orEmpty()}")
        }

        // 3) 重定向一律拒绝：目标域名未经 SSRF/白名单校验。
        if (response.status.value in 300..399) {
            return DownloadResult.Rejected("检测到重定向（${response.status.value}），不接受未校验的重定向目标")
        }
        if (response.status.value !in 200..299) {
            return DownloadResult.Rejected("HTTP ${response.status.value}")
        }

        // 4) 流式有界读取（Content-Length 预判 + 累计超限即取消）。
        return when (val body = readBodyBounded(response, maxBytes)) {
            is BoundedBodyRead.Ok -> DownloadResult.Success(body.text, body.bytes, url)
            is BoundedBodyRead.TooLarge -> DownloadResult.TooLarge(body.limit, body.observed)
        }
    }
}

/**
 * 受控下载的严格 URL 校验：在复用 LX 的 SSRF 判定之外，补上解析层容易被绕过的语法边界。
 * 返回 null 表示通过，否则为拒绝原因。只用于"脚本下载"这一条受控通道，
 * 不改变 LX 运行时脚本 lx.request 的白名单语义。
 *
 * 覆盖：HTTPS-only、userinfo、异常端口、尾随点、百分号编码主机、path/query 里的控制字符、
 * 以及 [LxUrlSafety] 提供的 localhost / IPv4 / IPv6 字面量 / 非标准 IP 表示判定。
 *
 * 已知边界（fail-closed 说明）：commonMain 无法做 DNS 解析后逐 IP 校验，因此存在 DNS rebinding
 * 的理论风险。缓解手段是"只允许下载候选记录里已登记的 URL"（由 Repository 校验白名单），
 * 以及对时间/体积的硬限制。这里不声称已彻底防住 DNS rebinding。
 */
internal fun validateDownloadUrl(raw: String): String? {
    val url = raw.trim()
    if (url != raw) return "URL 含首尾空白，拒绝"
    // 先过 LX 的 SSRF 检测（HTTPS-only + localhost/IP 字面量/非标准 IP 表示）。
    val danger = LxUrlSafety.dangerousTargetReason(url, httpsOnly = true)
    if (danger != null) return danger

    val lower = url.lowercase()
    val authority = lower.substringAfter("://", "").substringBefore('/')
        .substringBefore('?').substringBefore('#')
    if (authority.isEmpty()) return "URL 缺少主机名"
    if ('@' in authority) return "URL 含 userinfo，拒绝"
    if ('%' in authority) return "URL 主机含百分号编码，拒绝"

    // 端口：只允许默认 443（显式 :443 也接受）；其余端口一律拒绝，避免探测内网服务。
    val hostPart = if (authority.startsWith("[")) {
        authority.substringBefore(']') + "]"
    } else {
        authority.substringBefore(':')
    }
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        val after = authority.substring(close + 1)
        if (after.isNotEmpty() && after != ":443") return "IPv6 URL 端口非法（仅允许默认 443）"
    } else {
        val colon = authority.indexOf(':')
        if (colon >= 0) {
            val port = authority.substring(colon + 1)
            if (port.toIntOrNull() != 443) return "URL 端口非法（仅允许 443），拒绝"
        }
    }
    if (hostPart.endsWith(".")) return "URL 主机含尾随点，拒绝"
    if (hostPart.isEmpty()) return "URL 缺少主机名"

    // path/query 里不允许控制字符或空白（防止 HTTP 请求走私/头注入样式绕过）。
    if (url.any { it.isISOControl() || it.isWhitespace() }) return "URL 含控制字符或空白，拒绝"
    return null
}
