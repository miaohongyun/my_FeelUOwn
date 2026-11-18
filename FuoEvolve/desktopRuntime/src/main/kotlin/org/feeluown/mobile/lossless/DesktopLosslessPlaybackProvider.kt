package org.feeluown.mobile.lossless

import java.io.File
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.feeluown.mobile.AppSettingsRepository
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlaybackProviderPort
import org.feeluown.mobile.UnavailablePlaybackPolicy

/**
 * Desktop playback decorator. It prefers a previously verified local FLAC cache, then a verified
 * FLAC stream from Subsonic/Jamendo/Internet Archive. Newly selected remote candidates are cached
 * in the background for later offline playback. Header validation is not proof of an original
 * lossless master and this cache intentionally does not claim spectral validation.
 */
class DesktopLosslessPlaybackProvider(
    private val delegate: PlaybackProviderPort,
    private val settings: AppSettingsRepository,
    private val cacheDirectory: Path,
) : PlaybackProviderPort by delegate {
    private val archive = InternetArchiveClient()
    private val jamendo = JamendoClient(clientIdProvider = { currentSettings().jamendoClientId })
    private val subsonic = SubsonicClient(configProvider = {
        val value = currentSettings()
        SubsonicConfig(value.subsonicEnabled, value.subsonicServerUrl, value.subsonicUsername, value.subsonicPassword)
    })
    private val sources = listOf(subsonic, jamendo, archive)
    private val cacheStore = DesktopLosslessCacheStore(cacheDirectory)
    private val lastAttemptByTrack = HashMap<String, Long>()
    private val cacheJobs = ConcurrentHashMap<String, Boolean>()
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun resolve(
        track: MusicTrack,
        unavailablePolicy: UnavailablePlaybackPolicy,
        smartReplacementProviderIds: Set<String>,
        smartReplacementMinScore: Double,
        smartReplacementUseOriginalMetadata: Boolean,
        smartReplacementUseOriginalLyrics: Boolean,
    ): PlaybackPayload {
        val config = currentSettings()
        if (config.losslessEnhanceEnabled) {
            cacheStore.find(track)?.let { cached ->
                return cachedPayload(track, null, cached)
            }
        }
        var originalFailure: Exception? = null
        val original = try {
            delegate.resolve(
                track, unavailablePolicy, smartReplacementProviderIds, smartReplacementMinScore,
                smartReplacementUseOriginalMetadata, smartReplacementUseOriginalLyrics,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            originalFailure = error
            null
        }
        if (!config.losslessEnhanceEnabled) {
            return original ?: throw (originalFailure ?: IllegalStateException("无法解析播放地址：${track.title}"))
        }
        if (original?.let(::isAlreadyLossless) == true) return original

        val key = "${track.id}:${track.title}:${track.artists}"
        val now = System.currentTimeMillis()
        synchronized(lastAttemptByTrack) {
            val previous = lastAttemptByTrack[key]
            if (previous != null && now - previous < RETRY_INTERVAL_MS) {
                return original ?: throw (originalFailure ?: IllegalStateException("无可用播放源：${track.title}"))
            }
            lastAttemptByTrack[key] = now
            if (lastAttemptByTrack.size > 256) lastAttemptByTrack.entries.removeIf { now - it.value > RETRY_INTERVAL_MS }
        }
        val enhanced = withTimeoutOrNull(LOSSLESS_LOOKUP_TIMEOUT_MS) {
            var resolved: PlaybackPayload? = null
            for (source in sources) {
                val candidates = try {
                    source.search(track)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    continue
                }
                for (candidate in candidates.filter { it.isConfidentMatch() }.sortedByDescending { it.score }) {
                    val info = try {
                        source.probeFlacHeader(candidate.remoteUrl)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    val durationMs = (info.durationSeconds * 1_000).toLong().takeIf { it > 0 }
                        ?: original?.durationMs
                    resolved = (original ?: PlaybackPayload(
                        url = candidate.remoteUrl,
                        title = track.title,
                        artists = track.artists,
                        album = track.album,
                        source = "lossless:${candidate.source}",
                        coverUrl = track.coverUrl,
                        durationMs = durationMs,
                        lyrics = track.lyrics,
                    )).copy(
                        url = candidate.remoteUrl,
                        source = "lossless:${candidate.source}",
                        title = track.title,
                        artists = track.artists,
                        audioQuality = "FLAC ${"%.1f".format(Locale.ROOT, info.sampleRate / 1_000.0)}kHz/${info.bitsPerSample}bit（容器检查通过）",
                        providerName = "开放曲库 · ${source.displayName}",
                        durationMs = durationMs,
                    )
                    scheduleCacheDownload(track, source, candidate.remoteUrl, candidate.sizeBytes)
                    break
                }
                if (resolved != null) break
            }
            resolved
        }
        return enhanced ?: original ?: throw (originalFailure ?: IllegalStateException("无法解析播放地址：${track.title}"))
    }

    private fun currentSettings() = settings.state.value.settings

    private fun isAlreadyLossless(payload: PlaybackPayload): Boolean =
        isLosslessAudioLocation(payload.url) || isLosslessQualityLabel(payload.audioQuality)

    private fun cachedPayload(track: MusicTrack, original: PlaybackPayload?, file: File): PlaybackPayload {
        val info = checkNotNull(DesktopFlacFileValidator.validate(file))
        val durationMs = (info.durationSeconds * 1_000).toLong().takeIf { it > 0 } ?: original?.durationMs
        return (original ?: PlaybackPayload(
            url = file.toURI().toString(),
            title = track.title,
            artists = track.artists,
            album = track.album,
            source = "lossless-cache",
            coverUrl = track.coverUrl,
            durationMs = durationMs,
            lyrics = track.lyrics,
        )).copy(
            url = file.toURI().toString(),
            headers = emptyMap(),
            source = "lossless-cache",
            title = track.title,
            artists = track.artists,
            audioQuality = "FLAC ${"%.1f".format(Locale.ROOT, info.sampleRate / 1_000.0)}kHz/${info.bitsPerSample}bit（本地缓存·容器检查通过）",
            providerName = "本地无损缓存",
            durationMs = durationMs,
        )
    }

    private fun scheduleCacheDownload(track: MusicTrack, source: LosslessSource, remoteUrl: String, expectedSizeBytes: Long) {
        val key = losslessTrackKey(track)
        if (cacheJobs.putIfAbsent(key, true) != null) return
        cacheScope.launch {
            val temporary = cacheStore.temporaryFile(track)
            try {
                if (cacheStore.find(track) != null) return@launch
                if (!source.download(remoteUrl, temporary)) return@launch
                cacheStore.install(track, temporary, expectedSizeBytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Cache is opportunistic: a failed cache write must never interrupt playback.
            } finally {
                runCatching { temporary.delete() }
                cacheJobs.remove(key)
            }
        }
    }

    private companion object {
        const val RETRY_INTERVAL_MS = 5 * 60 * 1_000L
        const val LOSSLESS_LOOKUP_TIMEOUT_MS = 8_000L
    }
}
