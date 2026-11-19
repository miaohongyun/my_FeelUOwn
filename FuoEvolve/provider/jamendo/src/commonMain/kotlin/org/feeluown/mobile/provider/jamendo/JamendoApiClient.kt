package org.feeluown.mobile.provider.jamendo

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Jamendo API v3.0 客户端。
 *
 * 为什么手写 JSON 解析而不用序列化模型：Jamendo 的 `id` 在不同接口/版本里可能是字符串或数字，
 * kotlinx 严格模式会因此直接抛异常。这里按 JsonObject 逐字段取值，类型不匹配时退化为空，
 * 一个坏字段不会让整页搜索结果消失。
 *
 * 需要 client_id（developer.jamendo.com 免费申请）。没有配置时不发请求，直接返回空结果。
 */
class JamendoApiClient(
    private val client: HttpClient,
    private val clientIdProvider: suspend () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val timeoutMillis: Long = 8_000L,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    suspend fun search(keyword: String, limit: Int = 20): JamendoSearchResult {
        val query = keyword.trim()
        val clientId = clientIdProvider()?.trim()
        if (query.isEmpty() || clientId.isNullOrEmpty()) return JamendoSearchResult(emptyList(), 0)

        val url = buildString {
            append(baseUrl).append("/tracks/")
            append("?client_id=").append(encode(clientId))
            append("&format=json&limit=").append(limit.coerceIn(1, 50))
            append("&search=").append(encode(query))
            append("&audioformat=mp32&include=musicinfo&imagesize=400")
        }
        val body = fetch(url) ?: return JamendoSearchResult(emptyList(), 0)
        return parseSearch(body)
    }

    /** 重新取一次播放地址：Jamendo 的 `audio` 带时效，不能长期缓存。 */
    suspend fun trackById(trackId: String): JamendoTrack? {
        val clientId = clientIdProvider()?.trim()
        if (trackId.isBlank() || clientId.isNullOrEmpty()) return null
        val url = buildString {
            append(baseUrl).append("/tracks/")
            append("?client_id=").append(encode(clientId))
            append("&format=json&id=").append(encode(trackId))
            append("&audioformat=mp32")
        }
        val body = fetch(url) ?: return null
        return parseTracks(body).firstOrNull()
    }

    fun parseSearch(rawText: String): JamendoSearchResult {
        val root = runCatching { json.parseToJsonElement(rawText).jsonObject }.getOrNull()
            ?: return JamendoSearchResult(emptyList(), 0)
        val total = runCatching {
            (root["headers"] as? JsonObject)?.get("results_count")?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: 0
        return JamendoSearchResult(parseTracks(rawText), total)
    }

    private fun parseTracks(rawText: String): List<JamendoTrack> {
        val root = runCatching { json.parseToJsonElement(rawText).jsonObject }.getOrNull() ?: return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.text("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val audio = item.text("audio").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            JamendoTrack(
                id = id,
                name = item.text("name"),
                artist = item.text("artist_name"),
                album = item.text("album_name"),
                durationSeconds = item.text("duration").toIntOrNull(),
                audioUrl = audio,
                downloadUrl = item.text("audiodownload"),
                imageUrl = item.text("image"),
                shareUrl = item.text("shareurl"),
            )
        }
    }

    /**
     * 超时放在 [Dispatchers.Default] 的真实时间里计时。
     * 直接在调用方协程里 `withTimeoutOrNull` 会被虚拟时间调度器（例如 runTest）立刻触发，
     * 造成"请求还没发出去就超时"的假象——这个坑在 lxruntime 上踩过一次。
     */
    private suspend fun fetch(url: String): String? = withContext(Dispatchers.Default) {
        withTimeoutOrNull(timeoutMillis) {
            runCatching { client.get(url).bodyAsText() }.getOrNull()
        }
    }

    private fun JsonObject.text(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")

    private fun encode(value: String): String = buildString {
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
        const val DEFAULT_BASE_URL = "https://api.jamendo.com/v3.0"
    }
}
