package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A DLNA/UPnP MediaRenderer that can play audio on its own.
 *
 * [controlUrl] is the absolute URL of the renderer's AVTransport control endpoint; the cast
 * feature talks to it with SOAP calls.
 */
data class CastDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val controlUrl: String,
    val rendererType: String = "MediaRenderer",
    val manufacturer: String = "",
    val modelName: String = "",
)

enum class CastStatus {
    Idle,
    Discovering,
    Casting,
    Error,
}

data class CastState(
    val devices: List<CastDevice> = emptyList(),
    val discovering: Boolean = false,
    val activeDevice: CastDevice? = null,
    val status: CastStatus = CastStatus.Idle,
    val errorMessage: String? = null,
)

/**
 * Feature port for pushing the current playback to a remote renderer.
 *
 * The implementation owns discovery, the local proxy stream and the remote transport commands.
 */
interface CastControllerPort {
    val state: StateFlow<CastState>

    /** Searches the local network for renderers. */
    fun discover()

    /** Starts casting the current track to [device]. */
    fun castTo(device: CastDevice)

    /**
     * Pushes [track] to [device].
     *
     * The app uses it to follow the local player: whenever the queue moves on, the renderer has
     * to be told about the new track.
     */
    fun castTrack(device: CastDevice, track: MusicTrack)

    /** Stops casting and releases the local proxy stream. */
    fun stopCast()

    /** Resumes or pauses the remote playback. */
    fun togglePlayPause()

    /** Skips to the next track and pushes it to the remote renderer. */
    fun next()
}

/** No-op port for hosts that cannot cast. */
object NoOpCastControllerPort : CastControllerPort {
    private val mutableState = MutableStateFlow(CastState())
    override val state: StateFlow<CastState> = mutableState

    override fun discover() = Unit

    override fun castTo(device: CastDevice) = Unit

    override fun castTrack(device: CastDevice, track: MusicTrack) = Unit

    override fun stopCast() = Unit

    override fun togglePlayPause() = Unit

    override fun next() = Unit
}
