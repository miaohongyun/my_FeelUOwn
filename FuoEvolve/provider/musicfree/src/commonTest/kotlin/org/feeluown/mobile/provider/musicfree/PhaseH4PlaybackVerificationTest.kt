package org.feeluown.mobile.provider.musicfree

import org.feeluown.mobile.provider.musicfree.MusicFreePlaybackVerification.Level
import org.feeluown.mobile.provider.musicfree.MusicFreePlaybackVerification.StepStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 阶段 H4：真实播放闭环台账——五级分栏、8 步、域名拒绝后 NOT_RUN、HTTP206 不冒充播放。 */
class PhaseH4PlaybackVerificationTest {

    private val report = MusicFreePlaybackVerification.currentAudiomackReport()

    @Test
    fun h4_eightStepsRecorded(): Unit {
        assertEquals(8, report.steps.size)
        assertEquals((1..8).toList(), report.steps.map { it.index })
    }

    @Test
    fun h4_haltsAtNetworkPermissionStep(): Unit {
        assertEquals(4, report.haltedAtIndex)
        assertEquals(StepStatus.PASSED, report.steps[0].status) // 真实搜索
        assertEquals(StepStatus.PASSED, report.steps[1].status) // 身份匹配
        assertEquals(StepStatus.PASSED, report.steps[2].status) // URL解析
        assertEquals(StepStatus.FAILED, report.steps[3].status) // 网络权限
        assertTrue(report.steps[3].reason.contains("不放宽白名单"))
    }

    @Test
    fun h4_laterStepsNotRun(): Unit {
        (4..7).forEach { i -> assertEquals(StepStatus.NOT_RUN, report.steps[i].status) }
        assertTrue(report.steps[6].reason.contains("NOT_RUN"))
    }

    @Test
    fun h4_http206DoesNotBecomeRealPlayback(): Unit {
        assertFalse(report.realPlaybackAchieved)
        assertFalse(report.achievedLevels.contains(Level.REAL_PLAYBACK))
        assertFalse(report.achievedLevels.contains(Level.REAL_AUDIO_RESPONSE)) // 闭环内音频响应未通过
        assertTrue(report.achievedLevels.contains(Level.REAL_SEARCH))
        assertTrue(report.achievedLevels.contains(Level.REAL_TRACK_IDENTITY_VERIFIED))
        assertTrue(report.achievedLevels.contains(Level.REAL_URL_RESOLVE))
    }

    @Test
    fun h4_allFiveLevelsDistinguished(): Unit {
        assertEquals(5, Level.entries.size)
        assertTrue(Level.entries.containsAll(listOf(
            Level.REAL_SEARCH, Level.REAL_TRACK_IDENTITY_VERIFIED, Level.REAL_URL_RESOLVE,
            Level.REAL_AUDIO_RESPONSE, Level.REAL_PLAYBACK,
        )))
    }

    @Test
    fun h4_ifDomainAuthorizedStillNeedsUserActionForPlayback(): Unit {
        // 即使未来域名获授权，buildReport 默认 realPlaybackDone=false → 真实播放仍 NOT_RUN
        val hypothetical = MusicFreePlaybackVerification.buildReport(
            searchOk = true, identityMatch = true, urlResolved = true, networkAllowed = true,
            audioResponseOk = true, realPlaybackDone = false,
        )
        // 域名授权+音频响应通过，但播放器未启动 → 停在第6步（播放器读取），真实播放仍未达成。
        assertEquals(6, hypothetical.haltedAtIndex)
        assertFalse(hypothetical.realPlaybackAchieved)
        assertEquals(StepStatus.PASSED, hypothetical.steps[3].status) // 网络权限
        assertEquals(StepStatus.PASSED, hypothetical.steps[4].status) // 音频响应
        assertEquals(StepStatus.FAILED, hypothetical.steps[5].status) // 播放器读取未做
    }

    @Test
    fun h4_earlyFailureHaltsChain(): Unit {
        val noSearch = MusicFreePlaybackVerification.buildReport(
            searchOk = false, identityMatch = true, urlResolved = true, networkAllowed = true,
        )
        assertEquals(1, noSearch.haltedAtIndex)
        assertTrue(noSearch.steps.drop(1).all { it.status == StepStatus.NOT_RUN })
    }
}
