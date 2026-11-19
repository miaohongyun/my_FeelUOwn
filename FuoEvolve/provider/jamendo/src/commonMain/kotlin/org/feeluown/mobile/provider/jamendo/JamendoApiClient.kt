package org.feeluown.mobile.provider.jamendo

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
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
        if (query.isEmpty()) return JamendoSearchResult(emptyList(), 0)
        if (clientId.isNullOrEmpty()) {
            return JamendoSearchResult(emptyList(), 0, JamendoFailureKind.MissingClientId)
        }

        val url = buildString {
            append(baseUrl).append("/tracks/")
            append("?client_id=").append(encode(clientId))
            append("&format=json&limit=").append(limit.coerceIn(1, 50))
            append("&search=").append(encode(query))
            append("&audioformat=mp32&include=musicinfo&imagesize=400")
        }
        return when (val response = fetch(url)) {
            is FetchResult.Failure -> JamendoSearchResult(
                emptyList(), 0,
                if (response.kind == JamendoFailureKind.Network) JamendoFailureKind.Network
                else JamendoFailureKind.Upstream,
            )
            is FetchResult.Success -> parseSearch(response.body)
        }
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
        // 地址重取失败与"该曲目已下架"都退化成 null，由 Provider 的 resolve 统一提示，
        // 不在这里区分——避免接口签名复杂化，且播放失败提示对用户已足够明确。
        return when (val response = fetch(url)) {
            is FetchResult.Success -> parseTracks(response.body).firstOrNull()
            is FetchResult.Failure -> null
        }
    }

    /** 内部获取结果：区分正常响应、网络失败、HTTP 错误，供 search 填充 failure。 */
    private sealed interface FetchResult {
        data class Success(val body: String) : FetchResult
        data class Failure(val kind: JamendoFailureKind) : FetchResult
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
                licenseCcUrl = item.text("license_ccurl"),
            )
        }
    }

    /**
     * 超时放在 [Dispatchers.Default] 的真实时间里计时。
     * 直接在调用方协程里 `withTimeoutOrNull` 会被虚拟时间调度器（例如 runTest）立刻触发，
     * 造成"请求还没发出去就超时"的假象——这个坑在 lxruntime 上踩过一次。
     */
    private suspend fun fetch(url: String): FetchResult = withContext(Dispatchers.Default) {
        val result = withTimeoutOrNull(timeoutMillis) {
            try {
                val response = client.get(url)
                // Ktor 默认不因 4xx/5xx 抛异常，必须显式检查；否则错误页会被当成搜索结果解析成空。
                if (response.status.value !in 200..299) {
                    FetchResult.Failure(JamendoFailureKind.Upstream)
                } else {
                    FetchResult.Success(response.bodyAsText())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FetchResult.Failure(JamendoFailureKind.Network)
            }
        }
        // withTimeoutOrNull 超时返回 null —— 这是真实的网络失败，不能当成空结果。
        result ?: FetchResult.Failure(JamendoFailureKind.Network)
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
