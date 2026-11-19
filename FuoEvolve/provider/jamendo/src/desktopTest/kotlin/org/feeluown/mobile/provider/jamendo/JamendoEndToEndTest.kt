package org.feeluown.mobile.provider.jamendo

import org.feeluown.mobile.provider.core.network.createSharedProviderHttpClient
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 独立公开曲库（Jamendo）端到端真实验证（opt-in，默认不跑）。
 *
 * 这是"独立解析器"方向的验证：Jamendo 是 CC 授权开放曲库，API 有公开文档与 client_id，
 * 不包装任何商业平台的未公开接口，授权边界清晰。验证链路：
 *   REAL_SEARCH（公开搜索）→ REAL_URL_RESOLVE（trackById 重新取时效 audio URL）
 *   → REAL_AUDIO_RESPONSE（Range 取极短字节，验 Content-Type + 容器魔数）
 *   → REAL_TRACK_IDENTITY_VERIFIED（搜索 id 与音频 URL trackid 参数一致 + 元数据一致）
 *
 * 启用：`FUOE_REAL_VERIFY=1`；代理：`LX_VERIFY_PROXY=http://host:port`；
 * client_id 默认用内置公开值（可用 `JAMENDO_CLIENT_ID` 覆盖）。
 *
 * 合规：音频只用 Range 取 16B 验证容器，**不下载完整音乐**；CC 授权元数据（license_ccurl）
 * 必须非空，否则不声称已确认授权。
 */
class JamendoEndToEndTest {

    private val enabled = System.getenv("FUOE_REAL_VERIFY") == "1"
    private val clientId = System.getenv("JAMENDO_CLIENT_ID")?.takeIf { it.isNotBlank() } ?: "5ace5988"
    private val proxy: Proxy? = System.getenv("LX_VERIFY_PROXY")?.takeIf { it.isNotBlank() }?.let {
        val uri = java.net.URI(it)
        Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, uri.port))
    }

    /** 用生产 JamendoApiClient 的纯解析函数解析真实响应（复用同一套字段映射）。 */
    private val parser by lazy { JamendoApiClient(createSharedProviderHttpClient(), { clientId }) }

    data class HttpResult(
        val code: Int,
        val contentType: String,
        val contentRange: String,
        val bytes: ByteArray,
    )

    private fun http(url: String, rangeBytes: Int? = null): HttpResult {
        val conn = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection())
            as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (rangeBytes != null) conn.setRequestProperty("Range", "bytes=0-${rangeBytes - 1}")
        val code = conn.responseCode
        val ct = conn.contentType.orEmpty()
        val range = conn.getHeaderField("Content-Range").orEmpty()
        val bytes = conn.inputStream.use { stream ->
            // 多读 1 字节：readNBytes(N) 无法发现服务端返回超过 N 字节的正文；
            // 请求 0-(N-1) 却读到 N+1 字节即说明响应超长，交给调用方断言判失败。
            if (rangeBytes != null) stream.readNBytes(rangeBytes + 1) else stream.readBytes()
        }
        conn.disconnect()
        return HttpResult(code, ct, range, bytes)
    }

    private fun searchUrl(keyword: String, limit: Int = 3) =
        "${JamendoApiClient.DEFAULT_BASE_URL}/tracks/?client_id=$clientId&format=json" +
            "&limit=$limit&search=${URLEncoder.encode(keyword, "UTF-8")}" +
            "&audioformat=mp32&include=musicinfo&imagesize=400"

    /**
     * 端到端必须成功测试：opt-in 开启时任何一环失败都让测试失败，绿色即代表真实成功。
     */
    @Test
    fun `end to end real jamendo search identity audio and license`() {
        if (!enabled) {
            println("SKIP Jamendo 端到端：设置 FUOE_REAL_VERIFY=1 启用（常规 CI 不打真实网络）")
            return
        }

        // ---- REAL_SEARCH：真实公开搜索 + 生产解析 ----
        // Jamendo 公开 API 偶发返回空结果（本机多次观测：同一关键词有时 count=0），
        // 真实网络测试允许有限重试，区分"端点不可用"与"偶发空结果"。
        var result = parser.parseSearch("{}")
        var lastCode = 0
        for (attempt in 1..3) {
            val searchResp = http(searchUrl("ambient"))
            lastCode = searchResp.code
            val bodyText = searchResp.bytes.toString(Charsets.UTF_8)
            assertEquals(200, lastCode, "Jamendo 搜索应 HTTP 200: ${bodyText.take(200)}")
            result = parser.parseSearch(bodyText)
            if (result.tracks.isNotEmpty()) {
                println("[REAL] Jamendo 搜索第 $attempt 次成功，count=${result.totalCount}")
                break
            }
            println("[PROBE] Jamendo 搜索第 $attempt 次返回空结果，重试")
        }
        assertTrue(result.tracks.isNotEmpty(), "真实搜索应有结果（REAL_SEARCH，HTTP $lastCode，3 次尝试）")
        val track = result.tracks.first()
        println("[REAL] Jamendo REAL_SEARCH: id=${track.id} name=${track.name} artist=${track.artist} " +
            "duration=${track.durationSeconds}s license=${track.licenseCcUrl}")

        // 身份字段（REAL_SEARCH 的元数据一致性）
        assertTrue(track.id.isNotBlank() && track.name.isNotBlank() && track.artist.isNotBlank(),
            "曲目应有 id/name/artist 身份字段")
        assertTrue(track.durationSeconds != null && track.durationSeconds!! > 0, "应有正的时长")
        assertTrue(track.audioUrl.startsWith("https://"), "播放地址应为 https")

        // 授权元数据：CC URL 非空（开放曲库的核心前提）
        assertTrue(track.licenseCcUrl.contains("creativecommons.org", ignoreCase = true),
            "Jamendo 曲目应带 CC 授权元数据: '${track.licenseCcUrl}'")

        // ---- REAL_AUDIO_RESPONSE：Range GET 验证真实音频字节（不下载完整音乐）----
        // 审查要求严格验证 HTTP 206（不是任意 2xx——服务端忽略 Range 返回 200 整段不算），
        // Content-Range 起点必须为 0 且覆盖请求的 0-15 字节，实际取得字节数符合预期。
        val audioResp = http(track.audioUrl, rangeBytes = 16)
        val audioCode = audioResp.code
        val audioBytes = audioResp.bytes
        val headerInfo = "${audioResp.contentType} | ${audioResp.contentRange}"
        val ctOk = audioResp.contentType.startsWith("audio/", ignoreCase = true) ||
            audioResp.contentType.equals("application/octet-stream", ignoreCase = true)
        // 魔数只看前 16 字节（http() 多读了 1 字节用于识别超长响应）。
        val probe = audioBytes.copyOfRange(0, minOf(audioBytes.size, 16))
        val id3 = probe.size >= 3 && probe[0].toInt() == 0x49 &&
            probe[1].toInt() == 0x44 && probe[2].toInt() == 0x33
        val frameSync = probe.size >= 2 &&
            (probe[0].toInt() and 0xff) == 0xff && (probe[1].toInt() and 0xe0) == 0xe0
        val hex = probe.take(8).joinToString("") { b -> "%02x".format(b) }
        println("[REAL] Jamendo REAL_AUDIO_RESPONSE: HTTP $audioCode $headerInfo hex=$hex bytesRead=${audioBytes.size} id3=$id3 frameSync=$frameSync")

        // 严格 1：必须是 206 Partial Content，不能是服务端忽略 Range 后的 200 整段响应。
        assertEquals(206, audioCode, "Range 请求必须返回 HTTP 206（200 表示服务端忽略了 Range）")
        // 严格 2：Content-Range 必须是 bytes 0-15/<total>，起点 0、结束 15 与请求一致，
        //         且 total > 15（total 与区间矛盾则不接受）。
        val crMatch = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
            .find(audioResp.contentRange)
        assertTrue(crMatch != null, "必须返回 Content-Range: $headerInfo")
        assertEquals("0", crMatch!!.groupValues[1], "Content-Range 起点必须为 0: $headerInfo")
        assertEquals("15", crMatch.groupValues[2], "Content-Range 结束必须与请求 0-15 一致: $headerInfo")
        val total = crMatch.groupValues[3].toLongOrNull()
        assertTrue(total != null && total > 15, "Content-Range total 必须 > 区间终点 15: $headerInfo")
        // 严格 3：读了 17 字节仍恰好得到 16B——排除短响应，也排除忽略 Range 的超长整段响应。
        assertEquals(16, audioBytes.size, "读取 17B 时应恰好得到 16B（请求 0-15；17B 表示响应超长）")
        assertTrue(ctOk, "音频 Content-Type 应为 audio/* 或 octet-stream: $headerInfo")
        assertTrue(id3 || frameSync, "音频字节应为 ID3/MP3 容器魔数（排除 HTML 错误页）")

        // ---- REAL_TRACK_IDENTITY_VERIFIED：搜索 id 与音频 URL 的 trackid 参数一致 ----
        // audio URL 形如 ...?trackid=<id>&format=mp32...，比对确认这段音频属于搜索到的曲目。
        val trackIdInAudio = Regex("[?&]trackid=([^&]+)").find(track.audioUrl)?.groupValues?.get(1)
        assertEquals(track.id, trackIdInAudio,
            "音频 URL 的 trackid 必须与搜索曲目 id 一致，否则身份核验不通过")
        println("[REAL] Jamendo REAL_TRACK_IDENTITY_VERIFIED: trackId=${track.id} 与音频 URL trackid 一致")
    }

    /**
     * URL 时效重取验证（REAL_URL_RESOLVE）：trackById 重新取播放地址，
     * 确认仍是同一 id/身份。仅在 opt-in 开启时执行。
     */
    @Test
    fun `verification real jamendo track by id re-resolves same identity`() {
        if (!enabled) {
            println("SKIP Jamendo URL 重取验证：设置 FUOE_REAL_VERIFY=1 启用")
            return
        }
        // 先用搜索拿一个真实 id（同样允许有限重试应对偶发空结果）。
        // limit=1 时 Jamendo 偶发返回空，用 limit=3 + 5 次重试提高稳定性。
        var first = parser.parseSearch("{}").tracks.firstOrNull()
        for (attempt in 1..5) {
            val searchResp = http(searchUrl("ambient", limit = 3))
            assertEquals(200, searchResp.code)
            first = parser.parseSearch(searchResp.bytes.toString(Charsets.UTF_8)).tracks.firstOrNull()
            if (first != null) break
        }
        assertTrue(first != null, "搜索应返回至少一条（5 次尝试）")
        // trackById 用真实 HTTP 请求重新取。
        val detailUrl = "${JamendoApiClient.DEFAULT_BASE_URL}/tracks/?client_id=$clientId" +
            "&format=json&id=${first!!.id}&audioformat=mp32"
        val detailResp = http(detailUrl)
        assertEquals(200, detailResp.code)
        val reResolved = parser.parseSearch(detailResp.bytes.toString(Charsets.UTF_8)).tracks.firstOrNull()
        assertTrue(reResolved != null, "trackById 应返回曲目（REAL_URL_RESOLVE）")
        assertEquals(first.id, reResolved!!.id, "重取后 id 必须一致")
        assertEquals(first.name, reResolved.name, "重取后曲名必须一致（身份核验）")
        assertTrue(reResolved.audioUrl.startsWith("https://"), "重取后应仍给出有效播放地址")
        println("[REAL] Jamendo REAL_URL_RESOLVE ok: id=${reResolved.id} name=${reResolved.name}")
    }
}
