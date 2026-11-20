package org.feeluown.mobile.sourceupdate

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阶段 F 测试：真实验证台账诚信边界、质量评分样本量约束、主备调度门槛/冷却/隔离。
 * 全部离线纯逻辑，不发起任何网络请求。
 */
class PhaseFVerificationTest {

    // ===== 台账诚信：失败请求也在，模拟与真实分栏 =====

    @Test
    fun ledgerRecordsFailuresNotOnlySuccesses() {
        val all = CommunityRealVerificationLedger.attempts
        assertTrue(all.isNotEmpty())
        assertTrue(all.any { it.outcome == VerificationOutcome.FAILED }, "必须保留失败请求")
        assertTrue(all.any { it.errorCode != null }, "失败必须带错误码")
        val huibq = CommunityRealVerificationLedger.attemptsFor("huibq")
        assertTrue(huibq.any { it.errorCode == "SERVICE_SUSPENDED" }, "HUIBQ 503 必须入账")
        val zhizun = CommunityRealVerificationLedger.attemptsFor("zhizun")
        assertTrue(zhizun.any { it.domainRejectionReason != null }, "至尊域名拒绝原因必须入账")
    }

    @Test
    fun simulatedNeverCountedAsReal() {
        val gdSearch = CommunityRealVerificationLedger.summarize("gd-studio", VerificationStage.REAL_SEARCH)
        assertTrue(gdSearch.sampleSize >= 1)
        assertTrue(gdSearch.successCount >= 1)
        // LX_MOCK 记录不计入真实统计。
        assertTrue(gdSearch.successCount == CommunityRealVerificationLedger.attemptsFor(
            "gd-studio", VerificationStage.REAL_SEARCH
        ).count { it.source == "REAL_NETWORK" && it.outcome == VerificationOutcome.PASSED })
    }

    @Test
    fun noCandidateClaimsRealPlayback() {
        listOf("zhizun", "huibq", "gd-studio", "ikun-v515").forEach { id ->
            val play = CommunityRealVerificationLedger.summarize(id, VerificationStage.REAL_PLAYBACK)
            assertEquals(0, play.successCount, "$id 不得声称真实播放成功")
        }
    }

    @Test
    fun stageUnsupportedVsFailed() {
        assertEquals(VerificationOutcome.NOT_SUPPORTED,
            CommunityRealVerificationLedger.summarize("huibq", VerificationStage.REAL_SEARCH).outcome)
        assertEquals(VerificationOutcome.FAILED,
            CommunityRealVerificationLedger.summarize("huibq", VerificationStage.REAL_URL_RESOLVE).outcome)
    }

    // ===== 质量评分：样本量与诚实边界 =====

    @Test
    fun scoreNeverFullMarksWithoutSamples() {
        val gd = CommunityQualityScorer.score("gd-studio")
        assertTrue(gd.total in 0..100)
        assertTrue(gd.playbackStability == 0, "无真实播放样本不得给播放分")
        assertTrue(gd.realPlaybackSamples == 0)
        assertEquals(QualityGrade.UNVERIFIED, gd.grade, "样本不足只能是 UNVERIFIED")
        assertTrue(gd.reason.contains("样本"), "评分必须附样本量说明")
    }

    @Test
    fun blockedCandidateScoredBlocked() {
        val cq = CommunityQualityScorer.score("changqing")
        assertEquals(QualityGrade.BLOCKED, cq.grade)
        val flower = CommunityQualityScorer.score("flower-pdone")
        assertEquals(QualityGrade.BLOCKED, flower.grade)
    }

    @Test
    fun admissionsGateRequiresTwoRealSearchPass() {
        val admissions = CommunityQualityScorer.admissions()
        assertTrue(admissions.isNotEmpty())
        // 当前只有 gd-studio 一项真实搜索 PASSED（HUIBQ 服务暂停/至尊拒绝），不足 2 → 全部不接入。
        val eligible = listOf("zhizun", "huibq", "gd-studio").filter {
            CommunityRealVerificationLedger.summarize(it, VerificationStage.REAL_SEARCH).outcome == VerificationOutcome.PASSED
        }
        assertTrue(eligible.size < 2, "门禁应尚未打开（真实搜索通过 <2），当前=${eligible}")
        admissions.filter { it.candidateId in listOf("zhizun", "huibq", "gd-studio") }.forEach {
            assertFalse(it.recommendation == AdmissionRecommendation.PRIMARY ||
                it.recommendation == AdmissionRecommendation.BACKUP, "门禁未开不得建议主备")
        }
        assertEquals(AdmissionRecommendation.BLOCK, admissions.first { it.candidateId == "changqing" }.recommendation)
    }

    // ===== F3 调度器 =====

    @Test
    fun schedulerDisabledUntilTwoRealSearchPass(): Unit = runBlocking {
        var now = 1_000_000L
        val scheduler = SourceFallbackScheduler(nowMs = { now })
        val executed = mutableListOf<String>()
        val outcome = scheduler.run(
            order = listOf("gd-studio", "huibq"), platform = "wy",
            gateOpen = false, eligibleIds = setOf("gd-studio"),
        ) { id, _ ->
            executed += id
            SourceAttemptResult(id, "wy", true, "url")
        }
        assertFalse(outcome.enabled)
        assertNull(outcome.winner)
        assertTrue(executed.isEmpty(), "门禁未开不得执行任何后端请求")
    }

    @Test
    fun schedulerMaxAttemptsAndCooldown(): Unit = runBlocking {
        var now = 1_000_000L
        val scheduler = SourceFallbackScheduler(nowMs = { now }, maxAttempts = 2, cooldownMs = 1_000L)
        val order = listOf("a", "b", "c")
        val executed = mutableListOf<String>()
        // a、b 失败进入冷却；c 不会被执行（maxAttempts=2）。
        val outcome = scheduler.run(
            order = order, platform = "wy", gateOpen = true,
            eligibleIds = order.toSet(),
        ) { id, p ->
            executed += id
            SourceAttemptResult(id, p, false, failureReason = "fail-$id")
        }
        assertFalse(outcome.winner != null)
        assertEquals(listOf("a", "b"), executed, "最多 2 次尝试")
        assertNotNull(scheduler.cooldownState("a"))

        // 冷却窗口内跳过。
        now += 500L
        val outcome2 = scheduler.run(
            order = order, platform = "wy", gateOpen = true, eligibleIds = order.toSet(),
        ) { id, p -> SourceAttemptResult(id, p, true, "url-$id") }
        assertEquals(listOf("c"), outcome2.attempts.map { it.candidateId }, "a/b 冷却应跳过，仅剩 c")
        assertTrue(outcome2.skippedCooling.containsAll(listOf("a", "b")))

        // 冷却结束后可用。门禁要求 ≥2 个真实搜索通过候选，测试里补足 2 个但只调度 a。
        now += 1_000L
        scheduler.clearCooldown("a")
        val outcome3 = scheduler.run(
            order = listOf("a"), platform = "wy", gateOpen = true, eligibleIds = setOf("a", "b"),
        ) { id, p -> SourceAttemptResult(id, p, true, "url") }
        assertNotNull(outcome3.winner)
    }

    @Test
    fun schedulerRejectsSpoofedIdentityAndPlatform(): Unit = runBlocking {
        val scheduler = SourceFallbackScheduler(nowMs = { 0L })
        val outcome = scheduler.run(
            order = listOf("a"), platform = "wy", gateOpen = true, eligibleIds = setOf("a", "b"),
        ) { _, _ ->
            // 后端冒充 b 并返回跨平台结果。
            SourceAttemptResult("b", "kg", true, "spoofed")
        }
        assertNull(outcome.winner)
        assertTrue(outcome.attempts.first().failureReason!!.contains("冒充"))
    }

    @Test
    fun schedulerIsolatesPlatforms(): Unit = runBlocking {
        val scheduler = SourceFallbackScheduler(nowMs = { 0L })
        var seenPlatform = ""
        scheduler.run(
            order = listOf("a"), platform = "wy", gateOpen = true, eligibleIds = setOf("a", "b"),
        ) { id, p ->
            seenPlatform = p
            SourceAttemptResult(id, p, true, "url")
        }
        assertEquals("wy", seenPlatform, "一次调度不跨平台")
    }
}
