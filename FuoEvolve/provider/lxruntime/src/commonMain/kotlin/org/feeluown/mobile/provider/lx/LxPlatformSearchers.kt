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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.feeluown.mobile.sourceupdate.BoundedBodyRead
import org.feeluown.mobile.sourceupdate.HttpBodyLimits
import org.feeluown.mobile.sourceupdate.readBodyBounded

/** 顶层扩展：安全读取 JsonObject 的字符串字段，供各平台解析器共用。 */
private fun JsonObject.text(key: String): String =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

/**
 * kg / tx / wy / mg 四个平台的搜索适配器。
 *
 * ## 验证等级：SIMULATED_INIT_AND_SEARCH（模拟，非真实接口）
 * 解析逻辑按各平台**公开搜索接口的真实响应结构**编写，测试使用真实结构的 fixture
 * （见 LxPlatformSearcherTest）。**没有**对真实接口发起过请求，接口是否存活、字段是否变化、
 * 是否需要鉴权/是否触发风控均未验证。不得把这些适配器当作"真实可用"。
 *
 * ## 架构约束（与审查要求一一对应）
 * - 每个平台一个独立 Searcher，platform 常量固定，绝不跨平台冒名；
 * - 每个平台独立 ID 字段映射（kg=FileHash / tx=songmid / wy=id / mg=musicId）；
 * - 原始字段原样保留（[LxSongHit.rawFields]），不把 songId 赋给所有键；
 * - 搜索结果含标题、歌手、平台稳定 ID；
 * - 单个平台失败返回空，不影响其它平台（由 [LxSongSearchRegistry] 保证隔离）；
 * - 真实接口只测公开功能，不绕过会员/付费/访问控制（本类不做任何鉴权绕过）。
 */

/** 各平台内部共享的 JSON / 原始字段抽取 / 流式请求工具。 */
internal object LxSearchSupport {
    val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 把一条平台结果的全部原始字段（含嵌套数组/对象的 JSON 文本）保留下来，供脚本按协议取用。 */
    fun rawFieldsOf(item: JsonObject): Map<String, String> = buildMap {
        for ((key, value) in item) {
            val text = when (value) {
                is kotlinx.serialization.json.JsonPrimitive -> value.contentOrNull
                else -> runCatching { value.toString() }.getOrNull()
            }
            if (!text.isNullOrBlank()) put(key, text)
        }
    }

    fun unescape(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()

    fun percentEncode(value: String): String = buildString {
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

    /** 公共的"带超时 + 流式上限"请求；返回 null 表示失败/超限/空，调用方据此返回空列表。 */
    suspend fun fetch(client: HttpClient, url: String, timeoutMillis: Long): String? =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMillis) {
                try {
                    when (val read = readBodyBounded(client.get(url), HttpBodyLimits.SEARCH_RESPONSE_BYTES)) {
                        is BoundedBodyRead.Ok -> read.text
                        is BoundedBodyRead.TooLarge -> null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
}

/** 酷狗：ID 字段 FileHash；公开搜索 `song_search_v2` 响应结构。 */
class KugouSongSearcher(
    private val client: HttpClient,
    // https 变体 2026-11-19 实测可达（HTTP 200，返回真实《晴天》数据）；用 https 避免
    // Android targetSdk35 对明文 HTTP 的 NSC 限制，也不放宽全局网络安全配置。
    private val endpoint: String = "https://songsearch.kugou.com/song_search_v2",
    private val timeoutMillis: Long = 8_000L,
) : LxSongSearcher {
    override val supportedPlatforms: Set<String> = setOf(PLATFORM)

    override suspend fun search(keyword: String, platform: String, limit: Int): List<LxSongHit> {
        if (platform != PLATFORM || keyword.isBlank()) return emptyList()
        val url = "$endpoint?keyword=${LxSearchSupport.percentEncode(keyword.trim())}" +
            "&page=1&pagesize=${limit.coerceIn(1, 50)}&platform=WebFilter"
        val body = LxSearchSupport.fetch(client, url, timeoutMillis) ?: return emptyList()
        return parseLx(body).take(limit)
    }

    fun parseLx(raw: String): List<LxSongHit> {
        val root = runCatching { LxSearchSupport.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val list = root["data"]?.jsonObject?.get("lists")?.jsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val item = el as? JsonObject ?: return@mapNotNull null
            val songId = item.text("FileHash").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = item.text("SongName").ifBlank { return@mapNotNull null }
            LxSongHit(
                platform = PLATFORM,
                songId = songId,
                title = LxSearchSupport.unescape(title),
                artist = LxSearchSupport.unescape(item.text("SingerName")),
                album = LxSearchSupport.unescape(item.text("AlbumName")),
                durationSeconds = item.text("Duration").toIntOrNull(),
                rawFields = LxSearchSupport.rawFieldsOf(item),
            )
        }
    }

    companion object { const val PLATFORM = "kg" }
}

/** QQ 音乐：ID 字段 songmid；公开搜索 `client_search_cp` 响应结构。 */
class QQMusicSongSearcher(
    private val client: HttpClient,
    private val endpoint: String = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp",
    private val timeoutMillis: Long = 8_000L,
) : LxSongSearcher {
    override val supportedPlatforms: Set<String> = setOf(PLATFORM)

    override suspend fun search(keyword: String, platform: String, limit: Int): List<LxSongHit> {
        if (platform != PLATFORM || keyword.isBlank()) return emptyList()
        val url = "$endpoint?w=${LxSearchSupport.percentEncode(keyword.trim())}" +
            "&p=1&n=${limit.coerceIn(1, 50)}&format=json"
        val body = LxSearchSupport.fetch(client, url, timeoutMillis) ?: return emptyList()
        return parseLx(body).take(limit)
    }

    fun parseLx(raw: String): List<LxSongHit> {
        val root = runCatching { LxSearchSupport.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val list = root["data"]?.jsonObject?.get("song")?.jsonObject?.get("list")?.jsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val item = el as? JsonObject ?: return@mapNotNull null
            val songId = item.text("songmid").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = item.text("songname").ifBlank { return@mapNotNull null }
            val artist = item["singer"]?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.text("name")?.takeIf(String::isNotBlank) }
                ?.joinToString(",").orEmpty()
            LxSongHit(
                platform = PLATFORM,
                songId = songId,
                title = LxSearchSupport.unescape(title),
                artist = LxSearchSupport.unescape(artist),
                album = LxSearchSupport.unescape(item.text("albumname")),
                durationSeconds = item.text("interval").toIntOrNull(),
                rawFields = LxSearchSupport.rawFieldsOf(item),
            )
        }
    }

    companion object { const val PLATFORM = "tx" }
}

/** 网易云音乐：ID 字段 id；公开搜索 `/api/search/get` 响应结构。 */
class NeteaseSongSearcher(
    private val client: HttpClient,
    private val endpoint: String = "https://music.163.com/api/search/get",
    private val timeoutMillis: Long = 8_000L,
) : LxSongSearcher {
    override val supportedPlatforms: Set<String> = setOf(PLATFORM)

    override suspend fun search(keyword: String, platform: String, limit: Int): List<LxSongHit> {
        if (platform != PLATFORM || keyword.isBlank()) return emptyList()
        val url = "$endpoint?s=${LxSearchSupport.percentEncode(keyword.trim())}" +
            "&type=1&offset=0&limit=${limit.coerceIn(1, 50)}"
        val body = LxSearchSupport.fetch(client, url, timeoutMillis) ?: return emptyList()
        return parseLx(body).take(limit)
    }

    fun parseLx(raw: String): List<LxSongHit> {
        val root = runCatching { LxSearchSupport.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val list = root["result"]?.jsonObject?.get("songs")?.jsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val item = el as? JsonObject ?: return@mapNotNull null
            val songId = item.text("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = item.text("name").ifBlank { return@mapNotNull null }
            val artist = item["artists"]?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.text("name")?.takeIf(String::isNotBlank) }
                ?.joinToString(",").orEmpty()
            val album = item["album"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            LxSongHit(
                platform = PLATFORM,
                songId = songId,
                title = LxSearchSupport.unescape(title),
                artist = LxSearchSupport.unescape(artist),
                album = LxSearchSupport.unescape(album),
                durationSeconds = item.text("duration")?.let { it.toLongOrNull()?.div(1000)?.toInt() },
                rawFields = LxSearchSupport.rawFieldsOf(item),
            )
        }
    }

    companion object { const val PLATFORM = "wy" }
}

/** 咪咕音乐：ID 字段 musicId；公开搜索接口响应结构。 */
class MiguSongSearcher(
    private val client: HttpClient,
    private val endpoint: String = "https://music.migu.cn/v3/api/music/audioPlayer/search",
    private val timeoutMillis: Long = 8_000L,
) : LxSongSearcher {
    override val supportedPlatforms: Set<String> = setOf(PLATFORM)

    override suspend fun search(keyword: String, platform: String, limit: Int): List<LxSongHit> {
        if (platform != PLATFORM || keyword.isBlank()) return emptyList()
        val url = "$endpoint?keyword=${LxSearchSupport.percentEncode(keyword.trim())}" +
            "&page=1&size=${limit.coerceIn(1, 50)}"
        val body = LxSearchSupport.fetch(client, url, timeoutMillis) ?: return emptyList()
        return parseLx(body).take(limit)
    }

    fun parseLx(raw: String): List<LxSongHit> {
        val root = runCatching { LxSearchSupport.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val list = root["data"]?.jsonObject?.get("musicList")?.jsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val item = el as? JsonObject ?: return@mapNotNull null
            val songId = item.text("musicId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = item.text("songName").ifBlank { item.text("name") }.ifBlank { return@mapNotNull null }
            LxSongHit(
                platform = PLATFORM,
                songId = songId,
                title = LxSearchSupport.unescape(title),
                artist = LxSearchSupport.unescape(item.text("singerName").ifBlank { item.text("artist") }),
                album = LxSearchSupport.unescape(item.text("albumName")),
                durationSeconds = item.text("duration").toIntOrNull(),
                rawFields = LxSearchSupport.rawFieldsOf(item),
            )
        }
    }

    companion object { const val PLATFORM = "mg" }
}
