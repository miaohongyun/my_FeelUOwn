package org.feeluown.mobile.feature.community

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 阶段 F4：真实验证等级展示逻辑测试（离线，纯展示函数）。
 * 确认模拟/真实分栏、样本量、失败原因、门禁与主备标签一致，不提供安装路径。
 */
class PhaseFPresentationTest {

    @Test
    fun verificationTierSeparatesSimulatedAndReal() {
        val gd = CommunityDiscoveryPresentation.verificationTierLabel("gd-studio")
        assertTrue(gd.contains("LX模拟="), "应展示 LX 模拟等级")
        assertTrue(gd.contains("真实搜索="), "应展示真实搜索")
        assertTrue(gd.contains("真实播放=NOT_RUN"), "真实播放默认未运行")
        // 至尊的域名拒绝原因必须出现在失败行。
        val zz = CommunityDiscoveryPresentation.lastFailureLine("zhizun")
        assertTrue(zz.contains("域名") || zz.contains("HTTP_502") || zz.contains("权限"),
            "至尊失败原因应可追溯：$zz")
    }

    @Test
    fun stageLinesIncludeSampleSizes() {
        val lines = CommunityDiscoveryPresentation.stageLines("gd-studio")
        assertEquals(4, lines.size)
        assertTrue(lines.all { it.contains("样本=") && it.contains("成功=") && it.contains("失败=") })
        assertTrue(lines.all { it.contains("均响应=") })
    }

    @Test
    fun qualityLineShowsSamplesAndGrade() {
        val line = CommunityDiscoveryPresentation.qualityLine("gd-studio")
        assertTrue(line.contains("评分=") && line.contains("等级="))
        assertTrue(line.contains("真实样本"), "必须展示样本量")
        assertTrue(line.contains("播放0"), "无真实播放样本应如实显示")
    }

    @Test
    fun gateClosedMeansNoPrimaryBackupAdvice() {
        assertFalse(CommunityDiscoveryPresentation.schedulerEnabledByLedger(),
            "当前真实搜索通过 <2，调度器门槛应关闭")
        val admissions = CommunityDiscoveryPresentation.admissions()
        assertTrue(admissions.isNotEmpty())
        admissions.filter { it.candidateId in listOf("zhizun", "huibq", "gd-studio") }.forEach {
            val label = CommunityDiscoveryPresentation.admissionLabel(it.recommendation)
            assertFalse(label == "建议主音源" || label == "建议备用音源",
                "门禁未开不得给主备建议：${it.candidateId} -> $label")
        }
    }

    @Test
    fun blockedCandidatesLabelledBlock() {
        val admissions = CommunityDiscoveryPresentation.admissions()
        val changqing = admissions.firstOrNull { it.candidateId == "changqing" }
        assertFalse(changqing == null)
        assertEquals("BLOCK", CommunityDiscoveryPresentation.admissionLabel(changqing!!.recommendation))
    }

    @Test
    fun honestyNoteCoversAllBoundaries() {
        val note = CommunityDiscoveryPresentation.VERIFICATION_HONESTY_NOTE
        assertTrue(note.contains("LX模拟"))
        assertTrue(note.contains("URL解析成功≠播放成功"))
        assertTrue(note.contains("HTTP 206"))
        assertTrue(note.contains("一次成功不代表长期稳定"))
    }
}
