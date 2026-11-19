package org.feeluown.mobile.provider.lx

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.feeluown.mobile.sourceupdate.BoundedBodyRead
import org.feeluown.mobile.sourceupdate.HttpBodyLimits
import org.feeluown.mobile.sourceupdate.readBodyBounded

/**
 * 一首"可被 LX 脚本解析"的歌曲。
 *
 * 为什么需要这一层：LX 协议只有 `request(musicUrl)`，**没有搜索**。搜索是 LX Music 客户端自己
 * 按平台做的，脚本只负责把 songmid 换成播放地址。所以脚本音源必须自带一个搜索适配器，
 * 否则用户根本无从选中歌曲。
 */
data class LxSongHit(
    val platform: String,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int? = null,
    /**
     * 平台返回的原始字段（如酷我 `MUSICRID` / `DC_TARGETID`、酷狗 `hash`、腾讯 `strMediaMid`）。
     *
     * 各平台脚本读取的 musicInfo 字段并不相同，绝不能把 songId 赋给所有键——那会让"《晴天》
     * 解析到另一首歌"。这里保留原样，交给脚本按自己的协议取用；[songId] 只作为稳定主 ID。
     */
    val rawFields: Map<String, String> = emptyMap(),
)

interface LxSongSearcher {
    /** 该适配器能搜的平台（LX 平台标识：kw / kg / tx / wy / mg）。 */
    val supportedPlatforms: Set<String>

    suspend fun search(keyword: String, platform: String, limit: Int = 20): List<LxSongHit>
}

/**
 * 酷我搜索适配器。
 *
 * 选它作为首个实现的原因：`search.kuwo.cn/r.s?rformat=json` 返回的是合法 JSON（已实测），
 * 且酷我脚本在社区里最普遍，songmid 就是 `MUSICRID` 里的数字部分。
 *
 * 这是**独立于脚本**的公开接口：脚本只拿它给的 songmid 去换播放地址，不参与搜索，
 * 因此即使脚本里声明了 `kw`，搜索失败也不会污染脚本的信任状态。
 */
class KuwoSongSearcher(
    private val client: HttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val timeoutMillis: Long = 8_000L,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val maxResponseBytes: Int = HttpBodyLimits.SEARCH_RESPONSE_BYTES,
) : LxSongSearcher {

    /** 本适配器只负责酷我；其它平台由各自的适配器实现，[LxSongSearcher] 接口本身支持多平台集合。 */
    override val supportedPlatforms: Set<String> = setOf(PLATFORM)

    override suspend fun search(keyword: String, platform: String, limit: Int): List<LxSongHit> {
        val query = keyword.trim()
        if (platform != PLATFORM || query.isEmpty()) return emptyList()
        val url = buildString {
            append(endpoint)
            append("?all=").append(percentEncode(query))
            append("&client=kt&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1")
            append("&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012")
            append("&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1")
            append("&rn=").append(limit.coerceIn(1, 50))
            append("&pn=0")
        }
        // 放在真实时间里计时：runTest 的虚拟时钟会让 withTimeoutOrNull 立刻到期。
        // 流式读取：Content-Length 提前拒绝 + 无/伪造 CL 时逐块累计，超限立即中断，
        // 不会先用一个超大字符串占满内存再 substring。
        val read = withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMillis) {
                try {
                    readBodyBounded(client.get(url), maxResponseBytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        } ?: return emptyList()
        if (read !is BoundedBodyRead.Ok) return emptyList() // TooLarge：整条候选作废，不影响其它平台

        return parse(read.text).take(limit)
    }

    fun parse(rawText: String): List<LxSongHit> {
        val root = runCatching { json.parseToJsonElement(rawText).jsonObject }.getOrNull() ?: return emptyList()
        val list = root["abslist"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val songId = item.text("MUSICRID").substringAfterLast('_').takeIf { it.isNotBlank() }
                ?: item.text("DC_TARGETID").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = item.text("SONGNAME").ifBlank { item.text("NAME") }.ifBlank { return@mapNotNull null }
            // 保留平台原始字段：身份核验靠"搜索命中的歌名/歌手 + 原始 ID"，
            // 解析阶段再把同一份原始字段透传给脚本，避免用 songId 冒充所有字段导致解析错歌。
            val raw = rawFieldsOf(item)
            LxSongHit(
                platform = PLATFORM,
                songId = songId,
                title = unescape(title),
                artist = unescape(item.text("ARTIST").ifBlank { item.text("FARTIST") }),
                album = unescape(item.text("ALBUM")),
                durationSeconds = item.text("DURATION").toIntOrNull(),
                rawFields = raw,
            )
        }
    }

    /** 抽出响应里的原始字段（已反转义），供脚本按平台协议取用。 */
    private fun rawFieldsOf(item: JsonObject): Map<String, String> = buildMap {
        for ((key, value) in item) {
            val text = runCatching { value.jsonPrimitive.contentOrNull }.getOrNull() ?: continue
            put(key, unescape(text))
        }
    }

    private fun JsonObject.text(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")

    private fun unescape(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .trim()

    private fun percentEncode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and 0xff
            if (code in 0x30..0x39 || code in 0x41..0x5a || code in 0x61..0x7a || code == 0x2d || code == 0x2e) {
                append(code.toChar())
            } else {
                append('%')
                append("0123456789ABCDEF"[code ushr 4])
                append("0123456789ABCDEF"[code and 15])
            }
        }
    }

    companion object {
        const val PLATFORM = "kw"
        const val DEFAULT_ENDPOINT = "http://search.kuwo.cn/r.s"
        // 搜索响应上限见 HttpBodyLimits.SEARCH_RESPONSE_BYTES（四类响应各自独立）。
    }
}
