package org.feeluown.mobile.source

import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.sourceupdate.InMemorySourceFileStore
import org.feeluown.mobile.sourceupdate.InstalledSource
import org.feeluown.mobile.sourceupdate.SourceIndex
import org.feeluown.mobile.sourceupdate.SourceReviewStatus
import org.feeluown.mobile.sourceupdate.SourceVersionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 目录安装结果 → 待执行脚本列表。
 *
 * 这一层的唯一职责是"谁能被执行"，因此测试重点全部在过滤条件上：
 * 未启用不跑、未批准不跑、被隔离不跑、版本文件缺失不跑。
 */
class StoreLxInstalledScriptSourceTest {

    private suspend fun storeWith(vararg sources: InstalledSource): SourceVersionStore {
        val store = SourceVersionStore(InMemorySourceFileStore())
        // 同步写入版本文件：readActiveScript 依赖真实内容存在。
        sources.forEach { installed ->
            store.writeVersion(installed.scriptId, installed.activeVersion, "// ${installed.scriptId}")
        }
        store.saveIndex(SourceIndex(sources = sources.associateBy { it.scriptId }))
        return store
    }

    private fun installed(
        id: String,
        enabled: Boolean = true,
        reviewStatus: String = SourceReviewStatus.APPROVED.wireName,
        version: String = "1.0.0",
        platforms: List<String> = listOf("kw"),
    ) = InstalledSource(
        scriptId = id,
        name = id,
        activeVersion = version,
        sha256 = "0".repeat(64),
        installedAtMillis = 1L,
        enabled = enabled,
        reviewStatus = reviewStatus,
        allowedHosts = listOf("api.example.com"),
        platforms = platforms,
    )

    @Test
    fun returnsApprovedEnabledScripts() = runTest {
        val store = storeWith(installed("good"))
        val scripts = StoreLxInstalledScriptSource(store).scripts()

        assertEquals(1, scripts.size)
        val script = scripts.single()
        assertEquals("good", script.id)
        assertEquals("1.0.0", script.version)
        assertEquals(listOf("kw"), script.platforms)
        assertEquals(listOf("api.example.com"), script.allowedHosts)
        assertEquals("// good", script.source)
    }

    @Test
    fun skipsDisabledAndUnapprovedAndQuarantined() = runTest {
        val store = storeWith(
            installed("enabled"),
            installed("disabled", enabled = false),
            installed("candidate", reviewStatus = SourceReviewStatus.REVIEW_CANDIDATE.wireName),
            installed("quarantined"),
        )
        val scripts = StoreLxInstalledScriptSource(store) { setOf("quarantined") }.scripts()

        assertEquals(listOf("enabled"), scripts.map { it.id })
    }

    @Test
    fun skipsScriptWhoseActiveFileIsMissing() = runTest {
        val store = SourceVersionStore(InMemorySourceFileStore())
        store.saveIndex(SourceIndex(sources = mapOf("ghost" to installed("ghost"))))

        // index 说有、文件却没有（比如用户清了数据目录）：静默跳过，不要抛异常拖垮搜索。
        assertTrue(StoreLxInstalledScriptSource(store).scripts().isEmpty())
    }

    @Test
    fun statusMirrorsIndexAndQuarantine() = runTest {
        val store = storeWith(installed("a"), installed("b", enabled = false))
        val runtime = SourceCatalogRuntime(
            store = store,
            quarantine = { setOf("a") },
            clock = { 1_000L },
        )

        val status = runtime.status()
        assertEquals(2, status.installed.size)
        val a = status.installed.first { it.id == "a" }
        assertTrue(a.quarantined)
        // 隔离优先于启用标记：展示与执行必须一致。
        assertTrue(!a.enabled)
        val b = status.installed.first { it.id == "b" }
        assertTrue(!b.enabled && !b.quarantined)
    }

    @Test
    fun catalogVersionAndFailureCountersComeFromIndex() = runTest {
        val store = SourceVersionStore(InMemorySourceFileStore())
        store.saveIndex(SourceIndex(catalogVersion = 42, consecutiveFailures = 3, lastSuccessMillis = 2_000L))
        val runtime = SourceCatalogRuntime(store = store, clock = { 5_000L })

        val status = runtime.status()
        assertEquals(42, status.catalogVersion)
        assertEquals(3, status.consecutiveFailures)
        assertEquals(2_000L, status.lastSuccessMillis)
        assertTrue(!status.hasInstalled)
    }
}
