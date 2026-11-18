package org.feeluown.mobile.lossless

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.feeluown.mobile.PlaybackPayload

class LosslessLocationDetectionTest {
    @Test
    fun localFileUriIsNotAutomaticallyConsideredLossless() {
        assertFalse(isLosslessAudioLocation("file:///music/track.mp3"))
        assertFalse(isLosslessAudioLocation("file:///music/track.m4a"))
        assertTrue(isLosslessAudioLocation("file:///music/track.flac"))
    }

    @Test
    fun recognizesLosslessExtensionBeforeQueryParameters() {
        assertTrue(isLosslessAudioLocation("https://cdn.example.test/song.flac?token=abc"))
        assertTrue(isLosslessAudioLocation("https://cdn.example.test/song.ape"))
        assertFalse(isLosslessAudioLocation("https://cdn.example.test/song.mp3?format=flac"))
    }

    @Test
    fun recognizesExplicitLosslessCodecMetadata() {
        val payload = PlaybackPayload(
            url = "https://cdn.example.test/audio.m4a",
            title = "Song",
            artists = "Artist",
            album = "Album",
            source = "test",
            audioQuality = "ALAC 24bit",
        )

        assertTrue(payload.isLosslessPayload())
    }
}
