package org.feeluown.mobile

import kotlin.math.roundToInt

fun currentPlaybackPartLabel(state: PlaybackState): String? {
    val part = state.playbackParts.getOrNull(state.currentPartIndex) ?: return null
    return "第 ${state.currentPartIndex + 1}P · ${part.title.ifBlank { "未命名分段" }}"
}

fun sourceLabel(track: MusicTrack, downloadState: DownloadState?): String {
    val state = when (downloadState) {
        is DownloadState.Downloaded -> "已下载"
        is DownloadState.Downloading -> "下载中"
        DownloadState.Paused -> "下载已暂停"
        is DownloadState.Failed -> "下载失败"
        DownloadState.Queued -> "等待下载"
        else -> null
    }
    return listOfNotNull(
        when (track.sourceType) {
            TrackSourceType.Provider -> track.providerName ?: track.source.ifBlank { "音源" }
            TrackSourceType.LocalMediaStore -> "本地"
            TrackSourceType.Downloaded -> "本地"
        },
        "智能替换".takeIf { track.isSmartReplacement },
        "不可用".takeIf { track.isUnavailable },
        state,
    ).joinToString(" · ")
}

/**
 * 从 Provider 写入的 providerTags 中解析出的开放曲库许可证信息。
 *
 * 只有 Jamendo 这类 CC 曲库会写入这些 tag；商业来源没有 cc: 标签，解析结果为 null，
 * UI 因此不会误标。字段全部来自 [MusicTrack.providerTags]，不再硬编码曲库类型判断。
 */
data class TrackLicenseInfo(
    /** CC 许可证代码，如 BY-NC-ND。 */
    val code: String,
    /** 可读的适用条件说明（含 BY/NC/ND/SA 逐项限制）。 */
    val summary: String,
    /** 原始许可条款链接（creativecommons.org/...），供用户核对。 */
    val licenseUrl: String,
) {
    /** 列表 chip 上的紧凑标签，如 "CC BY-NC-ND"。 */
    val shortLabel: String get() = "CC $code"
}

/**
 * 纯解析：从 [tags] 中提取 CC 许可证信息。
 *
 * JamendoMusicProvider.toMusicTrack 写入的 tag 格式：
 * - `cc:BY-NC-ND` —— 许可证代码
 * - `license-url:http://creativecommons.org/...` —— 原始链接
 * - `BY-NC-ND：须署名(BY)、...` —— 可读条件摘要（以 code 开头）
 *
 * 没有任何 `cc:` 标签时返回 null（商业来源/普通曲目），保证不误标、不把它们说成开放资源。
 */
fun licenseInfoFromTags(tags: List<String>): TrackLicenseInfo? {
    val code = tags.firstNotNullOfOrNull { tag ->
        tag.takeIf { it.startsWith("cc:", ignoreCase = true) }
            ?.removePrefix("cc:")
            ?.removePrefix("CC:")
            ?.takeIf { it.isNotBlank() }
    } ?: return null
    val licenseUrl = tags.firstNotNullOfOrNull { tag ->
        tag.takeIf { it.startsWith("license-url:", ignoreCase = true) }
            ?.removePrefix("license-url:")
            ?.takeIf { it.isNotBlank() }
    }.orEmpty()
    // 摘要 tag 以 code 开头（如 "BY-NC-ND：..."）；找不到时至少回退到 code，绝不空着。
    val summary = tags.firstOrNull { tag ->
        tag.startsWith(code, ignoreCase = true) && !tag.startsWith("cc:", ignoreCase = true)
    } ?: code
    return TrackLicenseInfo(code = code, summary = summary, licenseUrl = licenseUrl)
}

fun replacementSourceLabel(track: MusicTrack): String {
    val source = track.replacementProviderName?.takeIf { it.isNotBlank() }
        ?: track.providerName?.takeIf { it.isNotBlank() }
        ?: track.replacementSource?.takeIf { it.isNotBlank() }
        ?: "音源"
    return "换源 • $source"
}

fun replacementProviderLabel(track: MusicTrack): String {
    return listOfNotNull(
        track.replacementProviderName ?: track.providerName,
        track.replacementSource?.takeIf { it != track.replacementProviderName },
    )
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" · ")
}

fun artistAlbumLabel(track: MusicTrack): String {
    return listOf(track.artists, track.album)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

fun formatPlayCount(value: Long): String {
    return when {
        value >= 100_000_000 -> "${value / 100_000_000} 亿次播放"
        value >= 10_000 -> "${value / 10_000} 万次播放"
        else -> "$value 次播放"
    }
}

fun formatBytes(value: Long): String {
    return when {
        value >= 1024L * 1024L * 1024L -> "${value / (1024L * 1024L * 1024L)} GB"
        value >= 1024L * 1024L -> "${value / (1024L * 1024L)} MB"
        value >= 1024L -> "${value / 1024L} KB"
        else -> "$value B"
    }
}

fun formatCacheLimit(value: Int): String {
    return if (value >= 1024 && value % 1024 == 0) {
        "${value / 1024}GB"
    } else {
        "${value}MB"
    }
}

fun formatSmartReplacementScore(value: Double): String {
    val hundred = (value.coerceIn(0.0, 1.0) * 100).roundToInt()
    return "${hundred / 100}.${(hundred % 100).toString().padStart(2, '0')}"
}

fun roundSmartReplacementScore(value: Double): Double {
    return (value.coerceIn(0.0, 1.0) * 20).roundToInt() / 20.0
}

fun localTitleSection(title: String): String {
    val first = title.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (first in 'A'..'Z') first.toString() else "#"
}

fun localTitleSectionOrder(section: String): Int {
    val first = section.firstOrNull() ?: return 26
    return if (first in 'A'..'Z') first - 'A' else 26
}

fun normalizedGroupName(value: String, fallback: String): String {
    return value.trim().ifBlank { fallback }
}

fun formatMs(value: Long): String {
    val totalSeconds = (value / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
