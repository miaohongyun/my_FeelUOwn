package org.feeluown.mobile.provider.musicfree

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.provider.lx.CandidateNetworkPolicy
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.GatewayResult
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.NetworkExecutor
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.ProviderNetworkPermission
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.RawResponse
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 阶段 J0：Gateway 网络出口整改测试（真实执行探测，非"只断言没抛异常"）。
 * - GatewayRedirectTest：跨域/私网/裸IP/循环重定向，底层 executor 不先行跟随的证据。
 * - GatewayStreamingLimitTest：Content-Length 预检 + 流式累计超限 + 取消释放。
 * - GatewayQueryEncodingTest：中文/空格/&/+/%/引号/emoji 的 UTF-8 form 编码。
 * - GatewayPermissionBindingTest：SHA 现场计算、方法/端口/凭据/版本绑定。
 */
class PhaseJ0GatewayTest {

    private val sha = "a".repeat(64)
    private fun policy(hosts: Set<String> = setOf("api.example.com"), maxBytes: Int = 1_000_000) =
        CandidateNetworkPolicy(
            candidateId = "mf-j0", boundSha256 = sha, boundVersion = "1.0",
            allowedHosts = hosts, maxResponseBytes = maxBytes,
            authorizationBasis = "J0 测试：具名域名",
        )

    private fun permission(
        hosts: Set<String> = setOf("api.example.com"),
        maxBytes: Int = 1_000_000,
        maxRedirects: Int = 0,
    ) = ProviderNetworkPermission(
        providerId = "mf-j0", providerVersion = "1.0", scriptSha256 = sha,
        candidatePolicy = policy(hosts, maxBytes),
        redirectPolicy = if (maxRedirects > 0)
            ProviderNetworkGateway.RedirectPolicy.REVALIDATE_EACH_HOP
        else ProviderNetworkGateway.RedirectPolicy.DENY_ALL,
        maxRedirects = maxRedirects,
        authorizationBasis = "J0 测试授权",
    )

    private fun redirectResponse(location: String) = RawResponse(
        302,
        mapOf("Location" to location),
        ProviderNetworkGateway.byteStreamOf("redirect".encodeToByteArray()),
    )

    // ---------- GatewayRedirectTest ----------

    @Test
    fun j0_denyAllDoesNotFollowCrossDomain(): Unit = runTest {
        val calls = mutableListOf<Request>()
        val g = ProviderNetworkGateway(NetworkExecutor { req ->
            calls += req
            redirectResponse("https://evil.example.org/steal")
        })
        val r = g.get(permission(), Request("https://api.example.com/start"), sha)
        assertTrue(r is GatewayResult.Denied, "DENY_ALL 应拒绝：$r")
        assertTrue(r.reason.contains("DENY_ALL"), r.reason)
        assertEquals(1, calls.size, "底层 executor 只应收到首跳——证明它没有先行跟随")
        assertEquals("https://api.example.com/start", calls[0].url)
    }

    @Test
    fun j0_revalidateRejectsCrossDomainRedirect(): Unit = runTest {
        val calls = mutableListOf<Request>()
        val g = ProviderNetworkGateway(NetworkExecutor { req ->
            calls += req
            redirectResponse("https://evil.example.org/x")
        })
        val r = g.get(permission(maxRedirects = 3), Request("https://api.example.com/start"), sha)
        assertTrue(r is GatewayResult.Denied, "跨域重定向必须被拒：$r")
        assertTrue(r.reason.contains("重定向目标未通过"), r.reason)
        assertEquals(1, calls.size, "未授权目标不得发起下一跳请求")
    }

    @Test
    fun j0_revalidateRejectsPrivateIpAndBareIpRedirect(): Unit = runTest {
        for (target in listOf("https://127.0.0.1/x", "https://192.168.1.1/x", "https://169.254.169.254/x", "https://110.42.36.53/x")) {
            val calls = mutableListOf<Request>()
            val g = ProviderNetworkGateway(NetworkExecutor { req ->
                calls += req
                redirectResponse(target)
            })
            val r = g.get(permission(maxRedirects = 3), Request("https://api.example.com/start"), sha)
            assertTrue(r is GatewayResult.Denied, "$target 重定向必须被拒：$r")
            assertEquals(1, calls.size, "$target 不得发起下一跳")
        }
    }

    @Test
    fun j0_revalidateRejectsCyclicRedirect(): Unit = runTest {
        var hops = 0
        val g = ProviderNetworkGateway(NetworkExecutor { req ->
            hops++
            redirectResponse("/loop")
        })
        val r = g.get(permission(maxRedirects = 2), Request("https://api.example.com/start"), sha)
        assertTrue(r is GatewayResult.Denied, "循环重定向必须被上限拦截：$r")
        assertTrue(r.reason.contains("重定向次数超过上限"), r.reason)
        assertEquals(3, hops, "首跳 + 2 次跟随，共 3 次执行后被上限拦截")
    }

    @Test
    fun j0_revalidateFollowsSameHostThenSucceeds(): Unit = runTest {
        val urls = mutableListOf<String>()
        val g = ProviderNetworkGateway(NetworkExecutor { req ->
            urls += req.url
            if (req.url.endsWith("/start")) redirectResponse("/next")
            else RawResponse(200, mapOf("Content-Type" to "application/json"), ProviderNetworkGateway.byteStreamOf("{\"ok\":true}".encodeToByteArray()))
        })
        val r = g.get(permission(maxRedirects = 2), Request("https://api.example.com/start"), sha)
        assertTrue(r is GatewayResult.Ok, "同域逐跳跟随应成功：$r")
        assertEquals(2, urls.size)
        assertEquals("https://api.example.com/next", urls[1])
        assertEquals("{\"ok\":true}", r.response.body)
    }

    // ---------- GatewayStreamingLimitTest ----------

    @Test
    fun j0_contentLengthPrecheckBeforeRead(): Unit = runTest {
        var reads = 0
        var cancelled = false
        val g = ProviderNetworkGateway(NetworkExecutor {
            RawResponse(200, mapOf("Content-Length" to "9999"), object : ProviderNetworkGateway.BodyStream {
                override suspend fun read(buffer: ByteArray): Int { reads++; return buffer.size }
                override fun cancel() { cancelled = true }
            })
        })
        val r = g.get(permission(maxBytes = 100), Request("https://api.example.com/x"), sha)
        assertTrue(r is GatewayResult.Denied, "Content-Length 超限应预检拒绝：$r")
        assertTrue(r.reason.contains("Content-Length"), r.reason)
        assertEquals(0, reads, "预检阶段不应读取 body")
        assertTrue(cancelled, "超限应释放 body")
    }

    @Test
    fun j0_streamingCumulativeLimitCancels(): Unit = runTest {
        var cancelled = false
        val g = ProviderNetworkGateway(NetworkExecutor {
            RawResponse(200, emptyMap(), object : ProviderNetworkGateway.BodyStream {
                private val chunks = List(50) { "0123456789".encodeToByteArray() }
                private var i = 0
                override suspend fun read(buffer: ByteArray): Int {
                    if (i >= chunks.size) return -1
                    val c = chunks[i++]
                    c.copyInto(buffer); return c.size
                }
                override fun cancel() { cancelled = true }
            })
        })
        val r = g.get(permission(maxBytes = 25), Request("https://api.example.com/x"), sha)
        assertTrue(r is GatewayResult.Denied, "流式累计超限应拒绝：$r")
        assertTrue(r.reason.contains("流式读取超过上限"), r.reason)
        assertTrue(cancelled, "流式超限应立即 cancel 释放")
    }

    @Test
    fun j0_normalAndTruncatedResponse(): Unit = runTest {
        val normal = ProviderNetworkGateway.fromStringExecutor { ProviderNetworkGateway.Response(200, "abc") }
        val ok = normal.get(permission(), Request("https://api.example.com/x"), sha)
        assertTrue(ok is GatewayResult.Ok && ok.response.body == "abc")

        val g = ProviderNetworkGateway(NetworkExecutor {
            RawResponse(200, emptyMap(), object : ProviderNetworkGateway.BodyStream {
                private var sent = false
                override suspend fun read(buffer: ByteArray): Int {
                    if (sent) return -1
                    sent = true
                    "截断数据".encodeToByteArray().copyInto(buffer)
                    return "截断数据".encodeToByteArray().size
                }
                override fun cancel() {}
            })
        })
        val partial = g.get(permission(), Request("https://api.example.com/x"), sha)
        assertTrue(partial is GatewayResult.Ok && partial.response.body == "截断数据", "截断（短读后 EOF）应作为已收部分返回：$partial")
    }

    @Test
    fun j0_timeoutAndCancellation(): Unit = runTest {
        // 超时：executor 永不返回，Gateway 的 withTimeout 应转为 Failed（不是 Denied/成功）。
        val slow = ProviderNetworkGateway(NetworkExecutor {
            delay(60_000)
            RawResponse(200, emptyMap(), ProviderNetworkGateway.byteStreamOf(ByteArray(0)))
        })
        val shortPolicy = policy().copy(requestTimeoutMs = 50L)
        val perm = permission().copy(candidatePolicy = shortPolicy)
        val timed = slow.get(perm, Request("https://api.example.com/x"), sha)
        assertTrue(timed is GatewayResult.Failed && timed.reason.contains("超时"), "超时应 Failed：$timed")

        // 取消：CancellationException 必须原样上抛，不吞成 Failed/Ok。
        val cancelled = ProviderNetworkGateway.fromStringExecutor { throw CancellationException("user-cancel") }
        var caught: CancellationException? = null
        try {
            cancelled.get(permission(), Request("https://api.example.com/x"), sha)
        } catch (e: CancellationException) {
            caught = e
        }
        assertEquals("user-cancel", caught?.message, "取消应原样传播")
    }

    // ---------- GatewayQueryEncodingTest ----------

    @Test
    fun j0_formEncodingSpecialCharacters(): Unit {
        assertEquals("hello+world", MusicFreePluginCompatVerification.formEncode("hello world"))
        assertEquals("q%C3%A9ng%E5%A4%A9+x", MusicFreePluginCompatVerification.formEncode("qéng天 x"))
        assertEquals("a%26b%3Dc%2Bd%25e%22f", MusicFreePluginCompatVerification.formEncode("a&b=c+d%e\"f"))
        assertTrue(MusicFreePluginCompatVerification.formEncode("🎵").contains("%F0%9F%8E%B5"), "emoji 应 UTF-8 逐字节编码")
        assertEquals("A-Z_a.z~", MusicFreePluginCompatVerification.formEncode("A-Z_a.z~"), "未保留字符原样")
    }

    @Test
    fun j0_appendQueryHandlesExistingQuery(): Unit {
        assertEquals("https://x/y?a=1", MusicFreePluginCompatVerification.appendQuery("https://x/y", "a=1"))
        assertEquals("https://x/y?old=0&a=1", MusicFreePluginCompatVerification.appendQuery("https://x/y?old=0", "a=1"))
        assertEquals("https://x/y?a=1", MusicFreePluginCompatVerification.appendQuery("https://x/y?", "a=1"))
        assertEquals("https://x/y?old=0&a=1", MusicFreePluginCompatVerification.appendQuery("https://x/y?old=0&", "a=1"))
    }

    @Test
    fun j0_bridgeEncodesChineseParamsAndKeepsSemantics(): Unit = runTest {
        val captured = mutableListOf<Request>()
        val g = ProviderNetworkGateway.fromStringExecutor { req ->
            captured += req
            ProviderNetworkGateway.Response(200, """{"items":["晴天"]}""", mapOf("Content-Type" to "application/json"))
        }
        val scriptBytes = "plugin-source".encodeToByteArray()
        val liveSha = MusicFreePluginCompatVerification.shaOfScriptBytes(scriptBytes)
        // 权限的 boundSha256 / scriptSha256 必须与现场字节 SHA 一致（J0 2.4）。
        val consistentPerm = permission().copy(
            scriptSha256 = liveSha,
            candidatePolicy = policy().copy(boundSha256 = liveSha),
        )
        val bridge = MusicFreePluginCompatVerification.gatewayBridge(g, consistentPerm, scriptBytes, mutableListOf())
        val out = bridge(
            "https://api.example.com/search?src=mf",
            """{"name":"周杰伦 & 晴天","page":1,"tag":"a+b%c"}""",
            """{"X-Test":"v 1"}""",
        )
        assertTrue(out.contains("\"status\":200"), out)
        assertTrue(out.contains("晴天"), out)
        assertTrue(out.contains("application/json"), out)
        assertEquals(1, captured.size)
        val url = captured[0].url
        assertTrue(url.contains("name=%E5%91%A8%E6%9D%B0%E4%BC%A6+%26+%E6%99%B4%E5%A4%A9"), url)
        assertTrue(url.contains("tag=a%2Bb%25c"), url)
        assertTrue(url.contains("src=mf&"), url)
        assertEquals("v 1", captured[0].headers["X-Test"])
    }

    // ---------- GatewayPermissionBindingTest ----------

    @Test
    fun j0_scriptBytesShaBindingLive(): Unit = runTest {
        val scriptBytes = "real-plugin-source".encodeToByteArray()
        val liveSha = MusicFreePluginCompatVerification.shaOfScriptBytes(scriptBytes)
        val correctPolicy = policy().copy(boundSha256 = liveSha)
        val perm = permission().copy(
            scriptSha256 = liveSha, candidatePolicy = correctPolicy,
        )
        val g = ProviderNetworkGateway.fromStringExecutor { ProviderNetworkGateway.Response(200, "ok") }
        val accepted = g.get(perm, Request("https://api.example.com/x"), liveSha)
        assertTrue(accepted is GatewayResult.Ok, "实际字节 SHA 一致应放行：$accepted")

        // 传固定常量冒充当前脚本 -> 拒绝
        val fake = g.get(perm, Request("https://api.example.com/x"), "b".repeat(64))
        assertTrue(fake is GatewayResult.Denied && fake.reason.contains("SHA"), "固定 SHA 冒充应被拒：$fake")
    }

    @Test
    fun j0_methodAndCredentialBinding(): Unit = runTest {
        val g = ProviderNetworkGateway.fromStringExecutor { ProviderNetworkGateway.Response(200, "ok") }
        val post = g.get(permission(), Request("https://api.example.com/x", method = "POST"), sha)
        assertTrue(post is GatewayResult.Denied && post.reason.contains("方法"), "未授权方法应拒：$post")
        val cred = g.get(permission(), Request("https://api.example.com/x", headers = mapOf("Cookie" to "a=b")), sha)
        assertTrue(cred is GatewayResult.Denied && cred.reason.contains("凭据"), "凭据头应拒：$cred")
    }
}
