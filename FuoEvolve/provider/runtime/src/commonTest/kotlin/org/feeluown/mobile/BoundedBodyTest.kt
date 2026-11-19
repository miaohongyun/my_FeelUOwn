package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.provider.core.network.BoundedBodyRead
import org.feeluown.mobile.provider.core.network.readProviderBodyBounded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Provider 网络响应体有界读取测试。
 *
 * 验证超限响应被拒绝（Content-Length 预判 + 流式累计两条路径），正常响应不受影响。
 */
class BoundedBodyTest {

    private fun clientReturning(body: String, declareLength: Boolean = true) = HttpClient(
        MockEngine {
            if (declareLength) {
                respond(body, headers = io.ktor.http.headersOf("Content-Length" to listOf(body.encodeToByteArray().size.toString())))
            } else {
                respond(body)
            }
        },
    )

    @Test
    fun normalBodyReadsOk() = runTest {
        val client = clientReturning("""{"results":[]}""")
        val response = client.get("https://example.test/search")
        val bounded = readProviderBodyBounded(response, maxBytes = 512)
        assertTrue(bounded is BoundedBodyRead.Ok)
        assertEquals("""{"results":[]}""", bounded.text)
        client.close()
    }

    @Test
    fun contentLengthOverLimitIsRejectedWithoutReading() = runTest {
        val body = "x".repeat(200)
        val client = clientReturning(body, declareLength = true)
        val response = client.get("https://example.test/search")
        val bounded = readProviderBodyBounded(response, maxBytes = 100)
        assertTrue(bounded is BoundedBodyRead.TooLarge)
        assertTrue(bounded.fromContentLength, "应由 Content-Length 预判拒绝")
        assertEquals(200, bounded.observed)
        client.close()
    }

    @Test
    fun streamOverLimitIsRejectedEvenWithoutContentLength() = runTest {
        // 无 Content-Length（伪造/分块）：靠流式累计识别超限。
        val body = "x".repeat(200)
        val client = clientReturning(body, declareLength = false)
        val response = client.get("https://example.test/search")
        val bounded = readProviderBodyBounded(response, maxBytes = 100)
        assertTrue(bounded is BoundedBodyRead.TooLarge)
        assertTrue(!bounded.fromContentLength, "应由流式累计识别")
        client.close()
    }
}
