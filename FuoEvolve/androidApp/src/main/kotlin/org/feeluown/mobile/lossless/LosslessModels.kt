package org.feeluown.mobile.lossless

import kotlinx.serialization.Serializable
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.SongVersionMatcher
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs


/**
 * Recognizes lossless codecs from a media URL's path, not from the mere fact that it is local.
 * A `file://` URI can point to MP3/AAC just as easily as FLAC; treating every local file as
 * lossless would incorrectly bypass the enhancer for ordinary lossy local files.
 */
internal fun isLosslessAudioLocation(location: String): Boolean {
    if (location.isBlank()) return false
    val uriPath = runCatching { URI(location).path }.getOrNull().orEmpty()
    val path = uriPath.ifBlank { location.substringBefore('?').substringBefore('#') }
    val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in LOSSLESS_AUDIO_EXTENSIONS
}

internal fun isLosslessQualityLabel(quality: String?): Boolean {
    val normalized = quality?.lowercase(Locale.ROOT).orEmpty()
    return LOSSLESS_QUALITY_MARKERS.any { marker -> normalized.contains(marker) }
}

private val LOSSLESS_AUDIO_EXTENSIONS = setOf("flac", "ape", "alac", "wv", "tta", "dsf", "dff")
private val LOSSLESS_QUALITY_MARKERS = listOf(
    "flac", "ape", "alac", "lossless", "无损", "hires", "hi-res", "24bit", "24-bit",
)

/** 开放曲库来源标识。 */
const val LOSSLESS_SOURCE_INTERNET_ARCHIVE = "internet-archive"

/** Jamendo 开放曲库来源标识。 */
const val LOSSLESS_SOURCE_JAMENDO = "jamendo"

/** Navidrome / Subsonic 自建音乐源标识。 */
const val LOSSLESS_SOURCE_SUBSONIC = "subsonic"

/** 无损缓存上限：超过后按最近最少使用淘汰，避免占满用户存储。 */
const val LOSSLESS_CACHE_MAX_BYTES = 1_536L * 1024L * 1024L

/** 单个无损文件下载上限，超过视为异常资源直接放弃。 */
const val LOSSLESS_MAX_FILE_BYTES = 220L * 1024L * 1024L

/** 一次已校验通过并落盘的无损缓存条目。 */
@Serializable
data class LosslessCacheEntry(
    val key: String,
    val trackId: String,
    val title: String,
    val artists: String,
    val source: String,
    val remoteUrl: String,
    val filePath: String,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val durationSeconds: Double,
    val sizeBytes: Long,
    val validatedAtMillis: Long,
    val spectralValidated: Boolean,
)

/** 落盘索引，条目以规范化后的曲目 key 为索引。 */
@Serializable
data class LosslessCacheIndex(
    val version: Int = 1,
    val entries: Map<String, LosslessCacheEntry> = emptyMap(),
)

/** 开放曲库检索到的一首候选曲目。 */
data class LosslessCandidate(
    val source: String,
    val title: String,
    val artists: String,
    val album: String = "",
    val remoteUrl: String,
    val fileFormat: String = "FLAC",
    val sizeBytes: Long = 0L,
    val durationSeconds: Double = 0.0,
    val score: Double = 0.0,
)

/** FLAC STREAMINFO 容器信息。 */
data class FlacStreamInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
) {
    val durationSeconds: Double
        get() = if (sampleRate > 0) totalSamples.toDouble() / sampleRate else 0.0
}

/** 频谱分析结果；用于识别 MP3 转码伪装的“无损”。 */
data class SpectralAnalysis(
    val sampleRate: Int,
    val analyzedFrames: Int,
    val midBandDb: Double,
    val highBandDb: Double,
    val maxCliffDb: Double,
    val cliffHz: Double,
    val looksTranscoded: Boolean,
)

/** 一次完整性校验的结果。 */
data class LosslessValidation(
    val accepted: Boolean,
    val reason: String,
    val streamInfo: FlacStreamInfo?,
    val spectral: SpectralAnalysis?,
)

/**
 * 无损音源适配器。
 *
 * 每个实现负责自己的一套检索协议，但统一暴露「检索 → 文件头探测 → 下载」三步，
 * 上层按顺序尝试，某个来源不可用时只让该来源退避，不影响其它来源。
 */
interface LosslessSource {
    val id: String
    val displayName: String

    /** 按标题 + 歌手检索，返回已按匹配度过滤的 FLAC 候选。 */
    suspend fun search(track: MusicTrack): List<LosslessCandidate>

    /** 只读取文件头，确认确实是 FLAC 容器。 */
    suspend fun probeFlacHeader(url: String): FlacStreamInfo?

    /** 下载整个文件，供完整校验与缓存。 */
    suspend fun download(url: String, target: File): Boolean

    /** 最近一次网络操作是否因网络层异常失败。 */
    val lastNetworkOperationFailed: Boolean
}

/**
 * 生成用于缓存索引的稳定 key。
 *
 * 只使用「主歌手 + 标题」的规范化文本，不包含时长：同一首歌在不同音源上的时长
 * 常有 1-2 秒差异，带上时长会导致同一首歌曲重复下载。
 */
fun losslessTrackKey(track: MusicTrack): String =
    sha1("${normalizeArtistsForMatch(track.artists)}|${normalizeForMatch(track.title)}")

/** 去掉括号注释、feat 信息与标点，只保留字母、数字和汉字。 */
fun normalizeForMatch(value: String): String {
    if (value.isBlank()) return ""
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val withoutBrackets = normalized
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("（[^）]*）"), " ")
        .replace(Regex("\\[[^]]*]"), " ")
        .replace(Regex("【[^】]*】"), " ")
        .replace(Regex("\\b(feat|ft|featuring)\\b.*$"), " ")
    return withoutBrackets
        .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

/** 取主歌手，避免「A / B」这类多歌手写法影响匹配。 */
fun normalizeArtistsForMatch(value: String): String {
    val lowered = value.lowercase(Locale.ROOT)
    val primary = lowered
        .split('/', ',', '&', ';', '、', '·')
        .firstOrNull()
        ?.substringBefore(" feat")
        ?.substringBefore(" ft")
        ?.trim()
        .orEmpty()
    return normalizeForMatch(primary.ifBlank { lowered })
}

/** 文本相似度：完全相等 1.0，包含 0.85，其余按词集合 Jaccard 计算。 */
fun textSimilarity(left: String, right: String): Double {
    if (left.isBlank() || right.isBlank()) return 0.0
    if (left == right) return 1.0
    if (left.contains(right) || right.contains(left)) return 0.85
    val leftTokens = left.split(' ').filter { it.isNotBlank() }.toSet()
    val rightTokens = right.split(' ').filter { it.isNotBlank() }.toSet()
    if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
    val intersection = leftTokens.intersect(rightTokens).size
    val union = leftTokens.union(rightTokens).size
    return if (union == 0) 0.0 else intersection.toDouble() / union
}

/**
 * 候选曲目与目标曲目的综合匹配分。
 *
 * 标题权重最高，其次是歌手，最后用时长做辅助校验；宁可漏匹配，也不要播错歌。
 */
fun scoreLosslessCandidate(track: MusicTrack, candidate: LosslessCandidate): Double {
    val normalizedTrackTitle = normalizeForMatch(SongVersionMatcher.stripTrailingVersionMarkers(track.title))
    val normalizedCandidateTitle = normalizeForMatch(SongVersionMatcher.stripTrailingVersionMarkers(candidate.title))
    val titleScore = textSimilarity(normalizedTrackTitle, normalizedCandidateTitle)
    val artistScore = textSimilarity(
        normalizeArtistsForMatch(track.artists),
        normalizeArtistsForMatch(candidate.artists),
    )

    // A loose weighted score alone can accept a same-title cover when artist metadata is
    // missing. Apply hard gates before duration contributes to the score.
    if (titleScore < MIN_TITLE_MATCH_SCORE || artistScore < MIN_ARTIST_MATCH_SCORE) return 0.0
    if (hasIncompatibleVersion(track, candidate)) return 0.0

    val trackDurationMs = track.durationMs
    val durationDeltaSeconds = if (trackDurationMs == null || candidate.durationSeconds <= 0.0) {
        null
    } else {
        abs(trackDurationMs / 1000.0 - candidate.durationSeconds)
    }
    if (durationDeltaSeconds != null && durationDeltaSeconds > MAX_DURATION_DELTA_SECONDS) return 0.0
    val durationScore = when {
        durationDeltaSeconds == null -> 0.5
        durationDeltaSeconds <= 3.0 -> 1.0
        durationDeltaSeconds <= 10.0 -> 0.7
        else -> 0.35
    }
    return titleScore * 0.55 + artistScore * 0.30 + durationScore * 0.15
}

/** 候选是否达到可播放/可下载的最低可信度。 */
fun LosslessCandidate.isConfidentMatch(): Boolean = score >= 0.70

/**
 * Reject a candidate whose recording version differs from the request in either direction.
 *
 * The comparison is deliberately symmetric: a studio request must not accept a live/cover
 * candidate, and a live request must not silently fall back to the studio recording. Title,
 * album and (for open-catalog sources) platform tags all count as version evidence because
 * providers disagree about where they put 「Live」「翻唱」.
 */
private fun hasIncompatibleVersion(track: MusicTrack, candidate: LosslessCandidate): Boolean =
    SongVersionMatcher.conflicts(
        requested = SongVersionMatcher.kindsOf(track.title, track.album),
        candidate = SongVersionMatcher.kindsOf(candidate.title, candidate.album),
    )

private const val MIN_TITLE_MATCH_SCORE = 0.82
private const val MIN_ARTIST_MATCH_SCORE = 0.50
private const val MAX_DURATION_DELTA_SECONDS = 20.0

private fun sha1(value: String): String =
    MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
