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
import java.security.MessageDigest
import kotlin.random.Random

/** Subsonic / Navidrome 连接配置。 */
data class SubsonicConfig(
    val enabled: Boolean,
    val serverUrl: String,
    val username: String,
    val password: String,
) {
    val isConfigured: Boolean
        get() = enabled && serverUrl.isNotBlank() && username.isNotBlank()
}

/**
 * Navidrome / Subsonic 自建音乐源。
 *
 * 使用官方 Subsonic API：`search3` 按标题+歌手搜索，`stream` 播放原始文件。
 * 认证参数直接放在 URL 上（token + salt），因此播放和下载都不需要额外请求头。
 */
class SubsonicClient(
    private val configProvider: () -> SubsonicConfig,
    private val maxCandidates: Int = 3,
) : LosslessSource {
    override val id: String = LOSSLESS_SOURCE_SUBSONIC
    override val displayName: String = "Navidrome/Subsonic"

    private val json = Json { ignoreUnknownKeys = true }
    private val transport = FlacHttpTransport()

    @Volatile
    private var requestFailed = false

    override val lastNetworkOperationFailed: Boolean
        get() = requestFailed || transport.lastNetworkOperationFailed

    override suspend fun search(track: MusicTrack): List<LosslessCandidate> = withContext(Dispatchers.IO) {
        val config = configProvider()
        if (!config.isConfigured) return@withContext emptyList()

        val query = "${track.title} ${track.artists}".trim()
        if (query.isBlank()) return@withContext emptyList()
        val url = buildUrl(
            config = config,
            endpoint = "search3.view",
            params = mapOf(
                "query" to query,
                "songCount" to maxCandidates.toString(),
                "artistCount" to "0",
                "albumCount" to "0",
            ),
        )
        val response = requestJson(url)?.get("subsonic-response") as? JsonObject
            ?: return@withContext emptyList()
        if (response.string("status")?.equals("ok", ignoreCase = true) != true) {
            AppLogger.w(TAG, "Subsonic 返回失败状态：${response.string("status")}")
            return@withContext emptyList()
        }
        val songs = ((response["searchResult3"] as? JsonObject)?.get("song") as? JsonArray)
            ?: return@withContext emptyList()

        songs.mapNotNull { element ->
            val song = element as? JsonObject ?: return@mapNotNull null
            val suffix = song.string("suffix").orEmpty()
            // 只接受 FLAC 原始文件；mp3/aac 等有损格式直接跳过。
            if (!suffix.equals("flac", ignoreCase = true)) return@mapNotNull null
            val songId = song.string("id") ?: return@mapNotNull null
            val title = song.string("title") ?: return@mapNotNull null
            val candidate = LosslessCandidate(
                source = LOSSLESS_SOURCE_SUBSONIC,
                title = title,
                artists = song.string("artist").orEmpty(),
                album = song.string("album").orEmpty(),
                remoteUrl = buildUrl(
                    config = config,
                    endpoint = "stream.view",
                    params = mapOf("id" to songId, "format" to "raw"),
                ),
                fileFormat = "FLAC",
                sizeBytes = song.string("size")?.toLongOrNull() ?: 0L,
                durationSeconds = song.string("duration")?.toDoubleOrNull() ?: 0.0,
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

    /** 生成带 token + salt 认证的 Subsonic 请求地址。 */
    private fun buildUrl(
        config: SubsonicConfig,
        endpoint: String,
        params: Map<String, String>,
    ): String {
        val salt = randomSalt()
        val token = md5(config.password + salt)
        return buildString {
            append(config.serverUrl.trim().trimEnd('/'))
            append("/rest/").append(endpoint)
            append("?u=").append(encode(config.username))
            append("&t=").append(token)
            append("&s=").append(salt)
            append("&v=1.16.1&c=FuoEvolve&f=json")
            params.forEach { (key, value) ->
                append('&').append(encode(key)).append('=').append(encode(value))
            }
        }
    }

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
            requestFailed = false
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            json.parseToJsonElement(text) as? JsonObject
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            requestFailed = true
            AppLogger.w(TAG, "Subsonic 请求失败", error)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun randomSalt(): String =
        (1..8).map { SALT_CHARS[Random.nextInt(SALT_CHARS.length)] }.joinToString("")

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val TAG = "LosslessSubsonic"
        const val USER_AGENT = "FuoEvolve-Android/1.0 (lossless enhancer)"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 12_000
        const val SALT_CHARS = "0123456789abcdef"
    }
}
