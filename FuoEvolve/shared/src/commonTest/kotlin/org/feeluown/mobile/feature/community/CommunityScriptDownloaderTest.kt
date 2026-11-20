package org.feeluown.mobile.feature.community

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 受控下载器测试（P1-2）：HTTPS-only / SSRF / 大小上限 / 重定向 / 状态分类。
 * 全部 MockEngine，零真实网络。
 */
class CommunityScriptDownloaderTest {

    private val scriptUrl = "https://raw.githubusercontent.com/o/r/main/a.js"

    @Test
    fun `https script downloads successfully`() = runBlocking {
        val body = "const x = 1"
        val dl = CommunityScriptDownloader(HttpClient(MockEngine { respond(body, HttpStatusCode.OK) }))
        val r = dl.download(scriptUrl)
        assertTrue(r is CommunityScriptDownloader.DownloadResult.Success)
        assertEquals(body, r.text)
        assertEquals(body.length, r.bytes)
    }

    @Test
    fun `http is rejected by default`() = runBlocking {
        val dl = CommunityScriptDownloader(HttpClient(MockEngine { respond("x", HttpStatusCode.OK) }))
        val r = dl.download("http://example.com/a.js")
        assertTrue(r is CommunityScriptDownloader.DownloadResult.Rejected)
        assertTrue(r.reason.contains("HTTPS"))
    }

    @Test
    fun `localhost private and metadata targets are rejected`() = runBlocking {
        val dl = CommunityScriptDownloader(HttpClient(MockEngine { respond("x", HttpStatusCode.OK) }))
        listOf(
            "https://localhost/a.js",
            "https://127.0.0.1/a.js",
            "https://192.168.1.1/a.js",
            "https://169.254.169.254/latest/meta-data",
            "https://[::1]/a.js",
            "https://2130706433/a.js",
        ).forEach { url ->
            val r = dl.download(url)
            assertTrue(r is CommunityScriptDownloader.DownloadResult.Rejected, "$url 应被拒绝: $r")
        }
    }

    @Test
    fun `redirect is rejected`() = runBlocking {
        // 与生产 Factory 一致：followRedirects=false，让 3xx 暴露给下载器自行拒绝。
        val dl = CommunityScriptDownloader(HttpClient(MockEngine {
            respond("redirect", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/x"))
        }).config { followRedirects = false })
        val r = dl.download(scriptUrl)
        assertTrue(r is CommunityScriptDownloader.DownloadResult.Rejected)
        assertTrue(r.reason.contains("重定向"))
    }

    @Test
    fun `content length over limit is rejected`() = runBlocking {
        // MockEngine 会把 Content-Length 设为实际 body 长度；返回 200 字节 > 100 上限，
        // 验证 Content-Length 预判路径（body 也确实超限，双重保险）。
        val dl = CommunityScriptDownloader(
            HttpClient(MockEngine { respond("x".repeat(200), HttpStatusCode.OK) }),
            maxBytes = 100,
        )
        val r = dl.download(scriptUrl)
        assertTrue(r is CommunityScriptDownloader.DownloadResult.TooLarge)
        assertTrue(r.observed >= 100)
    }

    @Test
    fun `streamed body over limit is rejected without content length`() = runBlocking {
        val dl = CommunityScriptDownloader(
            HttpClient(MockEngine { respond("x".repeat(500), HttpStatusCode.OK) }),
            maxBytes = 100,
        )
        val r = dl.download(scriptUrl)
        assertTrue(r is CommunityScriptDownloader.DownloadResult.TooLarge)
    }

    @Test
    fun `http error is rejected`() = runBlocking {
        val dl = CommunityScriptDownloader(HttpClient(MockEngine { respond("err", HttpStatusCode.InternalServerError) }))
        val r = dl.download(scriptUrl)
        assertTrue(r is CommunityScriptDownloader.DownloadResult.Rejected)
        assertTrue(r.reason.contains("500"))
    }
}
