package org.feeluown.mobile

/**
 * A playable audio stream a renderer can pull from the local proxy.
 *
 * [headers] carries the provider credentials (cookies, signatures) the renderer cannot send on
 * its own; the local proxy adds them when it fetches [url] upstream.
 */
data class CastStreamSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String = "audio/mpeg",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long? = null,
)

/** Finds renderers on the local network. */
interface CastDiscoveryPort {
    suspend fun discover(): List<CastDevice>
}

/**
 * What a renderer reports about itself.
 *
 * A renderer only knows the one URI it was given, so the cast session has to notice on its own
 * that the track ran out. [transportState] is always available; the position fields are filled in
 * when the device answers `GetPositionInfo`, which most TVs do.
 */
data class CastTransportSnapshot(
    val transportState: String,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
) {
    private val normalized: String = transportState.trim().uppercase()

    val isStopped: Boolean
        get() = normalized.contains("STOPPED") || normalized.contains("NO_MEDIA")

    val isPaused: Boolean
        get() = normalized.contains("PAUSED")

    val isRunning: Boolean
        get() = normalized.contains("PLAYING") || normalized.contains("TRANSITIONING")

    /** True once the renderer has nothing left to play or has run past the end of the track. */
    fun indicatesEndOfTrack(toleranceMs: Long = 2_000L): Boolean {
        if (isStopped) return true
        if (!isRunning) return false
        val duration = durationMs?.takeIf { it > 0 } ?: return false
        val position = positionMs ?: return false
        return position >= (duration - toleranceMs).coerceAtLeast(0L)
    }
}

/** Sends AVTransport commands to a renderer. */
interface CastTransportPort {
    /** Points the renderer at [uri] and hands it the DIDL-Lite [metadata]. */
    suspend fun setUri(device: CastDevice, uri: String, metadata: String)

    suspend fun play(device: CastDevice)

    suspend fun pause(device: CastDevice)

    suspend fun stop(device: CastDevice)

    /** Returns the renderer's transport state, for example `PLAYING` or `PAUSED_PLAYBACK`. */
    suspend fun transportState(device: CastDevice): String

    /**
     * Returns the state plus the playback position when the device supports it.
     *
     * The default keeps state-only transports working: the controller then relies on the
     * `PLAYING → STOPPED` transition alone to notice the end of a track.
     */
    suspend fun transportSnapshot(device: CastDevice): CastTransportSnapshot =
        CastTransportSnapshot(transportState = transportState(device))
}

/** Publishes a stream on the local network and hands back the URL a renderer should GET. */
interface CastStreamPort {
    /** Publishes [source] and returns the URL the renderer has to request. */
    suspend fun publish(source: CastStreamSource): String

    /** Revokes a previously published URL so it cannot be reused. */
    suspend fun release(url: String)
}

/**
 * Supplies the track that should be cast and resolves it into a playable stream.
 *
 * The app binds this to the running player, so the renderer keeps hearing what the phone plays.
 */
interface CastSourcePort {
    /** The track that is currently playing, if any. */
    fun currentTrack(): MusicTrack?

    /** Resolves [track] into a stream the local proxy can serve. */
    suspend fun resolveStream(track: MusicTrack): CastStreamSource?

    /**
     * Moves the local player to the next track so the renderer can follow along.
     *
     * Returns `true` when the local queue actually advanced.
     */
    fun playNext(): Boolean
}
