package org.feeluown.mobile.lossless

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.MusicTrack
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Jamendo 开放曲库客户端。
 *
 * Jamendo 在国内可直连，但接口需要免费的 client_id（在 developer.jamendo.com 注册应用即可）。
 * 请求时指定 `audioformat=flac`，只接受返回 FLAC 流的条目；没有配置 client_id 时直接跳过，
 * 不产生任何网络请求。
 */
class JamendoClient(
    private val clientIdProvider: () -> String,
    private val maxResults: Int = 10,
    private val maxCandidates: Int = 3,
) : LosslessSource {
    override val id: String = LOSSLESS_SOURCE_JAMENDO
    override val displayName: String = "Jamendo"

    private val json = Json { ignoreUnknownKeys = true }
    private val transport = FlacHttpTransport()

    @Volatile
    private var searchRequestFailed = false

    override val lastNetworkOperationFailed: Boolean
        get() = searchRequestFailed || transport.lastNetworkOperationFailed

    override suspend fun search(track: MusicTrack): List<LosslessCandidate> = withContext(Dispatchers.IO) {
        val clientId = clientIdProvider().trim()
        if (clientId.isBlank()) return@withContext emptyList()

        val query = "${track.title} ${track.artists}".trim()
        if (query.isBlank()) return@withContext emptyList()
        val url = buildString {
            append(SEARCH_BASE)
            append("?client_id=").append(encode(clientId))
            append("&format=json&limit=").append(maxResults)
            append("&audioformat=flac")
            append("&search=").append(encode(query))
        }
        val root = requestJson(url) ?: return@withContext emptyList()
        val results = root["results"] as? JsonArray ?: return@withContext emptyList()

        results.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            // audio 是流地址，audiodownload 是原始文件；优先取真正的 FLAC 地址。
            val audioUrl = listOfNotNull(item.string("audio"), item.string("audiodownload"))
                .firstOrNull { url ->
                    url.contains("format=flac", ignoreCase = true) ||
                        url.contains(".flac", ignoreCase = true)
                }
                ?: return@mapNotNull null
            val title = item.string("name") ?: return@mapNotNull null
            val candidate = LosslessCandidate(
                source = LOSSLESS_SOURCE_JAMENDO,
                title = title,
                artists = item.string("artist_name").orEmpty(),
                album = item.string("album_name").orEmpty(),
                remoteUrl = audioUrl,
                fileFormat = "FLAC",
                durationSeconds = item.string("duration")?.toDoubleOrNull() ?: 0.0,
            )
            candidate.copy(score = scoreLosslessCandidate(track, candidate))
        }
            .filter { it.isConfidentMatch() }
            .sortedByDescending { it.score }
            .take(maxCandidates)
            .onEach { candidate ->
                AppLogger.i(
                    TAG,
                    "命中候选 title=${candidate.title} artists=${candidate.artists} " +
                        "score=%.2f".format(candidate.score),
                )
            }
    }

    override suspend fun probeFlacHeader(url: String): FlacStreamInfo? =
        transport.probeFlacHeader(url)

    override suspend fun download(url: String, target: File): Boolean =
        transport.download(url, target)

    private fun requestJson(url: String): JsonObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return null
            searchRequestFailed = false
            val text = connection.readBoundedJsonText() ?: return null
            json.parseToJsonElement(text) as? JsonObject
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            searchRequestFailed = true
            AppLogger.w(TAG, "Jamendo 请求失败", error)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val TAG = "LosslessJamendo"
        const val SEARCH_BASE = "https://api.jamendo.com/v3.0/tracks/"
        const val USER_AGENT = "FuoEvolve-Android/1.0 (lossless enhancer)"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 12_000
    }
}
