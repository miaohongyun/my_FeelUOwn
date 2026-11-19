package org.feeluown.mobile.provider.lx

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Provider 层的多平台搜索行为验证（SIMULATED）。
 */
class LxMultiPlatformProviderTest {

    /** 脚本自述支持 kw/kg/tx，但目录只批准 kw/kg（tx 未批准）。 */
    private val script = """
        lx.on('request', function () { return Promise.resolve('https://cdn.example.com/x.mp3') });
        lx.send('inited', {
          status: true,
          sources: {
            kw: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] },
            kg: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] },
            tx: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] }
          }
        });
    """.trimIndent()

    private fun offlineClient() = HttpClient(MockEngine { error("搜索测试不应发起播放请求") })

    private fun installedScript(platforms: List<String>) = LxInstalledScript(
        id = "multi", name = "Multi", version = "1.0.0", source = script,
        allowedHosts = listOf("api.example.com"), platforms = platforms,
    )

    private fun source(vararg scripts: LxInstalledScript) = object : LxInstalledScriptSource {
        override suspend fun scripts(): List<LxInstalledScript> = scripts.toList()
    }

    private fun searcher(platform: String, id: String) = object : LxSongSearcher {
        override val supportedPlatforms = setOf(platform)
        override suspend fun search(keyword: String, p: String, limit: Int) =
            listOf(LxSongHit(p, id, "晴天", "周杰伦", "叶惠美", 269, mapOf("platformRawId" to id)))
    }

    @Test
    fun `provider searches each registered platform with its own id mapping`() = runTest {
        val registry = LxSongSearchRegistry(listOf(
            searcher("kw", "KW-1"), searcher("kg", "KG-1"), searcher("tx", "TX-1"),
        ))
        val provider = LxScriptMusicProvider(
            providerCredentials = InMemoryProviderCredentialStore(),
            scriptSource = source(installedScript(listOf("kw", "kg", "tx"))),
            httpClient = offlineClient(),
            searchRegistry = registry,
        )
        val tracks = provider.search("晴天").tracks
        // 目录批准 kw/kg/tx，三个平台各一条，ID 使用各自平台的原始值。
        assertEquals(3, tracks.size)
        assertTrue(tracks.any { it.providerId?.endsWith(":kw:KW-1") == true })
        assertTrue(tracks.any { it.providerId?.endsWith(":kg:KG-1") == true })
        assertTrue(tracks.any { it.providerId?.endsWith(":tx:TX-1") == true })
    }

    @Test
    fun `unregistered platform is skipped even if script declares it`() = runTest {
        // 注册只有 kw，但脚本/目录批准 kw/kg/tx → 只应返回 kw 一条。
        val registry = LxSongSearchRegistry(listOf(searcher("kw", "KW-1")))
        val provider = LxScriptMusicProvider(
            providerCredentials = InMemoryProviderCredentialStore(),
            scriptSource = source(installedScript(listOf("kw", "kg", "tx"))),
            httpClient = offlineClient(),
            searchRegistry = registry,
        )
        val tracks = provider.search("晴天").tracks
        assertEquals(1, tracks.size)
        assertTrue(tracks.single().providerId?.endsWith(":kw:KW-1") == true)
        assertEquals(setOf("kw"), provider.searchablePlatforms())
    }

    @Test
    fun `catalog not approved platform never appears in results`() = runTest {
        // 注册表有 kw/kg，但目录只批准 kw → 不得出现 kg。
        val registry = LxSongSearchRegistry(listOf(searcher("kw", "KW-1"), searcher("kg", "KG-1")))
        val provider = LxScriptMusicProvider(
            providerCredentials = InMemoryProviderCredentialStore(),
            scriptSource = source(installedScript(listOf("kw"))),
            httpClient = offlineClient(),
            searchRegistry = registry,
        )
        val tracks = provider.search("晴天").tracks
        assertEquals(1, tracks.size)
        assertTrue(tracks.single().providerId?.endsWith(":kw:KW-1") == true)
        assertFalse(tracks.any { it.providerId?.endsWith(":kg:") == true })
    }

    @Test
    fun `hit from wrong platform is rejected to prevent source spoofing`() = runTest {
        // Searcher 注册为 kw，却返回 platform=tx 的命中 → Provider 必须丢弃，防止冒充来源。
        val bad = object : LxSongSearcher {
            override val supportedPlatforms = setOf("kw")
            override suspend fun search(keyword: String, platform: String, limit: Int) =
                listOf(LxSongHit("tx", "SPOOF", "晴天", "周杰伦", "叶惠美"))
        }
        val provider = LxScriptMusicProvider(
            providerCredentials = InMemoryProviderCredentialStore(),
            scriptSource = source(installedScript(listOf("kw"))),
            httpClient = offlineClient(),
            searchRegistry = LxSongSearchRegistry(listOf(bad)),
        )
        assertTrue(provider.search("晴天").tracks.isEmpty(), "跨平台冒充的命中必须被丢弃")
    }
}
