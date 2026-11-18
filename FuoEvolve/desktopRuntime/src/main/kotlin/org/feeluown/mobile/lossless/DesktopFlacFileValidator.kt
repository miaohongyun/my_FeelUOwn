package org.feeluown.mobile.lossless

import java.io.File
import java.nio.file.Path
import org.feeluown.mobile.PlaybackPayload
import java.io.FileInputStream

/** Validates a downloaded FLAC's container header and basic file-size invariants. */
internal object DesktopFlacFileValidator {
    fun validate(file: File): FlacStreamInfo? {
        if (!file.isFile || file.length() < MINIMUM_FLAC_FILE_BYTES || file.length() > LOSSLESS_MAX_FILE_BYTES) {
            return null
        }
        return runCatching {
            FileInputStream(file).use { input ->
                val header = ByteArray(HEADER_BYTES)
                var offset = 0
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    if (count < 0) break
                    offset += count
                }
                DesktopFlacStreamInfoParser.parse(header.copyOf(offset))
            }
        }.getOrNull()
    }

    private const val HEADER_BYTES = 64 * 1024
    /**
     * A complete song can never be this small. Anything at or below the STREAMINFO header size
     * is a truncated download or an error page that happens to start with `fLaC`.
     */
    private const val MINIMUM_FLAC_FILE_BYTES = 1L * 1024L
}

/** Desktop download hook: FLAC-labelled downloads must pass a real container-header check. */
fun validateDesktopDownloadedMedia(file: Path, payload: PlaybackPayload? = null): String? =
    validateDesktopDownloadedMediaWith(file, payload, DesktopFlacDecodeVerifier::verify)

/**
 * Same contract as [validateDesktopDownloadedMedia], with an injectable decode step.
 *
 * Tests replace the FFmpeg call so the container-level behaviour is deterministic whether or
 * not the machine has FFmpeg installed.
 */
internal fun validateDesktopDownloadedMediaWith(
    file: Path,
    payload: PlaybackPayload? = null,
    decodeVerifier: (File) -> Boolean?,
): String? {
    val pathLooksFlac = file.fileName.toString().endsWith(".flac", ignoreCase = true) ||
        (payload?.let { isLosslessAudioLocation(it.url) &&
            runCatching { java.net.URI(it.url).path.orEmpty().endsWith(".flac", ignoreCase = true) }
                .getOrDefault(false) } == true)
    val qualityLooksFlac = payload?.audioQuality?.contains("flac", ignoreCase = true) == true
    if (!pathLooksFlac && !qualityLooksFlac) return null
    val localFile = file.toFile()
    if (DesktopFlacFileValidator.validate(localFile) == null) {
        return "FLAC 下载文件未通过容器校验，已拒绝保存"
    }
    return when (decodeVerifier(localFile)) {
        false -> "FLAC 下载文件未通过完整解码校验（文件可能损坏或截断），已拒绝保存"
        true -> null
        null -> null // FFmpeg 不可用时保留容器级校验结果，不伪称完整解码通过。
    }
}
