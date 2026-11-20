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
        // 1) URL 安全校验（HTTPS-only + SSRF），在发请求之前。
        val danger = LxUrlSafety.dangerousTargetReason(url, httpsOnly = true)
        if (danger != null) return DownloadResult.Rejected(danger)

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
