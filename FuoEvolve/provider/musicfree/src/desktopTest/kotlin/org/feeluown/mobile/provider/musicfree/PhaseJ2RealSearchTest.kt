package org.feeluown.mobile.provider.musicfree

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.feeluown.mobile.ProviderFailureKind
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 阶段 J2 真实网络搜索验证（REAL_SEARCH / REAL_RESULT_MAPPING）。
 *
 * 真实执行 audiomack.js 的 search 函数 → 宿主 axios 桥 → ProviderNetworkGateway
 * → Ktor OkHttp（经本机 7897 代理，与 G3 取证方式一致）→ api.audiomack.com
 * → parseMusicFreeSearch → MusicTrack/ProviderSearchResults。
 *
 * 不替换 Mock、不伪造结果：服务端返回 401/不可达/地域限制时如实记录为失败/NOT_RUN。
 */
class PhaseJ2RealSearchTest {

    /** 构造走本机 7897 代理的 executor（生产由统一 client 注入，这里仅为取证注入）。 */
    private fun proxiedExecutor(): ProviderNetworkGateway.NetworkExecutor {
        val builder = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7897)))
        val client = HttpClient(OkHttp) { engine { preconfigured = builder.build() } }
        return client.asProviderNetworkExecutor()
    }

    private fun newProvider(): AudiomackMusicProvider {
        val raw = HttpClient(OkHttp) {}
        val http = ProviderHttpClient(raw)
        val deps = ProviderRuntimeDependencies(http = http, credentials = InMemoryProviderCredentialStore())
        return AudiomackMusicProvider(http = http, dependencies = deps, client = raw).also {
            it.setExecutorFactoryForTest { proxiedExecutor() }
        }
    }

    private fun isProxyAvailable(): Boolean = try {
        val s = java.net.Socket()
        s.connect(InetSocketAddress("127.0.0.1", 7897), 2000)
        s.close(); true
    } catch (e: Exception) { false }

    @Test
    fun j2_realSearchCommonChineseSong(): Unit = runBlocking {
        if (!isProxyAvailable()) {
            println("J2_REAL_SEARCH=NOT_RUN reason=本机 7897 代理不可用（如实标记，不伪造成功）")
            return@runBlocking
        }
        val p = newProvider()
        try {
            val r: ProviderSearchResults = p.search("晴天")
            val audit = p.lastAudit
            println("J2_REAL_SEARCH keyword=晴天 domain=${audit?.domain} http=${audit?.httpStatus} " +
                "bytes=${audit?.responseBytes} outcome=${audit?.outcome} cls=${audit?.failureClass} " +
                "reason=${audit?.failureReason} tracks=${r.tracks.size}")
            assertNotNull(audit, "每次真实搜索必须产生审计记录")
            assertEquals("api.audiomack.com", audit.domain)
            assertEquals("GET", audit.method)
            assertEquals(443, audit.port)
            // 审计必须可追溯：要么拿到真实结果，要么保留失败分类——不允许静默成功。
            assertTrue(audit.outcome in setOf("SUCCESS", "ZERO_RESULTS", "FAILURE", "NOT_RUN"), audit.outcome)
            // 成功时字段映射必须正确、原始 id 保留。
            if (audit.outcome == "SUCCESS") {
                assertTrue(r.tracks.isNotEmpty(), "SUCCESS 必须有真实结果")
                val first = r.tracks.first()
                println("J2_REAL_MAPPING id=${first.id} originalId=${first.originalId} " +
                    "title=${first.title} artist=${first.artists} album=${first.album} cover=${first.coverUrl?.take(40)}")
                assertTrue(first.originalId!!.isNotBlank(), "原始来源 id 不得为空")
                assertTrue(first.title.isNotBlank(), "歌名不得为空")
                assertTrue(first.artists.isNotBlank(), "歌手不得为空")
                assertEquals("Provider", first.sourceType.name)
                assertEquals("mf-audiomack", first.providerId?.substringBefore(":") ?: first.source)
            }
            r.tracks.forEach { t ->
                assertTrue(t.id.startsWith("mf-audiomack:"), "统一 id 必须带来源前缀：${t.id}")
                assertNotNull(t.originalId, "必须保留插件原始 id")
                assertEquals("mf-audiomack", t.source)
                assertTrue(t.providerTags.contains("audiomack"), "结果必须标明来源")
            }
        } finally {
            p.dispose()
        }
    }

    @Test
    fun j2_realSearchArtistAndZeroResult(): Unit = runBlocking {
        if (!isProxyAvailable()) {
            println("J2_REAL_SEARCH_ZERO=NOT_RUN reason=本机 7897 代理不可用")
            return@runBlocking
        }
        val p = newProvider()
        try {
            val artist = p.search("周杰伦")
            println("J2_REAL_SEARCH keyword=周杰伦 outcome=${p.lastAudit?.outcome} http=${p.lastAudit?.httpStatus} tracks=${artist.tracks.size}")
            val zero = p.search("nonexistent_xyz_9981_real_j2")
            println("J2_REAL_SEARCH_ZERO keyword=nonexistent outcome=${p.lastAudit?.outcome} http=${p.lastAudit?.httpStatus} tracks=${zero.tracks.size}")
            // 真实零结果与请求失败必须可区分：失败时 failure 非空，真实零结果 failure 为空。
            if (p.lastAudit?.outcome == "ZERO_RESULTS") {
                assertTrue(zero.failure == null, "真实零结果不应带 failure")
            } else {
                assertNotNull(zero.failure, "请求失败必须带可诊断 failure")
            }
        } finally {
            p.dispose()
        }
    }

    @Test
    fun j2_gatewayDeniedWhenPlaybackDomainRequested(): Unit = runBlocking {
        // 反证：即使拿到播放域名 URL，也不得被本 Provider 的搜索权限放行。
        val p = newProvider()
        try {
            p.initialize()
            val decision = MusicFreePluginPolicy.forPlugin(
                MusicFreePluginRegistry.find(AudiomackMusicProvider.ID)!!,
                AudiomackMusicProvider.ID.let { MusicFreePluginCompatVerification.shaOfScriptBytes(AudiomackEmbeddedScript.scriptBytes) },
            )
            assertTrue(decision is MusicFreePluginPolicy.PolicyDecision.Authorized)
            val reason = MusicFreePluginPolicy.rejectionReason(decision, "https://music.audiomack.com/signed")
            assertTrue(reason != null, "播放域名必须被拒：$reason")
            assertTrue(reason.contains("域名"), reason)
        } finally {
            p.dispose()
        }
    }
}
