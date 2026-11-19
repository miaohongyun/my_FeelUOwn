package org.feeluown.mobile.provider.lx

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 官方协议里 `inited` 是**脚本主动 send 的事件**（宿主只监听），不是宿主回调，
 * 所以这里必须顶层调用 `lx.send(EVENT_NAMES.inited, ...)`；写成 `lx.on('inited')` 永远拿不到声明。
 */
private const val SCRIPT = """
lx.on('request', function (payload) {
  if (payload.action !== 'musicUrl') return Promise.resolve(null);
  var songmid = payload.info.musicInfo.songmid;
  return Promise.resolve('https://cdn.example.com/' + payload.source + '/' + songmid + '.' + payload.info.type + '.mp3');
});

lx.send('inited', {
  status: true,
  sources: {
    kw: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k', 'flac'] }
  }
});
"""

class LxScriptProviderTest {

    private fun offlineClient(): HttpClient = HttpClient(MockEngine { error("脚本测试不应发起真实网络请求") })

    private fun provider(
        scriptSource: LxInstalledScriptSource,
        searcher: LxSongSearcher,
    ): LxScriptMusicProvider = LxScriptMusicProvider(
        providerCredentials = InMemoryProviderCredentialStore(),
        scriptSource = scriptSource,
        httpClient = offlineClient(),
        searcher = searcher,
    )

    private fun scriptSource(vararg scripts: LxInstalledScript): LxInstalledScriptSource =
        object : LxInstalledScriptSource {
            override suspend fun scripts(): List<LxInstalledScript> = scripts.toList()
        }

    private fun fixtureScript(platforms: List<String> = listOf("kw")) = LxInstalledScript(
        id = "fixture",
        name = "Fixture",
        version = "1.0.0",
        source = SCRIPT,
        allowedHosts = listOf("cdn.example.com", "api.example.com"),
        platforms = platforms,
    )

    @Test
    fun searchDelegatesToSearcherForDeclaredPlatforms() = runTest {
        val searcher = object : LxSongSearcher {
            override val supportedPlatforms = setOf("kw")
            var calledWith: Pair<String, String>? = null
            override suspend fun search(keyword: String, platform: String, limit: Int): List<LxSongHit> {
                calledWith = keyword to platform
                return listOf(LxSongHit("kw", "228908", "晴天", "周杰伦", "叶惠美", 269))
            }
        }
        val results = provider(scriptSource(fixtureScript()), searcher).search("晴天")
        assertEquals("晴天" to "kw", searcher.calledWith)
        val track = results.tracks.single()
        assertEquals("晴天", track.title)
        assertEquals("周杰伦", track.artists)
        assertEquals(LxScriptMusicProvider.ID, track.source)
        assertTrue(track.providerId?.endsWith(":kw:228908") == true)
        assertTrue(track.providerTags.contains("fixture"))
    }

    @Test
    fun resolveReturnsUrlProducedByScript() = runTest {
        val searcher = object : LxSongSearcher {
            override val supportedPlatforms = setOf("kw")
            override suspend fun search(keyword: String, platform: String, limit: Int) =
                listOf(LxSongHit("kw", "228908", "晴天", "周杰伦", "叶惠美", 269))
        }
        val provider = provider(scriptSource(fixtureScript()), searcher)
        val track = provider.search("晴天").tracks.single()

        val payload = provider.resolve(track, "highest")
        assertNotNull(payload)
        // highest → 先试 flac，脚本按 info.type 生成地址，因此可以确认档位确实透传下去了
        assertEquals("https://cdn.example.com/kw/228908.flac.mp3", payload.url)
        assertEquals("flac", payload.audioQuality)
    }

    @Test
    fun resolveFallsBackToNextQualityWhenScriptRejects() = runTest {
        val flacRejecting = SCRIPT.replace(
            "if (payload.action !== 'musicUrl') return Promise.resolve(null);",
            "if (payload.action !== 'musicUrl') return Promise.resolve(null);\n  if (payload.info.type === 'flac') return Promise.reject(new Error('no flac'));",
        )
        val script = fixtureScript().copy(source = flacRejecting)
        val searcher = object : LxSongSearcher {
            override val supportedPlatforms = setOf("kw")
            override suspend fun search(keyword: String, platform: String, limit: Int) =
                listOf(LxSongHit("kw", "1", "t", "a", "b", null))
        }
        val provider = provider(scriptSource(script), searcher)
        val track = provider.search("t").tracks.single()
        val payload = provider.resolve(track, "highest")
        assertEquals("https://cdn.example.com/kw/1.320k.mp3", payload?.url)
    }

    @Test
    fun resolveReturnsNullWhenNoScriptDeclaresPlatform() = runTest {
        val searcher = object : LxSongSearcher {
            override val supportedPlatforms = setOf("kw", "kg")
            override suspend fun search(keyword: String, platform: String, limit: Int) =
                listOf(LxSongHit(platform, "1", "t", "a", "b", null))
        }
        // 先用「目录批准 kw」的脚本拿到一条 kw 歌曲，再换成「目录只批准 kg」的脚本去解析同一条：
        // 平台不在批准范围内时必须直接放弃，而不是偷偷让脚本越权解析。
        val track = provider(scriptSource(fixtureScript()), searcher).search("t").tracks.single()
        val restricted = provider(scriptSource(fixtureScript(platforms = listOf("kg"))), searcher)
        assertNull(restricted.resolve(track, "highest"))
    }

    @Test
    fun catalogApprovalCapsWhatScriptMayServe() = runTest {
        // 脚本自述 kw（inited 只声明 kw），目录只批准 kg：两个方向都必须是"目录说了算"。
        val searcher = object : LxSongSearcher {
            override val supportedPlatforms = setOf("kw", "kg")
            override suspend fun search(keyword: String, platform: String, limit: Int) =
                listOf(LxSongHit(platform, "1", "t", "a", "b", null))
        }
        val provider = provider(scriptSource(fixtureScript(platforms = listOf("kg"))), searcher)
        assertTrue(provider.search("t").tracks.isEmpty(), "目录未批准的 kw 不应出现在搜索结果里")
    }

    @Test
    fun disabledScriptStopsServingAndReleasesRuntime() = runTest {
        val searcher = object : LxSongSearcher {
            override val supportedPlatforms = setOf("kw")
            override suspend fun search(keyword: String, platform: String, limit: Int) =
                listOf(LxSongHit("kw", "1", "t", "a", "b", null))
        }
        var scripts = listOf(fixtureScript())
        val source = object : LxInstalledScriptSource {
            override suspend fun scripts(): List<LxInstalledScript> = scripts
        }
        val provider = provider(source, searcher)

        assertEquals(1, provider.search("t").tracks.size)
        // 用户在设置页关掉脚本：立刻不再参与搜索/解析。
        scripts = emptyList()
        assertTrue(provider.search("t").tracks.isEmpty())
    }

    @Test
    fun newVersionReloadsScriptContent() = runTest {
        val searcher = object : LxSongSearcher {
            override val supportedPlatforms = setOf("kw")
            override suspend fun search(keyword: String, platform: String, limit: Int) =
                listOf(LxSongHit("kw", "1", "t", "a", "b", null))
        }
        var current = fixtureScript()
        val source = object : LxInstalledScriptSource {
            override suspend fun scripts(): List<LxInstalledScript> = listOf(current)
        }
        val provider = provider(source, searcher)
        val track = provider.search("t").tracks.single()

        // v1：URL 里带 flac 档位
        assertTrue(provider.resolve(track, "128k")?.url?.endsWith(".128k.mp3") == true)

        // v2：脚本内容变了（这里换成固定返回），缓存 key 必须失效并重新加载。
        current = current.copy(
            version = "2.0.0",
            source = """
                lx.on('request', function () {
                  return Promise.resolve('https://cdn.example.com/v2.flac');
                });
                lx.send('inited', {
                  sources: { kw: { type: 'music', actions: ['musicUrl'], qualitys: ['320k'] } }
                });
            """.trimIndent(),
        )
        assertEquals("https://cdn.example.com/v2.flac", provider.resolve(track, "highest")?.url)
        assertEquals("320k", provider.resolve(track, "highest")?.audioQuality)
    }

    @Test
    fun searchWithoutEnabledScriptsReturnsEmpty() = runTest {
        val searcher = object : LxSongSearcher {
            override val supportedPlatforms = setOf("kw")
            override suspend fun search(keyword: String, platform: String, limit: Int) =
                error("没有脚本时不应该搜索")
        }
        assertTrue(provider(scriptSource(), searcher).search("x").tracks.isEmpty())
    }

    @Test
    fun qualityCandidatesFollowUserPolicy() {
        val declared = listOf("128k", "320k", "flac", "flac24bit")
        assertEquals(listOf("flac", "320k"), LxQuality.candidates("highest", declared))
        assertEquals(listOf("320k", "128k"), LxQuality.candidates("high", declared))
        assertEquals(listOf("128k", "320k"), LxQuality.candidates("standard", declared))
        assertEquals(listOf("320k", "128k"), LxQuality.candidates("high", listOf("128k", "320k")))
        assertTrue(LxQuality.candidates("highest", emptyList()).isEmpty())
    }

    @Test
    fun kuwoSearcherParsesRealResponseShape() = runTest {
        val body = """
            {"ARTISTPIC":"x","HIT":"1","abslist":[
              {"MUSICRID":"MUSIC_228908","SONGNAME":"晴天","NAME":"晴天","ARTIST":"周杰伦","FARTIST":"周杰伦","ALBUM":"叶惠美","DURATION":"269"},
              {"MUSICRID":"MUSIC_12","SONGNAME":"&nbsp;七里香&nbsp;","ARTIST":"周杰伦","ALBUM":"七里香","DURATION":"299"}
            ]}
        """.trimIndent()
        val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val searcher = KuwoSongSearcher(HttpClient(engine))

        val hits = searcher.search("晴天", "kw")
        assertEquals(2, hits.size)
        assertEquals("228908", hits[0].songId)
        assertEquals("晴天", hits[0].title)
        assertEquals("周杰伦", hits[0].artist)
        assertEquals(269, hits[0].durationSeconds)
        // HTML 实体与首尾空白要清掉，否则搜索列表里会出现 " 七里香 "
        assertEquals("七里香", hits[1].title)
    }

    @Test
    fun kuwoSearcherIgnoresOtherPlatformsAndBadInput() = runTest {
        val searcher = KuwoSongSearcher(HttpClient(MockEngine { error("不应发起请求") }))
        assertTrue(searcher.search("晴天", "kg").isEmpty())
        assertTrue(searcher.search("  ", "kw").isEmpty())
        assertTrue(searcher.parse("not json").isEmpty())
    }

    @Test
    fun kuwoSearcherKeepsRawFields() = runTest {
        val body = """
            {"abslist":[
              {"MUSICRID":"MUSIC_228908","SONGNAME":"晴天","ARTIST":"周杰伦",
               "ALBUM":"叶惠美","DURATION":"269","DC_TARGETID":"dc-9988"}
            ]}
        """.trimIndent()
        val hit = KuwoSongSearcher(HttpClient(MockEngine { respond(body, HttpStatusCode.OK) }))
            .parse(body).single()
        assertEquals("MUSIC_228908", hit.rawFields["MUSICRID"])
        assertEquals("dc-9988", hit.rawFields["DC_TARGETID"])
        assertEquals("晴天", hit.rawFields["SONGNAME"])
    }

    @Test
    fun resolvePassesPlatformRawFieldsToScript() = runTest {
        // 脚本回显 strMediaMid：只有搜索命中的原始字段被透传时才会得到真实值，
        // 而不是被 songId 冒充（这正是"《晴天》解析到另一首歌"的根因）。
        val echoScript = """
            lx.on('request', function (payload) {
              var mi = payload.info.musicInfo;
              return Promise.resolve('https://cdn.example.com/' + mi.hash + '/' + mi.strMediaMid);
            });
            lx.send('inited', {sources:{kw:{type:'music',actions:['musicUrl'],qualitys:['128k']}}});
        """.trimIndent()
        val script = LxInstalledScript(
            id = "echo", name = "Echo", version = "1.0.0", source = echoScript,
            allowedHosts = listOf("cdn.example.com"), platforms = listOf("kw"),
        )
        val searcher = object : LxSongSearcher {
            override val supportedPlatforms = setOf("kw")
            override suspend fun search(keyword: String, platform: String, limit: Int) = listOf(
                LxSongHit(
                    platform = "kw", songId = "228908", title = "晴天", artist = "周杰伦", album = "叶惠美",
                    rawFields = mapOf("hash" to "real-hash-x", "strMediaMid" to "real-media-y", "MUSICRID" to "MUSIC_228908"),
                ),
            )
        }
        val p = provider(scriptSource(script), searcher)
        val track = p.search("晴天").tracks.single()
        val payload = p.resolve(track, "highest")
        assertNotNull(payload)
        assertEquals(
            "https://cdn.example.com/real-hash-x/real-media-y",
            payload.url,
            "脚本必须拿到原始字段，而不是被 songId 覆盖",
        )
    }

    @Test
    fun resolveFallsBackToSongIdWhenRawFieldsMissing() = runTest {
        // 没有原始字段时仍回退到 songId，保持对旧适配器/手工构造 track 的兼容。
        val p = provider(scriptSource(fixtureScript()), object : LxSongSearcher {
            override val supportedPlatforms = setOf("kw")
            override suspend fun search(keyword: String, platform: String, limit: Int) =
                listOf(LxSongHit("kw", "228908", "晴天", "周杰伦", "叶惠美"))
        })
        val track = p.search("晴天").tracks.single()
        val payload = p.resolve(track, "128k")
        assertNotNull(payload)
        assertEquals("https://cdn.example.com/kw/228908.128k.mp3", payload.url)
    }
}
