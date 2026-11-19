package org.feeluown.mobile.sourceupdate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 解析器目录（设计文档 5.2 / 10）。
 *
 * 首版不接任何第三方解析器，但目录协议本身要能拒绝明显不合规的条目：
 * 非 http(s)、自托管提示、超时越界、重复 id。
 */
class ResolverCatalogTest {

    private fun catalog(vararg entries: String) = """
        {"schemaVersion":1,"resolvers":[${entries.joinToString(",")}]}
    """.trimIndent()

    private fun entry(
        id: String = "jamendo",
        kind: String = "open-catalog",
        baseUrl: String = "https://api.jamendo.com/v3.0",
        timeoutMs: Int = 8_000,
        allowedHosts: String = """["api.jamendo.com"]""",
    ) = """
        {"id":"$id","name":"$id","kind":"$kind","baseUrl":"$baseUrl","timeoutMs":$timeoutMs,"allowedHosts":$allowedHosts}
    """.trimIndent()

    @Test
    fun acceptsOpenCatalogWithPriorityOrdering() {
        val result = assertIs<ResolverValidation.Valid>(
            ResolverCatalogParser.parse(
                catalog(
                    entry(id = "slow", timeoutMs = 9_000).replace("\"timeoutMs\":9000", "\"priority\":200,\"timeoutMs\":9000"),
                    entry(id = "fast"),
                ),
            ),
        )
        // priority 小者优先；默认 100 排在 200 前面。
        assertEquals(listOf("fast", "slow"), result.entries.map { it.id })
        assertEquals(ResolverKind.OPEN_CATALOG, result.entries.first().resolverKind)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun rejectsUnsupportedKindAndScheme() {
        val badKind = ResolverCatalogParser.parse(catalog(entry(kind = "browser-scrape")))
        assertIs<ResolverValidation.Invalid>(badKind)

        val badScheme = ResolverCatalogParser.parse(catalog(entry(baseUrl = "ftp://example.com/api")))
        assertIs<ResolverValidation.Invalid>(badScheme)
    }

    @Test
    fun rejectsDuplicateIdsAndOutOfRangeTimeout() {
        assertIs<ResolverValidation.Invalid>(
            ResolverCatalogParser.parse(catalog(entry(), entry())),
        )
        assertIs<ResolverValidation.Invalid>(
            ResolverCatalogParser.parse(catalog(entry(timeoutMs = 120_000))),
        )
    }

    @Test
    fun warnsAboutSelfHostedAndMissingHosts() {
        val result = assertIs<ResolverValidation.Valid>(
            ResolverCatalogParser.parse(
                catalog(entry(kind = "self-hosted", allowedHosts = "[]")),
            ),
        )
        assertEquals(2, result.warnings.size)
        assertTrue(result.warnings.any { it.contains("自托管") })
        assertTrue(result.warnings.any { it.contains("allowedHosts") })
    }

    @Test
    fun rejectsNonJsonAndUnknownSchema() {
        assertIs<ResolverValidation.Invalid>(ResolverCatalogParser.parse("not json"))
        assertIs<ResolverValidation.Invalid>(
            ResolverCatalogParser.parse("""{"schemaVersion":2,"resolvers":[]}"""),
        )
    }

    @Test
    fun healthMonitorTripsAfterThresholdAndRecoversAfterCooldown() {
        val monitor = ResolverHealthMonitor(failureThreshold = 3, circuitMillis = 1_000L)
        assertTrue(monitor.isAvailable("jamendo", 0L))

        monitor.recordFailure("jamendo", 0L, "dns")
        monitor.recordFailure("jamendo", 10L, "tls")
        assertTrue(monitor.isAvailable("jamendo", 20L), "未达阈值不应熔断")

        monitor.recordFailure("jamendo", 30L, "timeout")
        assertTrue(!monitor.isAvailable("jamendo", 40L), "达到阈值必须熔断")

        // 冷却结束自动恢复；成功一次就把计数清零。
        assertTrue(monitor.isAvailable("jamendo", 30L + 1_000L))
        monitor.recordSuccess("jamendo")
        assertEquals(ResolverHealth(), monitor.snapshot()["jamendo"])
    }
}
