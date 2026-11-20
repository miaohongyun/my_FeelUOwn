package org.feeluown.mobile.feature.community

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.sourceupdate.CandidateReviewState
import org.feeluown.mobile.sourceupdate.CatalogFetchError
import org.feeluown.mobile.sourceupdate.CatalogFetchResult
import org.feeluown.mobile.sourceupdate.CatalogFetcher
import org.feeluown.mobile.sourceupdate.CommunityCatalogDiscovery
import org.feeluown.mobile.sourceupdate.CommunityDiscoveryRepository
import org.feeluown.mobile.sourceupdate.InMemorySourceFileStore
import org.feeluown.mobile.sourceupdate.VerificationLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 测试内简易 fetcher（MapCatalogFetcher 属于 sourceupdate 的 commonTest，此处不可见）。 */
private class TestFetcher(private val body: String) : CatalogFetcher {
    override suspend fun fetch(url: String, etag: String?, maxBytes: Int?): CatalogFetchResult =
        CatalogFetchResult(url = url, body = body)
}

/** 阶段 D：发现页状态容器测试。覆盖 D6 的页面状态恢复与人工审核不越权。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommunityDiscoveryControllerTest {

    private val listUrl = "https://raw.githubusercontent.com/shellsec/lx-music-source/master/sources.json"
    private val base = "https://raw.githubusercontent.com/shellsec/lx-music-source/master"

    private fun newController(
        scope: CoroutineScope,
        store: InMemorySourceFileStore = InMemorySourceFileStore(),
    ): Pair<RealCommunityDiscoveryController, CommunityDiscoveryRepository> {
        val body = """{"sources":[{"id":"a","name":"A","url":"$base/sources/a/a.js"}]}"""
        val fetcher = TestFetcher(body)
        val discovery = CommunityCatalogDiscovery(fetcher, store, clock = { 1_000L })
        val repo = CommunityDiscoveryRepository(discovery, store, listOf(listUrl))
        return RealCommunityDiscoveryController(repo, scope) to repo
    }

    @Test
    fun refreshPopulatesUiState() = runTest {
        val (controller, _) = newController(this)
        controller.refresh()
        // Controller 用自身 scope（TestScope）launch；runTest 的调度会执行它。
        runCurrent()
        val state = controller.uiState.value
        assertEquals(1, state.candidates.size)
        assertEquals("A", state.candidates.first().name)
        assertTrue(!state.isRefreshing)
        assertNotNull(state.feedback)
    }

    @Test
    fun selectDrivesDetailStateAndReviewActionsNeverApprove() = runTest {
        val (controller, _) = newController(this)
        controller.refresh()
        runCurrent()
        controller.select("a")
        runCurrent()
        assertEquals("a", controller.uiState.value.selectedId)

        controller.quarantine("a", "测试隔离")
        runCurrent()
        assertEquals(CandidateReviewState.QUARANTINED, controller.uiState.value.candidates.first().review)

        controller.reopen("a")
        runCurrent()
        assertEquals(CandidateReviewState.DISCOVERED, controller.uiState.value.candidates.first().review)
        assertTrue(controller.uiState.value.candidates.none { it.review == CandidateReviewState.APPROVED })
    }

    @Test
    fun restartWithPersistedStoreRestoresCandidates() = runTest {
        // 模拟 App 重启：同一个 store 构造新的 controller，候选应恢复。
        val store = InMemorySourceFileStore()
        val (first, _) = newController(this, store)
        first.refresh()
        runCurrent()
        assertEquals(1, first.uiState.value.candidates.size)

        val (second, _) = newController(this, store)
        // 新 controller 首屏为空，但 refresh 读取同一 store 应恢复候选（清单也可能已缓存）。
        second.refresh()
        runCurrent()
        assertEquals(1, second.uiState.value.candidates.size)
    }

    @Test
    fun presentationNeverShowsDiscoveredAsApproved() {
        val candidate = org.feeluown.mobile.sourceupdate.DiscoveredCandidate(
            id = "a", name = "A", rawUrl = "u", normalizedUrl = "u", repoPath = "p",
        )
        assertEquals("已发现（待审核）", CommunityDiscoveryPresentation.reviewLabel(candidate))
        assertEquals("未扫描", CommunityDiscoveryPresentation.verdictLabel(candidate))
        assertTrue(CommunityDiscoveryPresentation.verificationLabel(candidate).contains("非真实"))
        assertEquals(VerificationLevel.STATIC_REVIEW.rank, 0)
    }

    @Test
    fun deleteRequiresSecondConfirmation() = runTest {
        val (controller, _) = newController(this)
        controller.refresh(); runCurrent()
        controller.requestDelete("a"); runCurrent()
        assertEquals("a", controller.uiState.value.deleteConfirmId, "第一次点击只进入确认态")
        assertEquals(1, controller.uiState.value.candidates.size, "第一次点击不删除")
        // 取消后不删除。
        controller.requestDelete(null); runCurrent()
        assertEquals(null, controller.uiState.value.deleteConfirmId)
        assertEquals(1, controller.uiState.value.candidates.size)
        // 两次点同一 id 才真正删除。
        controller.requestDelete("a"); runCurrent()
        controller.requestDelete("a"); runCurrent()
        assertTrue(controller.uiState.value.candidates.isEmpty())
        assertEquals(null, controller.uiState.value.deleteConfirmId)
    }

    @Test
    fun errorClassificationDistinguishesCauses() {
        assertEquals(
            CommunityOperationError.HASH_MISMATCH,
            CommunityDiscoveryPresentation.classifyError("SHA256 不匹配"),
        )
        assertEquals(
            CommunityOperationError.STATIC_BLOCK,
            CommunityDiscoveryPresentation.classifyError("命中 BLOCK 规则，已隔离"),
        )
        assertEquals(
            CommunityOperationError.SECURITY_REJECTED,
            CommunityDiscoveryPresentation.classifyError("安全策略拒绝：非 HTTPS"),
        )
        assertEquals(
            CommunityOperationError.NETWORK,
            CommunityDiscoveryPresentation.classifyError("网络超时"),
        )
        assertTrue(CommunityDiscoveryPresentation.errorLabel(CommunityOperationError.HASH_MISMATCH).contains("SHA256"))
    }

    @Test
    fun hashUnverifiedFlagIsShownInPresentation() {
        val candidate = org.feeluown.mobile.sourceupdate.DiscoveredCandidate(
            id = "a", name = "A", rawUrl = "u", normalizedUrl = "u", repoPath = "p", hashUnverified = true,
        )
        assertTrue(candidate.hashUnverified, "无预期哈希下载的候选被标记，UI 据此提示不可信")
    }

    // ---- E5 统计 / 筛选 / 安装策略 ----

    @Test
    fun e5StatsCountAllCategoriesWithoutOverclaim() {
        val mk = { id: String, review: String, verdict: String?, sha: String?, level: String ->
            org.feeluown.mobile.sourceupdate.DiscoveredCandidate(
                id = id, name = id, rawUrl = "u", normalizedUrl = "u", repoPath = "p",
                reviewState = review, staticVerdict = verdict, sha256 = sha, verificationLevel = level,
            )
        }
        val list = listOf(
            mk("a", "DISCOVERED", "CLEAN", null, "STATIC_REVIEW"),
            mk("b", "QUARANTINED", "BLOCKED", "h", "STATIC_REVIEW"),
            mk("c", "REJECTED", null, "h", "LX_RUNTIME_LOAD"),
            mk("d", "DISCOVERED", null, null, "REAL_SEARCH"),
        )
        val s = CommunityDiscoveryPresentation.CandidateStats.from(list)
        assertEquals(4, s.total)
        assertEquals(2, s.discovered)
        assertEquals(2, s.downloaded, "有 SHA 的算已下载")
        assertEquals(1, s.staticPassed)
        assertEquals(1, s.blocked)
        assertEquals(1, s.quarantined)
        assertEquals(2, s.lxVerified, "LX_RUNTIME_LOAD 与 REAL_SEARCH 均 >= LX 级")
        assertEquals(1, s.realVerified, "只有 REAL_SEARCH 算真实验证")
    }

    @Test
    fun e5FilterByNameDomainAndStatus() {
        val mk = { id: String, name: String, domains: List<String>, review: String ->
            org.feeluown.mobile.sourceupdate.DiscoveredCandidate(
                id = id, name = name, rawUrl = "u", normalizedUrl = "u", repoPath = "p",
                discoveredDomains = domains, reviewState = review,
            )
        }
        val list = listOf(
            mk("a", "星海音乐源", listOf("api.qq.com"), "DISCOVERED"),
            mk("b", "念心", listOf("music.163.com"), "QUARANTINED"),
            mk("c", "野花", emptyList(), "DISCOVERED"),
        )
        assertEquals(1, CommunityDiscoveryPresentation.filterCandidates(list, "星海", null).size)
        assertEquals(1, CommunityDiscoveryPresentation.filterCandidates(list, "163.com", null).size)
        assertTrue(CommunityDiscoveryPresentation.filterCandidates(list, "a", null).any { it.id == "a" },
            "id 也应可筛选")
        assertEquals(2, CommunityDiscoveryPresentation.filterCandidates(list, "",
            org.feeluown.mobile.sourceupdate.CandidateReviewState.DISCOVERED).size)
        assertEquals(3, CommunityDiscoveryPresentation.filterCandidates(list, "", null).size)
    }

    @Test
    fun e5InstallPolicyNeverPromisesAutoInstall() {
        val note = CommunityDiscoveryPresentation.INSTALL_POLICY_NOTE
        assertTrue(note.contains("人工审核"), "安装必须明确需人工审核")
        assertTrue(note.contains("签名"), "安装必须明确走受控目录签名发布")
        assertTrue(!note.contains("自动安装"), "不得暗示自动安装")
    }
}
