package org.feeluown.mobile.provider.musicfree

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteReadChannel
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
 * J1.1 生命周期整改（任务书第 4 节）：
 * - `HttpClient.config {}` 返回的是**共享同一 engine/连接池的独立 client**，必须被 close，
 *   否则每次构造 executor 都会泄漏一个 client。本函数只调用一次并缓存到闭包；executor 的
 *   生命周期与传入 [client] 绑定——**调用方负责 close 传入的 client**（子类 client 随父 client
 *   关闭而释放共享 engine 资源）。生产环境应在 Provider 创建时构造一次 executor 并复用，
 *   在 Provider 关闭时由调用方 close 该 client；不提供逐请求新建 client 的路径。
 *
 * 调用方须管理传入 HttpClient 的生命周期（J1 生产接入时注入统一 client）。
 */
fun HttpClient.asProviderNetworkExecutor(): ProviderNetworkGateway.NetworkExecutor {
    // 只在构造 executor 时调用一次 config，不在每个请求里重复创建 client。
    val noRedirect = config { followRedirects = false }
    return ProviderNetworkGateway.NetworkExecutor { request ->
        val response = noRedirect.request(request.url) {
            method = HttpMethod.parse(request.method)
            request.headers.forEach { (name, value) -> header(name, value) }
            request.body?.let { setBody(it) }
        }
        ProviderNetworkGateway.RawResponse(
            statusCode = response.status.value,
            headers = response.headers.entries().associate { entry ->
                entry.key to entry.value.firstOrNull().orEmpty()
            },
            body = ktorBodyStream(response.bodyAsChannel()),
        )
    }
}

/**
 * J1.1：把 Ktor [ByteReadChannel] 包装为 [ProviderNetworkGateway.BodyStream]，明确异常分类。
 *
 * 异常语义（任务书第 1 节）：
 * - 主动 [cancel] 后，后续 [read] 返回 -1（EOF）。
 * - 正常 EOF（readAvailable 返回 -1）返回 -1。
 * - 真实网络 I/O 异常、协程取消异常（CancellationException）、超时异常均**原样向上抛出**，
 *   不吞、不伪装成 EOF；由 Gateway 统一把读取失败记为 Failed（CancellationException 原样传播）。
 *
 * 并发语义（任务书第 2 节）——**串行约束**：
 * - [read] 与 [cancel] 由 Gateway 的 readBounded 在同一协程**顺序调用**：循环 read → 超限/结束后
 *   才 cancel → cancel 后不再 read。不支持 read 挂起期间从另一个协程并发 cancel。
 * - 串行约束下普通 Boolean 标志即可保证可见性（同一协程顺序执行），跨平台一致；不使用
 *   `@Volatile`（Kotlin/Native 不支持）或平台专属锁，避免未经验证的并发同步。
 * - cancel 幂等：连续调用只生效一次，重复 close 底层 channel 无害。
 */
internal fun ktorBodyStream(channel: ByteReadChannel): ProviderNetworkGateway.BodyStream =
    ktorBodyStream(channel) { buffer -> channel.readAvailable(buffer, 0, buffer.size) }

/**
 * 可注入读取函数的实现，供 J1.1 异常语义测试真实触发 wire 级 I/O 异常。
 * 取消/EOF/异常分类逻辑与生产路径完全一致，只有读取来源可替换。
 */
internal fun ktorBodyStream(
    channel: ByteReadChannel,
    readFn: suspend (ByteArray) -> Int,
): ProviderNetworkGateway.BodyStream = object : ProviderNetworkGateway.BodyStream {
    private var cancelled = false

    override suspend fun read(buffer: ByteArray): Int {
        if (cancelled) return -1
        // 不吞异常：真实 I/O 失败 / CancellationException / Timeout 原样上抛；
        // 正常 EOF 由 readFn 返回 -1。
        return readFn(buffer)
    }

    override fun cancel() {
        if (cancelled) return
        cancelled = true
        runCatching { channel.cancel(null) }
    }
}
