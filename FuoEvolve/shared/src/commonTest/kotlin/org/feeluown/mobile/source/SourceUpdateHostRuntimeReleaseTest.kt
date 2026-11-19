package org.feeluown.mobile.source

import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.InMemoryAppSettingsRepository
import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.ProviderRegistryRepository
import org.feeluown.mobile.sourceupdate.InMemorySourceFileStore
import org.feeluown.mobile.sourceupdate.InstalledSource
import org.feeluown.mobile.sourceupdate.SourceIndex
import org.feeluown.mobile.sourceupdate.SourceReviewStatus
import org.feeluown.mobile.sourceupdate.SourceVersionStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 任务6（应用接入闭环）：用户停用/隔离脚本时必须**主动**释放对应 QuickJS context，
 * 而不是等下一次 search/resolve 惰性回收。
 *
 * 这里用 recording registry 断言 releaseDynamicScriptRuntime 确实按 scriptId 调用，
 * 覆盖"用户显式停用/隔离 → 应用层编排 → Provider 回收接口"这段闭环。
 * 后台自动更新不主动释放（避免打断正在解析的 resolve），故不在本测试范围内。
 */
class SourceUpdateHostRuntimeReleaseTest {

    private class RecordingRegistry : ProviderRegistryRepository {
        val released = mutableListOf<String>()
        override suspend fun initialize() = Unit
        override suspend fun providers(): List<ProviderInfo> = emptyList()
        override suspend fun releaseDynamicScriptRuntime(scriptId: String) {
            released += scriptId
        }
    }

    private lateinit var store: SourceVersionStore
    private lateinit var registry: RecordingRegistry
    private lateinit var host: SourceUpdateHost

    @BeforeTest
    fun setUp() {
        store = SourceVersionStore(InMemorySourceFileStore())
        registry = RecordingRegistry()
        val runtime = SourceCatalogRuntime(store = store)
        host = SourceUpdateHost(runtime = runtime, settings = InMemoryAppSettingsRepository(), providerRegistry = registry)
    }

    private suspend fun installScript(id: String) {
        store.writeVersion(id, "1.0.0", "// $id")
        store.saveIndex(
            SourceIndex(
                sources = mapOf(
                    id to InstalledSource(
                        scriptId = id,
                        name = id,
                        activeVersion = "1.0.0",
                        sha256 = "0".repeat(64),
                        installedAtMillis = 1L,
                        enabled = true,
                        reviewStatus = SourceReviewStatus.APPROVED.wireName,
                        allowedHosts = emptyList(),
                        platforms = listOf("kw"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun disablingScriptReleasesItsRuntime() = runTest {
        installScript("to-disable")
        host.setScriptEnabled("to-disable", false)
        assertEquals(listOf("to-disable"), registry.released)
        assertTrue(!store.loadIndex().sources.getValue("to-disable").enabled)
    }

    @Test
    fun quarantiningScriptReleasesItsRuntime() = runTest {
        installScript("to-quarantine")
        host.setScriptQuarantined("to-quarantine", true)
        assertEquals(listOf("to-quarantine"), registry.released)
        assertTrue(!store.loadIndex().sources.getValue("to-quarantine").enabled)
    }

    @Test
    fun enablingOrUnquarantiningDoesNotReleaseRuntime() = runTest {
        installScript("to-enable")
        host.setScriptEnabled("to-enable", true)
        host.setScriptQuarantined("to-enable", false)
        assertEquals(emptyList(), registry.released, "启用/解除隔离不应关闭正在使用的 context")
    }

    private suspend fun installScriptWithPrevious(id: String) {
        store.writeVersion(id, "1.0.0", "// $id v1")
        store.writeVersion(id, "2.0.0", "// $id v2")
        store.saveIndex(
            SourceIndex(
                sources = mapOf(
                    id to InstalledSource(
                        scriptId = id,
                        name = id,
                        activeVersion = "2.0.0",
                        previousVersion = "1.0.0",
                        sha256 = "0".repeat(64),
                        installedAtMillis = 1L,
                        enabled = true,
                        reviewStatus = SourceReviewStatus.APPROVED.wireName,
                        allowedHosts = emptyList(),
                        platforms = listOf("kw"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun rollbackRevertsVersionAndReleasesRuntime() = runTest {
        installScriptWithPrevious("to-rollback")
        val changed = host.rollbackScript("to-rollback", "运行失败")
        assertTrue(changed, "有上一版本时回滚应生效")
        val installed = store.loadIndex().sources.getValue("to-rollback")
        assertEquals("1.0.0", installed.activeVersion, "active 应切回上一版本")
        assertEquals(null, installed.previousVersion)
        assertEquals("运行失败", installed.lastError)
        assertEquals(listOf("to-rollback"), registry.released, "回滚后必须立即释放旧 context")
    }

    @Test
    fun rollbackWithoutPreviousVersionIsNoopAndDoesNotRelease() = runTest {
        installScript("no-previous")
        val changed = host.rollbackScript("no-previous", "boom")
        assertTrue(!changed, "无上一版本时回滚应失败")
        assertEquals(emptyList(), registry.released, "未回滚不应释放正在使用的 context")
        assertEquals(null, store.loadIndex().sources.getValue("no-previous").lastError)
    }
}
