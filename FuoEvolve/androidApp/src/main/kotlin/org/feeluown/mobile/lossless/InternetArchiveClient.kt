package org.feeluown.mobile.lossless

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * Internet Archive 开放曲库客户端。
 *
 * 只依赖 archive.org 的公开检索/元数据/下载接口，不需要任何账号或 API key。
 * 检索阶段只取少量高相关条目，再从中挑 FLAC 原始文件，避免无谓的网络请求。
 */
class InternetArchiveClient(
    private val maxDocs: Int = 4,
    private val maxCandidates: Int = 3,
) : LosslessSource {
    override val id: String = LOSSLESS_SOURCE_INTERNET_ARCHIVE
    override val displayName: String = "Internet Archive"

    private val json = Json { ignoreUnknownKeys = true }
    private val transport = FlacHttpTransport()

    /** 检索接口本身是否失败；与 FLAC 下载失败合并后供上层退避。 */
    @Volatile
    private var searchRequestFailed = false

    override val lastNetworkOperationFailed: Boolean
        get() = searchRequestFailed || transport.lastNetworkOperationFailed

    /** 按标题 + 歌手检索，返回按匹配分降序排列的 FLAC 候选。 */
    override suspend fun search(track: MusicTrack): List<LosslessCandidate> = withContext(Dispatchers.IO) {
        val docs = try {
            searchDocs(track)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLogger.w(TAG, "Internet Archive 检索失败 title=${track.title}", error)
            emptyList()
        }
        if (docs.isEmpty()) return@withContext emptyList()

        val candidates = LinkedHashMap<String, LosslessCandidate>()
        for (doc in docs.take(maxDocs)) {
            val identifier = doc.identifier
            val files = try {
                metadataFiles(identifier)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLogger.w(TAG, "Internet Archive 元数据失败 identifier=$identifier", error)
                emptyList()
            }
            for (candidate in files) {
                candidates.putIfAbsent(candidate.remoteUrl, candidate.copy(
                    score = scoreLosslessCandidate(track, candidate),
                ))
            }
            if (candidates.values.count { it.isConfidentMatch() } >= maxCandidates) break
        }

        candidates.values
            .filter { it.isConfidentMatch() }
            .sortedByDescending { it.score }
            .take(maxCandidates)
            .onEach { candidate ->
                AppLogger.i(
                    TAG,
                    "命中候选 title=${candidate.title} artists=${candidate.artists} " +
                        "score=%.2f size=%.1fMB".format(candidate.score, candidate.sizeBytes / 1024.0 / 1024.0),
                )
            }
    }

    override suspend fun probeFlacHeader(url: String): FlacStreamInfo? =
        transport.probeFlacHeader(url)

    override suspend fun download(url: String, target: File): Boolean =
        transport.download(url, target)

    private fun searchDocs(track: MusicTrack): List<IaDoc> {
        val titleTerm = solrEscape(track.title)
        if (titleTerm.isBlank()) return emptyList()
        val artistTerm = solrEscape(track.artists)
        val query = if (artistTerm.isBlank()) {
            "title:(\"$titleTerm\") AND mediatype:(audio)"
        } else {
            "title:(\"$titleTerm\") AND creator:(\"$artistTerm\") AND mediatype:(audio)"
        }
        val docs = requestJson(searchUrl(query))
            ?.obj("response")
            ?.array("docs")
            ?.mapNotNull { element -> element as? JsonObject }
            ?.mapNotNull { doc ->
                val identifier = doc.string("identifier") ?: return@mapNotNull null
                IaDoc(identifier = identifier, title = doc.string("title").orEmpty())
            }
            .orEmpty()
        if (docs.isNotEmpty()) return docs

        // 歌手写法差异很常见，退化为只按标题检索一次。
        if (artistTerm.isBlank()) return emptyList()
        val fallbackQuery = "title:(\"$titleTerm\") AND mediatype:(audio)"
        return requestJson(searchUrl(fallbackQuery))
            ?.obj("response")
            ?.array("docs")
            ?.mapNotNull { element -> element as? JsonObject }
            ?.mapNotNull { doc ->
                val identifier = doc.string("identifier") ?: return@mapNotNull null
                IaDoc(identifier = identifier, title = doc.string("title").orEmpty())
            }
            .orEmpty()
    }

    private fun metadataFiles(identifier: String): List<LosslessCandidate> {
        val root = requestJson("$METADATA_BASE/$identifier") ?: return emptyList()
        val metadata = root.obj("metadata")
        val metadataTitle = metadata?.string("title").orEmpty()
        val metadataArtists = metadata?.let(::creatorText).orEmpty()
        val metadataAlbum = metadata?.string("album").orEmpty()
        val files = root.array("files") ?: return emptyList()

        return files.mapNotNull { element ->
            val file = element as? JsonObject ?: return@mapNotNull null
            val name = file.string("name") ?: return@mapNotNull null
            val format = file.string("format").orEmpty()
            val isFlac = name.endsWith(".flac", ignoreCase = true) ||
                format.contains("flac", ignoreCase = true)
            if (!isFlac) return@mapNotNull null
            // 跳过频谱图、封面之类的小文件。
            val size = file.string("size")?.toLongOrNull() ?: 0L
            if (size in 1L until MIN_FLAC_BYTES) return@mapNotNull null

            val title = file.string("title")?.takeIf { it.isNotBlank() }
                ?: metadataTitle
            if (title.isBlank()) return@mapNotNull null
            val artists = file.string("artist")?.takeIf { it.isNotBlank() }
                ?: file.string("creator")?.takeIf { it.isNotBlank() }
                ?: metadataArtists
            LosslessCandidate(
                source = LOSSLESS_SOURCE_INTERNET_ARCHIVE,
                title = title,
                artists = artists,
                album = file.string("album")?.takeIf { it.isNotBlank() } ?: metadataAlbum,
                remoteUrl = downloadUrl(identifier, name),
                fileFormat = "FLAC",
                sizeBytes = size,
                durationSeconds = parseDurationSeconds(file.string("length")),
            )
        }.sortedByDescending { candidate ->
            // 原始文件优先，IA 的衍生文件通常是有损或低码率。
            if (candidate.sizeBytes >= MIN_FLAC_BYTES * 4) 1 else 0
        }
    }

    private fun creatorText(metadata: JsonObject): String {
        val creator = metadata["creator"] ?: return ""
        return when (creator) {
            is JsonArray -> creator.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            is JsonPrimitive -> creator.contentOrNull.orEmpty()
            else -> ""
        }
    }

    private fun searchUrl(query: String): String = buildString {
        append(SEARCH_BASE)
        append("?q=").append(encode(query))
        append("&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator")
        append("&rows=").append(maxDocs)
        append("&page=1&output=json")
    }

    private fun downloadUrl(identifier: String, fileName: String): String {
        val encodedPath = fileName.split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { segment -> encode(segment).replace("+", "%20") }
        return "$DOWNLOAD_BASE/$identifier/$encodedPath"
    }

    private fun requestJson(url: String): JsonElement? {
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
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            json.parseToJsonElement(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            searchRequestFailed = true
            AppLogger.w(TAG, "请求失败 url=$url", error)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun solrEscape(value: String): String = value
        .replace(Regex("[+\\-&|!(){}\\[\\]^~*?:\\\\/\"]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun parseDurationSeconds(raw: String?): Double {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return 0.0
        value.toDoubleOrNull()?.let { return it }
        val parts = value.split(':').mapNotNull { it.toDoubleOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> 0.0
        }
    }

    private data class IaDoc(
        val identifier: String,
        val title: String,
    )

    private companion object {
        const val TAG = "LosslessArchive"
        const val SEARCH_BASE = "https://archive.org/advancedsearch.php"
        const val METADATA_BASE = "https://archive.org/metadata"
        const val DOWNLOAD_BASE = "https://archive.org/download"
        const val USER_AGENT = "FuoEvolve-Android/1.0 (lossless enhancer)"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 12_000
        const val MIN_FLAC_BYTES = 1024L * 1024L
    }
}

private fun JsonElement?.obj(key: String): JsonObject? =
    (this as? JsonObject)?.get(key) as? JsonObject

private fun JsonElement?.array(key: String): JsonArray? =
    (this as? JsonObject)?.get(key) as? JsonArray

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
