package org.feeluown.mobile.sourceupdate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G1：声称实现 search 的候选登记不变量（离线，纯台账）。
 * 固化来源/版本/SHA/许可与"协议无 search"的诚信边界。
 */
class PhaseG1RegistryTest {

    private val g1Ids = listOf("suyin", "huanyin", "ciallo", "feichangdao")

    @Test
    fun g1_candidates_have_real_sha_and_source() {
        listOf("suyin", "huanyin", "ciallo").forEach { id ->
            val c = CommunitySourceRegistry.find(id) ?: throw AssertionError("G1 候选缺登记: $id")
            assertNotNull(c.sha256, "$id 应有真实 SHA")
            assertTrue(c.sha256!!.length == 64, "$id SHA 应为 64 hex")
            assertNotNull(c.repositoryUrl, "$id 应有来源仓库")
            assertTrue(c.downloadUrl != null, "$id 应有可复核下载地址")
        }
    }

    @Test
    fun g1_license_is_recorded_honestly() {
        // pdone 两个候选来源仓库无 LICENSE → license=null，不得伪造许可证。
        assertNull(CommunitySourceRegistry.find("suyin")!!.license)
        assertNull(CommunitySourceRegistry.find("huanyin")!!.license)
        // guoyue2010 仓库有 Apache-2.0。
        assertEquals("Apache-2.0", CommunitySourceRegistry.find("ciallo")!!.license)
    }

    @Test
    fun g1_search_declarers_are_not_recommended_as_sources() {
        // G0 已证协议无 search action、产品无调用链；G1 这些声明 search 的候选都未达"合规可接入"。
        listOf("suyin", "huanyin", "ciallo").forEach { id ->
            val c = CommunitySourceRegistry.find(id)!!
            assertTrue(c.status == CommunityAuditStatus.LX_RUNTIME_LOAD_PASSED,
                "$id 只到隔离加载级，不得升级为 REAL_SEARCH_PASSED/ELIGIBLE")
            assertTrue(c.evidence.contains("无调用链") || c.evidence.contains("未接入"),
                "$id 证据必须明确未接入/无调用链")
        }
        // 非常刀声明 search 但为 QUARANTINE，禁止执行。
        assertEquals(CommunityAuditStatus.QUARANTINED, CommunitySourceRegistry.find("feichangdao")!!.status)
    }

    @Test
    fun g1_no_fake_search_passed_status() {
        // 登记表状态枚举里没有把任何 G1 候选标成真实搜索通过。
        CommunitySourceRegistry.candidates.filter { it.candidateId in g1Ids }.forEach {
            assertTrue(it.status != CommunityAuditStatus.REAL_SEARCH_PASSED,
                "${it.candidateId} 未做真实搜索验证，不得标 REAL_SEARCH_PASSED")
            assertTrue(it.status != CommunityAuditStatus.ELIGIBLE_FOR_CONTROLLED_TEST)
        }
    }
}
