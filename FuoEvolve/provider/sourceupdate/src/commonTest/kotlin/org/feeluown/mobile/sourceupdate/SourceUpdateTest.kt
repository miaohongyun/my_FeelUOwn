package org.feeluown.mobile.sourceupdate

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun entryFor(content: String, version: String): SourceCatalogEntry = SourceFixtures.catalogEntry(
    version = version,
    sha256 = CatalogSignatureVerifier.sha256Hex(content.encodeToByteArray()),
)

class SourceInstallTransactionTest {

    private suspend fun newStore(): Pair<SourceVersionStore, InMemorySourceFileStore> {
        val files = InMemorySourceFileStore()
        return SourceVersionStore(files) to files
    }

    @Test
    fun installsAndActivatesVersion() = runTest {
        val (store, _) = newStore()
        val transaction = SourceInstallTransaction(store, now = { 42L })
        val content = SourceFixtures.SCRIPT

        val outcome = transaction.install(entryFor(content, "1.0.0"), content, enable = true)
        val installed = assertIs<InstallOutcome.Installed>(outcome)
        assertEquals("1.0.0", installed.version)
        assertNull(installed.previousVersion)

        val index = store.loadIndex()
        val record = index.sources.getValue("fixture-source")
        assertEquals("1.0.0", record.activeVersion)
        assertTrue(record.enabled)
        assertEquals(42L, record.installedAtMillis)
        assertEquals(content, store.readActiveScript("fixture-source"))
    }

    @Test
    fun rejectsBadHashWithoutWritingAnything() = runTest {
        val (store, files) = newStore()
        val transaction = SourceInstallTransaction(store)
        val bad = SourceFixtures.catalogEntry(sha256 = "0".repeat(64))

        val outcome = transaction.install(bad, SourceFixtures.SCRIPT)
        assertIs<InstallOutcome.Rejected>(outcome)
        assertTrue(store.listVersions("fixture-source").isEmpty())
        assertNull(store.readActiveScript("fixture-source"))
        assertFalse(files.snapshot().keys.any { it.contains("fixture-source") })
    }

    @Test
    fun rejectsScriptBlockedByStaticPolicy() = runTest {
        val (store, _) = newStore()
        val transaction = SourceInstallTransaction(store)
        val content = "eval('1+1');"
        val outcome = transaction.install(entryFor(content, "1.0.0"), content)
        val rejected = assertIs<InstallOutcome.Rejected>(outcome)
        assertTrue(rejected.reason.contains("静态扫描"))
        assertNull(store.readActiveScript("fixture-source"))
    }

    @Test
    fun secondInstallKeepsPreviousVersionForRollback() = runTest {
        val (store, _) = newStore()
        val transaction = SourceInstallTransaction(store, now = { 1L })
        val v1 = SourceFixtures.SCRIPT
        val v2 = "$v1\n// v2"

        transaction.install(entryFor(v1, "1.0.0"), v1, enable = true)
        val second = transaction.install(entryFor(v2, "1.1.0"), v2)
        assertEquals("1.0.0", assertIs<InstallOutcome.Installed>(second).previousVersion)
        assertEquals(v2, store.readActiveScript("fixture-source"))

        assertTrue(transaction.rollback("fixture-source", "运行失败"))
        assertEquals(v1, store.readActiveScript("fixture-source"))
        assertEquals("运行失败", store.loadIndex().sources.getValue("fixture-source").lastError)
        // 回滚后仍保持启用状态，用户不需要重新打开开关
        assertTrue(store.loadIndex().sources.getValue("fixture-source").enabled)
    }

    @Test
    fun rollbackWithoutPreviousReturnsFalse() = runTest {
        val (store, _) = newStore()
        val transaction = SourceInstallTransaction(store)
        transaction.install(entryFor(SourceFixtures.SCRIPT, "1.0.0"), SourceFixtures.SCRIPT)
        assertFalse(transaction.rollback("fixture-source", "boom"))
    }

    @Test
    fun keepsOnlyTwoVersions() = runTest {
        val (store, _) = newStore()
        val transaction = SourceInstallTransaction(store)
        val versions = listOf("1.0.0" to SourceFixtures.SCRIPT, "1.1.0" to SourceFixtures.SCRIPT + "\n//1", "1.2.0" to SourceFixtures.SCRIPT + "\n//2")
        for ((version, content) in versions) {
            transaction.install(entryFor(content, version), content)
        }
        assertEquals(listOf("1.1.0", "1.2.0"), store.listVersions("fixture-source").sorted())
    }

    @Test
    fun reinstallSameVersionIsIdempotent() = runTest {
        val (store, _) = newStore()
        val transaction = SourceInstallTransaction(store)
        val entry = entryFor(SourceFixtures.SCRIPT, "1.0.0")
        assertIs<InstallOutcome.Installed>(transaction.install(entry, SourceFixtures.SCRIPT))
        assertIs<InstallOutcome.AlreadyInstalled>(transaction.install(entry, SourceFixtures.SCRIPT))
    }

    @Test
    fun removeDeletesFilesAndRecord() = runTest {
        val (store, _) = newStore()
        val transaction = SourceInstallTransaction(store)
        transaction.install(entryFor(SourceFixtures.SCRIPT, "1.0.0"), SourceFixtures.SCRIPT)
        transaction.remove("fixture-source")
        assertTrue(store.listVersions("fixture-source").isEmpty())
        assertFalse(store.loadIndex().sources.containsKey("fixture-source"))
    }
}

class SourceUpdatePolicyTest {

    private val hour = 60L * 60L * 1000L
    private val policy = SourceUpdatePolicy(intervalHours = 24)

    @Test
    fun firstRunAlwaysChecks() {
        assertTrue(policy.shouldCheck(SourceIndex(), nowMillis = 1_000L))
    }

    @Test
    fun respectsDailyInterval() {
        val index = SourceIndex(lastSuccessMillis = 10 * hour)
        assertFalse(policy.shouldCheck(index, nowMillis = 11 * hour))
        assertTrue(policy.shouldCheck(index, nowMillis = 34 * hour))
        assertTrue(policy.shouldCheck(index, nowMillis = 11 * hour, force = true))
    }

    @Test
    fun backoffGrowsWithFailures() {
        assertEquals(hour, policy.backoffMillis(1))
        assertEquals(4 * hour, policy.backoffMillis(2))
        assertEquals(12 * hour, policy.backoffMillis(3))
        assertEquals(24 * hour, policy.backoffMillis(4))
        assertEquals(24 * hour, policy.backoffMillis(9))
    }

    @Test
    fun failuresDelayNextCheck() {
        val index = SourceIndex(lastCheckMillis = 10 * hour, consecutiveFailures = 2)
        assertFalse(policy.shouldCheck(index, nowMillis = 11 * hour))
        assertTrue(policy.shouldCheck(index, nowMillis = 14 * hour))
    }

    @Test
    fun jitterStaysInsideWindowAndIsDeterministic() {
        val first = policy.jitterMillis(seed = 7L)
        val second = policy.jitterMillis(seed = 7L)
        assertEquals(first, second)
        assertTrue(first in 0 until policy.intervalMillis / 12)
    }
}

class SourceMirrorPolicyTest {

    @Test
    fun rejectsInsecureAndUnlistedMirrors() {
        val policy = SourceMirrorPolicy(
            mirrors = listOf("http://mirror.example.com/a", "https://evil.example.net/b", "https://ok.example.com/c"),
            allowlist = setOf("ok.example.com"),
        )
        assertEquals(listOf("https://ok.example.com/c"), policy.orderedMirrors(nowMillis = 0L))
        assertFalse(policy.isAllowed("http://ok.example.com/c"))
    }

    @Test
    fun opensCircuitAfterRepeatedFailuresAndRecovers() {
        val policy = SourceMirrorPolicy(mirrors = listOf("https://ok.example.com/c"))
        val url = "https://ok.example.com/c"
        policy.recordFailure(url, nowMillis = 1_000L, reason = "timeout")
        assertTrue(policy.isAvailable(url, nowMillis = 2_000L))
        policy.recordFailure(url, nowMillis = 3_000L, reason = "timeout")
        assertFalse(policy.isAvailable(url, nowMillis = 4_000L))
        assertFalse(policy.orderedMirrors(4_000L).contains(url))
        assertTrue(policy.isAvailable(url, nowMillis = 3_000L + 30L * 60L * 1000L))
        policy.recordSuccess(url, nowMillis = 5_000L)
        assertEquals(0, policy.snapshot()[url]?.failures)
    }
}

class SourceUpdateCoordinatorTest {

    private val now = 1_000L

    private fun coordinator(
        files: InMemorySourceFileStore = InMemorySourceFileStore(),
        bodies: Map<String, String> = mapOf(
            SourceFixtures.CATALOG_URL to SourceFixtures.CATALOG_JSON,
            "${SourceFixtures.CATALOG_URL}.sig" to SourceFixtures.CATALOG_SIGNATURE,
            SourceFixtures.SCRIPT_URL to SourceFixtures.SCRIPT,
        ),
        clock: () -> Long = { now },
        appVersion: String = "1.0.0",
        smokeTest: suspend (SourceCatalogEntry, String) -> String? = { _, _ -> null },
        verifier: CatalogSignatureVerifier = SourceFixtures.signedVerifier(),
    ): Triple<SourceUpdateCoordinator, SourceVersionStore, MapCatalogFetcher> {
        val fetcher = MapCatalogFetcher(bodies)
        val store = SourceVersionStore(files)
        val transaction = SourceInstallTransaction(store, now = clock)
        val updater = SourceCatalogUpdater(
            fetcher = fetcher,
            mirrors = SourceMirrorPolicy(mirrors = emptyList()),
            signatureVerifier = verifier,
            clock = clock,
        )
        val coordinator = SourceUpdateCoordinator(
            catalogUrls = listOf(SourceFixtures.CATALOG_URL),
            updater = updater,
            fetcher = fetcher,
            store = store,
            transaction = transaction,
            policy = SourceUpdatePolicy(intervalHours = 24),
            clock = clock,
            appVersion = appVersion,
            smokeTest = smokeTest,
        )
        return Triple(coordinator, store, fetcher)
    }

    @Test
    fun installsSignedCatalogEntry() = runTest {
        val (coordinator, store, fetcher) = coordinator()
        val report = coordinator.checkForUpdates(force = true)

        val installed = assertIs<UpdateReport.Installed>(report)
        assertEquals(listOf("fixture-source"), installed.scriptIds)
        assertTrue(installed.errors.isEmpty(), installed.errors.joinToString())

        val record = store.loadIndex().sources.getValue("fixture-source")
        assertEquals("1.0.0", record.activeVersion)
        assertTrue(record.enabled)
        assertEquals(42L, store.loadIndex().catalogVersion)
        assertEquals(SourceFixtures.SCRIPT, store.readActiveScript("fixture-source"))
        assertTrue(fetcher.requested.contains("${SourceFixtures.CATALOG_URL}.sig"))
        // 安装成功后应清空失败计数
        assertEquals(0, store.loadIndex().consecutiveFailures)
    }

    @Test
    fun rejectsCatalogWithBrokenSignature() = runTest {
        val bodies = mapOf(
            SourceFixtures.CATALOG_URL to SourceFixtures.CATALOG_JSON.replace("42", "43"),
            "${SourceFixtures.CATALOG_URL}.sig" to SourceFixtures.CATALOG_SIGNATURE,
            SourceFixtures.SCRIPT_URL to SourceFixtures.SCRIPT,
        )
        val (coordinator, store, _) = coordinator(bodies = bodies)
        val report = coordinator.checkForUpdates(force = true)
        val failed = assertIs<UpdateReport.Failed>(report)
        assertTrue(failed.attempts.any { it.contains("签名") })
        assertTrue(store.loadIndex().sources.isEmpty())
        assertEquals(1, store.loadIndex().consecutiveFailures)
    }

    @Test
    fun skipsWhenCheckedRecentlyAndBacksOffAfterFailure() = runTest {
        var clock = now
        val (coordinator, store, _) = coordinator(clock = { clock })
        assertIs<UpdateReport.Installed>(coordinator.checkForUpdates(force = true))

        clock += 60L * 60L * 1000L
        assertIs<UpdateReport.Skipped>(coordinator.checkForUpdates())

        // 网络全挂：第一次失败后进入 1 小时退避，紧接着再查会被跳过
        val broken = coordinator(bodies = emptyMap(), clock = { clock })
        val first = broken.first.checkForUpdates(force = true)
        assertIs<UpdateReport.Failed>(first)
        assertEquals(1, broken.second.loadIndex().consecutiveFailures)
        assertIs<UpdateReport.Skipped>(broken.first.checkForUpdates())
        assertTrue(store.loadIndex().sources.isNotEmpty())
    }

    @Test
    fun rollsBackWhenSmokeTestFails() = runTest {
        val (coordinator, store, _) = coordinator(smokeTest = { _, _ -> "初始化超时" })
        val report = coordinator.checkForUpdates(force = true)
        val result = assertIs<UpdateReport.Installed>(report)
        assertTrue(result.scriptIds.isEmpty())
        assertTrue(result.errors.any { it.contains("冒烟测试") })
        // 首次安装失败且无可回滚版本 → 不应留下 active 记录
        assertFalse(store.loadIndex().sources.containsKey("fixture-source"))
    }

    @Test
    fun skipsEntryThatIsNotApprovedForAutoInstall() = runTest {
        val manualCatalog = """
            {"schemaVersion":1,"catalogVersion":5,"signatureAlgorithm":"Ed25519","keyId":"test-key","sources":[
              {"id":"manual-source","name":"Manual","version":"1.0.0","scriptUrl":"https://catalog.example.com/scripts/fixture.js",
               "sha256":"${SourceFixtures.SCRIPT_SHA256}","reviewStatus":"APPROVED","autoInstall":false}
            ]}
        """.trimIndent()
        // 该目录用测试私钥重新签名过（见 config/source-catalog/tools），这里直接用宽松策略验证筛选逻辑
        val permissive = CatalogSignatureVerifier(requireSignature = false)
        val (coordinator, store, _) = coordinator(
            bodies = mapOf(SourceFixtures.CATALOG_URL to manualCatalog),
            verifier = permissive,
        )
        val report = coordinator.checkForUpdates(force = true)
        assertEquals(UpdateReport.UpToDate(5L), report)
        assertTrue(store.loadIndex().sources.isEmpty())
    }

    @Test
    fun respectsMinAppVersion() = runTest {
        val gated = SourceFixtures.CATALOG_JSON.replace(
            "\"catalogVersion\":42",
            "\"catalogVersion\":42,\"minAppVersion\":\"9.9.9\"",
        )
        // 签名不再匹配 → 用宽松策略把关注点放在版本门禁本身
        val (coordinator, store, _) = coordinator(
            bodies = mapOf(SourceFixtures.CATALOG_URL to gated, SourceFixtures.SCRIPT_URL to SourceFixtures.SCRIPT),
            verifier = CatalogSignatureVerifier(requireSignature = false),
            appVersion = "1.0.0",
        )
        val report = coordinator.checkForUpdates(force = true)
        val skipped = assertIs<UpdateReport.Skipped>(report)
        assertTrue(skipped.reason.contains("9.9.9"))
        assertTrue(store.loadIndex().sources.isEmpty())
    }
}

class CompareVersionsTest {

    @Test
    fun comparesSemanticVersions() {
        assertTrue(compareVersions("1.0.0", "0.9.0") > 0)
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertTrue(compareVersions("0.9.0", "0.9.1") < 0)
        assertTrue(compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(compareVersions("0.9.0-beta1", "0.9.0") == 0)
    }
}
