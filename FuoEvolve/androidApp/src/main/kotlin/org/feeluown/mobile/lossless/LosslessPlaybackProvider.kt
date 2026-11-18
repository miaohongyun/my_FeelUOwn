package org.feeluown.mobile.lossless

import org.feeluown.mobile.AudioQualityPolicy
import org.feeluown.mobile.DownloadQualityResolver
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlaybackProviderPort
import org.feeluown.mobile.UnavailablePlaybackPolicy
import kotlinx.coroutines.CancellationException

/**
 * 播放链路装饰器：先走原音源解析，再尝试用开放曲库中匹配的 FLAC 候选替换。
 * FLAC 容器/频谱检查属于质量筛查，不构成对原始无损母带的证明。
 *
 * - 原音源已经是 FLAC / 本地文件时直接返回，不做额外网络请求；
 * - 原音源解析失败（例如无会员）时，只要开放曲库找到无损就仍然可以播放；
 * - 找不到无损且原音源也失败时，把原始异常抛回去，保留原有的跳过/智能替换逻辑。
 */
class LosslessPlaybackProvider(
    private val delegate: PlaybackProviderPort,
    private val enhancer: LosslessEnhancer,
) : PlaybackProviderPort by delegate {

    override suspend fun resolve(
        track: MusicTrack,
        unavailablePolicy: UnavailablePlaybackPolicy,
        smartReplacementProviderIds: Set<String>,
        smartReplacementMinScore: Double,
        smartReplacementUseOriginalMetadata: Boolean,
        smartReplacementUseOriginalLyrics: Boolean,
    ): PlaybackPayload {
        var originalFailure: Throwable? = null
        val original = try {
            delegate.resolve(
                track = track,
                unavailablePolicy = unavailablePolicy,
                smartReplacementProviderIds = smartReplacementProviderIds,
                smartReplacementMinScore = smartReplacementMinScore,
                smartReplacementUseOriginalMetadata = smartReplacementUseOriginalMetadata,
                smartReplacementUseOriginalLyrics = smartReplacementUseOriginalLyrics,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            originalFailure = error
            null
        }
        if (original != null && (enhancer.isAlreadyLossless(original) || !enhancer.isEnabled())) {
            return original
        }
        val enhanced = try {
            enhancer.enhanceForPlayback(track, original)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (enhanced != null) return enhanced
        original?.let { return it }
        throw originalFailure ?: IllegalStateException("无法解析播放地址：${track.title}")
    }
}

/**
 * 下载链路装饰器：最高档（无损）时优先使用开放曲库的完整校验文件。
 *
 * 用户主动下载可以多等一会儿；超时仍没有结果就回落到原解析结果，
 * 不会因为无损源不可用而让下载失败。
 */
class LosslessDownloadQualityResolver(
    private val delegate: DownloadQualityResolver,
    private val enhancer: LosslessEnhancer,
    private val timeoutMillis: Long = DOWNLOAD_LOSSLESS_TIMEOUT_MS,
) : DownloadQualityResolver {

    override suspend fun resolveWithQualityPolicy(
        track: MusicTrack,
        qualityPolicy: String,
    ): PlaybackPayload? {
        val original = try {
            delegate.resolveWithQualityPolicy(track, qualityPolicy)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (!enhancer.isEnabled() || qualityPolicy != AudioQualityPolicy.Highest.policy) {
            return original
        }
        if (original.isLosslessPayload()) return original
        val entry = try {
            enhancer.findValidatedEntry(track, timeoutMillis)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return original
        return enhancer.buildDownloadPayload(track, entry, original)
    }

    override suspend fun availableQualities(track: MusicTrack): List<String>? =
        delegate.availableQualities(track)

    private companion object {
        const val DOWNLOAD_LOSSLESS_TIMEOUT_MS = 35_000L
    }
}
