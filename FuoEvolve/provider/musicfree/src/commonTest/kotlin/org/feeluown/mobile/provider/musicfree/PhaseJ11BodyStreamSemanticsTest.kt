package org.feeluown.mobile.provider.musicfree

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import org.feeluown.mobile.provider.lx.CandidateNetworkPolicy
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.GatewayResult
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.ProviderNetworkPermission
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 阶段 J1.1：BodyStream 异常语义与生命周期修复测试。
 *
 * 真实触发每一类异常（委托 ByteReadChannel 在读时抛 IOException / 协程取消 / withTimeout），
 * 不靠检查异常类型字符串。校验：取消/正常 EOF → -1；真实 I/O / Cancellation /
 * Timeout 原样上抛不吞；Gateway 把读取失败记为 Failed 而非 Ok。
 *
 * 保留 J1 真实 socket server 对照实验（见 PhaseJ1RealNetworkBaseTest，在各平台测试源集）。
 */
class PhaseJ11BodyStreamSemanticsTest {

    private val sha = "e".repeat(64)

    private fun permission() = ProviderNetworkPermission(
        providerId = "mf-j11", providerVersion = "1.0", scriptSha256 = sha,
        candidatePolicy = CandidateNetworkPolicy(
            candidateId = "mf-j11", boundSha256 = sha, boundVersion = "1.0",
            allowedHosts = setOf("api.example.com"), authorizationBasis = "J1.1 异常语义测试",
        ),
        authorizationBasis = "J1.1 测试授权",
    )

    // ---------- 取消与 EOF 语义 ----------

    @Test
    fun j11_activeCancelThenReadReturnsEof(): Unit = runBlocking {
        val stream = ktorBodyStream(ByteChannel())
        val buf = ByteArray(8)
        // 主动 cancel 后，cancelled 标志必须同步拦截后续 read，返回 EOF（不触碰底层 channel）。
        stream.cancel()
        assertEquals(-1, stream.read(buf), "主动 cancel 后 read 必须返回 EOF")
    }

    @Test
    fun j11_cancelAfterDataThenReadReturnsEof(): Unit = runBlocking {
        // 取消前能正常读到数据、取消后立即 EOF（用注入 readFn 避免 ByteChannel 挂起写的调度干扰）。
        var calls = 0
        val stream = ktorBodyStream(ByteChannel()) { buf ->
            calls++
            "abc".encodeToByteArray().copyInto(buf)
            3
        }
        val buf = ByteArray(8)
        assertEquals(3, stream.read(buf), "取消前应读到数据")
        stream.cancel()
        assertEquals(-1, stream.read(buf), "取消后必须 EOF")
        assertEquals(1, calls, "取消后 readFn 不应再被调用（cancelled 标志拦截）")
    }

    @Test
    fun j11_normalEofReturnsEof(): Unit = runBlocking {
        // 注入 readFn 模拟底层 readAvailable 返回 -1（正常 EOF），stream 必须原样透传 -1。
        var calls = 0
        val stream = ktorBodyStream(ByteChannel()) { _ ->
            calls++
            if (calls == 1) 2 else -1
        }
        val buf = ByteArray(8)
        assertEquals(2, stream.read(buf))
        assertEquals(-1, stream.read(buf), "正常 EOF（底层返回 -1）必须原样透传")
    }

    @Test
    fun j11_repeatedCancelIsIdempotent(): Unit = runTest {
        val channel = ByteChannel()
        val stream = ktorBodyStream(channel)
        val buf = ByteArray(4)
        stream.cancel()
        stream.cancel()
        stream.cancel()
        assertEquals(-1, stream.read(buf), "重复 cancel 后仍应稳定返回 EOF，不抛异常")
    }

    @Test
    fun j11_cancelReleasesChannelResource(): Unit = runBlocking {
        val channel = ByteChannel()
        val stream = ktorBodyStream(channel)
        channel.writeStringUtf8("payload")
        stream.cancel()
        assertTrue(channel.isClosedForRead, "cancel 必须关闭底层 channel 释放资源")
    }

    // ---------- 异常不被吞 ----------

    @Test
    fun j11_ioExceptionNotMaskedAsEof(): Unit = runBlocking {
        // 真实 wire 级 I/O 失败：注入一个确实抛出 IOException 的读取函数，
        // 验证 ktorBodyStream 的异常分类不会把它吞成 EOF（-1）。
        val stream = ktorBodyStream(ByteChannel()) { throw IOException("wire-reset") }
        val buf = ByteArray(8)
        val thrown = assertFailsWith<IOException> { stream.read(buf) }
        assertEquals("wire-reset", thrown.message, "真实 I/O 异常必须原样上抛，不得伪装成 EOF")
    }

    @Test
    fun j11_timeoutNotMaskedAsEof(): Unit = runTest {
        val channel = ByteChannel() // 永不写入、永不关闭 -> read 挂起
        val stream = ktorBodyStream(channel)
        val buf = ByteArray(8)
        // 真实超时：withTimeout 包裹挂起的 read，TimeoutCancellationException 必须穿透。
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(30L) { stream.read(buf) }
        }
    }

    @Test
    fun j11_cancellationExceptionPropagates(): Unit = runTest {
        val channel = ByteChannel() // 挂起
        val stream = ktorBodyStream(channel)
        val buf = ByteArray(8)
        coroutineScope {
            val job = async {
                stream.read(buf) // 挂起等待数据
            }
            delay(30L)
            job.cancelAndJoin()
            val outcome = runCatching { job.await() }
            assertTrue(outcome.isFailure, "被取消的 read 应以异常结束，而非返回 EOF")
            assertTrue(outcome.exceptionOrNull() is CancellationException,
                "取消异常必须原样传播，不得被吞成 EOF：${outcome.exceptionOrNull()}")
        }
    }

    @Test
    fun j11_unknownExceptionNotSwallowed(): Unit = runBlocking {
        val stream = ktorBodyStream(ByteChannel()) { throw IllegalStateException("unknown-read-failure") }
        val buf = ByteArray(8)
        val thrown = assertFailsWith<IllegalStateException> { stream.read(buf) }
        assertEquals("unknown-read-failure", thrown.message, "未知异常同样不得被吞成 EOF")
    }

    // ---------- Gateway 把读取失败记为 Failed ----------

    @Test
    fun j11_gatewayReadFailureIsFailedNotOk(): Unit = runTest {
        val g = ProviderNetworkGateway(ProviderNetworkGateway.NetworkExecutor {
            ProviderNetworkGateway.RawResponse(200, emptyMap(), object : ProviderNetworkGateway.BodyStream {
                override suspend fun read(buffer: ByteArray): Int =
                    throw IOException("mid-stream-wire-error")
                override fun cancel() {}
            })
        })
        val result = g.get(permission(), Request("https://api.example.com/x"), sha)
        assertTrue(result is GatewayResult.Failed, "读取中途 I/O 失败必须是 Failed，不能是 Ok：$result")
        assertTrue(result.reason.contains("网络执行失败"), "应归类为网络执行失败：$result")
        assertTrue(result.reason.contains("mid-stream-wire-error"), "应保留真实异常原因：$result")
    }

    @Test
    fun j11_gatewayTimeoutIsFailedNotEof(): Unit = runTest {
        val g = ProviderNetworkGateway(ProviderNetworkGateway.NetworkExecutor {
            ProviderNetworkGateway.RawResponse(200, emptyMap(), object : ProviderNetworkGateway.BodyStream {
                override suspend fun read(buffer: ByteArray): Int {
                    delay(60_000)
                    return -1
                }
                override fun cancel() {}
            })
        })
        val short = permission().copy(
            candidatePolicy = permission().candidatePolicy.copy(requestTimeoutMs = 40L),
        )
        val result = g.get(short, Request("https://api.example.com/x"), sha)
        assertTrue(result is GatewayResult.Failed && result.reason.contains("超时"), "超时必须是 Failed：$result")
    }
}
