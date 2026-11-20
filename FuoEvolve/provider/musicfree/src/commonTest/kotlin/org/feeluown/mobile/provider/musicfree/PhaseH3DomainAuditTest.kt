package org.feeluown.mobile.provider.musicfree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 阶段 H3：播放域名审查"证据不足→保持拒绝"测试。 */
class PhaseH3DomainAuditTest {

    @Test
    fun h3_resolvedHostDiffersFromDeclared(): Unit {
        assertFalse(MusicFreePlaybackDomainAudit.declaredApiHost.equals(
            MusicFreePlaybackDomainAudit.resolvedPlaybackHost, ignoreCase = true))
    }

    @Test
    fun h3_decisionIsDeniedWithReasons(): Unit {
        val d = MusicFreePlaybackDomainAudit.playbackHostDecision
        assertEquals(MusicFreePlaybackDomainAudit.Decision.DENIED, d.decision)
        assertTrue(d.reasons.size >= 5)
        assertTrue(d.reasons.any { it.contains("条款") })
        assertTrue(d.reasons.any { it.contains("DNS rebinding") })
        assertTrue(d.followUpRequired.any { it.contains("书面授权") || it.contains("API 条款") })
    }

    @Test
    fun h3_playbackHostNotAllowedEvenIfAudited(): Unit {
        assertFalse(MusicFreePlaybackDomainAudit.mayAllowPlaybackHost("music.audiomack.com"))
        assertFalse(MusicFreePlaybackDomainAudit.mayAllowPlaybackHost("MUSIC.AUDIOMACK.COM"))
    }

    @Test
    fun h3_playbackUrlRejectedNoWhitelistRelaxation(): Unit {
        val reason = MusicFreePlaybackDomainAudit.playbackUrlRejectionReason(
            "https://music.audiomack.com/stream?id=10921920")
        assertNotNull(reason)
        assertTrue(reason.contains("不放宽白名单"))
        val other = MusicFreePlaybackDomainAudit.playbackUrlRejectionReason("https://m701.music.126.net/x")
        assertNotNull(other)
        assertTrue(other.contains("默认拒绝"))
    }

    @Test
    fun h3_dnsEvidenceRecorded(): Unit {
        val playback = MusicFreePlaybackDomainAudit.evidence.first { it.hostname == "music.audiomack.com" }
        assertTrue(playback.dnsAddresses.any { it.startsWith("2a03:2880") }) // Meta IP 段
        assertTrue(playback.ownership.contains("Meta"))
    }
}
