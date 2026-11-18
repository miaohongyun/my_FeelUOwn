package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

/**
 * Binds the cast feature to the running player.
 *
 * The feature module stays free of provider and player types; this factory is the only place
 * that knows how a track becomes a stream a renderer can pull.
 */
fun createCastControllerPort(
    discovery: CastDiscoveryPort,
    transport: CastTransportPort,
    stream: CastStreamPort,
    playbackSource: PlaybackProviderSourcePort,
    playbackSession: PlaybackSession,
    presentation: PlaybackPresentationPort,
    queue: PlaybackQueueUiPort,
    scope: CoroutineScope,
): CastControllerPort {
    val source = object : CastSourcePort {
        override fun currentTrack(): MusicTrack? = presentation.currentTrack

        override suspend fun resolveStream(track: MusicTrack): CastStreamSource? {
            val payload = playbackSource.resolveTrack(track) ?: return null
            if (payload.url.isBlank()) return null
            return CastStreamSource(
                url = payload.url,
                headers = payload.headers,
                mimeType = DlnaProtocol.guessMimeType(payload.url),
                title = payload.title,
                artist = payload.artists,
                album = payload.album,
                durationMs = payload.durationMs,
            )
        }

        override fun playNext(): Boolean {
            val session = playbackSession.state.value
            if (!session.canGoNext) return false
            val audible = session.status == PlaybackSessionStatus.Playing
            playbackSession.next()
            // While casting the renderer is the speaker, so a silent queue change has to stay
            // silent: the phone is muted anyway, and pausing keeps it from pulling the stream.
            if (!audible) playbackSession.pause()
            return true
        }
    }

    val controller = DefaultCastController(
        discovery = discovery,
        transport = transport,
        stream = stream,
        source = source,
        scope = scope,
        ticker = castPollTicker(),
    )
    followTheLocalPlayer(controller, queue, scope)
    keepThePhoneSilent(controller, playbackSession, scope)
    return controller
}

/**
 * Keeps the renderer in sync with the local player.
 *
 * A renderer only knows one URI, so whenever the phone moves to another track the new track has
 * to be pushed to the device. The queue is the signal here: it is what moves on when the song
 * ends or the user skips.
 */
private fun followTheLocalPlayer(
    controller: CastControllerPort,
    queue: PlaybackQueueUiPort,
    scope: CoroutineScope,
) {
    val queueStateFlow = queue.queueStateFlow ?: return
    scope.launch {
        queueStateFlow
            .map { it.currentTrack() }
            .distinctUntilChangedBy { it?.id }
            .collect { track ->
                if (track == null) return@collect
                val device = controller.state.value.activeDevice ?: return@collect
                controller.castTrack(device, track)
            }
    }
}

/**
 * Mutes the phone while the renderer is playing and puts the previous volume back afterwards.
 *
 * The queue has to keep running for the renderer to follow it, but the phone must not play the
 * same song out loud over the TV.
 */
private fun keepThePhoneSilent(
    controller: CastControllerPort,
    playbackSession: PlaybackSession,
    scope: CoroutineScope,
) {
    scope.launch {
        var mutedFrom: Double? = null
        controller.state
            // Keyed on the device, not the status: a track that fails to resolve mid-session
            // must not make the phone audible again while the TV keeps playing.
            .map { it.activeDevice != null }
            .distinctUntilChanged()
            .collect { casting ->
                if (casting) {
                    if (mutedFrom != null) return@collect
                    mutedFrom = playbackSession.state.value.volume
                    playbackSession.setVolume(0.0)
                } else {
                    val volume = mutedFrom ?: return@collect
                    mutedFrom = null
                    playbackSession.setVolume(volume)
                }
            }
    }
}

