package org.feeluown.mobile.provider.jamendo

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.provider.core.network.createSharedProviderHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Jamendo 生产 API Client 路径真实测试（opt-in，默认不跑）。
 *
 * 与 JamendoEndToEndTest 的区别：那个用 HttpURLConnection 发请求 + 生产解析函数；
 * 这个**完全走生产 JamendoApiClient.search() / trackById() 的 HTTP 请求路径**（Ktor + OkHttp），
 * 只替换一个带代理的 OkHttp 客户端以便本机联网——请求构造、URL、参数、超时、解析全是生产代码。
 * 这是审查要求的"完整生产端到端验证"。
 *
 * 启用：`FUOE_REAL_VERIFY=1`；代理：`LX_VERIFY_PROXY=http://host:port`。
 */
class JamendoProductionPathTest {

    private val enabled = System.getenv("FUOE_REAL_VERIFY") == "1"
    private val clientId = System.getenv("JAMENDO_CLIENT_ID")?.takeIf { it.isNotBlank() } ?: "5ace5988"
    private val proxyHostPort = System.getenv("LX_VERIFY_PROXY")?.takeIf { it.isNotBlank() }?.let {
        val uri = java.net.URI(it)
        uri.host to uri.port
    }

    /**
     * 生产 JamendoApiClient + 生产 HTTP 客户端（createSharedProviderHttpClient，desktop 为 OkHttp）。
     * 代理通过 JVM 系统属性注入，OkHttp 的默认 ProxySelector 会读取它——不改动任何生产代码，
     * search()/trackById() 的 URL 构造、参数、超时、解析全部走生产路径。
     */
    private fun productionClient(): Pair<JamendoApiClient, HttpClient> {
        if (proxyHostPort != null) {
            System.setProperty("https.proxyHost", proxyHostPort.first)
            System.setProperty("https.proxyPort", proxyHostPort.second.toString())
        }
        val http = createSharedProviderHttpClient()
        return JamendoApiClient(http, { clientId }) to http
    }

    /** 生产 search() 的真实网络路径：HTTP 请求 + Ktor 解析 + 字段映射全走生产代码。 */
    @Test
    fun `production search hits real network`() = runTest {
        if (!enabled) {
            println("SKIP Jamendo 生产路径 search：设置 FUOE_REAL_VERIFY=1 启用")
            return@runTest
        }
        val (api, http) = productionClient()
        try {
            // 偶发空结果，有限重试（与 EndToEnd 测试一致）。
            var result = api.search("ambient", limit = 3)
            for (attempt in 1..3) {
                if (result.tracks.isNotEmpty()) break
                result = api.search("ambient", limit = 3)
            }
            println("[REAL][PROD] JamendoApiClient.search: count=${result.totalCount} tracks=${result.tracks.size}")
            assertTrue(result.tracks.isNotEmpty(), "生产 search() 应返回真实结果")
            val t = result.tracks.first()
            println("[REAL][PROD] first: id=${t.id} name=${t.name} artist=${t.artist} audio=${t.audioUrl.take(60)}")
            assertTrue(t.id.isNotBlank() && t.audioUrl.startsWith("https://"), "生产 search 结果应有 id 与 https 播放地址")
            assertTrue(t.licenseCcUrl.contains("creativecommons.org", ignoreCase = true),
                "生产 search 结果应带 CC 授权元数据（许可证名称/链接需保留，不等同无条件开放）: ${t.licenseCcUrl}")
        } finally {
            http.close()
        }
    }

    /** 生产 trackById() 的真实网络路径：重新取时效播放地址并核对身份。 */
    @Test
    fun `production trackById re-resolves same identity`() = runTest {
        if (!enabled) {
            println("SKIP Jamendo 生产路径 trackById：设置 FUOE_REAL_VERIFY=1 启用")
            return@runTest
        }
        val (api, http) = productionClient()
        try {
            var first = api.search("ambient", limit = 1).tracks.firstOrNull()
            for (attempt in 1..3) {
                if (first != null) break
                first = api.search("ambient", limit = 1).tracks.firstOrNull()
            }
            assertTrue(first != null, "需先通过生产 search 拿到真实 id")
            // Jamendo 公开 API 偶发失败，trackById 同样允许有限重试。
            var reResolved = api.trackById(first!!.id)
            for (attempt in 1..3) {
                if (reResolved != null) break
                reResolved = api.trackById(first.id)
            }
            println("[REAL][PROD] JamendoApiClient.trackById(${first.id}): ${reResolved?.name}")
            assertTrue(reResolved != null, "生产 trackById() 应返回曲目（REAL_URL_RESOLVE）")
            assertEquals(first.id, reResolved!!.id, "重取 id 必须一致")
            assertEquals(first.name, reResolved.name, "重取曲名必须一致（身份核验）")
            assertTrue(reResolved.audioUrl.startsWith("https://"), "重取应给出有效 https 播放地址")
        } finally {
            http.close()
        }
    }
}
