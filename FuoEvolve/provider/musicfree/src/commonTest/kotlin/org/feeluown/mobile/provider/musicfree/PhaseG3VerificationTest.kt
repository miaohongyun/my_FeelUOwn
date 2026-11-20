package org.feeluown.mobile.provider.musicfree

import org.feeluown.mobile.provider.musicfree.MusicFreeG3Verification.Outcome
import org.feeluown.mobile.provider.musicfree.MusicFreeG3Verification.Stage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 阶段 G3 台账诚实性测试：真实与模拟分栏、失败/拒绝记录、播放 NOT_RUN、门禁关闭。 */
class PhaseG3VerificationTest {

    private val record = MusicFreePluginRegistry.find("mf-audiomack")!!

    @Test
    fun g3_realSearchSamplesIncludeFailuresAndEmpty(): Unit {
        val searches = MusicFreeG3Verification.searchAttempts
        assertTrue(searches.size >= 6)
        assertTrue(searches.all { it.stage == Stage.REAL_SEARCH && it.httpStatus == 200 })
        // 空结果与不存在关键词如实记录（结果=0 但请求成功，不伪造为失败）
        assertTrue(searches.any { it.keyword.isNullOrEmpty() })
        assertTrue(searches.any { it.keyword?.contains("nonexistent") == true })
        assertTrue(searches.any { it.detail.contains("晴天") && it.detail.contains("10921920") })
    }

    @Test
    fun g3_urlResolveIdentityMatch(): Unit {
        val resolve = MusicFreeG3Verification.resolveAttempts.first { it.outcome == Outcome.PASSED }
        assertTrue(resolve.detail.contains("MATCH"))
        assertEquals(200, resolve.httpStatus)
        // 未探测的失败样本如实标注，不伪造
        val invalid = MusicFreeG3Verification.resolveAttempts.first { it.outcome == Outcome.FAILED }
        assertTrue(invalid.errorCode == "NOT_PROBED" || invalid.detail.contains("未"))
    }

    @Test
    fun g3_domainMismatchRecordedNotRelaxed(): Unit {
        val denied = MusicFreeG3Verification.audioAttempts.first { it.outcome == Outcome.FAILED }
        assertEquals("DOMAIN_NOT_AUTHORIZED", denied.errorCode)
        assertTrue(denied.detail.contains("不放宽白名单"))
        assertTrue(denied.detail.contains("music.audiomack.com"))
    }

    @Test
    fun g3_playbackNotRunAndLevelsSeparated(): Unit {
        val playback = MusicFreeG3Verification.playbackAttempts
        assertTrue(playback.all { it.outcome == Outcome.NOT_RUN })
        assertTrue(playback.all { it.detail.contains("不等于真实播放") || it.errorCode == "REQUIRES_USER_ACTION" })
        val summary = MusicFreeG3Verification.summary(record)
        assertTrue(summary.realSearch.startsWith("REAL_SEARCH_PASSED"))
        assertTrue(summary.realUrlResolve.startsWith("REAL_URL_RESOLVE_PASSED"))
        assertTrue(summary.audioResponse.startsWith("REAL_AUDIO_RESPONSE_PASSED"))
        assertTrue(summary.realPlayback.startsWith("NOT_RUN"))
        assertTrue(summary.sampleNote.contains("不宣称长期稳定"))
    }

    @Test
    fun g3_schedulingGateClosedWithSingleCandidate(): Unit {
        assertTrue(!MusicFreeG3Verification.schedulingGateOpen(1))
        assertTrue(MusicFreeG3Verification.schedulingGateOpen(2))
    }

    @Test
    fun g3_ledgerShaMatchesRegistry(): Unit {
        assertEquals(record.sha256, MusicFreeG3Verification.SCRIPT_SHA256)
    }
}
