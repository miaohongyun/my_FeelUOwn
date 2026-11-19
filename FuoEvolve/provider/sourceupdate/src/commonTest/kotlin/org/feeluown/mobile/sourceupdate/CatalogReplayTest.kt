package org.feeluown.mobile.sourceupdate

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P1：目录反重放 / 版本单调性验证。
 *
 * 说明：签名验证由 SourceUpdateTest 的既有用例覆盖（真实 Ed25519 fixture）。
 * 本文件用 `requireSignature=false` 的 verifier 构造任意版本目录，专注验证
 * catalogVersion 单调性、同版本内容变化、镜像版本选择——这些逻辑与签名验证正交。
 */
class CatalogReplayTest {

    /** 生成结构合法、可解析的目录 JSON（仅变版本与 notes）。 */
    private fun catalogJson(version: Long, note: String = "n") = """
        {"schemaVersion":1,"catalogVersion":$version,"signatureAlgorithm":"Ed25519","keyId":"test-key","sources":[{"id":"fixture-source","name":"Fixture Source","version":"1.0.0","scriptUrl":"https://catalog.example.com/scripts/fixture.js","sha256":"${SourceFixtures.SCRIPT_SHA256}","reviewStatus":"APPROVED","license":"MIT","autoInstall":true,"autoEnable":true,"allowedHosts":["api.example.com"],"platforms":["kw"],"capabilities":["musicUrl"],"notes":"$note"}]}
    """.trimIndent()

    /** 测试专用：不要求签名，便于构造多版本目录。 */
    private fun unsignedVerifier() = CatalogSignatureVerifier(
        trustedKeys = emptyMap(),
        requireSignature = false,
    )

    private val bodiesForV42 = mapOf(
        SourceFixtures.CATALOG_URL to catalogJson(42),
        SourceFixtures.SCRIPT_URL to SourceFixtures.SCRIPT,
    )

    private fun setup(
        bodies: Map<String, String> = bodiesForV42,
        mirrors: List<String> = emptyList(),
        files: InMemorySourceFileStore = InMemorySourceFileStore(),
    ): Triple<SourceUpdateCoordinator, SourceVersionStore, MapCatalogFetcher> {
        val fetcher = MapCatalogFetcher(bodies)
        val store = SourceVersionStore(files)
        val transaction = SourceInstallTransaction(store, now = { 1L }, smokeTest = { _, _ -> null })
        val updater = SourceCatalogUpdater(
            fetcher = fetcher,
            mirrors = SourceMirrorPolicy(mirrors = mirrors, allowlist = SourceFixtures.TEST_ALLOWLIST),
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
        return Triple(coordinator, store, fetcher)
    }

    @Test
    fun `first install accepts current signed catalog`() = runTest {
        val (coordinator, store, _) = setup()
        val report = assertIs<UpdateReport.Installed>(coordinator.checkForUpdates(force = true))
        assertEquals(42L, report.catalogVersion)
        assertEquals(42L, store.loadIndex().catalogVersion)
        assertTrue(store.loadIndex().acceptedCatalogSha256!!.isNotBlank())
    }

    @Test
    fun `rejects older catalog replay and keeps index untouched`() = runTest {
        val (coordinator, store, _) = setup()
        assertIs<UpdateReport.Installed>(coordinator.checkForUpdates(force = true))

        // 模拟镜像/攻击者提供旧目录 v41。
        val files = InMemorySourceFileStore()
        val (coordinator2, store2, _) = setup(
            bodies = mapOf(
                SourceFixtures.CATALOG_URL to catalogJson(41),
                SourceFixtures.SCRIPT_URL to SourceFixtures.SCRIPT,
            ),
            files = files,
        )
        // 把已接受版本写入同一 store（模拟本地已锚定 42）。
        store2.saveIndex(store.loadIndex())
        val report2 = coordinator2.checkForUpdates(force = true)
        assertIs<UpdateReport.Failed>(report2)
        assertTrue(report2.reason.contains("重放") || report2.reason.contains("更旧"), report2.reason)
        assertEquals(42L, store2.loadIndex().catalogVersion, "旧目录不得回退本地版本")
        assertTrue(store2.loadIndex().sources.isNotEmpty(), "旧目录不得破坏已装脚本")
    }

    @Test
    fun `rejects same catalog version with mutated content`() = runTest {
        val (coordinator, store, _) = setup()
        assertIs<UpdateReport.Installed>(coordinator.checkForUpdates(force = true))

        val (coordinator2, store2, _) = setup(
            bodies = mapOf(
                SourceFixtures.CATALOG_URL to catalogJson(42, note = "mutated"),
                SourceFixtures.SCRIPT_URL to SourceFixtures.SCRIPT,
            ),
        )
        store2.saveIndex(store.loadIndex())
        val report2 = coordinator2.checkForUpdates(force = true)
        assertIs<UpdateReport.Failed>(report2)
        assertTrue(report2.reason.contains("同版本内容变化"), report2.reason)
        assertEquals(42L, store2.loadIndex().catalogVersion)
        assertTrue(store2.loadIndex().sources.isNotEmpty())
    }

    @Test
    fun `accepts newer catalog version with changed content`() = runTest {
        val (coordinator, store, _) = setup()
        assertIs<UpdateReport.Installed>(coordinator.checkForUpdates(force = true))

        val (coordinator2, store2, _) = setup(
            bodies = mapOf(
                SourceFixtures.CATALOG_URL to catalogJson(43, note = "changed"),
                SourceFixtures.SCRIPT_URL to SourceFixtures.SCRIPT,
            ),
        )
        store2.saveIndex(store.loadIndex())
        val report2 = coordinator2.checkForUpdates(force = true)
        // 脚本内容未变时安装被跳过 → UpToDate；无论哪种，目录版本都必须前进到 43。
        assertTrue(report2 is UpdateReport.Installed || report2 is UpdateReport.UpToDate, "$report2")
        assertEquals(43L, store2.loadIndex().catalogVersion)
    }

    @Test
    fun `chooses highest catalog version across primary and mirrors`() = runTest {
        val mirror = "https://mirror.example.com/source-catalog.json"
        val bodies = mapOf(
            SourceFixtures.CATALOG_URL to catalogJson(42),
            "$mirror" to catalogJson(45),
            SourceFixtures.SCRIPT_URL to SourceFixtures.SCRIPT,
        )
        val (coordinator, store, fetcher) = setup(bodies = bodies, mirrors = listOf(mirror))
        val report = assertIs<UpdateReport.Installed>(coordinator.checkForUpdates(force = true))
        assertEquals(45L, report.catalogVersion, "应跨镜像选版本最高者")
        assertEquals(45L, store.loadIndex().catalogVersion)
        assertTrue(fetcher.requested.contains(mirror))
    }

    @Test
    fun `mirror older than primary never downgrades`() = runTest {
        val mirror = "https://mirror.example.com/source-catalog.json"
        val bodies = mapOf(
            SourceFixtures.CATALOG_URL to catalogJson(44),
            "$mirror" to catalogJson(40),
            SourceFixtures.SCRIPT_URL to SourceFixtures.SCRIPT,
        )
        val (coordinator, store, _) = setup(bodies = bodies, mirrors = listOf(mirror))
        val report = assertIs<UpdateReport.Installed>(coordinator.checkForUpdates(force = true))
        assertEquals(44L, report.catalogVersion)
        assertEquals(44L, store.loadIndex().catalogVersion)
    }

    @Test
    fun `updater rejects candidate below minimum version`() = runTest {
        val fetcher = MapCatalogFetcher(mapOf(SourceFixtures.CATALOG_URL to catalogJson(10)))
        val updater = SourceCatalogUpdater(
            fetcher = fetcher,
            mirrors = SourceMirrorPolicy(mirrors = emptyList(), allowlist = SourceFixtures.TEST_ALLOWLIST),
            signatureVerifier = unsignedVerifier(),
            clock = { 1L },
        )
        val load = updater.load(SourceFixtures.CATALOG_URL, minCatalogVersion = 42)
        assertIs<CatalogLoad.Failed>(load)
        assertTrue(load.attempts.any { "重放" in it }, load.attempts.joinToString())
    }
}
