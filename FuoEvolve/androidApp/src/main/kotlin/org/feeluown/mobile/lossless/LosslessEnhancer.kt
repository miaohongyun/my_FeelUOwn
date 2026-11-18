package org.feeluown.mobile.lossless

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.AppSettingsRepository
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import java.io.File

/**
 * 无损增强编排器。
 *
 * 播放一首非无损歌曲时：
 * 1. 命中本地缓存 → 直接用 `file://` 播放；
 * 2. 否则后台检索开放曲库，找到 FLAC 后先校验文件头再返回可流式播放的地址；
 * 3. 完整下载 + 频谱校验在后台继续，成功后写入缓存供下次秒开；
 * 4. 首次等待最多 N 秒（默认 10 秒），超时立刻用原音源，不阻塞播放。
 *
 * 预取时提前为下一首执行同样的流程，因此正常连播时基本感知不到等待。
 */
class LosslessEnhancer(
    context: Context,
    private val settingsRepository: AppSettingsRepository,
    /** 当前是否移动网络；用于“仅 Wi-Fi 预取”判断。 */
    private val isCellularConnection: () -> Boolean = { false },
) {
    private val cache = LosslessCacheStore(context)
    private val archive = InternetArchiveClient()
    private val jamendo = JamendoClient(clientIdProvider = { settings.jamendoClientId })
    private val subsonic = SubsonicClient(
        configProvider = {
            SubsonicConfig(
                enabled = settings.subsonicEnabled,
                serverUrl = settings.subsonicServerUrl,
                username = settings.subsonicUsername,
                password = settings.subsonicPassword,
            )
        },
    )

    /**
     * 自建曲库优先（曲库可控、命中率最高），其次 Jamendo（国内可直连），
     * Internet Archive 作为最后补充。
     */
    private val sources: List<LosslessSource> = listOf(subsonic, jamendo, archive)
    private val validator = FlacValidator()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = mutableMapOf<String, Session>()
    private val failedAt = mutableMapOf<String, Long>()

    /** 单个来源网络失败后的退避截止时间，避免国内无法直连的来源反复卡超时。 */
    private val sourceBackoffUntil = mutableMapOf<String, Long>()

    /** 播放期可用的无损来源：已落盘的完整文件，或已通过头部校验的远端流。 */
    private sealed interface Playable {
        data class Cached(val entry: LosslessCacheEntry) : Playable
        data class Streaming(val candidate: LosslessCandidate, val info: FlacStreamInfo) : Playable
    }

    private class Session(val track: MusicTrack) {
        /** 首个可用来源，供播放等待。 */
        val playable = CompletableDeferred<Playable?>()

        /** 完整下载并通过容器/频谱启发式检查后的条目，供下载链路等待。 */
        val validated = CompletableDeferred<LosslessCacheEntry?>()
    }

    private val settings
        get() = settingsRepository.state.value.settings

    fun isEnabled(): Boolean = settings.losslessEnhanceEnabled

    fun isPrefetchEnabled(): Boolean = settings.losslessEnhanceEnabled && settings.losslessPrefetchEnabled

    fun playbackWaitMillis(): Long =
        settings.losslessPlaybackWaitSeconds.coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS) * 1_000L

    /** 已经是无损音源时不再替换，避免重复下载。 */
    fun isAlreadyLossless(payload: PlaybackPayload): Boolean =
        isLosslessAudioLocation(payload.url) || isLosslessQualityLabel(payload.audioQuality)

    /**
     * 为播放准备无损音源；没有可用结果时返回 null，由调用方继续使用原音源。
     *
     * [basePayload] 仅用于继承封面/歌词等元数据，可能为空（原音源解析失败）。
     */
    suspend fun enhanceForPlayback(track: MusicTrack, basePayload: PlaybackPayload?): PlaybackPayload? {
        if (!isEnabled() || track.localUri != null) return null
        val key = losslessTrackKey(track)
        cache.find(key)?.let { entry ->
            AppLogger.i(TAG, "命中无损缓存：${track.title}")
            return buildPayload(track, basePayload, entry)
        }
        if (isBackingOff(key)) return null
        val session = sessionFor(track, key)
        val playable = withTimeoutOrNull(playbackWaitMillis()) { session.playable.await() }
            ?: run {
                AppLogger.i(TAG, "等待无损超时，先用原音源：${track.title}")
                return null
            }
        return when (playable) {
            is Playable.Cached -> buildPayload(track, basePayload, playable.entry)
            is Playable.Streaming -> buildPayload(
                track = track,
                basePayload = basePayload,
                url = playable.candidate.remoteUrl,
                qualityLabel = "FLAC 候选 · 完整校验中",
                durationSeconds = playable.info.durationSeconds,
            )
        }
    }

    /** 为下载准备已经完成容器与频谱启发式检查的 FLAC 条目；超时返回 null。 */
    suspend fun findValidatedEntry(track: MusicTrack, timeoutMillis: Long): LosslessCacheEntry? {
        if (!isEnabled() || track.localUri != null) return null
        val key = losslessTrackKey(track)
        cache.find(key)?.let { return it }
        if (isBackingOff(key)) return null
        val session = sessionFor(track, key)
        return withTimeoutOrNull(timeoutMillis) { session.validated.await() }
    }

    /** 把已校验条目转换成下载用的远端地址，保留原始音源的元数据。 */
    fun buildDownloadPayload(track: MusicTrack, entry: LosslessCacheEntry, base: PlaybackPayload?): PlaybackPayload =
        buildPayload(track, base, entry, preferRemote = true)

    /** 听当前歌时提前准备下一首，下载与校验都在后台完成。 */
    fun prefetch(track: MusicTrack?) {
        if (track == null || !isPrefetchEnabled() || track.localUri != null) return
        if (settings.losslessPrefetchWifiOnly && isCellularConnection()) {
            AppLogger.i(TAG, "移动网络下跳过预取：${track.title}")
            return
        }
        val key = losslessTrackKey(track)
        if (isBackingOff(key)) return
        if (sessions.containsKey(key)) return
        AppLogger.i(TAG, "预取无损：${track.title}")
        scope.launch {
            cache.find(key)?.let { return@launch }
            sessionFor(track, key)
        }
    }

    @Synchronized
    private fun sessionFor(track: MusicTrack, key: String): Session {
        sessions[key]?.let { return it }
        val session = Session(track)
        sessions[key] = session
        scope.launch { runSession(session, key) }
        return session
    }

    private suspend fun runSession(session: Session, key: String) {
        var delivered = false
        var attempted = false
        var successSourceId: String? = null
        try {
            for (source in sources) {
                if (isSourceBackingOff(source.id)) {
                    AppLogger.i(TAG, "跳过暂不可用来源 ${source.displayName}")
                    continue
                }
                attempted = true
                val candidates = try {
                    source.search(session.track)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLogger.w(TAG, "${source.displayName} 检索失败：${session.track.title}", error)
                    emptyList()
                }
                if (source.lastNetworkOperationFailed) {
                    markSourceFailure(source.id)
                }
                if (candidates.isEmpty()) {
                    AppLogger.i(TAG, "${source.displayName} 未找到候选：${session.track.title}")
                    continue
                }
                for (candidate in candidates) {
                    val info = source.probeFlacHeader(candidate.remoteUrl)
                    if (info == null) {
                        if (source.lastNetworkOperationFailed) markSourceFailure(source.id)
                        AppLogger.i(TAG, "候选不是有效 FLAC，跳过：${candidate.remoteUrl}")
                        continue
                    }
                    if (info.sampleRate < MIN_SAMPLE_RATE || info.bitsPerSample < MIN_BITS_PER_SAMPLE) {
                        AppLogger.i(TAG, "候选音质不达标，跳过：${info.sampleRate}Hz/${info.bitsPerSample}bit")
                        continue
                    }
                    if (!session.playable.isCompleted) {
                        session.playable.complete(Playable.Streaming(candidate, info))
                        delivered = true
                    }

                    val entry = downloadAndValidate(source, session.track, key, candidate) ?: continue
                    successSourceId = source.id
                    if (!session.playable.isCompleted) session.playable.complete(Playable.Cached(entry))
                    if (!session.validated.isCompleted) session.validated.complete(entry)
                    return
                }
                AppLogger.i(TAG, "${source.displayName} 的候选均未通过校验：${session.track.title}")
            }
            if (attempted) AppLogger.i(TAG, "所有来源均未找到可用无损：${session.track.title}")
        } catch (throwable: Throwable) {
            if (throwable is kotlinx.coroutines.CancellationException) throw throwable
            AppLogger.w(TAG, "无损检索异常：${session.track.title}", throwable)
        } finally {
            if (!session.playable.isCompleted) session.playable.complete(null)
            if (!session.validated.isCompleted) session.validated.complete(null)
            synchronized(this) {
                if (sessions[key] === session) sessions.remove(key)
            }
            when {
                successSourceId != null -> {
                    clearTrackFailure(key)
                    clearSourceFailure(successSourceId)
                }
                // 已经有可播放的远端 FLAC 时不算失败，下次仍可复用同一路径。
                delivered -> Unit
                // 所有来源都在退避时不要惩罚这首歌，等来源恢复后还能重试。
                attempted -> markTrackFailure(key)
            }
        }
    }

    /**
     * 命中退避时直接放弃本次增强，避免每首歌都重复等待网络超时。
     *
     * 单曲退避用于“这首确实没有可用无损”；来源退避用于“这个曲库连不上”，
     * 两者互不干扰，因此换个来源仍可能成功。
     */
    @Synchronized
    private fun isBackingOff(key: String): Boolean {
        val now = System.currentTimeMillis()
        val failedAtMillis = failedAt[key] ?: return false
        if (now - failedAtMillis < TRACK_RETRY_BACKOFF_MS) return true
        failedAt.remove(key)
        return false
    }

    @Synchronized
    private fun isSourceBackingOff(sourceId: String): Boolean {
        val until = sourceBackoffUntil[sourceId] ?: return false
        if (System.currentTimeMillis() < until) return true
        sourceBackoffUntil.remove(sourceId)
        return false
    }

    /** 记录来源级网络失败；只影响该来源，其它曲库继续可用。 */
    @Synchronized
    private fun markSourceFailure(sourceId: String) {
        sourceBackoffUntil[sourceId] = System.currentTimeMillis() + SOURCE_BACKOFF_MS
        AppLogger.w(TAG, "来源 $sourceId 暂不可用，${SOURCE_BACKOFF_MS / 60_000} 分钟内跳过")
    }

    @Synchronized
    private fun clearSourceFailure(sourceId: String) {
        sourceBackoffUntil.remove(sourceId)
    }

    /** 记录单曲失败，避免同一首歌反复等待检索。 */
    @Synchronized
    private fun markTrackFailure(key: String) {
        val now = System.currentTimeMillis()
        failedAt[key] = now
        if (failedAt.size > MAX_TRACK_BACKOFF_ENTRIES) {
            val cutoff = now - TRACK_RETRY_BACKOFF_MS
            failedAt.entries.removeAll { it.value < cutoff }
        }
    }

    @Synchronized
    private fun clearTrackFailure(key: String) {
        failedAt.remove(key)
    }

    private suspend fun downloadAndValidate(
        source: LosslessSource,
        track: MusicTrack,
        key: String,
        candidate: LosslessCandidate,
    ): LosslessCacheEntry? {
        val temp = cache.temporaryFile(key)
        return try {
            if (!source.download(candidate.remoteUrl, temp)) return null
            val validation = validator.validate(temp)
            if (!validation.accepted) {
                AppLogger.i(TAG, "无损校验未通过（${validation.reason}）：${candidate.remoteUrl}")
                return null
            }
            val info = validation.streamInfo ?: return null
            val target = cache.finalFile(key)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }
            val entry = LosslessCacheEntry(
                key = key,
                trackId = track.id,
                title = track.title,
                artists = track.artists,
                source = candidate.source,
                remoteUrl = candidate.remoteUrl,
                filePath = target.absolutePath,
                sampleRate = info.sampleRate,
                bitsPerSample = info.bitsPerSample,
                channels = info.channels,
                durationSeconds = info.durationSeconds,
                sizeBytes = target.length(),
                validatedAtMillis = System.currentTimeMillis(),
                spectralValidated = validation.spectral != null,
            )
            cache.save(entry)
            AppLogger.i(
                TAG,
                "FLAC 已缓存（校验通过）：${track.title} ${info.bitsPerSample}bit/${info.sampleRate}Hz " +
                    "${"%.1f".format(target.length() / 1024.0 / 1024.0)}MB",
            )
            entry
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun buildPayload(
        track: MusicTrack,
        basePayload: PlaybackPayload?,
        entry: LosslessCacheEntry,
        preferRemote: Boolean = false,
    ): PlaybackPayload = buildPayload(
        track = track,
        basePayload = basePayload,
        url = if (preferRemote) entry.remoteUrl else Uri.fromFile(File(entry.filePath)).toString(),
        qualityLabel = buildString {
            append("FLAC · ")
            append(if (entry.spectralValidated) "频谱启发式检查通过" else "仅容器检查通过")
            append(" · ${entry.bitsPerSample}bit/${entry.sampleRate}Hz · 本地缓存")
        },
        durationSeconds = entry.durationSeconds,
    )

    private fun buildPayload(
        track: MusicTrack,
        basePayload: PlaybackPayload?,
        url: String,
        qualityLabel: String,
        durationSeconds: Double,
    ): PlaybackPayload = PlaybackPayload(
        url = url,
        title = basePayload?.title ?: track.title,
        artists = basePayload?.artists ?: track.artists,
        album = basePayload?.album ?: track.album,
        source = basePayload?.source ?: track.source,
        headers = emptyMap(),
        coverUrl = basePayload?.coverUrl ?: track.coverUrl,
        durationMs = basePayload?.durationMs
            ?: track.durationMs
            ?: durationSeconds.takeIf { it > 0 }?.times(1_000)?.toLong(),
        lyrics = basePayload?.lyrics ?: track.lyrics,
        audioQuality = qualityLabel,
        providerName = basePayload?.providerName ?: track.providerName,
        isSmartReplacement = basePayload?.isSmartReplacement ?: false,
    )

    private companion object {
        const val TAG = "LosslessEnhancer"
        const val MIN_WAIT_SECONDS = 0
        const val MAX_WAIT_SECONDS = 30
        const val MIN_SAMPLE_RATE = 44_100
        const val MIN_BITS_PER_SAMPLE = 16
        /** 单曲失败后 10 分钟内不再重复检索。 */
        const val TRACK_RETRY_BACKOFF_MS = 10 * 60 * 1_000L
        /** 网络层失败后 3 分钟内跳过该来源，避免逐首卡超时。 */
        const val SOURCE_BACKOFF_MS = 3 * 60 * 1_000L
        const val MAX_TRACK_BACKOFF_ENTRIES = 256
    }
}

/** 便捷扩展：判断当前 payload 是否已经无损。 */
fun PlaybackPayload?.isLosslessPayload(): Boolean =
    this != null && (isLosslessAudioLocation(url) || isLosslessQualityLabel(audioQuality))
