package org.feeluown.mobile.provider.musicfree

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.feeluown.mobile.provider.lx.CandidateNetworkPolicy
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.GatewayResult
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.ProviderNetworkPermission
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.Request
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 阶段 J1（用户收窄范围）：Gateway 共用网络底座的真实验证。
 *
 * J0 的 14 项测试全用假执行器（fromStringExecutor/自定义 NetworkExecutor），
 * 新增的 asProviderNetworkExecutor()（Ktor OkHttp + followRedirects=false）从未真实执行。
 * 本测试用**真实 socket HTTP server + 真实 Ktor OkHttp client**补上该缺口：
 * - 对照实验证明底层确实没有先行跟随 3xx（任务书 J0 2.1 的硬要求）；
 * - 真实响应体/Content-Length 头经 BodyStream 流式读取，验证 Gateway 决策层限额。
 *
 * 诚实边界：Gateway.get() 的生产策略禁止明文 HTTP + 裸 IP，完整 get() 链路**无法**
 * 打本地 127.0.0.1 server；为了测试而放宽即违反红线。因此分层：
 * - executor 契约层（本测试）用真实网络验证 followRedirects/流式 body；
 * - Gateway 决策层沿用 J0 受控执行器测试，本测试再把"真实抓到的响应"喂进去复核。
 * 不涉及任何插件接入、不做 REAL_SEARCH/PLAYBACK 声称。
 */
class PhaseJ1RealNetworkBaseTest {

    private val sha = "d".repeat(64)

    private fun permission(maxBytes: Int = 1_000_000, maxRedirects: Int = 0) = ProviderNetworkPermission(
        providerId = "mf-j1-real", providerVersion = "1.0", scriptSha256 = sha,
        candidatePolicy = CandidateNetworkPolicy(
            candidateId = "mf-j1-real", boundSha256 = sha, boundVersion = "1.0",
            allowedHosts = setOf("api.example.com"), maxResponseBytes = maxBytes,
            authorizationBasis = "J1 真实验证：具名域名",
        ),
        redirectPolicy = if (maxRedirects > 0)
            ProviderNetworkGateway.RedirectPolicy.REVALIDATE_EACH_HOP
        else ProviderNetworkGateway.RedirectPolicy.DENY_ALL,
        maxRedirects = maxRedirects,
        authorizationBasis = "J1 真实验证授权",
    )

    /** 启动真实 HTTP server；返回端口与命中计数（按路径）。 */
    private data class Server(val port: Int, val hits: MutableMap<String, AtomicInteger>, val close: () -> Unit)

    private fun startServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): Server {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = mutableMapOf<String, AtomicInteger>()
        server.createContext("/") { ex ->
            hits.getOrPut(ex.requestURI.path) { AtomicInteger(0) }.incrementAndGet()
            handler(ex)
        }
        server.start()
        return Server(server.address.port, hits) { server.stop(0) }
    }

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, status: Int, body: String, location: String? = null) {
        location?.let { ex.responseHeaders.set("Location", it) }
        val bytes = body.encodeToByteArray()
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    // ---------- 重定向：底层不先行跟随（对照实验铁证）----------

    @Test
    fun j1_realExecutorDoesNotFollowRedirect(): Unit = runBlocking {
        val server = startServer { ex ->
            when (ex.requestURI.path) {
                "/start" -> respond(ex, 302, "moved", "/final")
                "/final" -> respond(ex, 200, "FINAL_BODY")
                else -> respond(ex, 404, "nope")
            }
        }
        try {
            // 对照 A：默认 Ktor OkHttp client 自动跟随 302 -> 得到最终 200。
            val naive = HttpClient(OkHttp) {}
            val naiveResp: HttpResponse = naive.get("http://127.0.0.1:${server.port}/start")
            val naiveBody = naiveResp.bodyAsText()
            assertEquals(HttpStatusCode.OK, naiveResp.status, "对照：默认 client 应跟随到最终 200")
            assertEquals("FINAL_BODY", naiveBody)
            assertTrue(server.hits["/final"]?.get() == 1, "对照：默认 client 命中 /final 一次")
            naive.close()

            // 重置最终路径命中计数。
            server.hits["/final"]?.set(0)

            // 实验 B：经 asProviderNetworkExecutor 包装后，底层必须原样上交 302，不跟随。
            val guarded = HttpClient(OkHttp) {}.asProviderNetworkExecutor()
            val raw = guarded.execute(Request("http://127.0.0.1:${server.port}/start"))
            assertEquals(302, raw.statusCode, "底层 executor 必须原样返回 302（未先行跟随）")
            val loc = ProviderNetworkGateway.header(raw.headers, "Location")
            assertEquals("/final", loc, "3xx Location 必须原样上报给网关决策层")
            raw.body.cancel()
            assertEquals(0, server.hits["/final"]?.get(), "关键铁证：executor 未请求最终目标，证明 followRedirects=false 真实生效")
        } finally {
            server.close()
        }
    }

    @Test
    fun j1_realRedirectResponsesDriveGatewayDecision(): Unit = runBlocking {
        // 用真实 server 抓到的 302 + Location，喂给 Gateway 决策层复核 DENY_ALL 与逐跳校验。
        val server = startServer { ex ->
            when (ex.requestURI.path) {
                "/start" -> respond(ex, 302, "moved", "https://evil.example.org/steal")
                else -> respond(ex, 404, "nope")
            }
        }
        try {
            val guarded = HttpClient(OkHttp) {}.asProviderNetworkExecutor()
            val raw = guarded.execute(Request("http://127.0.0.1:${server.port}/start"))
            assertEquals(302, raw.statusCode)
            val capturedLocation = ProviderNetworkGateway.header(raw.headers, "Location")!!
            raw.body.cancel()

            // 决策层：真实抓到的 Location 指向未授权域名 -> DENY_ALL 必须拒绝，不发起下一跳。
            var executorCalls = 0
            val g = ProviderNetworkGateway(ProviderNetworkGateway.NetworkExecutor { req ->
                executorCalls++
                ProviderNetworkGateway.RawResponse(
                    302, mapOf("Location" to capturedLocation),
                    ProviderNetworkGateway.byteStreamOf("moved".encodeToByteArray()),
                )
            })
            val denied = g.get(permission(), Request("https://api.example.com/start"), sha)
            assertTrue(denied is GatewayResult.Denied, "真实抓到的跨域 Location 必须被拒：$denied")
            assertEquals(1, executorCalls, "DENY_ALL 下只请求首跳，不跟随到未授权域名")
        } finally {
            server.close()
        }
    }

    // ---------- 流式限额：真实响应体 + Content-Length ----------

    @Test
    fun j1_realContentLengthHeaderFromWire(): Unit = runBlocking {
        val payload = "x".repeat(2048)
        val server = startServer { ex -> respond(ex, 200, payload) }
        try {
            val guarded = HttpClient(OkHttp) {}.asProviderNetworkExecutor()
            val raw = guarded.execute(Request("http://127.0.0.1:${server.port}/data"))
            assertEquals(200, raw.statusCode)
            val declared = ProviderNetworkGateway.header(raw.headers, "Content-Length")
            assertEquals("2048", declared, "真实 wire 的 Content-Length 必须上报给网关做预检")
            // 流式读取累计，验证真实 body 与声明长度一致。
            val buf = ByteArray(512)
            var total = 0
            val sb = StringBuilder()
            while (true) {
                val n = raw.body.read(buf)
                if (n < 0) break
                total += n
                sb.append(buf.decodeToString(0, n))
            }
            assertEquals(2048, total)
            assertEquals(payload, sb.toString())
        } finally {
            server.close()
        }
    }

    @Test
    fun j1_realWireResponseDrivesGatewayLimit(): Unit = runBlocking {
        // 从真实 wire 抓取 Content-Length 与 body 字节（真实数据，非凭空构造），
        // 再喂给 Gateway.get() 的合规域名请求，验证决策层限额逻辑。
        val payload = "y".repeat(4096)
        val server = startServer { ex -> respond(ex, 200, payload) }
        try {
            val guarded = HttpClient(OkHttp) {}.asProviderNetworkExecutor()
            val wire = guarded.execute(Request("http://127.0.0.1:${server.port}/data"))
            val wireLength = ProviderNetworkGateway.header(wire.headers, "Content-Length")!!
            val wireHeaders = wire.headers.toMap()
            val chunks = mutableListOf<ByteArray>()
            val tmp = ByteArray(512)
            var off = 0
            while (true) {
                val n = wire.body.read(tmp)
                if (n < 0) break
                chunks += tmp.copyOfRange(0, n)
                off += n
            }
            val wireBytes = ByteArray(off)
            var pos = 0
            chunks.forEach { it.copyInto(wireBytes, pos); pos += it.size }
            assertEquals("4096", wireLength)
            assertEquals(payload.length, off, "真实抓到的字节数应与 payload 一致")
            assertEquals(payload, wireBytes.decodeToString(), "真实抓到的 body 内容应一致")

            // (1) Content-Length 预检：真实头 4096 > 上限 100 -> 0 读取即拒。
            var reads = 0
            var cancelled = false
            val g = ProviderNetworkGateway(ProviderNetworkGateway.NetworkExecutor {
                ProviderNetworkGateway.RawResponse(200, wireHeaders, object : ProviderNetworkGateway.BodyStream {
                    override suspend fun read(buffer: ByteArray): Int { reads++; return -1 }
                    override fun cancel() { cancelled = true }
                })
            })
            val denied = g.get(permission(maxBytes = 100), Request("https://api.example.com/data"), sha)
            assertTrue(denied is GatewayResult.Denied && denied.reason.contains("Content-Length"), "真实 Content-Length 超限应预检拒绝：$denied")
            assertEquals(0, reads, "预检阶段不应读取 body")
            assertTrue(cancelled, "超限应释放 body")

            // (2) 无 Content-Length 时流式累计：真实 4096 字节 > 上限 100 -> 流式拒绝并 cancel。
            var streamCancelled = false
            val g2 = ProviderNetworkGateway(ProviderNetworkGateway.NetworkExecutor {
                val stream = object : ProviderNetworkGateway.BodyStream {
                    private val delegate = ProviderNetworkGateway.byteStreamOf(wireBytes)
                    override suspend fun read(buffer: ByteArray) = delegate.read(buffer)
                    override fun cancel() { streamCancelled = true; delegate.cancel() }
                }
                ProviderNetworkGateway.RawResponse(200, emptyMap(), stream)
            })
            val streamResult = g2.get(permission(maxBytes = 100), Request("https://api.example.com/data"), sha)
            assertTrue(streamResult is GatewayResult.Denied && streamResult.reason.contains("流式读取超过上限"), "真实字节流式超限应拒绝：$streamResult")
            assertTrue(streamCancelled, "真实字节流式超限必须 cancel 释放 body")
        } finally {
            server.close()
        }
    }

    @Test
    fun j1_realStreamBodyCancellationReleasesResource(): Unit = runBlocking {
        val payload = "z".repeat(8192)
        val server = startServer { ex -> respond(ex, 200, payload) }
        try {
            val guarded = HttpClient(OkHttp) {}.asProviderNetworkExecutor()
            val raw = guarded.execute(Request("http://127.0.0.1:${server.port}/data"))
            val buf = ByteArray(256)
            raw.body.read(buf) // 读一块后取消
            raw.body.cancel()
            // cancel 后再次读取应结束（返回 -1），证明底层资源已释放。
            val after = raw.body.read(buf)
            assertEquals(-1, after, "cancel 后流式 body 应停止产出，底层连接释放")
            // server 仍可服务下一个请求，证明未泄漏。
            val again = guarded.execute(Request("http://127.0.0.1:${server.port}/data"))
            assertEquals(200, again.statusCode)
            again.body.cancel()
        } finally {
            server.close()
        }
    }
}
