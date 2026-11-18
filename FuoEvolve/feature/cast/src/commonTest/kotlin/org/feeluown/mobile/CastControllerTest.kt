package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastControllerTest {
    @Test
    fun discoverFillsTheDeviceList() = runTest {
        val discovery = FakeDiscovery(listOf(device()))
        val controller = DefaultCastController(
            discovery = discovery,
            transport = FakeTransport(),
            stream = FakeStream(),
            source = FakeSource(),
            scope = this,
        )

        controller.discover()
        advanceUntilIdle()

        assertEquals(1, controller.state.value.devices.size)
        assertEquals(CastStatus.Idle, controller.state.value.status)
        assertNull(controller.state.value.errorMessage)
    }

    @Test
    fun discoveryFailureIsReported() = runTest {
        val discovery = FakeDiscovery().apply { failure = IllegalStateException("网线没插") }
        val controller = DefaultCastController(
            discovery = discovery,
            transport = FakeTransport(),
            stream = FakeStream(),
            source = FakeSource(),
            scope = this,
        )

        controller.discover()
        advanceUntilIdle()

        assertEquals(CastStatus.Error, controller.state.value.status)
        assertEquals("网线没插", controller.state.value.errorMessage)
        assertTrue(controller.state.value.devices.isEmpty())
    }

    @Test
    fun castingPublishesTheStreamAndStartsTheRenderer() = runTest {
        val transport = FakeTransport()
        val stream = FakeStream()
        val source = FakeSource().apply {
            track = track()
            resolved = CastStreamSource(
                url = "http://cdn/host/song.mp3",
                headers = mapOf("Cookie" to "MUSIC_U=1"),
                mimeType = "audio/mpeg",
                title = "晴天",
                artist = "周杰伦",
                album = "叶惠美",
                durationMs = 269_000,
            )
        }
        val controller = DefaultCastController(
            discovery = FakeDiscovery(),
            transport = transport,
            stream = stream,
            source = source,
            scope = this,
        )

        controller.castTo(device())
        advanceUntilIdle()

        assertEquals(CastStatus.Casting, controller.state.value.status)
        assertEquals(device(), controller.state.value.activeDevice)
        assertEquals(1, stream.published.size)
        assertEquals("http://cdn/host/song.mp3", stream.published[0].url)
        assertEquals(mapOf("Cookie" to "MUSIC_U=1"), stream.published[0].headers)
        assertTrue(transport.setUriUri.startsWith("http://192.168.1.2:8765/stream/"))
        assertTrue(transport.setUriMetadata.contains("<dc:title>晴天</dc:title>"))
        assertTrue(transport.calls.contains("play"))
    }

    @Test
    fun castingWithoutATrackFails() = runTest {
        val controller = DefaultCastController(
            discovery = FakeDiscovery(),
            transport = FakeTransport(),
            stream = FakeStream(),
            source = FakeSource(),
            scope = this,
        )

        controller.castTo(device())
        advanceUntilIdle()

        assertEquals(CastStatus.Error, controller.state.value.status)
        assertEquals("当前没有正在播放的歌曲", controller.state.value.errorMessage)
    }

    @Test
    fun castingAnUnresolvableTrackFails() = runTest {
        val source = FakeSource().apply {
            track = track()
            resolved = null
        }
        val controller = DefaultCastController(
            discovery = FakeDiscovery(),
            transport = FakeTransport(),
            stream = FakeStream(),
            source = source,
            scope = this,
        )

        controller.castTo(device())
        advanceUntilIdle()

        assertEquals(CastStatus.Error, controller.state.value.status)
        assertEquals("无法解析这首歌的播放地址", controller.state.value.errorMessage)
    }

    @Test
    fun stoppingReleasesTheStreamAndTheRenderer() = runTest {
        val transport = FakeTransport()
        val stream = FakeStream()
        val source = FakeSource().apply {
            track = track()
            resolved = CastStreamSource(url = "http://cdn/host/song.mp3")
        }
        val controller = DefaultCastController(
            discovery = FakeDiscovery(),
            transport = transport,
            stream = stream,
            source = source,
            scope = this,
        )

        controller.castTo(device())
        advanceUntilIdle()
        controller.stopCast()
        advanceUntilIdle()

        assertEquals(CastStatus.Idle, controller.state.value.status)
        assertNull(controller.state.value.activeDevice)
        assertTrue(transport.calls.contains("stop"))
        assertEquals(1, stream.released.size)
    }

    @Test
    fun playPauseFollowsTheRendererState() = runTest {
        val transport = FakeTransport().apply { state = "PLAYING" }
        val controller = DefaultCastController(
            discovery = FakeDiscovery(),
            transport = transport,
            stream = FakeStream(),
            source = FakeSource().apply {
                track = track()
                resolved = CastStreamSource(url = "http://cdn/host/song.mp3")
            },
            scope = this,
        )

        controller.castTo(device())
        advanceUntilIdle()
        transport.calls.clear()

        controller.togglePlayPause()
        advanceUntilIdle()
        assertTrue(transport.calls.contains("pause"))

        transport.state = "PAUSED_PLAYBACK"
        controller.togglePlayPause()
        advanceUntilIdle()
        assertTrue(transport.calls.contains("play"))
    }

    @Test
    fun nextPushesTheFollowingTrack() = runTest {
        val transport = FakeTransport()
        val stream = FakeStream()
        val source = FakeSource().apply {
            track = track()
            resolved = CastStreamSource(url = "http://cdn/host/song.mp3")
        }
        val controller = DefaultCastController(
            discovery = FakeDiscovery(),
            transport = transport,
            stream = stream,
            source = source,
            scope = this,
        )

        controller.castTo(device())
        advanceUntilIdle()
        controller.next()
        advanceUntilIdle()

        assertEquals(2, stream.published.size)
        assertTrue(transport.setUriMetadata.contains("<dc:title>第二首</dc:title>"))
        assertEquals(CastStatus.Casting, controller.state.value.status)
    }

    @Test
    fun nextWithoutAFollowingTrackReportsAnError() = runTest {
        val source = FakeSource().apply {
            track = track()
            resolved = CastStreamSource(url = "http://cdn/host/song.mp3")
            canAdvance = false
        }
        val controller = DefaultCastController(
            discovery = FakeDiscovery(),
            transport = FakeTransport(),
            stream = FakeStream(),
            source = source,
            scope = this,
        )

        controller.castTo(device())
        advanceUntilIdle()
        controller.next()
        advanceUntilIdle()

        assertEquals(CastStatus.Error, controller.state.value.status)
        assertEquals("没有下一首了", controller.state.value.errorMessage)
    }

    @Test
    fun aFinishedTrackMovesTheQueueOnEvenWhenThePhoneStaysPaused() = runTest {
        val transport = FakeTransport().apply { state = "PLAYING" }
        val stream = FakeStream()
        val source = FakeSource().apply {
            track = track()
            resolved = CastStreamSource(url = "http://cdn/host/song.mp3")
        }
        val ticker = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val controller = watchedController(transport, stream, source, ticker)

        controller.castTo(device())
        advanceUntilIdle()
        ticker.tryEmit(Unit)
        advanceUntilIdle()

        transport.state = "STOPPED"
        ticker.tryEmit(Unit)
        advanceUntilIdle()

        assertEquals(1, source.advanceCalls)
        assertEquals(2, stream.published.size)
        assertTrue(transport.setUriMetadata.contains("<dc:title>第二首</dc:title>"))
        assertEquals(CastStatus.Casting, controller.state.value.status)

        controller.stopCast()
        advanceUntilIdle()
    }

    @Test
    fun aPausedRendererLeavesTheQueueAlone() = runTest {
        val transport = FakeTransport().apply { state = "PLAYING" }
        val stream = FakeStream()
        val source = FakeSource().apply {
            track = track()
            resolved = CastStreamSource(url = "http://cdn/host/song.mp3")
        }
        val ticker = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val controller = watchedController(transport, stream, source, ticker)

        controller.castTo(device())
        advanceUntilIdle()
        ticker.tryEmit(Unit)
        advanceUntilIdle()

        transport.state = "PAUSED_PLAYBACK"
        ticker.tryEmit(Unit)
        ticker.tryEmit(Unit)
        advanceUntilIdle()

        assertEquals(0, source.advanceCalls)
        assertEquals(1, stream.published.size)

        controller.stopCast()
        advanceUntilIdle()
    }

    @Test
    fun aStopReportedBeforeTheRendererEverPlayedIsIgnored() = runTest {
        // Some TVs answer STOPPED while they are still opening the stream.
        val transport = FakeTransport().apply { state = "STOPPED" }
        val stream = FakeStream()
        val source = FakeSource().apply {
            track = track()
            resolved = CastStreamSource(url = "http://cdn/host/song.mp3")
        }
        val ticker = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val controller = watchedController(transport, stream, source, ticker)

        controller.castTo(device())
        advanceUntilIdle()
        ticker.tryEmit(Unit)
        ticker.tryEmit(Unit)
        advanceUntilIdle()

        assertEquals(0, source.advanceCalls)
        assertEquals(1, stream.published.size)

        controller.stopCast()
        advanceUntilIdle()
    }

    @Test
    fun theTrackThePhoneAlreadyMovedToIsPushedWithoutSkippingAgain() = runTest {
        val transport = FakeTransport().apply { state = "PLAYING" }
        val stream = FakeStream()
        val source = FakeSource().apply {
            track = track()
            resolved = CastStreamSource(url = "http://cdn/host/song.mp3")
        }
        val ticker = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val controller = watchedController(transport, stream, source, ticker)

        controller.castTo(device())
        advanceUntilIdle()
        ticker.tryEmit(Unit)
        advanceUntilIdle()

        // The phone's own player moved on while the renderer was still finishing the old track.
        source.track = track().copy(id = "track-9", title = "第三首")
        transport.state = "STOPPED"
        ticker.tryEmit(Unit)
        advanceUntilIdle()

        assertEquals(0, source.advanceCalls)
        assertEquals(2, stream.published.size)
        assertTrue(transport.setUriMetadata.contains("<dc:title>第三首</dc:title>"))

        controller.stopCast()
        advanceUntilIdle()
    }

    @Test
    fun aRendererStuckAtTheEndOfTheFileIsTreatedAsFinished() = runTest {
        // Some renderers keep saying PLAYING after the file runs out and park the position.
        val transport = FakeTransport().apply {
            snapshot = CastTransportSnapshot(
                transportState = "PLAYING",
                positionMs = 268_500,
                durationMs = 269_000,
            )
        }
        val stream = FakeStream()
        val source = FakeSource().apply {
            track = track()
            resolved = CastStreamSource(url = "http://cdn/host/song.mp3")
        }
        val ticker = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val controller = watchedController(transport, stream, source, ticker)

        controller.castTo(device())
        advanceUntilIdle()
        ticker.tryEmit(Unit)
        advanceUntilIdle()
        assertEquals(0, source.advanceCalls, "a first sighting of the end is not enough")

        ticker.tryEmit(Unit)
        advanceUntilIdle()

        assertEquals(1, source.advanceCalls)
        assertEquals(2, stream.published.size)

        controller.stopCast()
        advanceUntilIdle()
    }

    @Test
    fun theLastTrackOfTheQueueEndsTheCast() = runTest {
        val transport = FakeTransport().apply { state = "PLAYING" }
        val stream = FakeStream()
        val source = FakeSource().apply {
            track = track()
            resolved = CastStreamSource(url = "http://cdn/host/song.mp3")
            canAdvance = false
        }
        val ticker = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val controller = watchedController(transport, stream, source, ticker)

        controller.castTo(device())
        advanceUntilIdle()
        ticker.tryEmit(Unit)
        advanceUntilIdle()

        transport.state = "STOPPED"
        ticker.tryEmit(Unit)
        advanceUntilIdle()

        assertEquals(CastStatus.Idle, controller.state.value.status)
        assertNull(controller.state.value.activeDevice)
        assertEquals("队列已经播完了", controller.state.value.errorMessage)
        assertEquals(1, stream.released.size)
    }

    @Test
    fun stoppingStopsWatchingTheRenderer() = runTest {
        val transport = FakeTransport().apply { state = "PLAYING" }
        val stream = FakeStream()
        val source = FakeSource().apply {
            track = track()
            resolved = CastStreamSource(url = "http://cdn/host/song.mp3")
        }
        val ticker = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val controller = watchedController(transport, stream, source, ticker)

        controller.castTo(device())
        advanceUntilIdle()
        controller.stopCast()
        advanceUntilIdle()

        transport.state = "STOPPED"
        ticker.tryEmit(Unit)
        advanceUntilIdle()

        assertEquals(0, source.advanceCalls)
        assertEquals(1, stream.published.size)

        controller.stopCast()
        advanceUntilIdle()
    }

    @Test
    fun aTrackChangedWhileTheUserPausedTheCastStaysPaused() = runTest {
        val transport = FakeTransport().apply { state = "PLAYING" }
        val stream = FakeStream()
        val source = FakeSource().apply {
            track = track()
            resolved = CastStreamSource(url = "http://cdn/host/song.mp3")
        }
        val controller = DefaultCastController(
            discovery = FakeDiscovery(),
            transport = transport,
            stream = stream,
            source = source,
            scope = this,
        )

        controller.castTo(device())
        advanceUntilIdle()
        controller.togglePlayPause()
        advanceUntilIdle()
        transport.calls.clear()

        source.track = track().copy(id = "track-7", title = "第四首")
        controller.castTrack(device(), source.track!!)
        advanceUntilIdle()

        assertEquals(2, stream.published.size)
        assertTrue(transport.calls.contains("setUri"))
        assertTrue(transport.calls.contains("pause"))
        assertFalse(transport.calls.contains("play"))
    }

    private fun TestScope.watchedController(
        transport: FakeTransport,
        stream: FakeStream,
        source: FakeSource,
        ticker: MutableSharedFlow<Unit>,
    ) = DefaultCastController(
        discovery = FakeDiscovery(),
        transport = transport,
        stream = stream,
        source = source,
        scope = this,
        ticker = ticker,
    )

    private fun track(): MusicTrack = MusicTrack(
        id = "track-1",
        title = "晴天",
        artists = "周杰伦",
        album = "叶惠美",
        source = "netease",
        sourceType = TrackSourceType.Provider,
    )
    private fun device(): CastDevice = CastDevice(
        id = "uuid:tcl",
        name = "TCL 电视",
        host = "192.168.1.5",
        port = 8200,
        controlUrl = "http://192.168.1.5:8200/AVTransport/Control",
    )

    private class FakeDiscovery(var devices: List<CastDevice> = emptyList()) : CastDiscoveryPort {
        var failure: Exception? = null

        override suspend fun discover(): List<CastDevice> {
            failure?.let { throw it }
            return devices
        }
    }

    private class FakeTransport : CastTransportPort {
        val calls = mutableListOf<String>()
        var state = "PLAYING"
        var snapshot: CastTransportSnapshot? = null
        var setUriUri = ""
        var setUriMetadata = ""

        override suspend fun setUri(device: CastDevice, uri: String, metadata: String) {
            calls += "setUri"
            setUriUri = uri
            setUriMetadata = metadata
        }

        override suspend fun play(device: CastDevice) {
            calls += "play"
        }

        override suspend fun pause(device: CastDevice) {
            calls += "pause"
        }

        override suspend fun stop(device: CastDevice) {
            calls += "stop"
        }

        override suspend fun transportState(device: CastDevice): String = state

        override suspend fun transportSnapshot(device: CastDevice): CastTransportSnapshot =
            snapshot ?: CastTransportSnapshot(transportState = state)
    }

    private class FakeStream : CastStreamPort {
        val published = mutableListOf<CastStreamSource>()
        val released = mutableListOf<String>()

        override suspend fun publish(source: CastStreamSource): String {
            published += source
            return "http://192.168.1.2:8765/stream/token/sid${published.size}/audio.mp3"
        }

        override suspend fun release(url: String) {
            released += url
        }
    }

    private class FakeSource : CastSourcePort {
        var track: MusicTrack? = null
        var resolved: CastStreamSource? = null
        var canAdvance = true
        var advanceCalls = 0

        override fun currentTrack(): MusicTrack? = track

        override suspend fun resolveStream(track: MusicTrack): CastStreamSource? = resolved

        override fun playNext(): Boolean {
            if (!canAdvance) return false
            advanceCalls += 1
            track = track?.copy(id = "track-2", title = "第二首")
            return true
        }
    }
}
