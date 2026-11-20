package org.feeluown.mobile.provider.musicfree

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 阶段 G2：MusicFree 插件协议契约（只读映射，不执行插件代码）。
 *
 * 依据官方 `maotoumao/MusicFreePlugins` 的 `types/plugin.d.ts`（GPL-3.0）：
 * - `search(query, page, type) -> { isEnd?, data: IMusicItem[] }`
 * - `getMediaSource(musicItem, quality) -> { url?, headers?, userAgent?, quality? }`
 * - `getMusicInfo(musicBase) -> Partial<IMusicItem>?`
 * - `getLyric(musicItem) -> { lrc?, rawLrc? }?`
 *
 * 本文件只定义**数据形状与解析**，不含网络、不含脚本执行、不含凭据。
 * 与 LX 协议的差异（search 是宿主能力 vs MusicFree search 是插件能力）全部收口在适配层。
 */
internal val mfJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** MusicFree 支持的媒体搜索类型（对应 ICommon.SupportMediaType）。 */
enum class MusicFreeSearchType(val wire: String) {
    MUSIC("music"), ALBUM("album"), ARTIST("artist"), SHEET("sheet");

    companion object {
        fun from(wire: String?): MusicFreeSearchType? =
            entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) }
    }
}

/** MusicFree 音质键（IMusic.IQualityKey），与内部 quality policy 字符串互转。 */
enum class MusicFreeQuality(val wire: String) {
    LOW("low"), STANDARD("standard"), HIGH("high"), SUPER("super");

    companion object {
        fun from(wire: String?): MusicFreeQuality? =
            entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) }
    }
}

/** 一个 MusicFree 音乐条目（映射到内部 MusicTrack 前的中性模型）。 */
data class MusicFreeMusicItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artwork: String? = null,
    val durationMs: Long? = null,
    /** 平台原始字段，resolve 阶段原样回传给插件，避免错歌。 */
    val raw: Map<String, String> = emptyMap(),
)

/** 搜索结果（对应 ISearchResult）。 */
data class MusicFreeSearchResult(
    val type: MusicFreeSearchType,
    val isEnd: Boolean,
    val items: List<MusicFreeMusicItem>,
)

/** 播放地址结果（对应 IMediaSourceResult）。 */
data class MusicFreeMediaSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val quality: MusicFreeQuality? = null,
)

/** 歌词结果（对应 ILyricSource）。 */
data class MusicFreeLyric(val lrc: String? = null, val rawLrc: String? = null)

/** 解析/校验失败（fail-closed，绝不伪造条目）。 */
data class MusicFreeParseError(val reason: String) : Exception(reason)

/** 从插件返回的 JSON 文本解析搜索结果；结构不符返回错误，不抛异常、不伪造数据。 */
fun parseMusicFreeSearch(json: String, type: MusicFreeSearchType): Result<MusicFreeSearchResult> {
    val root = runCatching { mfJson.parseToJsonElement(json).jsonObject }
        .getOrElse { return Result.failure(MusicFreeParseError("搜索响应不是 JSON 对象: ${it.message}")) }
    val dataArray = root["data"]?.runCatching { jsonArray }
        ?.getOrElse { return Result.failure(MusicFreeParseError("搜索响应 data 不是数组")) }
        ?: return Result.failure(MusicFreeParseError("搜索响应缺少 data"))
    val isEnd = (root["isEnd"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
    val items = dataArray.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.str("id") ?: return@mapNotNull null
        val title = obj.str("title") ?: return@mapNotNull null
        MusicFreeMusicItem(
            id = id,
            title = title,
            artist = obj.str("artist") ?: obj.str("artists") ?: "",
            album = obj.str("album") ?: "",
            artwork = obj.str("artwork") ?: obj.str("coverImg"),
            durationMs = obj.num("duration")?.toLongOrNull(),
            raw = obj.entries.associate { (k, v) -> k to (v as? JsonPrimitive)?.contentOrNull.orEmpty() },
        )
    }
    return Result.success(MusicFreeSearchResult(type, isEnd, items))
}

/** 解析 getMediaSource 返回；无合法 HTTPS URL 即失败。 */
fun parseMusicFreeMediaSource(json: String): Result<MusicFreeMediaSource> {
    val obj = runCatching { mfJson.parseToJsonElement(json).jsonObject }
        .getOrElse { return Result.failure(MusicFreeParseError("URL 响应不是 JSON 对象: ${it.message}")) }
    val url = obj.str("url")?.trim()
    if (url.isNullOrEmpty()) return Result.failure(MusicFreeParseError("URL 响应缺少可用 url"))
    if (!url.startsWith("https://", ignoreCase = true)) {
        return Result.failure(MusicFreeParseError("getMediaSource 必须返回 HTTPS URL（禁止明文/协议相对）"))
    }
    val headers = (obj["headers"] as? JsonObject)?.entries
        ?.associate { (k, v) -> k to (v as? JsonPrimitive)?.contentOrNull.orEmpty() }
        ?: emptyMap()
    return Result.success(
        MusicFreeMediaSource(
            url = url,
            headers = headers,
            userAgent = obj.str("userAgent"),
            quality = MusicFreeQuality.from(obj.str("quality")),
        )
    )
}

/** 解析 getLyric 返回。 */
fun parseMusicFreeLyric(json: String): Result<MusicFreeLyric> {
    val obj = runCatching { mfJson.parseToJsonElement(json).jsonObject }
        .getOrElse { return Result.failure(MusicFreeParseError("歌词响应不是 JSON 对象: ${it.message}")) }
    return Result.success(MusicFreeLyric(lrc = obj.str("lrc"), rawLrc = obj.str("rawLrc")))
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.num(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
