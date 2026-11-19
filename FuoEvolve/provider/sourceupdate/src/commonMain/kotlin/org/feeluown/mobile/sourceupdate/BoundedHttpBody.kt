package org.feeluown.mobile.sourceupdate

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException

/**
 * 网络响应体积上限（四类独立用途，禁止互相借用）：
 *
 * - [CATALOG_JSON_BYTES]：签名目录 JSON（含镜像），1 MiB；
 * - [SCRIPT_DOWNLOAD_BYTES]：目录里下载的单个脚本源码，1 MiB；
 * - [LX_HTTP_RESPONSE_BYTES]：LX 脚本 `lx.request` 拿到的 HTTP 响应，512 KiB；
 * - [SEARCH_RESPONSE_BYTES]：各平台搜索接口响应，512 KiB。
 *
 * 每个值只用于自己的读取路径；某类响应异常变大不应被迫调高其它类的上限。
 */
object HttpBodyLimits {
    const val CATALOG_JSON_BYTES = 1024 * 1024
    const val SCRIPT_DOWNLOAD_BYTES = 1024 * 1024
    const val LX_HTTP_RESPONSE_BYTES = 512 * 1024
    const val SEARCH_RESPONSE_BYTES = 512 * 1024
}

/** 流式读取结果。与设计里"超限立即终止、不先整块读入内存"对应。 */
sealed interface BoundedBodyRead {
    data class Ok(val text: String, val bytes: Int) : BoundedBodyRead
    /** 由 Content-Length 提前判定，或流式读取中累计超限时返回。 */
    data class TooLarge(val limit: Int, val observed: Long, val fromContentLength: Boolean) : BoundedBodyRead
}

/**
 * 有上限的响应体读取。
 *
 * 为什么不用 `bodyAsText()` 再 `substring`：那已经把**整个**超大响应读进内存，
 * substring 只是事后裁剪，内存峰值发生在裁剪之前，对异常/被劫持的响应没有保护。
 *
 * 策略（顺序）：
 * 1. 有 Content-Length 且可信（>=0、<=Int.MAX）时提前拒绝超限响应，一个字节都不读；
 * 2. 无 Content-Length / 伪造为较小值 / chunked 时，[bodyAsChannel] 流式读取，
 *    每块累计字节数，一旦 > [maxBytes] 立即 [cancel] channel 并抛出[BodyTooLargeException]，
 *    不把剩余数据读进内存；
 * 3. 取消（[CancellationException]）原样上抛，由调用方决定降级；
 * 4. 用 ByteArray 分块累积后一次性 decodeToString，避免 UTF-8 多字节字符被 chunk 边界切断。
 *
 * 注意 Content-Length 是**不可信输入**：伪造一个小于上限的值无法绕过保护，
 * 因为第 2 步仍会在真实累计超限时中断；伪造一个超大值只会导致提前拒绝（安全方向）。
 */
suspend fun readBodyBounded(
    response: HttpResponse,
    maxBytes: Int,
    bufferSize: Int = 8 * 1024,
): BoundedBodyRead {
    val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    // Content-Length 只在"明确大于上限"时提前拒绝；负值/非数字一律忽略，走流式累计。
    if (declared != null && declared > maxBytes) {
        response.bodyAsChannel().cancel(null)
        return BoundedBodyRead.TooLarge(maxBytes, declared, fromContentLength = true)
    }

    val channel = response.bodyAsChannel()
    val chunks = mutableListOf<ByteArray>()
    val buffer = ByteArray(bufferSize)
    var total = 0
    try {
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read <= 0) break // -1 = EOF
            total += read
            if (total > maxBytes) {
                channel.cancel(null)
                return BoundedBodyRead.TooLarge(maxBytes, total.toLong(), fromContentLength = false)
            }
            chunks += buffer.copyOf(read)
        }
        val all = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(all, offset)
            offset += chunk.size
        }
        return BoundedBodyRead.Ok(all.decodeToString(), total)
    } finally {
        // 正常读完 channel 会自动关闭；异常/取消路径下确保释放底层连接。
        channel.cancel(null)
    }
}

/** 供调用方在流式超限时抛出（保持与已有 LxScriptException 语义解耦）。 */
class BodyTooLargeException(limit: Int, observed: Long) :
    RuntimeException("响应超过 ${limit} 字节上限（实际累计 $observed 字节）")
