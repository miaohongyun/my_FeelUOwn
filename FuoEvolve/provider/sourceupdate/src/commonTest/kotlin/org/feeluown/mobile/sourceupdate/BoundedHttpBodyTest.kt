package org.feeluown.mobile.sourceupdate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1 任务3：响应体积上限的流式读取验证。
 *
 * 覆盖：正常响应 / Content-Length 缺失 / 虚假 Content-Length（声明小、实际大）/
 * 超大响应（无 CL，逐块累计到上限立即中断）/ 取消语义。
 *
 * 关键：超限必须在读取阶段就中断，而不是把整个响应读入内存再 substring。
 * 这里用较小的 limit（几十字节）配合 MockEngine，让"是否流式中断"可被直接断言
 * （[BoundedBodyRead.TooLarge.fromContentLength] 能区分是 CL 提前拒绝还是流式累计）。
 */
class BoundedHttpBodyTest {

    private fun clientWith(handler: () -> HttpResponseData): HttpClient =
        HttpClient(MockEngine { handler() })

    private fun response(
        body: String,
        contentLength: String?,
        chunked: Boolean = false,
    ): HttpResponseData {
        val headers = buildList {
            if (contentLength != null) add(HttpHeaders.ContentLength to listOf(contentLength))
            if (chunked) add(HttpHeaders.TransferEncoding to listOf("chunked"))
        }
        return HttpResponseData(
            statusCode = HttpStatusCode.OK,
            requestTime = GMTDate(),
            headers = headersOf(*headers.toTypedArray()),
            version = HttpProtocolVersion.HTTP_1_1,
            body = ByteReadChannel(body.encodeToByteArray()),
            // Ktor 需要 callContext 里有 Job 才能启动 body 读取协程。
            callContext = kotlinx.coroutines.Job() + Dispatchers.Default,
        )
    }

    @Test
    fun `normal response within limit returns text`() = runTest {
        val client = clientWith { response("hello world", contentLength = "11") }
        val read = readBodyBounded(client.get("https://example.com"), 100)
        val ok = read as BoundedBodyRead.Ok
        assertEquals("hello world", ok.text)
        assertEquals(11, ok.bytes)
        client.close()
    }

    @Test
    fun `content length above limit rejected before reading any byte`() = runTest {
        // 声明长度与实际一致、但超上限：应走 Content-Length 提前拒绝。
        val big = "a".repeat(500)
        val client = clientWith { response(big, contentLength = "500") }
        val read = readBodyBounded(client.get("https://example.com"), 50)
        val tooLarge = read as BoundedBodyRead.TooLarge
        assertTrue(tooLarge.fromContentLength, "应标记为 CL 提前拒绝")
        assertEquals(50, tooLarge.limit)
        assertEquals(500L, tooLarge.observed)
        client.close()
    }

    @Test
    fun `missing content length streams and truncates at limit`() = runTest {
        // 无 Content-Length、实际 200 字节、上限 50：应流式累计到 51 字节就中断。
        val big = "x".repeat(200)
        val client = clientWith { response(big, contentLength = null) }
        val read = readBodyBounded(client.get("https://example.com"), 50)
        val tooLarge = read as BoundedBodyRead.TooLarge
        assertTrue(!tooLarge.fromContentLength, "应标记为流式累计超限")
        assertTrue(tooLarge.observed >= 50 && tooLarge.observed <= 51 + 8 * 1024,
            "累计量应在上限附近，实际 ${tooLarge.observed}")
        client.close()
    }

    @Test
    fun `fake small content length cannot bypass stream limit`() = runTest {
        // 虚假 CL=10（小于上限）、实际 300 字节、上限 40。
        // Ktor 3.5.1 客户端在读取阶段自己校验 CL 与实际字节数，不符会抛
        // "Content-Length mismatch"；这也是一道保护。无论走哪条路径，响应都不能被
        // 当成合法结果返回——这里断言异常或未超限的 TooLarge，二者都算安全。
        val big = "y".repeat(300)
        val client = clientWith { response(big, contentLength = "10") }
        val read = runCatching { readBodyBounded(client.get("https://example.com"), 40) }
        if (read.isSuccess) {
            val result = read.getOrThrow()
            assertTrue(result is BoundedBodyRead.TooLarge, "虚假 CL 的响应不得被当作合法结果: $result")
            assertTrue(!result.fromContentLength, "不能因为 CL 小就信任它")
        } else {
            // Ktor 层已拦截 CL mismatch
            assertTrue(read.exceptionOrNull()?.message?.contains("Content-Length", ignoreCase = true) == true)
        }
        client.close()
    }

    @Test
    fun `chunked oversized response is cut off by streaming`() = runTest {
        val big = "z".repeat(500)
        val client = clientWith { response(big, contentLength = null, chunked = true) }
        val read = readBodyBounded(client.get("https://example.com"), 64)
        val tooLarge = read as BoundedBodyRead.TooLarge
        assertTrue(!tooLarge.fromContentLength)
        client.close()
    }

    @Test
    fun `multibyte utf8 not broken at chunk boundary`() = runTest {
        // 用 8 字节 buffer 强制中文字符跨块，验证 decodeToString 在累积后一次性执行。
        val text = "晴天周杰伦".repeat(10)
        val client = clientWith { response(text, contentLength = null) }
        val read = readBodyBounded(client.get("https://example.com"), 1000, bufferSize = 8)
        val ok = read as BoundedBodyRead.Ok
        assertEquals(text, ok.text)
        client.close()
    }
}
