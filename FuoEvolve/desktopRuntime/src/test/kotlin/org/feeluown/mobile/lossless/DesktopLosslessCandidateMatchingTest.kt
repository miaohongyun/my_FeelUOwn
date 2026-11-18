package org.feeluown.mobile.lossless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.TrackSourceType

class DesktopLosslessCandidateMatchingTest {
    @Test
    fun exactTitleAndArtistRemainConfident() {
        val score = scoreLosslessCandidate(track(), candidate())
        assertTrue(score >= 0.70)
        assertTrue(candidate(score).isConfidentMatch())
    }

    @Test
    fun sameTitleByDifferentArtistIsRejected() {
        val score = scoreLosslessCandidate(track(), candidate(artists = "另一位歌手"))
        assertEquals(0.0, score)
        assertFalse(candidate(score).isConfidentMatch())
    }

    @Test
    fun liveCoverAndInstrumentalVersionsAreNotSubstitutedForStudioTrack() {
        listOf("晴天 Live", "晴天 Live版", "晴天 翻唱", "晴天 伴奏", "晴天 Instrumental").forEach { title ->
            assertEquals(0.0, scoreLosslessCandidate(track(), candidate(title = title)), title)
        }
    }

    @Test
    fun candidateWithVeryDifferentDurationIsRejected() {
        assertEquals(
            0.0,
            scoreLosslessCandidate(track(), candidate().copy(durationSeconds = 215.0)),
        )
    }

    @Test
    fun requestedLiveVersionCanMatchLiveCandidate() {
        val score = scoreLosslessCandidate(
            track(title = "晴天 Live"),
            candidate(title = "晴天 (Live)")
        )
        assertTrue(score >= 0.70)
    }

    @Test
    fun requestedLiveVersionIsNotReplacedByStudioRecording() {
        assertEquals(0.0, scoreLosslessCandidate(track(title = "晴天 Live"), candidate(title = "晴天")))
        assertEquals(0.0, scoreLosslessCandidate(track(title = "晴天 现场版"), candidate(title = "晴天")))
    }

    @Test
    fun chineseAndEnglishVersionMarkersAreEquivalent() {
        val score = scoreLosslessCandidate(
            track(title = "晴天 現場"),
            candidate(title = "晴天 (Live)")
        )
        assertTrue(score >= 0.70)
    }

    @Test
    fun candidateAlbumVersionMarkerIsRejected() {
        assertEquals(
            0.0,
            scoreLosslessCandidate(track(), candidate(title = "晴天").copy(album = "叶惠美 世界巡回演唱会")),
        )
    }

    private fun track(title: String = "晴天") = MusicTrack(
        id = "track-1",
        title = title,
        artists = "周杰伦",
        album = "叶惠美",
        source = "netease",
        sourceType = TrackSourceType.Provider,
        durationMs = 269_000L,
    )

    private fun candidate(
        score: Double = 0.0,
        title: String = "晴天",
        artists: String = "周杰伦",
    ) = LosslessCandidate(
        source = "test",
        title = title,
        artists = artists,
        remoteUrl = "https://example.test/song.flac",
        durationSeconds = 269.0,
        score = score,
    )
}
