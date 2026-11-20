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
}
