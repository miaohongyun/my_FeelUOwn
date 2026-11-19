package org.feeluown.mobile.sourceupdate

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P1：目录索引损坏后的反重放边界。
 *
 * 审查发现：原 loadIndex() 在索引文件解析失败时 getOrElse { SourceIndex() } 静默回退默认索引，
 * 会被判为首次安装，从而丢失反重放锚点、接受旧的签名目录。本文件验证修复后的行为：
 * - 只有"主索引与独立锚点都不存在"才是一次真正首次安装；
 * - 主索引损坏/缺失但锚点存在时从锚点恢复，且不会被判首次安装；
 * - 恢复态下旧版本、同版本目录都被拒绝，只有更高版本能通过（重新锚定）。
 */
class CatalogIndexCorruptionTest {

    private fun catalogJson(version: Long, note: String = "n") = """
        {"schemaVersion":1,"catalogVersion":$version,"signatureAlgorithm":"Ed25519","keyId":"test-key","sources":[{"id":"fixture-source","name":"Fixture Source","version":"1.0.0","scriptUrl":"https://catalog.example.com/scripts/fixture.js","sha256":"${SourceFixtures.SCRIPT_SHA256}","reviewStatus":"APPROVED","license":"MIT","autoInstall":true,"autoEnable":true,"allowedHosts":["api.example.com"],"platforms":["kw"],"capabilities":["musicUrl"],"notes":"$note"}]}
    """.trimIndent()

    private fun unsignedVerifier() = CatalogSignatureVerifier(
        trustedKeys = emptyMap(),
        requireSignature = false,
    )

    private fun setup(
        body: String = catalogJson(42),
        files: InMemorySourceFileStore = InMemorySourceFileStore(),
    ): Triple<SourceUpdateCoordinator, SourceVersionStore, InMemorySourceFileStore> {
        val bodies = mapOf(
            SourceFixtures.CATALOG_URL to body,
            SourceFixtures.SCRIPT_URL to SourceFixtures.SCRIPT,
        )
        val fetcher = MapCatalogFetcher(bodies)
        val store = SourceVersionStore(files)
        val transaction = SourceInstallTransaction(store, now = { 1L }, smokeTest = { _, _ -> null })
        val updater = SourceCatalogUpdater(
            fetcher = fetcher,
            mirrors = SourceMirrorPolicy(mirrors = emptyList(), allowlist = SourceFixtures.TEST_ALLOWLIST),
            signatureVerifier = unsignedVerifier(),
            clock = { 1L },
        )
        val coordinator = SourceUpdateCoordinator(
            catalogUrls = listOf(SourceFixtures.CATALOG_URL),
            updater = updater,
            fetcher = fetcher,
            store = store,
            transaction = transaction,
            policy = SourceUpdatePolicy(intervalHours = 24),
            clock = { 1L },
            appVersion = "1.0.0",
        )
        return Triple(coordinator, store, files)
    }

    /** 走一次真实更新，使 store 同时写入 index.json 与独立锚点。 */
    private suspend fun installAt(coordinator: SourceUpdateCoordinator, store: SourceVersionStore): Long {
        val report = assertIs<UpdateReport.Installed>(coordinator.checkForUpdates(force = true))
        assertEquals(report.catalogVersion, store.loadIndex().catalogVersion)
        return report.catalogVersion
    }

    @Test
    fun `fresh install with no index and no anchor is first install`() = runTest {
        val (_, store, _) = setup()
        val index = store.loadIndex()
        assertTrue(index.isFirstInstall, "索引与锚点都不存在才允许判首次安装")
        assertFalse(index.recoveredFromAnchor)
        assertEquals(0L, index.catalogVersion)
    }

    @Test
    fun `successful update writes independent anchor`() = runTest {
        val (coordinator, store, files) = setup()
        installAt(coordinator, store)
        val anchorRaw = files.readText(SOURCE_ANCHOR_PATH)
        assertTrue(anchorRaw != null, "成功接受目录后必须写入独立锚点")
        val anchor = store.loadIndex()
        assertEquals(42L, anchor.catalogVersion)
    }

    @Test
    fun `corrupted index with intact anchor recovers version and is not first install`() = runTest {
        val (coordinator, store, files) = setup()
        installAt(coordinator, store)
        // 模拟 index.json 损坏（截断/乱码），锚点保持完好。
        files.writeText("sources/index.json", "{ this is not valid json ")
        val index = store.loadIndex()
        assertTrue(index.recoveredFromAnchor, "损坏后应标记从锚点恢复")
        assertEquals(42L, index.catalogVersion, "必须从锚点恢复已接受版本")
        assertFalse(index.isFirstInstall, "绝不能把损坏索引误判为首次安装")
    }

    @Test
    fun `missing index with intact anchor recovers version and is not first install`() = runTest {
        val (coordinator, store, files) = setup()
        installAt(coordinator, store)
        files.delete("sources/index.json")
        val index = store.loadIndex()
        assertTrue(index.recoveredFromAnchor)
        assertEquals(42L, index.catalogVersion)
        assertFalse(index.isFirstInstall)
    }

    @Test
    fun `recovered index rejects older catalog replay`() = runTest {
        val (coordinator, store, files) = setup()
        installAt(coordinator, store)
        files.writeText("sources/index.json", "CORRUPT")

        // 换一个提供旧版本 v41 的协调器（锚点仍在 files 中）。
        val (oldCoordinator, _, _) = setup(body = catalogJson(41), files = files)
        val report = oldCoordinator.checkForUpdates(force = true)
        assertIs<UpdateReport.Failed>(report)
        assertTrue(report.reason.contains("重放") || report.reason.contains("锚点"), "应明确拒绝旧目录: ${report.reason}")
        assertEquals(42L, store.loadIndex().catalogVersion, "拒绝后锚点版本不得变化")
    }

    @Test
    fun `recovered index rejects same version catalog`() = runTest {
        val (coordinator, store, files) = setup()
        installAt(coordinator, store)
        files.writeText("sources/index.json", "CORRUPT")

        // 同版本 v42、不同内容：恢复态下要求严格大于锚点，故同版本一律拒绝。
        val (sameCoordinator, _, _) = setup(body = catalogJson(42, "changed"), files = files)
        val report = sameCoordinator.checkForUpdates(force = true)
        assertIs<UpdateReport.Failed>(report)
        assertTrue(report.reason.contains("锚点"), "应明确拒绝不高于锚点版本: ${report.reason}")
    }

    @Test
    fun `recovered index accepts strictly newer catalog and reanchors`() = runTest {
        val (coordinator, store, files) = setup()
        installAt(coordinator, store)
        files.writeText("sources/index.json", "CORRUPT")

        val (newCoordinator, newStore, _) = setup(body = catalogJson(43), files = files)
        assertIs<UpdateReport.Installed>(newCoordinator.checkForUpdates(force = true))
        val index = newStore.loadIndex()
        assertEquals(43L, index.catalogVersion, "更高版本应被接受并重新锚定")
        assertFalse(index.recoveredFromAnchor, "成功重锚后应恢复正常态")
        assertFalse(index.isFirstInstall)
    }

    @Test
    fun `both index and anchor corrupt does not silently become first install`() = runTest {
        val (coordinator, store, files) = setup()
        installAt(coordinator, store)
        files.writeText("sources/index.json", "CORRUPT")
        files.writeText(SOURCE_ANCHOR_PATH, "CORRUPT")

        val index = store.loadIndex()
        // 锚点也坏了没有可信版本号，但不能静默当首次安装——标记异常，版本保守取 0。
        assertTrue(index.recoveredFromAnchor, "主索引损坏就应标记异常恢复态")
        assertFalse(index.isFirstInstall, "损坏态不得判首次安装，即便锚点也损坏")
    }
}
