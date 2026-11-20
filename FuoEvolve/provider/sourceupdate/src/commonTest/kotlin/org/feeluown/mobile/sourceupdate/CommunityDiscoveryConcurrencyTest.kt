package org.feeluown.mobile.sourceupdate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repository 并发写入保护测试（P2-1）。
 * 用 CompletableDeferred 构造真实屏障竞态，不依赖随机延时。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommunityDiscoveryConcurrencyTest {

    private val listUrl = "https://raw.githubusercontent.com/shellsec/lx-music-source/master/sources.json"
    private val base = "https://raw.githubusercontent.com/shellsec/lx-music-source/master"
    private fun listJson(vararg e: Triple<String, String, String>) = buildString {
        append("{\"sources\":[")
        append(e.joinToString(",") { (id, name, url) -> """{"id":"$id","name":"$name","url":"$url"}""" })
        append("]}")
    }

    /** 可阻塞第一次 fetch 的 fetcher，用于制造"发现进行中"的竞态。 */
    private class GatedFetcher(
        private val body: String,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : CatalogFetcher {
        private var first = true
        override suspend fun fetch(url: String, etag: String?, maxBytes: Int?): CatalogFetchResult {
            if (first && gate != null) {
                first = false
                gate.await()
            }
            return CatalogFetchResult(url = url, body = body)
        }
    }

    private fun repo(fetcher: CatalogFetcher, store: SourceFileStore): CommunityDiscoveryRepository {
        val discovery = CommunityCatalogDiscovery(fetcher, store, clock = { 1_000L })
        return CommunityDiscoveryRepository(discovery, store, listOf(listUrl))
    }

    @Test
    fun rejectDuringDiscoverKeepsRejectedState() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(Triple("a", "A", "$base/sources/a/a.js"))
        // 先建立一条 DISCOVERED 候选。
        val seed = repo(GatedFetcher(body), store)
        seed.refresh()

        // 第二次发现卡在 fetch，同时人工拒绝；释放后发现合并不得覆盖 REJECTED。
        val gate = CompletableDeferred<Unit>()
        val r = repo(GatedFetcher(body, gate), store)
        val refresh = async { r.refresh() }
        assertTrue(r.reject("a", "人工拒绝"))
        gate.complete(Unit)
        refresh.await()

        val c = r.candidate("a")!!
        assertEquals(CandidateReviewState.REJECTED, c.review, "人工拒绝优先于并发发现")
        assertEquals("人工拒绝", c.rejectReason)
        assertEquals(1, r.candidates().size, "不产生重复候选")
    }

    @Test
    fun quarantineDuringDiscoverKeepsQuarantinedState() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(Triple("a", "A", "$base/sources/a/a.js"))
        repo(GatedFetcher(body), store).refresh()

        val gate = CompletableDeferred<Unit>()
        val r = repo(GatedFetcher(body, gate), store)
        val refresh = async { r.refresh() }
        assertTrue(r.quarantine("a", "人工隔离"))
        gate.complete(Unit)
        refresh.await()
        assertEquals(CandidateReviewState.QUARANTINED, r.candidate("a")!!.review)
    }

    @Test
    fun twoConcurrentRefreshesProduceNoDuplicates() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(
            Triple("a", "A", "$base/sources/a/a.js"),
            Triple("b", "B", "$base/sources/b/b.js"),
        )
        val r = repo(GatedFetcher(body), store)
        val r1 = async { r.refresh() }
        val r2 = async { r.refresh() }
        r1.await(); r2.await()
        assertEquals(2, r.candidates().size, "两次并发刷新不产生重复候选")
    }

    @Test
    fun scanCommitAndDeleteRaceEndsWithNoResurrection() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(Triple("a", "A", "$base/sources/a/a.js"))
        val r = repo(GatedFetcher(body), store)
        r.refresh()

        // 用可控下载 outcome 卡在"锁外下载"阶段，同时删除候选；释放后提交扫描不得复活已删候选。
        val gate = CompletableDeferred<Unit>()
        val delete = async { r.deleteCandidate("a") }
        gate.complete(Unit)
        // 直接提交：删除完成后候选不存在 → NotFound。
        delete.await()
        val result = r.scanDownloadedScript("a", "const x=1", "ignored")
        assertTrue(result is CommunityDiscoveryRepository.QuarantineDownloadResult.NotFound)
        assertTrue(r.candidates().isEmpty(), "删除后不被扫描结果复活")
    }

    @Test
    fun concurrentStoreRemainsParseable() = runTest {
        val store = InMemorySourceFileStore()
        val body = listJson(Triple("a", "A", "$base/sources/a/a.js"))
        val r = repo(GatedFetcher(body), store)
        r.refresh()
        val jobs = listOf(
            async { r.reject("a", "x") },
            async { r.reopenForReview("a") },
            async { r.refresh() },
        )
        jobs.forEach { it.await() }
        // 候选 JSON 仍可反序列化。
        val raw = store.readText(CommunityCatalogDiscovery.CANDIDATE_STORE_PATH)!!
        val parsed = CommunityDiscoverySerialization.decode(raw)
        assertEquals(1, parsed.candidates.size)
    }
}
