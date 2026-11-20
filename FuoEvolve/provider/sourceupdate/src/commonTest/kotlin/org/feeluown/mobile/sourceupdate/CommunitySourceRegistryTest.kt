package org.feeluown.mobile.sourceupdate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 阶段 E0-E2 候选登记表测试。
 * 校验台账自身的一致性与安全不变量，不依赖网络。
 */
class CommunitySourceRegistryTest {

    @Test
    fun allCandidatesHaveStableUniqueIds() {
        val ids = CommunitySourceRegistry.candidates.map { it.candidateId }
        assertEquals(ids.size, ids.toSet().size, "candidateId 必须唯一")
        assertTrue(ids.all { it.isNotBlank() && it == it.trim() })
        // 任务书要求的每个候选都必须登记（野花在 A/B 清单重复，取 flower-pdone 一条）。
        val required = listOf(
            // A 此前五候选
            "changqing", "feichangdao", "yuningxi", "fish-music", "flower-pdone",
            // B 新增独立音源（野花已计入 A）
            "sixyin", "yecao", "nianxin", "qdy", "huibq", "tongyi", "xinghai", "molan",
            "yiyin", "huanyin", "kulao", "dujia-v7", "zhizun",
            // C 系列与功能型
            "ikun-v515", "mygo", "lingchuan", "miyin", "chunriying", "lanyin",
            "xiaoxiongmao", "feicai-gongshe",
            // D MusicFree 生态
            "musicfree-z-series", "yuanli-decompiled", "yunyun-music", "musicfree-subscription-hebijunge",
            // E 聚合
            "stellarwave",
        )
        val missing = required.filterNot { it in ids }
        assertTrue(missing.isEmpty(), "任务书候选缺登记: $missing")
    }

    @Test
    fun unverifiableCandidatesNeverCarryFabricatedHash() {
        CommunitySourceRegistry.candidates
            .filter { it.status == CommunityAuditStatus.UNVERIFIABLE }
            .forEach { c ->
                assertTrue(c.sha256 == null, "${c.candidateId} 无法验证却带 SHA，属于伪造")
                assertTrue(c.downloadUrl == null, "${c.candidateId} 无法验证却带 downloadUrl")
            }
    }

    @Test
    fun recordedHashesAreValidSha256Hex() {
        CommunitySourceRegistry.candidates
            .filter { it.sha256 != null }
            .forEach { c ->
                assertTrue(isSha256Hex(c.sha256!!), "${c.candidateId} 的 SHA 非 64 位 hex: ${c.sha256}")
                assertTrue((c.fileSize ?: 0) > 0, "${c.candidateId} 有 SHA 却无文件大小")
            }
    }

    @Test
    fun blockedAndObfuscatedNeverMarkedVerifiedOrInstallable() {
        CommunitySourceRegistry.candidates
            .filter { it.status.isTerminalBlock }
            .forEach { c ->
                assertTrue(c.status != CommunityAuditStatus.STATIC_REVIEW_PASSED, "${c.candidateId} 阻断态却标审查通过")
                assertTrue(c.status != CommunityAuditStatus.ELIGIBLE_FOR_CONTROLLED_TEST)
                assertTrue(c.status != CommunityAuditStatus.REAL_SEARCH_PASSED)
                assertTrue(c.evidence.isNotBlank(), "${c.candidateId} 阻断必须有证据")
            }
    }

    @Test
    fun noCandidateIsAutoApproved() {
        // 登记表里不存在 APPROVED 概念；状态枚举里根本没有 APPROVED。
        assertTrue(CommunityAuditStatus.entries.none { it.name == "APPROVED" })
        assertTrue(CommunitySourceRegistry.candidates.none {
            it.status == CommunityAuditStatus.ELIGIBLE_FOR_CONTROLLED_TEST && it.sha256 == null
        }, "标为可受控测试的候选必须先有已核验文件")
    }

    @Test
    fun duplicateGroupsDetectedBySha() {
        val groups = CommunitySourceRegistry.duplicateGroupsBySha()
        // grass(pdone) 与 grass(shellsec) 同 SHA；lx(独家) 与 lx-pdone 同 SHA。
        assertTrue(groups.values.any { it.contains("grass-pdone") && it.contains("yecao") } ||
            CommunitySourceRegistry.find("yecao")?.duplicateOf == "grass-pdone",
            "野草/Grass 同 SHA 重复应被识别")
    }

    @Test
    fun previousFiveCandidatesRetainedWithEvidence() {
        val five = listOf("changqing", "feichangdao", "yuningxi", "fish-music", "flower-pdone")
        five.forEach { id ->
            val c = CommunitySourceRegistry.find(id)
                ?: throw AssertionError("此前五候选缺登记: $id")
            assertTrue(c.evidence.isNotBlank(), "$id 必须有取证证据")
            assertTrue(c.sha256 != null, "$id 已重新取证，应带真实 SHA")
        }
        // 长青保持 BLOCK，且不关闭规则。
        assertEquals(CommunityAuditStatus.BLOCKED, CommunitySourceRegistry.find("changqing")!!.status)
        assertTrue(CommunitySourceRegistry.find("changqing")!!.staticFindings.containsKey("membership-bypass"))
    }

    @Test
    fun e3LxSimulatedPassedOnlyWithPlatformEvidence() {
        CommunitySourceRegistry.candidates
            .filter { it.status == CommunityAuditStatus.LX_RUNTIME_LOAD_PASSED }
            .forEach { c ->
                assertTrue(c.lxSimulatedPlatforms.isNotEmpty(), "${c.candidateId} 标 LX 通过但无平台证据")
                assertTrue(c.sha256 != null, "${c.candidateId} 标 LX 通过但无已核验文件")
                assertTrue(c.lxSimulatedPassed, "${c.candidateId} lxSimulatedPassed 应为 true")
            }
        // E3 实际加载成功的候选。
        listOf("zhizun", "huibq", "gd-studio").forEach { id ->
            assertEquals(CommunityAuditStatus.LX_RUNTIME_LOAD_PASSED,
                CommunitySourceRegistry.find(id)!!.status, "$id 应在 E3 加载成功")
        }
        // E3 因空 allowedHosts 加载失败的候选不得被错误升级。
        assertEquals(CommunityAuditStatus.FETCHED, CommunitySourceRegistry.find("ikun-v515")!!.status)
        // BLOCK 候选即使历史做过模拟也不得升级为 LX 通过。
        assertEquals(CommunityAuditStatus.BLOCKED, CommunitySourceRegistry.find("changqing")!!.status)
    }

    @Test
    fun e4BundlesHaveRealEvidenceAndNoOverclaim() {
        val mf = CommunitySourceRegistry.bundles.first { it.bundleId == "musicfree-57" }
        assertEquals(57, mf.verifiedCount, "E4 实际解析出 57 插件条目")
        assertTrue(mf.children.size >= 10, "应展开插件清单示例")
        val sw = CommunitySourceRegistry.bundles.first { it.bundleId == "stellarwave-backends" }
        assertEquals(CommunityAuditStatus.BLOCKED, sw.status, "星澜因 QQ越权/内嵌Cookie 阻断")
        assertTrue(sw.evidence.contains("platform=23") || sw.evidence.contains("Cookie"),
            "星澜阻断必须含具体越权证据")
        assertTrue(sw.verifiedCount < sw.claimedCount!!, "不得把 87 声明写成已验证")
    }

    @Test
    fun keyAggregatorsAreRegisteredAsBundles() {
        assertTrue(CommunitySourceRegistry.bundles.isNotEmpty())
        val stellar = CommunitySourceRegistry.bundles.first { it.bundleId == "stellarwave-backends" }
        assertTrue(stellar.claimedCount != null && stellar.claimedCount!! >= 80)
        assertTrue(stellar.children.isNotEmpty(), "星澜应拆解出具体后端清单")
        // 声明后端数 != 已验证数，不得把 87 写成已验证。
        assertTrue(stellar.verifiedCount < stellar.claimedCount!!)
    }
}
