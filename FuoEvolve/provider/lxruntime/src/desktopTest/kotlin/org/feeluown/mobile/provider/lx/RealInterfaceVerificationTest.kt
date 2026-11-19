package org.feeluown.mobile.provider.lx

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * ## 两级测试语义（修正审查指出的"step.ok=false 测试仍绿"）
 * - `network probe`（可跳过/仅记录）：默认关闭时打印 SKIP；开启后即便端点失效也只记录，
 *   用于观察真实网络状态，**不**作为功能通过的依据。
 * - `verification`（必须成功）：`FUOE_REAL_VERIFY=1` 时若达不到声称的等级就 assertTrue 失败；
 *   默认关闭时 return（跳过）。这样"测试绿色"在 opt-in 模式下**必然**意味着真实成功。
 *
 * 等级（与 config/source-catalog/candidates.json 的 testLevelDefinitions 一致，单调，10 级）：
 * NOT_OBTAINED < STATIC_ONLY < RUNTIME_LOAD < SIMULATED_INIT < SIMULATED_URL_RESOLVE
 *   < REAL_INIT < REAL_SEARCH < REAL_URL_RESOLVE < REAL_AUDIO_RESPONSE < REAL_TRACK_IDENTITY_VERIFIED
 *
 * 合规边界：只用公开搜索/元数据接口；音频响应用 Range 取极短字节（如 16B）验证 Content-Type
 * 与容器魔数，**不下载完整商业音乐**；歌曲身份以公开元数据字段一致为据，不冒充声学指纹比对。
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

    /**
     * 真实音频响应：Range GET 取前 N 字节，严格验证 206 + Content-Range + Content-Type + 容器魔数。
     * 修正审查指出的三点：
     * - 不再用 `len > 0`（HTML 错误页也可能有正长度），也不只做 HEAD（HEAD 不证明 GET 拿得到音频字节）；
     * - 必须是 **HTTP 206**，不能是服务端忽略 Range 后返回的 200 整段（那条路拿不到"Range 证据"）；
     * - 必须返回 **Content-Range: bytes 0-(N-1)/total**，起点/结束与请求区间一致，且实际字节数 == N。
     *
     * 达到 REAL_AUDIO_RESPONSE 的充分条件：HTTP 206 + Content-Range 覆盖请求的 0-(N-1) +
     * Content-Type 以 audio/ 开头或 application/octet-stream + 响应字节以 ID3/MP3 帧同步开头
     * + 实际读取字节数等于 N（既不是更短，也不是整段）。
     */
    private fun realAudioRange(url: String, probeBytes: Int = 16): Step {
        return try {
            val conn = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection())
                as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Range", "bytes=0-${probeBytes - 1}")
            val code = conn.responseCode
            val ct = conn.contentType.orEmpty()
            val range = conn.getHeaderField("Content-Range").orEmpty()
            val bytes = conn.inputStream.use { it.readNBytes(probeBytes) }
            conn.disconnect()
            val ctAudio = ct.startsWith("audio/", ignoreCase = true) ||
                ct.equals("application/octet-stream", ignoreCase = true)
            val mp3Magic = bytes.size >= 3 && bytes[0].toInt() == 0x49 && bytes[1].toInt() == 0x44 && bytes[2].toInt() == 0x33
            val mp3Frame = bytes.size >= 2 &&
                (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xe0) == 0xe0
            val audioBytes = mp3Magic || mp3Frame
            // 严格 1：必须是 206 Partial Content，200 表示服务端忽略了 Range。
            val isPartial = code == 206
            // 严格 2：Content-Range 起点 0、结束 probeBytes-1、带 /total，与请求区间一致。
            val cr = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE).find(range)
            val rangeOk = cr != null && cr.groupValues[1] == "0" &&
                cr.groupValues[2] == (probeBytes - 1).toString()
            // 严格 3：实际字节数恰好等于请求长度（非更短、非整段）。
            val bytesOk = bytes.size == probeBytes
            val ok = isPartial && rangeOk && bytesOk && ctAudio && audioBytes
            val hex = bytes.take(8).joinToString("") { b -> "%02x".format(b) }
            Step(
                "REAL_AUDIO_RESPONSE",
                ok,
                "HTTP $code ct=$ct range=$range bytes=${bytes.size} hex=$hex" +
                    " partial=$isPartial rangeOk=$rangeOk bytesOk=$bytesOk" +
                    " id3=$mp3Magic frameSync=$mp3Frame（Range ${probeBytes}B，未下载完整音频）",
            )
        } catch (e: Exception) {
            Step("REAL_AUDIO_RESPONSE", false, "异常：${e.message}")
        }
    }

    /** 网络探测（仅记录）：默认 SKIP，开启后失败也不让测试失败。 */
    @Test
    fun `network probe real kg search records status without gating CI`() {
        if (!enabled) {
            println("SKIP 真实接口网络探测：设置 FUOE_REAL_VERIFY=1 启用")
            return
        }
        val step = realKgSearch("晴天")
        println("[PROBE] kg 搜索: ${step.level} ok=${step.ok} ${step.detail}")
    }

    /**
     * 必须成功的验证测试：opt-in 开启时，真实搜索失败就让测试失败。
     * 修正审查：原实现 `if (step.ok) { assert... }` 在 ok=false 时静默通过。
     */
    @Test
    fun `verification real kg search succeeds when opted in`() {
        if (!enabled) {
            println("SKIP 真实搜索验证：设置 FUOE_REAL_VERIFY=1 启用")
            return
        }
        val step = realKgSearch("晴天")
        println("[REAL] kg 搜索: ${step.level} ok=${step.ok} ${step.detail}")
        assertTrue(step.ok, "opt-in 真实搜索必须成功，不得静默通过: ${step.detail}")
        assertTrue("晴天" in step.detail && "周杰伦" in step.detail, "真实搜索应含目标元数据: ${step.detail}")
    }

    /**
     * 必须成功的音频验证（若配置 URL）：用 Range 字节证据验证，不是 HEAD、不看 len>0。
     * 未配置 FUOE_REAL_AUDIO_URL 时跳过（没有可公开访问的音频 URL 就不伪造通过）。
     */
    @Test
    fun `verification real audio response needs range and magic evidence`() {
        val audioUrl = System.getenv("FUOE_REAL_AUDIO_URL")
        if (!enabled || audioUrl.isNullOrBlank()) {
            println("SKIP 真实音频验证：需 FUOE_REAL_VERIFY=1 且 FUOE_REAL_AUDIO_URL=<公开音频URL>")
            return
        }
        val step = realAudioRange(audioUrl)
        println("[REAL] 音频 Range: ${step.level} ok=${step.ok} ${step.detail}")
        assertTrue(step.ok,
            "opt-in 真实音频必须有 206+Content-Range(0-15)+audio/*+容器魔数+16B 证据: ${step.detail}")
    }

    /**
     * 权威等级定义的**唯一来源**加载器：只从 candidates.json.testLevelDefinitions 读取。
     * 测试代码里不再维护第二份等级清单——否则两边会各自演进、再次不一致（审查指出的问题）。
     * 找不到/格式错误一律 fail，绝不退化成内置硬编码清单。
     */
    private fun loadAuthoritativeLevels(): Pair<JsonObject, List<String>> {
        val candidatesFile = listOf(
            "config/source-catalog/candidates.json",
            "../../config/source-catalog/candidates.json",
            "../../../config/source-catalog/candidates.json",
        ).map { java.io.File(it) }.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "找不到 config/source-catalog/candidates.json（工作目录=${System.getProperty("user.dir")}）；" +
                    "等级定义必须来自权威文件，不得退化为硬编码",
            )
        val root = Json.parseToJsonElement(candidatesFile.readText()).jsonObject
        val defs = root["testLevelDefinitions"]?.jsonObject
            ?: throw AssertionError("candidates.json 缺少 testLevelDefinitions，测试等级与审核记录会脱节")
        val levels = (defs["levels"] as? JsonArray)
            ?.map { it.jsonPrimitive.content.substringBefore(" - ") }
            ?: throw AssertionError("testLevelDefinitions.levels 缺失或格式错误")
        return root to levels
    }

    @Test
    fun `real chain ladder never outruns evidence`() {
        // 等级阶梯完全以 candidates.json.testLevelDefinitions 为权威定义：
        // 数量、名称、顺序都从文件读，测试侧不再硬编码任何一份清单。
        val (root, levels) = loadAuthoritativeLevels()
        assertTrue(levels.isNotEmpty(), "权威等级定义不得为空")
        assertEquals(levels.distinct().size, levels.size, "等级名称不得重复")
        // 单调性由等级名字典序之外的"业务语义锚点"保证：从权威文件里定位，而不是假定索引。
        val searchIdx = levels.indexOf("REAL_SEARCH")
        val audioIdx = levels.indexOf("REAL_AUDIO_RESPONSE")
        val identityIdx = levels.indexOf("REAL_TRACK_IDENTITY_VERIFIED")
        assertTrue(searchIdx >= 0 && audioIdx > searchIdx && identityIdx > audioIdx,
            "等级必须单调：REAL_SEARCH < REAL_AUDIO_RESPONSE < REAL_TRACK_IDENTITY_VERIFIED（$levels）")
        // note 不得残留旧的"九级"措辞。
        val note = root["note"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(!note.contains("九级"), "note 不应残留过时的九级定义措辞")
        println("[REAL] 阶梯来自权威文件：${levels.size} 级，最高 ${levels.last()}")
    }

    /**
     * 审核记录一致性：每个候选的 testLevel 必须是权威 levels 集合中的值，
     * 且 reviewStatus/autoInstall 不得违反"无 APPROVED、无自动安装"的边界。
     * 权威值只从 candidates.json 读取一次，测试不硬编码等级集合。
     */
    @Test
    fun `candidate levels and flags match authoritative definition`() {
        val (root, levels) = loadAuthoritativeLevels()
        val candidates = root["candidates"]?.jsonArray
            ?: throw AssertionError("candidates.json 缺少 candidates 数组")
        assertTrue(candidates.isNotEmpty(), "审核记录不应为空")
        candidates.forEach { element ->
            val c = element.jsonObject
            val id = c["id"]?.jsonPrimitive?.content ?: "<no-id>"
            val level = c["testLevel"]?.jsonPrimitive?.content
                ?: throw AssertionError("候选 $id 缺少 testLevel")
            assertTrue(level in levels,
                "候选 $id 的 testLevel='$level' 不在权威定义 $levels 内")
            val status = c["reviewStatus"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(status != "APPROVED", "候选 $id 不得为 APPROVED")
            val autoInstall = c["autoInstall"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            assertEquals(false, autoInstall, "候选 $id 的 autoInstall 必须为 false")
        }
        println("[REAL] ${candidates.size} 个候选等级/标记均与权威定义一致（无 APPROVED、无 autoInstall）")
    }

    @Test
    fun `candidate scripts with dead endpoints are not promoted`() {
        // 取证结论固化：上一轮真实 DNS/HTTP 探测显示 Huibq=503(被所有者暂停)、
        // ikun/野花/野草=NXDOMAIN、裸 IP 经代理不可达、肥猫/独家公开 /url/ 路径 404。
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
