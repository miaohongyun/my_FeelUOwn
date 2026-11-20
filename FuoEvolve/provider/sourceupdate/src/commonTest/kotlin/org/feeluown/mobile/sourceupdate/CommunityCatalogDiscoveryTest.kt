package org.feeluown.mobile.sourceupdate

import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.sourceupdate.crypto.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 社区音源自动发现框架测试（阶段 C）。
 *
 * 重点验证隔离红线：发现/去重/扫描可用，但绝不自动批准、绝不扩大白名单、绝不覆盖正式目录。
 */
class CommunityCatalogDiscoveryTest {

    private val primary = "https://raw.githubusercontent.com/shellsec/lx-music-source/master/sources.json"
    private val mirror = "https://gh-proxy.com/raw.githubusercontent.com/shellsec/lx-music-source/master/sources.json"
    private val base = "https://raw.githubusercontent.com/shellsec/lx-music-source/master"
    private val baseMirror = "https://gh-proxy.com/raw.githubusercontent.com/shellsec/lx-music-source/master"

    private fun listJson(vararg entries: Triple<String, String, String>): String = buildString {
        append("{\"sources\":[")
        append(entries.joinToString(",") { (id, name, url) ->
            """{"id":"$id","name":"$name","url":"$url"}"""
        })
        append("]}")
    }

    private fun newDiscovery(
        bodies: Map<String, String>,
        failures: Map<String, CatalogFetchError> = emptyMap(),
        fileStore: SourceFileStore = InMemorySourceFileStore(),
    ): Pair<CommunityCatalogDiscovery, MapCatalogFetcher> {
        val fetcher = MapCatalogFetcher(bodies, failures = failures)
        return CommunityCatalogDiscovery(fetcher, fileStore, clock = { 1_000L }) to fetcher
    }

    @Test
    fun discoversEntriesAndTriesMirrorsInOrder() = runTest {
        val body = listJson(
            Triple("a", "A 音源", "$primary/sources/a/a.js"),
            Triple("b", "B 音源", "$primary/sources/b/b.js"),
        )
        val (d, fetcher) = newDiscovery(mapOf(mirror to body), mapOf(primary to CatalogFetchError.DNS))
        val report = d.discover(listOf(primary, mirror))

        assertTrue(report.ok)
        assertEquals(2, report.entriesInList)
        assertEquals(2, report.added)
        assertEquals(mirror, report.listUrl, "主地址失败应回退镜像")
        assertEquals(listOf(primary, mirror), fetcher.requested)
        val store = d.loadStore()
        assertEquals(2, store.candidates.size)
        assertTrue(store.candidates.all { it.review == CandidateReviewState.DISCOVERED })
        assertTrue(store.candidates.all { it.verification == VerificationLevel.STATIC_REVIEW })
    }

    @Test
    fun dedupsByNormalizedUrlAndRepoPathAcrossMirrors() = runTest {
        // 一次清单内：不同 id 但同一脚本（镜像前缀不同、仓库路径相同）→ 归并为镜像。
        val body = listJson(
            Triple("fish-music", "Fish", "$baseMirror/sources/fish-music/fish_music.js"),
            Triple("fish-dup", "Fish 镜像", "$base/sources/fish-music/fish_music.js"),
        )
        val fileStore = InMemorySourceFileStore()
        val (d, _) = newDiscovery(mapOf(primary to body), fileStore = fileStore)
        val report = d.discover(listOf(primary))

        assertEquals(1, report.added, "同一脚本只登记一次")
        assertEquals(1, report.duplicateUrlsMerged, "不同 id 同仓库路径归并为镜像")
        val fish = d.loadStore().candidates.single()
        assertEquals(1, fish.mirrorUrls.size, "归并的镜像地址被记录")

        // 下一次清单：同 id 名称变化 → 只更新线索元数据，不重复登记。
        val body2 = listJson(Triple("fish-music", "Fish 更新名", "$baseMirror/sources/fish-music/fish_music.js"))
        val (d2, _) = newDiscovery(mapOf(primary to body2), fileStore = fileStore)
        val report2 = d2.discover(listOf(primary))
        assertEquals(1, report2.sameIdUpdated)
        assertEquals(0, report2.added)
        assertEquals("Fish 更新名", d2.loadStore().candidates.single().name, "同 id 用最新清单名称")
    }

    @Test
    fun keepsLastGoodListWhenFetchFailsOrListEmpty() = runTest {
        val good = listJson(Triple("a", "A", "$primary/sources/a/a.js"))
        val fileStore = InMemorySourceFileStore()
        val (first, _) = newDiscovery(mapOf(primary to good), fileStore = fileStore)
        assertEquals(1, first.discover(listOf(primary)).added)

        // 全失败：用缓存原文继续，候选不丢。
        val (second, _) = newDiscovery(emptyMap(), mapOf(primary to CatalogFetchError.TIMEOUT), fileStore)
        val r2 = second.discover(listOf(primary))
        assertTrue(r2.usedCachedList)
        assertTrue(r2.ok)
        assertEquals(1, second.loadStore().candidates.size)

        // sources 空数组：拒绝解析，保留候选（不清空）。
        val (third, _) = newDiscovery(mapOf(primary to """{"sources":[]}"""), fileStore = fileStore)
        val r3 = third.discover(listOf(primary))
        assertFalse(r3.ok)
        assertNotNull(r3.parseError)
        assertEquals(1, third.loadStore().candidates.size, "空清单不得清空本地候选")
    }

    @Test
    fun marksDisappearedEntriesStaleWithoutDeleting() = runTest {
        val fileStore = InMemorySourceFileStore()
        val (first, _) = newDiscovery(
            mapOf(primary to listJson(
                Triple("a", "A", "$primary/sources/a/a.js"),
                Triple("b", "B", "$primary/sources/b/b.js"),
            )), fileStore = fileStore,
        )
        first.discover(listOf(primary))
        val (second, _) = newDiscovery(
            mapOf(primary to listJson(Triple("a", "A", "$primary/sources/a/a.js"))), fileStore = fileStore,
        )
        val report = second.discover(listOf(primary))
        assertEquals(1, report.markedStale)
        val store = second.loadStore()
        assertEquals(2, store.candidates.size, "STALE 条目不删除")
        assertEquals(CandidateReviewState.STALE, store.candidates.first { it.id == "b" }.review)
    }

    @Test
    fun staticScanQuarantinesBlockedScriptWithoutApproving() = runTest {
        val (d, _) = newDiscovery(mapOf(primary to listJson(
            Triple("a", "A", "$primary/sources/a/a.js"),
        )))
        d.discover(listOf(primary))

        // 命中 membership-bypass(BLOCK) 的脚本：自动隔离，绝不 APPROVED。
        val blockedScript = "/* @name 长青SVIP音源 */ const x = 1"
        val realHash = Sha256.hex(blockedScript.encodeToByteArray())
        val outcome = d.scanQuarantined("a", blockedScript, realHash, version = "1.3.0")
        assertTrue(outcome is CommunityCatalogDiscovery.ScanOutcome.Scanned)
        assertEquals(CandidateReviewState.QUARANTINED, outcome.candidate.review)
        assertEquals(StaticVerdict.BLOCKED, outcome.report.verdict)
        assertEquals(CandidateRisk.CRITICAL.name, outcome.candidate.risk)
        assertTrue(outcome.candidate.staticBlockReasons.any { "membership-bypass" in it })

        val store = d.loadStore()
        val c = store.candidates.single()
        assertEquals(realHash, c.sha256, "框架内部计算并保存实际 SHA256")
        assertEquals("1.3.0", c.version, "版本随扫描结果保留")
        assertEquals(1_000L, c.scannedAtMillis, "扫描时间被记录")
        assertEquals(VerificationLevel.STATIC_REVIEW, c.verification, "静态扫描不得提升为 REAL 等级")
        assertFalse(c.review == CandidateReviewState.APPROVED, "发现框架绝不自动批准")
        assertFalse(store.lastGoodListRaw.isNullOrBlank(), "正式目录未被触碰")
    }

    @Test
    fun cleanScanKeepsStateButNeverApproves() = runTest {
        val (d, _) = newDiscovery(mapOf(primary to listJson(
            Triple("a", "A", "$primary/sources/a/a.js"),
        )))
        d.discover(listOf(primary))
        // 干净脚本：扫描通过但状态不变（仍 DISCOVERED），域名只登记到候选元数据，不扩白名单。
        val clean = "const { request } = globalThis.lx; request('https://api.example.com/x')"
        val outcome = d.scanQuarantined("a", clean, Sha256.hex(clean.encodeToByteArray()))
        assertTrue(outcome is CommunityCatalogDiscovery.ScanOutcome.Scanned)
        assertEquals(CandidateReviewState.DISCOVERED, outcome.candidate.review)
        assertEquals(StaticVerdict.CLEAN, outcome.report.verdict)
        assertTrue(outcome.candidate.discoveredDomains.contains("api.example.com"))
    }

    @Test
    fun mergedMirrorAliasNotMarkedStaleWhenAliasStillInList() = runTest {
        // 问题1回归：fish-music 与 fish-dup 因同仓库路径归并，主记录叫 fish-music。
        val first = listJson(
            Triple("fish-music", "Fish", "$baseMirror/sources/fish-music/fish_music.js"),
            Triple("fish-dup", "Fish 镜像", "$base/sources/fish-music/fish_music.js"),
        )
        val fileStore = InMemorySourceFileStore()
        val (d1, _) = newDiscovery(mapOf(primary to first), fileStore = fileStore)
        assertEquals(1, d1.discover(listOf(primary)).added)
        val merged = d1.loadStore().candidates.single()
        assertEquals("fish-music", merged.id)
        assertTrue(merged.aliasIds.any { it.equals("fish-dup", ignoreCase = true) }, "别名 id 被保存")

        // 第二次清单只剩别名 fish-dup：主记录不得被误标 STALE。
        val second = listJson(Triple("fish-dup", "Fish", "$base/sources/fish-music/fish_music.js"))
        val (d2, _) = newDiscovery(mapOf(primary to second), fileStore = fileStore)
        val report = d2.discover(listOf(primary))
        assertEquals(0, report.markedStale, "别名仍在清单里，不算消失")
        val c = d2.loadStore().candidates.single()
        assertFalse(c.review == CandidateReviewState.STALE, "不得误标 STALE")
    }

    @Test
    fun staleOnlyRevivesToDiscoveredNeverToApprovedStates() = runTest {
        val fileStore = InMemorySourceFileStore()
        // 先建立一条候选，再让它消失 → STALE。
        val (d1, _) = newDiscovery(mapOf(primary to listJson(
            Triple("a", "A", "$primary/sources/a/a.js"))), fileStore = fileStore)
        d1.discover(listOf(primary))
        val (d2, _) = newDiscovery(mapOf(primary to listJson(
            Triple("b", "B", "$primary/sources/b/b.js"))), fileStore = fileStore)
        assertEquals(1, d2.discover(listOf(primary)).markedStale)

        // 手工把该候选设为 APPROVED（模拟人工结论），再让它重新出现：绝不自动降级/改写。
        val snapshot = d2.loadStore()
        val approved = snapshot.candidates.map {
            if (it.id == "a") it.copy(reviewState = CandidateReviewState.APPROVED.wireName) else it
        }
        // 通过 store 写回（用 discovery 的 store 引用）。
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
        fileStore.writeText(CommunityCatalogDiscovery.CANDIDATE_STORE_PATH,
            json.encodeToString(CandidateStore.serializer(), snapshot.copy(candidates = approved)))

        val (d3, _) = newDiscovery(mapOf(primary to listJson(
            Triple("a", "A", "$primary/sources/a/a.js"),
            Triple("b", "B", "$primary/sources/b/b.js"))), fileStore = fileStore)
        val report = d3.discover(listOf(primary))
        assertEquals(CandidateReviewState.APPROVED,
            d3.loadStore().candidates.first { it.id == "a" }.review,
            "APPROVED 人工结论不被自动恢复逻辑改写")
        assertEquals(0, report.revivedFromStale, "APPROVED 不属于 STALE，不计入恢复")

        // 再把一条真正的 STALE 设为 STALE 后重新出现 → 只恢复到 DISCOVERED。
        val snap2 = d3.loadStore()
        val staleA = snap2.candidates.map {
            if (it.id == "a") it.copy(reviewState = CandidateReviewState.STALE.wireName) else it
        }
        fileStore.writeText(CommunityCatalogDiscovery.CANDIDATE_STORE_PATH,
            json.encodeToString(CandidateStore.serializer(), snap2.copy(candidates = staleA)))
        val (d4, _) = newDiscovery(mapOf(primary to listJson(
            Triple("a", "A", "$primary/sources/a/a.js"),
            Triple("b", "B", "$primary/sources/b/b.js"))), fileStore = fileStore)
        val r4 = d4.discover(listOf(primary))
        assertEquals(1, r4.revivedFromStale)
        assertEquals(CandidateReviewState.DISCOVERED,
            d4.loadStore().candidates.first { it.id == "a" }.review, "STALE 只恢复到 DISCOVERED")
    }

    @Test
    fun repoPathCannotMergeDifferentReposWithSameTailPath() = runTest {
        val pa = "https://raw.githubusercontent.com/owner-a/repo-x/master/sources/common/song.js"
        val pb = "https://raw.githubusercontent.com/owner-b/repo-y/master/sources/common/song.js"
        assertEquals("", CommunityCatalogNormalizer.repoPath("https://cdn.example.com/sources/common/song.js"),
            "非 GitHub raw 地址无法确认仓库身份")
        assertTrue(CommunityCatalogNormalizer.repoPath(pa) != CommunityCatalogNormalizer.repoPath(pb),
            "不同仓库相同末尾路径必须产生不同 repoPath")

        val body = listJson(
            Triple("song-a", "Song A", pa),
            Triple("song-b", "Song B", pb),
        )
        val (d, _) = newDiscovery(mapOf(primary to body))
        val report = d.discover(listOf(primary))
        assertEquals(2, report.added, "不同仓库的相同末尾路径不得误合并")
        assertEquals(0, report.duplicateUrlsMerged)
    }

    @Test
    fun normalizeUrlPreservesPathCase() = runTest {
        val lower = "https://raw.githubusercontent.com/o/r/master/Foo.js"
        val upper = "https://raw.githubusercontent.com/o/r/master/FOO.js"
        assertTrue(CommunityCatalogNormalizer.normalizeUrl(lower) != CommunityCatalogNormalizer.normalizeUrl(upper),
            "路径大小写不同（区分大小写的服务器）不得被规范化为相同")
        // 协议与主机名大小写仍被规范化（程序化构造大写前缀，避免手拼域名出错）。
        val prefix = lower.substringBefore("/o/r")
        val upperCasePrefix = prefix.uppercase() + lower.substringAfter(prefix)
        assertEquals(
            CommunityCatalogNormalizer.normalizeUrl(lower),
            CommunityCatalogNormalizer.normalizeUrl(upperCasePrefix),
        )
    }

    @Test
    fun scanRejectsMismatchedSha256AndKeepsCandidate() = runTest {
        val (d, _) = newDiscovery(mapOf(primary to listJson(
            Triple("a", "A", "$primary/sources/a/a.js"))))
        d.discover(listOf(primary))
        val script = "const x = 1"
        val before = d.loadStore().candidates.single()
        val outcome = d.scanQuarantined("a", script, "deadbeef")
        assertTrue(outcome is CommunityCatalogDiscovery.ScanOutcome.Sha256Mismatch)
        assertEquals(Sha256.hex(script.encodeToByteArray()), outcome.actual)
        val after = d.loadStore().candidates.single()
        assertEquals(before, after, "哈希不符时扫描结果整条拒绝写入，候选保持原样")
    }

    @Test
    fun parserRejectsMalformedAndDuplicateIds() = runTest {
        assertTrue(CommunityListParser.parse("") is CommunityListParse.Invalid)
        assertTrue(CommunityListParser.parse("not json") is CommunityListParse.Invalid)
        assertTrue(CommunityListParser.parse("""{"sources":[]}""") is CommunityListParse.Invalid)
        assertTrue(CommunityListParser.parse("""{"sources":[{"id":"x","name":"X","url":"ftp://bad"}]}""")
            is CommunityListParse.Invalid)
        val dup = listJson(Triple("x", "X", "$primary/x.js"), Triple("x", "X2", "$primary/y.js"))
        assertTrue(CommunityListParser.parse(dup) is CommunityListParse.Invalid)
    }
}
