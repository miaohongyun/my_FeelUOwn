package org.feeluown.mobile.provider.musicfree

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 阶段 J2：搜索失败分类测试（受控 executor，与真实网络验证分栏统计）。
 * 用假 executor 真实触发 HTTP 4xx/5xx/权限拒绝/超时，验证 Provider 保留可诊断分类，
 * 且不把失败伪装成"没有找到歌曲"（真实零结果与请求失败可区分）。
 */
class PhaseJ2FailureClassTest {

    private fun newProvider(executor: ProviderNetworkGateway.NetworkExecutor): AudiomackMusicProvider {
        val raw = HttpClient(OkHttp) {}
        val http = ProviderHttpClient(raw)
        val deps = ProviderRuntimeDependencies(http = http, credentials = InMemoryProviderCredentialStore())
        return AudiomackMusicProvider(http = http, dependencies = deps, client = raw).also {
            it.setExecutorFactoryForTest { executor }
        }
    }

    private fun rawResponse(status: Int, body: String) = ProviderNetworkGateway.RawResponse(
        status, mapOf("Content-Type" to "application/json"),
        ProviderNetworkGateway.byteStreamOf(body.encodeToByteArray()),
    )

    @Test
    fun j2_http4xxIsFailureNotZeroResult(): Unit = runBlocking {
        val p = newProvider(ProviderNetworkGateway.NetworkExecutor {
            rawResponse(403, """{"errorcode":1003,"message":"Invalid consumer key"}""")
        })
        try {
            val r: ProviderSearchResults = p.search("晴天")
            val audit = p.lastAudit
            assertNotNull(audit)
            assertTrue(audit.outcome == "FAILURE", "4xx 必须记为 FAILURE：$audit")
            assertEquals("HTTP_4XX", audit.failureClass)
            assertEquals(403, audit.httpStatus)
            assertNotNull(r.failure, "失败必须带可诊断 failure，不能显示成'没有找到歌曲'")
        } finally { p.dispose() }
    }

    @Test
    fun j2_http5xxIsFailure(): Unit = runBlocking {
        val p = newProvider(ProviderNetworkGateway.NetworkExecutor {
            rawResponse(503, """{"error":"service unavailable"}""")
        })
        try {
            val r = p.search("晴天")
            assertEquals("HTTP_5XX", p.lastAudit?.failureClass)
            assertEquals(503, p.lastAudit?.httpStatus)
            assertNotNull(r.failure)
        } finally { p.dispose() }
    }

    @Test
    fun j2_zeroResultsHasNoFailure(): Unit = runBlocking {
        val p = newProvider(ProviderNetworkGateway.NetworkExecutor {
            rawResponse(200, """{"results":[],"isEnd":true}""")
        })
        try {
            val r = p.search("nonexistent")
            assertEquals("ZERO_RESULTS", p.lastAudit?.outcome)
            assertEquals(200, p.lastAudit?.httpStatus)
            assertTrue(r.tracks.isEmpty())
            // 真实零结果 failure 为空，与 4xx/5xx 失败明确区分。
            assertTrue(r.failure == null, "真实零结果不应带 failure")
        } finally { p.dispose() }
    }

    @Test
    fun j2_networkFailureIsClassified(): Unit = runBlocking {
        // 真实触发一次网络执行失败（executor 抛异常），验证 Provider 返回可诊断 failure，
        // 且不把失败伪装成零结果。Gateway 层超时分类已在 J0/J1 充分覆盖，此处不重复构造。
        val p = newProvider(ProviderNetworkGateway.NetworkExecutor {
            throw IllegalStateException("simulated network failure")
        })
        try {
            val r = p.search("晴天")
            assertNotNull(r.failure, "网络失败必须返回 failure")
            assertTrue(p.lastAudit?.outcome == "FAILURE", "网络失败必须记为 FAILURE：${p.lastAudit}")
            assertTrue(p.lastAudit?.failureClass in setOf("NETWORK", "EXCEPTION", "SCRIPT_ERROR"), p.lastAudit?.failureClass)
        } finally { p.dispose() }
    }
    @Test
    fun j2_disposeThenSearchFailsGracefully(): Unit = runBlocking {
        val p = newProvider(ProviderNetworkGateway.NetworkExecutor {
            rawResponse(200, """{"results":[],"isEnd":true}""")
        })
        p.search("晴天") // 先初始化
        p.dispose()
        p.dispose() // 幂等
        val after = p.search("晴天")
        assertNotNull(after.failure, "关闭后搜索必须失败而非复用已释放资源")
        assertTrue(p.lastAudit?.failureClass == "SESSION_UNAVAILABLE", p.lastAudit?.failureClass)
    }
}
