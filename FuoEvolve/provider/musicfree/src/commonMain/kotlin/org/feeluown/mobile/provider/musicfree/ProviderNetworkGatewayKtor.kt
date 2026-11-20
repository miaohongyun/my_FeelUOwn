package org.feeluown.mobile.provider.musicfree

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable

/**
 * 阶段 J0：把 Ktor HttpClient 适配为 [ProviderNetworkGateway.NetworkExecutor]。
 *
 * 关键整改（对应任务书 2.1 / 2.2）：
 * - `config { followRedirects = false }` —— **底层不自动跟随 3xx**，把重定向决策完全交给
 *   Gateway，由后者逐跳重新执行完整权限校验。这是"executor 确实未先行跟随"的证据。
 * - body 包装为 [ProviderNetworkGateway.BodyStream]，read 直接读 Ktor ByteReadChannel，
 *   cancel 调用 channel.cancel 释放连接；超限/取消时 Gateway 立即中断流式读取。
 *
 * 调用方须管理传入 HttpClient 的生命周期（J1 生产接入时注入统一 client）。
 */
fun HttpClient.asProviderNetworkExecutor(): ProviderNetworkGateway.NetworkExecutor {
    val noRedirect = config { followRedirects = false }
    return ProviderNetworkGateway.NetworkExecutor { request ->
        val response = noRedirect.request(request.url) {
            method = HttpMethod.parse(request.method)
            request.headers.forEach { (name, value) -> header(name, value) }
            request.body?.let { setBody(it) }
        }
        val channel = response.bodyAsChannel()
        ProviderNetworkGateway.RawResponse(
            statusCode = response.status.value,
            headers = response.headers.entries().associate { entry ->
                entry.key to entry.value.firstOrNull().orEmpty()
            },
            body = object : ProviderNetworkGateway.BodyStream {
                override suspend fun read(buffer: ByteArray): Int =
                    channel.readAvailable(buffer, 0, buffer.size)

                override fun cancel() {
                    runCatching { channel.cancel(null) }
                }
            },
        )
    }
}
