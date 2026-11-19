package org.feeluown.mobile.provider.core.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException

/**
 * Provider 网络响应体的统一大小上限。
 *
 * 为什么要限制：`bodyAsText()` 会把整个响应读进内存。网络异常、被劫持或配置错误返回超大响应时，
 * 应用可能卡顿甚至 OOM。这里在读取阶段就设上限，超限立即取消连接，读到多少算多少。
 *
 * 与 sourceupdate 的 BoundedHttpBody 思路一致，但独立放在 provider 网络层，避免模块耦合。
 * 不与签名目录/脚本下载的上限互相借用——每类响应有自己的合理体积。
 */
object ProviderBodyLimits {
    /** 普通 JSON API 响应（搜索/详情/无损元数据），512 KiB 足够。 */
    const val JSON_BYTES = 512 * 1024
}

/** 有界读取结果。 */
sealed interface BoundedBodyRead {
    data class Ok(val text: String, val bytes: Int) : BoundedBodyRead
    /** Content-Length 预判超限，或流式累计超限。 */
    data class TooLarge(val limit: Int, val observed: Long, val fromContentLength: Boolean) : BoundedBodyRead
}

/**
 * 有上限地读取响应体为文本。
 *
 * 1. Content-Length 明确超过上限时，一个字节都不读，直接拒绝；
 * 2. 否则流式分块读取，累计超过 [maxBytes] 立即取消 channel 返回 TooLarge；
 * 3. 取消异常（[CancellationException]）原样上抛；
 * 4. ByteArray 分块累积后一次 decodeToString，避免 UTF-8 多字节被块边界切断。
 */
suspend fun readProviderBodyBounded(
    response: HttpResponse,
    maxBytes: Int = ProviderBodyLimits.JSON_BYTES,
    bufferSize: Int = 8 * 1024,
): BoundedBodyRead {
    val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
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
            if (read <= 0) break
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
        channel.cancel(null)
    }
}
