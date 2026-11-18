package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioQualityProfileTest {
    @Test
    fun tiersMapToProviderPoliciesAndOrderByRank() {
        assertEquals(AudioQualityTier.Lossless, AudioQualityTier.fromPolicy(">>>"))
        assertEquals(AudioQualityTier.High, AudioQualityTier.fromPolicy("hq<>"))
        assertEquals(AudioQualityTier.Standard, AudioQualityTier.fromPolicy("sq<>"))
        assertEquals(AudioQualityTier.Low, AudioQualityTier.fromPolicy("lq<>"))
        assertNull(AudioQualityTier.fromPolicy(null))
        assertTrue(
            AudioQualityTier.entries.sortedByDescending { it.rank }.map { it.rank } ==
                listOf(4, 3, 2, 1),
        )
    }

    @Test
    fun legacyPolicyLabelsStayStableForThePlayerChip() {
        assertEquals("SHQ", AudioQualityTier.Lossless.shortLabel)
        assertEquals("HQ", AudioQualityTier.High.shortLabel)
        assertEquals("SQ", AudioQualityTier.Standard.shortLabel)
        assertEquals("LQ", AudioQualityTier.Low.shortLabel)
    }

    @Test
    fun bitrateFallbackDoesNotClaimLossless() {
        assertEquals(AudioQualityTier.Low, AudioQualityTier.fromBitrateKbps(128))
        assertEquals(AudioQualityTier.Standard, AudioQualityTier.fromBitrateKbps(192))
        assertEquals(AudioQualityTier.High, AudioQualityTier.fromBitrateKbps(320))
        assertNull(AudioQualityTier.fromBitrateKbps(null))
        assertNull(AudioQualityTier.fromBitrateKbps(0))
    }

    @Test
    fun losslessProfileDescribesContainerAndResolution() {
        val profile = AudioQualityProfile.of(
            tier = null,
            codec = "flac",
            bitrateKbps = 1_411,
            sampleRateHz = 96_000,
            bitsPerSample = 24,
            channels = 2,
            isLossless = true,
            evidence = AudioQualityEvidence.ContainerVerified,
        )

        assertEquals(AudioQualityTier.Lossless, profile.tier)
        assertEquals("SHQ", profile.displayLabel())
        assertEquals("FLAC 24bit/96kHz 1411kbps 2ch", profile.technicalSummary())
        assertEquals(AudioQualityEvidence.ContainerVerified, profile.evidence)
    }

    @Test
    fun lossyProfileFallsBackToCodecAndBitrate() {
        val profile = AudioQualityProfile(
            tier = AudioQualityTier.Standard,
            codec = "mp3",
            bitrateKbps = 192,
            rawLabel = "sq",
        )

        assertEquals("SQ", profile.displayLabel())
        assertEquals("MP3 192kbps", profile.technicalSummary())
    }

    @Test
    fun unknownProfileHasNoInventedValues() {
        val profile = AudioQualityProfile()
        assertNull(profile.displayLabel())
        assertNull(profile.technicalSummary())
        assertNull(AudioQualityTier.fromBitrateKbps(null))
    }

    @Test
    fun playbackFormatInfoConvertsToProfile() {
        val format = AudioFormatInfo(
            format = "audio/flac",
            codec = "flac",
            averageBitrate = 920_000,
            sampleRateHz = 44_100,
            channelCount = 2,
        )

        val profile = format.toQualityProfile(isLossless = true)

        assertEquals(AudioQualityTier.Lossless, profile.tier)
        assertEquals(920, profile.bitrateKbps)
        assertEquals(44_100, profile.sampleRateHz)
        assertEquals(2, profile.channels)
        assertEquals(AudioQualityEvidence.PlaybackEngine, profile.evidence)
    }

    @Test
    fun sampleRateAndChannelFormattingIsShared() {
        assertEquals("44.1kHz", formatAudioSampleRate(44_100))
        assertEquals("48kHz", formatAudioSampleRate(48_000))
        assertEquals("22.1kHz", formatAudioSampleRate(22_050))
        assertEquals("", formatAudioSampleRate(0))
        assertEquals("单声道", formatAudioChannels(1))
        assertEquals("立体声", formatAudioChannels(2))
        assertEquals("6 声道", formatAudioChannels(6))
        assertNull(formatAudioChannels(null))
    }
}
