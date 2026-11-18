package org.feeluown.mobile

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * 跨音源、跨平台的音质档位。
 *
 * 档位是唯一用来排序/比较音质的东西；编码、采样率、位深只做展示，不参与「谁更高」的判断，
 * 因为 AAC 256k 与 MP3 320k 的码率数字并不能直接比较。
 */
@Serializable
enum class AudioQualityTier(
    val policy: String,
    val label: String,
    val shortLabel: String,
    val rank: Int,
) {
    Lossless(AudioQualityPolicy.Highest.policy, "无损 / 最高可用", "SHQ", 4),
    High(AudioQualityPolicy.High.policy, "高音质", "HQ", 3),
    Standard(AudioQualityPolicy.Standard.policy, "标准音质", "SQ", 2),
    Low(AudioQualityPolicy.Low.policy, "省流量", "LQ", 1),
    ;

    companion object {
        /** Provider quality dialect (`>>>` / `hq<>` / `sq<>` / `lq<>`) → tier. */
        fun fromPolicy(policy: String?): AudioQualityTier? {
            val normalized = policy?.trim().orEmpty()
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { it.policy == normalized }
        }

        /** Last-resort mapping when a provider only reports a bitrate. */
        fun fromBitrateKbps(bitrateKbps: Int?): AudioQualityTier? {
            val bitrate = bitrateKbps?.takeIf { it > 0 } ?: return null
            return when {
                bitrate <= 128 -> Low
                bitrate < 256 -> Standard
                else -> High
            }
        }
    }
}

/**
 * 一条音质结论来自哪里。
 *
 * 同一份「FLAC」标签，可能只是音源元数据声明，也可能是已经用当前账户取到流、解析过容器头，
 * 甚至做过频谱检查。展示与排障时必须能区分，否则「元数据说无损」会被误当成事实。
 */
@Serializable
enum class AudioQualityEvidence {
    /** 音源接口的元数据声明，未验证账户权限。 */
    ProviderDeclared,
    /** 已用当前账户实际取到播放地址。 */
    AccountVerified,
    /** 已解析容器头（例如 FLAC STREAMINFO）。 */
    ContainerVerified,
    /** 已通过频谱启发式检查。 */
    SpectralVerified,
    /** 播放器解码后回报的实际参数。 */
    PlaybackEngine,
}

/**
 * 统一的音质信息模型，Android 与 Windows 共用。
 *
 * 设计原则：
 * - [tier] 决定展示的档位标签与排序；
 * - [codec]/[container]/[bitrateKbps]/[sampleRateHz]/[bitsPerSample]/[channels] 只做技术描述；
 * - [evidence] 说明这份信息有多可信，避免把「声明」当「已验证」。
 */
@Serializable
data class AudioQualityProfile(
    val tier: AudioQualityTier? = null,
    val codec: String? = null,
    val container: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val channels: Int? = null,
    val isLossless: Boolean = false,
    val evidence: AudioQualityEvidence = AudioQualityEvidence.ProviderDeclared,
    val rawLabel: String? = null,
) {
    /** 播放器角标使用的短标签；未知时退回音源原始标签。 */
    fun displayLabel(): String? =
        tier?.shortLabel ?: rawLabel?.trim()?.takeIf(String::isNotEmpty)

    /** 例如 `FLAC 24bit/96kHz · 2ch` 或 `MP3 320kbps`。没有可展示信息时返回 null。 */
    fun technicalSummary(): String? {
        val parts = buildList {
            val resolvedCodec = codec?.trim()?.takeIf(String::isNotEmpty)
                ?: container?.trim()?.takeIf(String::isNotEmpty)
            resolvedCodec?.let { add(it.uppercase()) }
            if (bitsPerSample != null && sampleRateHz != null) {
                add("${bitsPerSample}bit/${formatSampleRate(sampleRateHz)}")
            } else {
                sampleRateHz?.let { add(formatSampleRate(it)) }
                bitsPerSample?.let { add("${it}bit") }
            }
            bitrateKbps?.takeIf { it > 0 }?.let { add("${it}kbps") }
            channels?.takeIf { it > 0 }?.let { add("${it}ch") }
        }
        return parts.joinToString(" ").takeIf(String::isNotEmpty)
    }

    companion object {
        fun of(
            tier: AudioQualityTier?,
            codec: String? = null,
            container: String? = null,
            bitrateKbps: Int? = null,
            sampleRateHz: Int? = null,
            bitsPerSample: Int? = null,
            channels: Int? = null,
            isLossless: Boolean = tier == AudioQualityTier.Lossless,
            evidence: AudioQualityEvidence = AudioQualityEvidence.ProviderDeclared,
            rawLabel: String? = null,
        ): AudioQualityProfile {
            // Keep the two fields consistent: a lossless stream is always the lossless tier, and
            // a caller that names the lossless tier is describing a lossless stream.
            val lossless = isLossless || tier == AudioQualityTier.Lossless
            return AudioQualityProfile(
                tier = if (lossless) AudioQualityTier.Lossless else tier,
                codec = codec,
                container = container,
                bitrateKbps = bitrateKbps,
                sampleRateHz = sampleRateHz,
                bitsPerSample = bitsPerSample,
                channels = channels,
                isLossless = lossless,
                evidence = evidence,
                rawLabel = rawLabel,
            )
        }
    }
}

private fun formatSampleRate(sampleRateHz: Int): String = formatAudioSampleRate(sampleRateHz)

/** 例如 `44.1kHz` / `48kHz` / `22050Hz`，跨平台展示保持一致。 */
fun formatAudioSampleRate(sampleRateHz: Int): String {
    if (sampleRateHz <= 0) return ""
    if (sampleRateHz % 1_000 != 0) {
        val tenths = (sampleRateHz / 100.0).roundToInt()
        val whole = tenths / 10
        val fraction = tenths % 10
        return if (fraction == 0) "${whole}kHz" else "$whole.${fraction}kHz"
    }
    return "${sampleRateHz / 1_000}kHz"
}

/** 声道数的可读写法；未知或异常值返回 null。 */
fun formatAudioChannels(channels: Int?): String? = when {
    channels == null || channels <= 0 -> null
    channels == 1 -> "单声道"
    channels == 2 -> "立体声"
    else -> "${channels} 声道"
}
