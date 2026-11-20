package org.feeluown.mobile.sourceupdate

import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.sourceupdate.crypto.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 社区发现 Repository（阶段 D）测试：刷新包装、人工审核、隔离区哈希核验、删除隔离。
 * 全部离线，验证"不执行/不安装/不批准/不碰正式目录"边界。
 */
class CommunityDiscoveryRepositoryTest {

    private val listUrl = "https://raw.githubusercontent.com/shellsec/lx-music-source/master/sources.json"
    private val base = "https://raw.githubusercontent.com/shellsec/lx-music-source/master"

    private fun listJson(vararg e: Triple<String, String, String>) = buildString {
        append("{\"sources\":[")
        append(e.joinToString(",") { (id, name, url) -> """{"id":"$id","name":"$name","url":"$url"}""" })
        append("]}")
    }

    private fun newRepo(
        bodies: Map<String, String>,
        failures: Map<String, CatalogFetchError> = emptyMap(),
        fileStore: SourceFileStore = InMemorySourceFileStore(),
    ): CommunityDiscoveryRepository {
        val fetcher = MapCatalogFetcher(bodies, failures = failures)
        val discovery = CommunityCatalogDiscovery(fetcher, fileStore, clock = { 1_000L })
        return CommunityDiscoveryRepository(discovery, fileStore, listOf(listUrl))
    }

    @Test
    fun firstRefreshAddsCandidates() = runTest {
        val repo = newRepo(mapOf(listUrl to listJson(
            Triple("a", "A", "$base/sources/a/a.js"),
            Triple("b", "B", "$base/sources/b/b.js"),
        )))
        val r = repo.refresh()
        assertTrue(r.ok)
        assertEquals(2, r.candidateCount)
        assertEquals(2, r.added)
        assertEquals(2, repo.candidates().size)
    }

    @Test
    fun repeatedRefreshUsesCacheWhenFetchFailsAndNeverClears() = runTest {
        val fileStore = InMemorySourceFileStore()
        val first = newRepo(mapOf(listUrl to listJson(Triple("a", "A", "$base/sources/a/a.js"))), fileStore = fileStore)
        assertEquals(1, first.refresh().candidateCount)

        // 全失败 + 空清单：候选都不清空。
        val failed = newRepo(emptyMap(), mapOf(listUrl to CatalogFetchError.TIMEOUT), fileStore)
        val r2 = failed.refresh()
        assertTrue(r2.ok, "缓存回退解析成功即视为 ok")
        assertTrue(r2.usedCachedList)
        assertEquals(1, failed.candidates().size)

        val empty = newRepo(mapOf(listUrl to """{"sources":[]}"""), fileStore = fileStore)
        val r3 = empty.refresh()
        assertFalse(r3.ok)
        assertEquals(1, empty.candidates().size)
    }

    @Test
    fun manualReviewActionsNeverApprove() = runTest {
        val repo = newRepo(mapOf(listUrl to listJson(Triple("a", "A", "$base/sources/a/a.js"))))
        repo.refresh()

        assertTrue(repo.quarantine("a", "命中 BLOCK"))
        assertEquals(CandidateReviewState.QUARANTINED, repo.candidate("a")!!.review)
        assertEquals("命中 BLOCK", repo.candidate("a")!!.rejectReason)

        assertTrue(repo.reject("a", "人工拒绝"))
        assertEquals(CandidateReviewState.REJECTED, repo.candidate("a")!!.review)

        assertTrue(repo.reopenForReview("a"))
        assertEquals(CandidateReviewState.DISCOVERED, repo.candidate("a")!!.review)
        // 全程无 APPROVED。
        assertTrue(repo.candidates().none { it.review == CandidateReviewState.APPROVED })
        assertFalse(repo.quarantine("not-exist", "x"), "不存在的候选返回 false")
    }

    @Test
    fun quarantineDownloadVerifiesSha256AndScans() = runTest {
        val repo = newRepo(mapOf(listUrl to listJson(Triple("a", "A", "$base/sources/a/a.js"))))
        repo.refresh()

        val script = "/* @name 长青SVIP音源 */ const x = 1"
        val hash = Sha256.hex(script.encodeToByteArray())

        // 哈希不符：整条拒绝，不写隔离脚本、不改候选。
        val bad = repo.scanDownloadedScript("a", script, "deadbeef")
        assertTrue(bad is CommunityDiscoveryRepository.QuarantineDownloadResult.Sha256Mismatch)
        assertNull(repo.quarantinedScriptText("a"), "哈希不符时不写隔离脚本")
        assertNull(repo.candidate("a")!!.sha256, "哈希不符时不更新候选")

        // 哈希正确：写隔离脚本 + 扫描（BLOCK 脚本被隔离）。
        val good = repo.scanDownloadedScript("a", script, hash, version = "1.3.0")
        assertTrue(good is CommunityDiscoveryRepository.QuarantineDownloadResult.Scanned)
        assertEquals(hash, good.candidate.sha256)
        assertEquals("1.3.0", good.candidate.version)
        assertEquals(CandidateReviewState.QUARANTINED, good.candidate.review)
        assertNotNull(repo.quarantinedScriptText("a"), "哈希正确时写隔离脚本")
        assertTrue(repo.quarantinePath("a").startsWith(CommunityCatalogDiscovery.QUARANTINE_PREFIX))
    }

    @Test
    fun workerDispositionClassifiesSuccessRetryFailure() = runTest {
        val repo = newRepo(mapOf(listUrl to listJson(Triple("a", "A", "$base/sources/a/a.js"))))
        val ok = repo.refresh()
        assertEquals(
            org.feeluown.mobile.sourceupdate.CommunityDiscoveryRepository.RefreshDisposition.SUCCESS,
            repo.workerDisposition(ok),
        )
        // 网络失败但用了缓存 → ok=true → 仍算 SUCCESS（缓存可用，不该 retry 无限）。
        val cached = org.feeluown.mobile.sourceupdate.CommunityDiscoveryRepository.RefreshResult(
            ok = true, candidateCount = 1, added = 0, sameIdUpdated = 0, merged = 0,
            markedStale = 0, revivedFromStale = 0, usedCachedList = true, parseError = null,
        )
        assertEquals(
            org.feeluown.mobile.sourceupdate.CommunityDiscoveryRepository.RefreshDisposition.SUCCESS,
            repo.workerDisposition(cached),
        )
        // 清单读取失败且无缓存 → retry。
        val retry = cached.copy(ok = false, usedCachedList = false)
        assertEquals(
            org.feeluown.mobile.sourceupdate.CommunityDiscoveryRepository.RefreshDisposition.RETRY,
            repo.workerDisposition(retry),
        )
        // 结构非法（parseError 非空）→ failure，不无限重试。
        val failure = retry.copy(parseError = "清单 sources 为空")
        assertEquals(
            org.feeluown.mobile.sourceupdate.CommunityDiscoveryRepository.RefreshDisposition.FAILURE,
            repo.workerDisposition(failure),
        )
    }

    @Test
    fun deleteCandidateOnlyTouchesQuarantineSubtree() = runTest {
        val fileStore = InMemorySourceFileStore()
        // 放一个"正式脚本"键，确认删除候选不会碰它。
        fileStore.writeText("sources/active/official.js", "official-content")
        val repo = newRepo(mapOf(listUrl to listJson(Triple("a", "A", "$base/sources/a/a.js"))), fileStore = fileStore)
        repo.refresh()
        val script = "const x = 1"
        repo.scanDownloadedScript("a", script, Sha256.hex(script.encodeToByteArray()))

        assertTrue(repo.deleteCandidate("a"))
        assertTrue(repo.candidates().isEmpty())
        assertNull(repo.quarantinedScriptText("a"), "隔离脚本被删")
        assertEquals("official-content", fileStore.readText("sources/active/official.js"), "正式脚本不受影响")
    }
}
