package org.feeluown.mobile.provider.lx

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 真实社区脚本兼容性回归测试（desktop）。
 *
 * 重要：脚本文件是**原样下载**的第三方代码（见 config/source-catalog/third-party-raw），
 * 本测试不修改它们。初始化用脚本自身的本地声明；播放地址解析用 MockEngine 按脚本
 * 期望的 API 契约 `GET /url/{source}/{songId}/{quality} -> {code:0,url/data}` 返回。
 *
 * 因此这里证明的是"脚本能在当前 QuickJS 宿主完成初始化并按其公开协议解析播放地址，
 * 且返回的 URL 与请求的歌曲 ID 一致"——属于**模拟测试通过**，
 * 不是真实音源接口验证（真实网络/真实曲库需在有真实脚本授权与网络条件下另测）。
 */
class ThirdPartyScriptCompatTest {

    private val scriptDir = File("src/desktopTest/resources/third-party")

    private data class Case(
        val file: String,
        val name: String,
        val hosts: Set<String>,
        val expectedPlatforms: Set<String>,
        /** 该脚本返回播放地址的 JSON 字段名：huibq 用 url，其余用 data。 */
        val urlField: String = "data",
        val successCode: Int = 0,
    )

    private val cases = listOf(
        Case("feimao.js", "肥猫不肥", setOf("music.xn--z7x900a.live"), setOf("kw", "kg", "tx", "wy", "mg")),
        Case("huibq.js", "Huibq_lxmusic源", setOf("lxmusicapi.onrender.com"), setOf("kw", "kg", "tx", "wy", "mg"), urlField = "url"),
        Case("yahua.js", "野花音源", setOf("flower.tempmusics.tk"), setOf("kw", "wy", "mg", "tx", "kg")),
        Case("yecao.js", "野草音源", setOf("grass.tempmusics.tk"), setOf("kw")),
        Case("zhizun.js", "至尊源", setOf("110.42.36.53"), setOf("kw", "kg", "tx", "wy", "mg")),
        Case("zicheng2.js", "梓澄公益音源二代", setOf("103.239.247.51"), setOf("kw", "kg", "tx", "wy", "mg")),
        Case("dujia_v4.js", "[独家音源]", setOf("88.lxmusic.xn--fiqs8s"), setOf("kw", "kg", "tx", "wy", "mg")),
        Case("ikun_latest.js", "ikun音源", setOf("api.ikunshare.com"), setOf("kw", "kg", "tx", "wy", "mg"), successCode = 200),
    )

    /** 模拟各脚本期望的播放地址 API：从 URL 路径取回 songId，让返回值携带同一 ID。 */
    private fun mockApiClient(successCode: Int): Pair<HttpClient, () -> String> {
        var lastRequestedId: String? = null
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val segments = path.split('/').filter { it.isNotEmpty() }
            // 两种形态：/url/{source}/{songId}/{quality} 与 /url?source=..&songId=..
            val urlIndex = segments.indexOf("url")
            val fromPath = if (urlIndex >= 0 && segments.size > urlIndex + 2) segments[urlIndex + 2] else null
            val fromQuery = request.url.parameters["songId"]
            val songId = fromQuery ?: fromPath ?: "unknown"
            lastRequestedId = songId
            val audioUrl = "https://cdn.example.com/$songId/128k.flac"
            val json = buildJsonObject {
                put("code", successCode)
                put("data", audioUrl)
                put("url", audioUrl)
            }.toString()
            respond(json, HttpStatusCode.OK)
        })
        return client to { lastRequestedId ?: "" }
    }

    @Test
    fun `each third-party script initializes and resolves matching song id`() = runBlocking {
        val summary = StringBuilder()
        for (c in cases) {
            val source = File(scriptDir, c.file).readText()
            val (client, requestedId) = mockApiClient(c.successCode)
            val runtime = LxScriptRuntime(client)
            try {
                runtime.load(c.name, source, allowedHosts = c.hosts)
                val meta = runtime.initialize()
                val platform = c.expectedPlatforms.first()
                val songId = "test-song-228908"
                val musicInfo = buildJsonObject {
                    put("songmid", songId)
                    put("hash", songId)
                    put("name", "晴天")
                    put("singer", "周杰伦")
                }
                assertTrue(
                    c.expectedPlatforms.all { it in meta.platforms },
                    "${c.file}: 期望平台 ${c.expectedPlatforms} 实际 ${meta.platforms}",
                )
                val url = runtime.requestMusicUrl(platform, musicInfo, "128k")
                assertNotNull(url, "${c.file}: 未返回播放地址")
                assertEquals(songId, requestedId(), "${c.file}: 脚本请求的歌曲 ID 与输入不一致")
                assertTrue(url.contains(songId), "${c.file}: 播放地址不含请求的歌曲 ID: $url")
                assertTrue(meta.supports(platform), "${c.file}: meta 不含平台 $platform")
                summary.appendLine("${c.file}: OK platforms=${meta.platforms} reqId=${requestedId()} idMatch=true")
            } finally {
                runtime.close()
                client.close()
            }
        }
        File("build/third-party-compat.txt").apply { parentFile.mkdirs(); writeText(summary.toString()) }
        Unit // JUnit4 要求 @Test 方法返回 void；不要让它推断成 File
    }

    @Test
    fun `network error is surfaced not turned into a fake url`() = runBlocking {
        val source = File(scriptDir, "feimao.js").readText()
        // 模拟 API 返回 500：脚本应抛错，而不是返回一个伪造的播放地址。
        val client = HttpClient(MockEngine { respond("{\"msg\":\"server error\"}", HttpStatusCode.InternalServerError) })
        val runtime = LxScriptRuntime(client)
        try {
            runtime.load("肥猫不肥", source, allowedHosts = setOf("music.xn--z7x900a.live"))
            runtime.initialize()
            val failed = try {
                runtime.requestMusicUrl(
                    "kw",
                    buildJsonObject { put("songmid", "x1"); put("hash", "x1") },
                    "128k",
                )
                false
            } catch (e: Exception) {
                true
            }
            assertTrue(failed, "网络错误必须以异常上抛，不能返回假 URL")
        } finally {
            runtime.close()
            client.close()
        }
        Unit
    }

    @Test
    fun `close releases script instance`() = runBlocking {
        val source = File(scriptDir, "huibq.js").readText()
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })
        val runtime = LxScriptRuntime(client)
        runtime.load("Huibq_lxmusic源", source, allowedHosts = setOf("lxmusicapi.onrender.com"))
        runtime.initialize()
        assertTrue(runtime.isLoaded)
        runtime.close()
        assertEquals(false, runtime.isLoaded, "close 后脚本实例必须释放")
        runtime.close() // 幂等
        client.close()
        Unit
    }
}
