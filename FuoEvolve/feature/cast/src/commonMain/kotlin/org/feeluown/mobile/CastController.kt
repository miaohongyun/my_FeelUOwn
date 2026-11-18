package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives a DLNA renderer.
 *
 * The controller owns the conversation, not the transport: discovery, the SOAP calls and the
 * local proxy stream are separate ports so the app can plug in the platform implementations.
 *
 * A renderer only knows the single URI it was handed, so two things keep a queue playing:
 * [castTo]/[castTrack] push whatever the local player moved on to, and the renderer watch below
 * pushes the next track when the device reports it ran out — which is the only signal available
 * when the phone itself is paused.
 */
class DefaultCastController(
    private val discovery: CastDiscoveryPort,
    private val transport: CastTransportPort,
    private val stream: CastStreamPort,
    private val source: CastSourcePort,
    private val scope: CoroutineScope,
    /**
     * Wakes the renderer watch up. Defaults to "never", which leaves the controller purely
     * player-driven; the app passes [castPollTicker] so a finished track always moves the queue on.
     */
    private val ticker: Flow<Unit> = emptyFlow(),
) : CastControllerPort {

    private val mutableState = MutableStateFlow(CastState())
    override val state: StateFlow<CastState> = mutableState.asStateFlow()

    /** The proxy URL that is currently live, so it can be revoked when the track changes. */
    private var publishedUrl: String? = null

    /** The device and track that were pushed last, so the same pair is never pushed twice. */
    private var pushedKey: String? = null

    /** The track the renderer is holding, used to tell "renderer finished" from "phone moved on". */
    private var pushedTrackId: String? = null

    /** True once the renderer has been seen playing the pushed track, so a stray STOPPED is ignored. */
    private var rendererStarted = false

    /** The last position the renderer reported, so a frozen position at the end is visible. */
    private var lastObservedPositionMs: Long? = null

    /** Guards against two end-of-track signals arriving for the same URI. */
    private var advancing = false

    /** True when the user paused the renderer, so a later track does not silently resume it. */
    private var remotePaused = false

    private var watchJob: Job? = null
    private var watchedDeviceId: String? = null

    /** Serialises pushes: the queue follower and the renderer watch can fire at the same time. */
    private val pushMutex = Mutex()

    override fun discover() {
        scope.launch {
            mutableState.update { it.copy(discovering = true, errorMessage = null) }
            val devices = try {
                discovery.discover()
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        discovering = false,
                        status = CastStatus.Error,
                        errorMessage = error.message ?: "设备发现失败",
                    )
                }
                return@launch
            }
            mutableState.update {
                it.copy(
                    devices = devices,
                    discovering = false,
                    status = if (it.activeDevice != null) CastStatus.Casting else CastStatus.Idle,
                )
            }
        }
    }

    override fun castTo(device: CastDevice) {
        scope.launch {
            val track = source.currentTrack()
            if (track == null) {
                fail("当前没有正在播放的歌曲")
                return@launch
            }
            pushTrack(device, track)
        }
    }

    override fun castTrack(device: CastDevice, track: MusicTrack) {
        scope.launch {
            pushTrack(device, track)
        }
    }

    override fun stopCast() {
        scope.launch {
            val device = mutableState.value.activeDevice
            if (device != null) {
                runCatching { transport.stop(device) }
            }
            releasePublished()
            stopWatching()
            pushedKey = null
            pushedTrackId = null
            remotePaused = false
            mutableState.update { it.copy(activeDevice = null, status = CastStatus.Idle) }
        }
    }

    override fun togglePlayPause() {
        scope.launch {
            val device = mutableState.value.activeDevice ?: return@launch
            try {
                val state = transport.transportState(device)
                if (state.contains("PAUSED", ignoreCase = true)) {
                    remotePaused = false
                    transport.play(device)
                } else {
                    remotePaused = true
                    transport.pause(device)
                }
            } catch (error: Exception) {
                fail(error.message ?: "控制设备失败")
            }
        }
    }

    override fun next() {
        scope.launch {
            val device = mutableState.value.activeDevice ?: return@launch
            val previousId = source.currentTrack()?.id
            if (!source.playNext()) {
                fail("没有下一首了")
                return@launch
            }
            val track = awaitTrack(previousId)
            if (track == null) {
                fail("没有下一首了")
                return@launch
            }
            pushTrack(device, track)
        }
    }

    /**
     * Resolves [track], publishes it on the local network and hands the URL to the renderer.
     *
     * The old stream is only revoked once the renderer accepted the new one, so a failure never
     * leaves the device without a playable source. [force] re-pushes the same track, which is what
     * a repeat-one queue needs when the renderer reaches the end.
     */
    private suspend fun pushTrack(device: CastDevice, track: MusicTrack, force: Boolean = false) {
        pushMutex.withLock { pushTrackLocked(device, track, force) }
    }

    private suspend fun pushTrackLocked(device: CastDevice, track: MusicTrack, force: Boolean) {
        val key = "${device.id}|${track.id}"
        if (!force && key == pushedKey) return
        val streamSource = source.resolveStream(track)
        if (streamSource == null) {
            fail("无法解析这首歌的播放地址")
            return
        }
        val url = try {
            stream.publish(streamSource)
        } catch (error: Exception) {
            fail(error.message?.let { "代理流启动失败：$it" } ?: "代理流启动失败")
            return
        }
        val metadata = DlnaProtocol.buildDidlItem(
            url = url,
            title = streamSource.title.ifBlank { track.title },
            artist = streamSource.artist.ifBlank { track.artists },
            album = streamSource.album.ifBlank { track.album },
            durationMs = streamSource.durationMs ?: track.durationMs,
            mimeType = streamSource.mimeType,
        )
        try {
            transport.setUri(device, url, metadata)
            // The user may have paused the cast; a new track must not start playing behind
            // their back just because the queue moved on.
            if (remotePaused) transport.pause(device) else transport.play(device)
        } catch (error: Exception) {
            runCatching { stream.release(url) }
            fail(error.message?.let { "投屏失败：$it" } ?: "投屏失败")
            return
        }
        releasePublished()
        publishedUrl = url
        pushedKey = key
        pushedTrackId = track.id
        rendererStarted = false
        lastObservedPositionMs = null
        mutableState.update {
            it.copy(activeDevice = device, status = CastStatus.Casting, errorMessage = null)
        }
        startWatching(device)
    }

    /**
     * Watches the renderer so a finished track moves the queue on.
     *
     * This is the case the local player cannot cover: when the phone is paused — the usual state
     * while the TV is the speaker — nothing on this side ever moves to the next track, so the
     * renderer would simply stop after one song.
     */
    private fun startWatching(device: CastDevice) {
        if (watchJob?.isActive == true && watchedDeviceId == device.id) return
        stopWatching()
        watchedDeviceId = device.id
        watchJob = scope.launch {
            ticker.collect {
                if (mutableState.value.activeDevice?.id != device.id) return@collect
                inspectRenderer(device)
            }
        }
    }

    private fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
        watchedDeviceId = null
        rendererStarted = false
        lastObservedPositionMs = null
    }

    private suspend fun inspectRenderer(device: CastDevice) {
        val snapshot = runCatching { transport.transportSnapshot(device) }.getOrNull() ?: return
        if (snapshot.transportState.isBlank()) return
        if (snapshot.isRunning) {
            // A STOPPED answer right after SetAVTransportURI is normal, so the end of a track is
            // only trusted once the renderer has actually been seen playing it.
            rendererStarted = true
            val position = snapshot.positionMs
            // Some renderers keep saying PLAYING once the file runs out. They give themselves
            // away by parking the position at the end, which is only an end if it stops moving.
            val stuckAtEnd = snapshot.indicatesEndOfTrack() &&
                position != null &&
                position == lastObservedPositionMs
            lastObservedPositionMs = position
            if (stuckAtEnd) moveToTheNextTrack(device)
            return
        }
        lastObservedPositionMs = null
        if (!rendererStarted) return
        if (snapshot.isPaused) return
        if (!snapshot.isStopped) return
        moveToTheNextTrack(device)
    }

    /**
     * Picks the track that should follow on the renderer.
     *
     * If the phone already moved on, that track is the answer and the queue must not be advanced
     * twice. Otherwise the renderer is ahead of the phone, so the queue is advanced here.
     */
    private suspend fun moveToTheNextTrack(device: CastDevice) {
        if (advancing) return
        advancing = true
        try {
            val pushed = pushedTrackId
            val current = source.currentTrack()
            if (current != null && current.id != pushed) {
                pushTrack(device, current)
                return
            }
            if (!source.playNext()) {
                finishQueue()
                return
            }
            val next = awaitTrack(current?.id)
            if (next == null) {
                finishQueue()
                return
            }
            // Repeat-one hands back the same track, which still has to be pushed again.
            pushTrack(device, next, force = next.id == pushed)
        } finally {
            advancing = false
        }
    }

    /** Ends the session when the queue runs out, instead of leaving a dead cast behind. */
    private suspend fun finishQueue() {
        releasePublished()
        stopWatching()
        pushedKey = null
        pushedTrackId = null
        remotePaused = false
        mutableState.update {
            it.copy(
                activeDevice = null,
                status = CastStatus.Idle,
                errorMessage = "队列已经播完了",
            )
        }
    }

    /** Waits for the player to move on to another track. */
    private suspend fun awaitTrack(previousId: String?): MusicTrack? {
        repeat(TRACK_ADVANCE_ATTEMPTS) {
            val track = source.currentTrack()
            if (track != null && track.id != previousId) return track
            delay(TRACK_ADVANCE_INTERVAL_MS)
        }
        return null
    }

    private suspend fun releasePublished() {
        val url = publishedUrl ?: return
        publishedUrl = null
        runCatching { stream.release(url) }
    }

    private fun fail(message: String) {
        mutableState.update { it.copy(status = CastStatus.Error, errorMessage = message) }
    }

    private companion object {
        const val TRACK_ADVANCE_ATTEMPTS = 60
        const val TRACK_ADVANCE_INTERVAL_MS = 50L
    }
}

/**
 * A ticker that wakes the controller up so it can look at the renderer.
 *
 * Two seconds is a compromise: a finished TV usually reports `STOPPED` within one poll, and the
 * SOAP round trip is cheap enough that it does not disturb playback.
 */
fun castPollTicker(intervalMs: Long = 2_000L): Flow<Unit> = flow {
    while (true) {
        delay(intervalMs)
        emit(Unit)
    }
}

