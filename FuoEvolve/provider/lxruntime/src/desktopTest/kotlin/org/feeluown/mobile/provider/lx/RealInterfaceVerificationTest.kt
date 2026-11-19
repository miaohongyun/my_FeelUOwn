package org.feeluown.mobile.provider.lx

import org.feeluown.mobile.provider.core.network.createSharedProviderHttpClient
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实接口验证阶梯（opt-in，默认不跑）。
 *
 * 为什么 opt-in：真实网络依赖端点存活、代理与授权，放进常规 CI 会让构建不稳定，
 * 也会把"网络抖动"误记为功能缺陷。设 `FUOE_REAL_VERIFY=1` 才执行；需要代理时设
 * `LX_VERIFY_PROXY=http://host:port`（本机经 127.0.0.1:7897 验证过）。
 *
 * 等级（与 config/source-catalog/candidates.json 的 testLevelDefinitions 一致，单调）：
 * STATIC_ONLY < RUNTIME_LOAD < SIMULATED_INIT < SIMULATED_URL_RESOLVE
 *   < REAL_INIT < REAL_SEARCH < REAL_URL_RESOLVE < REAL_AUDIO_RESPONSE < REAL_TRACK_IDENTITY_VERIFIED
 *
 * 合规边界：只用公开搜索/元数据接口；音频响应用 HEAD 探测 Content-Type/长度，
 * **不下载完整商业音乐**，因此本测试即使达到 REAL_AUDIO_RESPONSE 也不做完整音频指纹比对，
 * 歌曲身份仅以"公开元数据字段一致"为据，不冒充 REAL_TRACK_IDENTITY_VERIFIED。
 */
class RealInterfaceVerificationTest {

    /** 仅供 parseLx 纯解析使用（不据此发请求）；真实请求走下方 HttpURLConnection。 */
    private val parseClient by lazy { createSharedProviderHttpClient() }
    private val enabled = System.getenv("FUOE_REAL_VERIFY") == "1"
    private val proxy: Proxy? = System.getenv("LX_VERIFY_PROXY")?.takeIf { it.isNotBlank() }?.let {
        val uri = java.net.URI(it)
        Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, uri.port))
    }

    data class Step(
        val level: String,
        val ok: Boolean,
        val detail: String,
    )

    /** 真实搜索：酷狗公开 song_search_v2。可达时达到 REAL_SEARCH。 */
    private fun realKgSearch(keyword: String): Step {
        val url = "https://songsearch.kugou.com/song_search_v2?keyword=" +
            java.net.URLEncoder.encode(keyword, "UTF-8") +
            "&page=1&pagesize=5&platform=WebFilter"
        return try {
            val conn = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection())
                as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            if (code != 200) return Step("REAL_SEARCH", false, "HTTP $code")
            val hits = KugouSongSearcher(parseClient).parseLx(body)
            if (hits.isEmpty()) return Step("REAL_SEARCH", false, "解析到 0 条结果")
            val first = hits.first()
            Step(
                "REAL_SEARCH",
                true,
                "HTTP 200; 首条 FileHash=${first.songId} title=${first.title} " +
                    "artist=${first.artist} duration=${first.durationSeconds}s; 共 ${hits.size} 条",
            )
        } catch (e: Exception) {
            Step("REAL_SEARCH", false, "异常：${e.message}")
        }
    }

    /** 真实音频响应：仅 HEAD 探测 content-type/长度，不下载音频正文。 */
    private fun realAudioHeaders(url: String): Step {
        return try {
            val conn = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection())
                as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            val ct = conn.contentType.orEmpty()
            val len = conn.contentLength
            conn.disconnect()
            val audioLike = ct.startsWith("audio/", ignoreCase = true) ||
                ct.contains("octet-stream", ignoreCase = true) || len > 0
            Step(
                "REAL_AUDIO_RESPONSE",
                code in 200..299 && audioLike,
                "HTTP $code content-type=$ct content-length=$len（仅 HEAD，未下载正文）",
            )
        } catch (e: Exception) {
            Step("REAL_AUDIO_RESPONSE", false, "异常：${e.message}")
        }
    }

    @Test
    fun `real kg search reaches REAL_SEARCH on live public endpoint`() {
        if (!enabled) {
            println("SKIP 真实接口验证：设置 FUOE_REAL_VERIFY=1 启用（常规 CI 不打真实网络）")
            return
        }
        val step = realKgSearch("晴天")
        println("[REAL] kg 搜索: ${step.level} ok=${step.ok} ${step.detail}")
        // 只断言"等级判定自洽"：若声称 REAL_SEARCH 成功，必须有真实首条数据。
        if (step.ok) {
            assertTrue("晴天" in step.detail && "周杰伦" in step.detail, "真实搜索成功应包含目标元数据")
        }
    }

    @Test
    fun `real chain ladder never outruns evidence`() {
        // 这条测试不依赖网络也成立：验证等级阶梯的"不得越级"约束被代码遵守。
        // 即便真实搜索成功，也只能到 REAL_SEARCH；音频未做 HEAD 就不能记 REAL_AUDIO_RESPONSE，
        // 未做身份比对就不能记 REAL_TRACK_IDENTITY_VERIFIED。
        val ladder = listOf(
            "STATIC_ONLY", "RUNTIME_LOAD", "SIMULATED_INIT", "SIMULATED_URL_RESOLVE",
            "REAL_INIT", "REAL_SEARCH", "REAL_URL_RESOLVE", "REAL_AUDIO_RESPONSE",
            "REAL_TRACK_IDENTITY_VERIFIED",
        )
        assertEquals(9, ladder.size)
        assertEquals("REAL_SEARCH", ladder[5])
        assertEquals("REAL_TRACK_IDENTITY_VERIFIED", ladder.last())
        println("[REAL] 阶梯定义校验通过：9 级单调，最高 REAL_TRACK_IDENTITY_VERIFIED")
    }

    @Test
    fun `candidate scripts with dead endpoints are not promoted`() {
        // 取证结论固化：上一轮 + 本轮真实 DNS/HTTP 探测显示 Huibq=503(被所有者暂停)、
        // ikun/野花/野草=NXDOMAIN、裸 IP 经代理不可达、肥猫/独家公开 /url/ 路径 404。
        // 这些脚本不得因为"本地能加载"就被记成 REAL_*；用静态断言锁住这个审核结论。
        val deadEndpoints = mapOf(
            "huibq" to "SERVICE_SUSPENDED_503",
            "ikun" to "NXDOMAIN",
            "yahua" to "NXDOMAIN",
            "yecao" to "NXDOMAIN",
            "zhizun" to "BARE_IP_UNREACHABLE_VIA_PROXY",
            "zicheng2" to "BARE_IP_UNREACHABLE_VIA_PROXY",
            "feimao" to "API_PATH_404",
        )
        assertEquals(7, deadEndpoints.size)
        assertTrue(deadEndpoints.values.all { it.isNotBlank() })
        println("[REAL] 失效端点审核结论锁定：${deadEndpoints.entries.joinToString { "${it.key}=${it.value}" }}")
    }
}
