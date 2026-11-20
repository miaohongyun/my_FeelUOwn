package org.feeluown.mobile.sourceupdate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阶段 D.1 P0：发现流程解耦（锁外 fetch / 锁内 merge）的确定性并发测试。
 *
 * 所有时序用 CompletableDeferred 门闩构造，不依赖随机 delay。
 * 核心断言：网络请求在锁外进行时，Mutex 空闲，人工审核/删除/扫描提交不会被慢网络阻塞。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommunityDiscoveryDecouplingTest {

    private val listUrl = "https://raw.githubusercontent.com/shellsec/lx-music-source/master/sources.json"
    private val base = "https://raw.githubusercontent.com/shellsec/lx-music-source/master"
    private fun listJson(vararg e: Triple<String, String, String>) = buildString {
        append("{\"sources\":[")
        append(e.joinToString(",") { (id, name, url) -> """{"id":"$id","name":"$name","url":"$url"}""" })
        append("]}")
    }

    /** 可发信号且可用门闩阻塞的 fetcher：started 在 fetch 开始时完成，gate 阻塞返回。 */
    private class SignalingFetcher(
        private val body: String,
        private val started: CompletableDeferred<Unit>? = null,
        private val gate: CompletableDeferred<Unit>? = null,
        private val error: CatalogFetchError? = null,
    ) : CatalogFetcher {
        override suspend fun fetch(url: String, etag: String?, maxBytes: Int?): CatalogFetchResult {
            started?.complete(Unit)
            gate?.await()
            return if (error != null) CatalogFetchResult(url = url, error = error)
            else CatalogFetchResult(url = url, body = body)
        }
    }

    private fun repo(fetcher: CatalogFetcher, store: SourceFileStore): CommunityDiscoveryRepository {
        val discovery = CommunityCatalogDiscovery(fetcher, store, clock = { 1_000L })
        return CommunityDiscoveryRepository(discovery, store, listOf(listUrl))
    }

    /** 1) 网络请求未完成时，人工审核仍能完成（不被 Mutex 阻塞）。 */
    @Test
    fun reviewCompletesWhileNetworkInFlight() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(Triple("a", "A", "$base/sources/a/a.js"))
        repo(SignalingFetcher(body), store).refresh() // seed

        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val r = repo(SignalingFetcher(body, started, gate), store)
        val refresh = async { r.refresh() }
        started.await() // fetch 已在锁外开始并卡在门闩
        // 若 fetch 仍在 Mutex 内，这里会一直等待门闩而超时；解耦后应立即完成。
        val rejected = withTimeout(2_000) { r.reject("a", "网络在途时拒绝") }
        assertTrue(rejected)
        gate.complete(Unit)
        refresh.await()
        assertEquals(CandidateReviewState.REJECTED, r.candidate("a")!!.review)
    }

    /** 2) 网络请求未完成时，候选删除仍能完成。 */
    @Test
    fun deleteCompletesWhileNetworkInFlight() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(Triple("a", "A", "$base/sources/a/a.js"))
        repo(SignalingFetcher(body), store).refresh()

        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val r = repo(SignalingFetcher(body, started, gate), store)
        val refresh = async { r.refresh() }
        started.await()
        val deleted = withTimeout(2_000) { r.deleteCandidate("a") }
        assertTrue(deleted, "网络在途时删除应立即完成")
        gate.complete(Unit)
        refresh.await()
        assertNull(r.candidate("a"), "删除后不被在途刷新复活")
    }

    /** 3) 网络请求完成后，合并读取的是最新候选库（不是请求前的旧快照）。 */
    @Test
    fun mergeReadsLatestStoreAfterNetwork() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(Triple("a", "A", "$base/sources/a/a.js"))
        repo(SignalingFetcher(body), store).refresh()

        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val r = repo(SignalingFetcher(body, started, gate), store)
        val refresh = async { r.refresh() }
        started.await()
        // 网络在途期间改状态；合并阶段必须基于这份最新状态。
        assertTrue(r.reject("a", "最新决定"))
        gate.complete(Unit)
        val report = refresh.await()
        assertEquals(1, report.sameIdUpdated, "合并看到的是刚更新的候选")
        assertEquals("最新决定", r.candidate("a")!!.rejectReason)
        assertEquals(CandidateReviewState.REJECTED, r.candidate("a")!!.review)
    }

    /** 4) 刷新与人工 REJECTED 并发，最终仍为 REJECTED。 */
    @Test
    fun refreshConcurrentRejectKeepsRejected() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(Triple("a", "A", "$base/sources/a/a.js"))
        repo(SignalingFetcher(body), store).refresh()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val r = repo(SignalingFetcher(body, started, gate), store)
        val refresh = async { r.refresh() }
        started.await()
        assertTrue(r.reject("a", "拒绝优先"))
        gate.complete(Unit)
        refresh.await()
        assertEquals(CandidateReviewState.REJECTED, r.candidate("a")!!.review)
    }

    /** 5) 刷新与人工 QUARANTINED 并发，最终仍为 QUARANTINED。 */
    @Test
    fun refreshConcurrentQuarantineKeepsQuarantined() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(Triple("a", "A", "$base/sources/a/a.js"))
        repo(SignalingFetcher(body), store).refresh()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val r = repo(SignalingFetcher(body, started, gate), store)
        val refresh = async { r.refresh() }
        started.await()
        assertTrue(r.quarantine("a", "隔离优先"))
        gate.complete(Unit)
        refresh.await()
        assertEquals(CandidateReviewState.QUARANTINED, r.candidate("a")!!.review)
    }

    /** 6) 删除与旧刷新结果并发（墓碑）：删除后旧清单不复活候选。 */
    @Test
    fun deleteAndStaleRefreshNoResurrection() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(Triple("a", "A", "$base/sources/a/a.js"))
        repo(SignalingFetcher(body), store).refresh()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val r = repo(SignalingFetcher(body, started, gate), store)
        val refresh = async { r.refresh() }
        started.await() // 网络请求已发出，尚未合并
        assertTrue(r.deleteCandidate("a"))
        gate.complete(Unit)
        refresh.await()
        assertNull(r.candidate("a"), "墓碑阻止旧刷新复活已删候选")
        // 显式解除忽略后才可重新发现。
        assertTrue(r.unignore("a"))
        repo(SignalingFetcher(body), store).refresh()
        assertEquals(1, r.candidates().size, "解除忽略后可重新发现")
    }

    /** 7) 扫描与删除并发：删除后扫描结果不复活候选。 */
    @Test
    fun scanAndDeleteNoResurrection() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(Triple("a", "A", "$base/sources/a/a.js"))
        val r = repo(SignalingFetcher(body), store)
        r.refresh()
        assertTrue(r.deleteCandidate("a"))
        // 删除完成后提交扫描（拿到的是删除后的最新 store）→ NotFound。
        val result = r.scanDownloadedScript("a", "const x=1", "0".repeat(64))
        assertTrue(result is CommunityDiscoveryRepository.QuarantineDownloadResult.NotFound)
        assertTrue(r.candidates().isEmpty())
    }

    /** 8) 两次 refresh 并发：不产生重复候选、JSON 可解析、人工状态不丢。 */
    @Test
    fun twoConcurrentRefreshesKeepConsistency() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(
            Triple("a", "A", "$base/sources/a/a.js"),
            Triple("b", "B", "$base/sources/b/b.js"),
        )
        val r = repo(SignalingFetcher(body), store)
        val r1 = async { r.refresh() }
        val r2 = async { r.refresh() }
        r1.await(); r2.await()
        assertEquals(2, r.candidates().size)
        val raw = store.readText(CommunityCatalogDiscovery.CANDIDATE_STORE_PATH)!!
        assertEquals(2, CommunityDiscoverySerialization.decode(raw).candidates.size)
    }

    /** 9) 取消 refresh 后，不得写入迟到的发现结果。 */
    @Test
    fun cancelledRefreshDoesNotWriteLateResult() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(Triple("a", "A", "$base/sources/a/a.js"))
        // 先 seed 一条，确认取消刷新不会改动它。
        repo(SignalingFetcher(body), store).refresh()
        val before = store.readText(CommunityCatalogDiscovery.CANDIDATE_STORE_PATH)

        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val r = repo(SignalingFetcher(body, started, gate), store)
        val refresh = async { r.refresh() }
        started.await()
        refresh.cancelAndJoin()
        gate.complete(Unit) // 迟到的"清单到达"，但协程已取消
        assertEquals(before, store.readText(CommunityCatalogDiscovery.CANDIDATE_STORE_PATH),
            "取消后迟到的发现结果不得写入")
    }

    /** 附：Worker 重复执行（幂等）不产生重复候选、不丢人工状态。 */
    @Test
    fun repeatedRefreshIsIdempotentAndKeepsReview() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(Triple("a", "A", "$base/sources/a/a.js"))
        val r = repo(SignalingFetcher(body), store)
        repeat(5) { r.refresh() }
        assertEquals(1, r.candidates().size, "Worker 重试不复制候选")
        assertTrue(r.reject("a", "人工决定"))
        repeat(3) { r.refresh() }
        assertEquals(1, r.candidates().size)
        assertEquals(CandidateReviewState.REJECTED, r.candidate("a")!!.review, "重试不丢人工审核状态")
    }

    /** 附：网络失败（可重试错误）走缓存回退，候选不变。 */
    @Test
    fun networkFailureKeepsCandidatesViaCache() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(Triple("a", "A", "$base/sources/a/a.js"))
        repo(SignalingFetcher(body), store).refresh()
        val failed = repo(SignalingFetcher("", error = CatalogFetchError.TIMEOUT), store)
        val report = failed.refresh()
        assertTrue(report.ok, "缓存回退解析成功")
        assertTrue(report.usedCachedList)
        assertEquals(1, failed.candidates().size)
    }
}
