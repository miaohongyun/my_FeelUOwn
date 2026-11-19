package org.feeluown.mobile.provider.lx

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1 任务4：多平台搜索适配器的模拟验证。
 *
 * 验证等级：**SIMULATED**（真实响应结构的 fixture + MockEngine，未打真实接口）。
 * 每个 fixture 都按对应平台公开搜索接口的真实字段结构构造，但接口是否存活、
 * 字段是否变化、是否需要鉴权均未验证——见 roadmap §9.2。
 */
class LxPlatformSearcherTest {

    /** 酷狗 `song_search_v2` 真实响应结构。 */
    private val kugouFixture = """
        {"data":{"lists":[
          {"FileHash":"ABC123HASH","SongName":"晴天","SingerName":"周杰伦","AlbumName":"叶惠美","Duration":"269","AlbumID":"77"},
          {"FileHash":"DEF456HASH","SongName":"晴天 (Live)","SingerName":"周杰伦","AlbumName":"演唱会","Duration":"300"}
        ]},"errcode":0}
    """.trimIndent()

    /** QQ 音乐 `client_search_cp` 真实响应结构。 */
    private val qqFixture = """
        {"data":{"song":{"list":[
          {"songmid":"004RaTea2dCIuZ","songname":"晴天","singer":[{"name":"周杰伦"},{"name":"和声"}],"albumname":"叶惠美","interval":"269","songid":12345},
          {"songmid":"001XYZmid9Q","songname":"晴天 (Live)","singer":[{"name":"周杰伦"}],"albumname":"演唱会","interval":"300"}
        ]}}}
    """.trimIndent()

    /** 网易云 `/api/search/get` 真实响应结构（duration 单位毫秒）。 */
    private val neteaseFixture = """
        {"result":{"songs":[
          {"id":186016,"name":"晴天","artists":[{"name":"周杰伦"}],"album":{"name":"叶惠美"},"duration":269000,"alias":[]},
          {"id":186017,"name":"晴天 (Live)","artists":[{"name":"周杰伦"}],"album":{"name":"演唱会"},"duration":300000}
        ]}}
    """.trimIndent()

    /** 咪咕公开搜索接口真实响应结构。 */
    private val miguFixture = """
        {"data":{"musicList":[
          {"musicId":"MIGU001","songName":"晴天","singerName":"周杰伦","albumName":"叶惠美","duration":"269"},
          {"musicId":"MIGU002","songName":"晴天 (Live)","singerName":"周杰伦","albumName":"演唱会","duration":"300"}
        ]}}
    """.trimIndent()

    private fun mockClient(body: String) = HttpClient(MockEngine { respond(body, HttpStatusCode.OK) })

    @Test
    fun `kugou maps FileHash as stable id and keeps raw fields`() {
        val hits = KugouSongSearcher(mockClient(kugouFixture)).parseLx(kugouFixture)
        assertEquals(2, hits.size)
        assertEquals("ABC123HASH", hits[0].songId)
        assertEquals("晴天", hits[0].title)
        assertEquals("周杰伦", hits[0].artist)
        assertEquals("叶惠美", hits[0].album)
        assertEquals(269, hits[0].durationSeconds)
        // 原始字段原样保留，不把 songId 赋给所有键。
        assertEquals("ABC123HASH", hits[0].rawFields["FileHash"])
        assertEquals("77", hits[0].rawFields["AlbumID"])
        assertTrue(hits[0].rawFields.keys.size >= 6)
    }

    @Test
    fun `qqmusic maps songmid and joins multiple singers`() {
        val hits = QQMusicSongSearcher(mockClient(qqFixture)).parseLx(qqFixture)
        assertEquals(2, hits.size)
        assertEquals("004RaTea2dCIuZ", hits[0].songId)
        assertEquals("晴天", hits[0].title)
        assertEquals("周杰伦,和声", hits[0].artist)
        assertEquals("叶惠美", hits[0].album)
        // songid 是另一个原始字段，必须原样保留，不能被 songmid 覆盖。
        assertEquals("12345", hits[0].rawFields["songid"])
        assertEquals("004RaTea2dCIuZ", hits[0].rawFields["songmid"])
    }

    @Test
    fun `netease maps numeric id and converts millisecond duration`() {
        val hits = NeteaseSongSearcher(mockClient(neteaseFixture)).parseLx(neteaseFixture)
        assertEquals(2, hits.size)
        assertEquals("186016", hits[0].songId)
        assertEquals("晴天", hits[0].title)
        assertEquals("周杰伦", hits[0].artist)
        assertEquals("叶惠美", hits[0].album)
        assertEquals(269, hits[0].durationSeconds, "269000ms 应换算成 269 秒")
        assertEquals("186016", hits[0].rawFields["id"])
    }

    @Test
    fun `migu maps musicId as stable id`() {
        val hits = MiguSongSearcher(mockClient(miguFixture)).parseLx(miguFixture)
        assertEquals(2, hits.size)
        assertEquals("MIGU001", hits[0].songId)
        assertEquals("晴天", hits[0].title)
        assertEquals("周杰伦", hits[0].artist)
        assertEquals("MIGU001", hits[0].rawFields["musicId"])
    }

    @Test
    fun `each platform keeps its own id field without cross-contamination`() = runTest {
        val kg = KugouSongSearcher(mockClient(kugouFixture)).parseLx(kugouFixture).first()
        val tx = QQMusicSongSearcher(mockClient(qqFixture)).parseLx(qqFixture).first()
        val wy = NeteaseSongSearcher(mockClient(neteaseFixture)).parseLx(neteaseFixture).first()
        val mg = MiguSongSearcher(mockClient(miguFixture)).parseLx(miguFixture).first()

        // 四个平台的稳定 ID 完全不同，绝不能互相冒充。
        assertEquals(setOf(kg.songId, tx.songId, wy.songId, mg.songId).size, 4)
        assertEquals("kg", kg.platform)
        assertEquals("tx", tx.platform)
        assertEquals("wy", wy.platform)
        assertEquals("mg", mg.platform)
    }

    @Test
    fun `malformed and empty responses yield no hits`() {
        val searcher = KugouSongSearcher(mockClient(""))
        assertTrue(searcher.parseLx("not json").isEmpty())
        assertTrue(searcher.parseLx("{}").isEmpty())
        assertTrue(searcher.parseLx("""{"data":{"lists":[]}}""").isEmpty())
        // 缺 ID 的条目必须跳过，不能返回空 songId。
        assertTrue(searcher.parseLx("""{"data":{"lists":[{"SongName":"x"}]}}""").isEmpty())
    }

    @Test
    fun `registry routes by platform and marks unregistered platforms unsearchable`() = runTest {
        val client = mockClient(kugouFixture)
        val registry = LxSongSearchRegistry(listOf(KuwoSongSearcher(client), KugouSongSearcher(client)))
        assertEquals(setOf("kw", "kg"), registry.searchablePlatforms)
        assertTrue(registry.canSearch("kw"))
        assertTrue(registry.canSearch("kg"))
        assertFalse(registry.canSearch("tx"), "未注册平台必须明确不可搜索")
        assertFalse(registry.canSearch("mg"))
        assertTrue(registry.search("晴天", "tx").isEmpty(), "未注册平台搜索返回空")
    }

    @Test
    fun `one platform failure does not affect another`() = runTest {
        // 同一个 registry 内：kw 返回非法 JSON（失败），kg 返回正常结果。
        // 验证 kw 的失败被隔离成空列表，kg 仍正常返回。
        val failingClient = HttpClient(MockEngine {
            respond("not json", HttpStatusCode.InternalServerError)
        })
        val registry = LxSongSearchRegistry(listOf(
            KuwoSongSearcher(failingClient),
            KugouSongSearcher(mockClient(kugouFixture)),
        ))
        assertTrue(registry.search("晴天", "kw").isEmpty(), "kw 失败应被隔离")
        val hits = registry.search("晴天", "kg")
        assertEquals(2, hits.size, "同一个 registry 里 kw 失败不得影响 kg")
    }

    /**
     * 2026-11-19 真实捕获的酷狗 song_search_v2 响应片段（脱敏：仅保留解析相关字段）。
     * 真实端点 GET https://songsearch.kugou.com/song_search_v2?keyword=晴天 返回 HTTP 200，
     * 首条为周杰伦《晴天》FileHash=B3A52A7A...、Duration=269。这里用 MockEngine 回放该结构，
     * 证明解析器对真实字段（含各类 *FileHash）不会串用 ID；仍属 SIMULATED，
     * 真实接口存活已另行取证（roadmap §10.5），不等于脚本 URL 解析真实通过。
     */
    private val kugouRealCaptured = """
        {"status":1,"error_code":0,"error_msg":"","data":{"lists":[
          {"FileHash":"B3A52A7A958BF0AED0EBFBA2E9A818B7","SongName":"晴天","SingerName":"周杰伦","AlbumName":"叶惠美","Duration":"269",
           "HQFileHash":"1B56126A8A03924F1DD066259C095CBC","SQFileHash":"0A69169202DE95AAF24A9944CCF0730D","AlbumID":"77"},
          {"FileHash":"48C685F679FFC7CF08B8A8341CA9DB44","SongName":"晴天","SingerName":"蓝心羽","AlbumName":"晴天","Duration":"176"}
        ]}}
    """.trimIndent()

    @Test
    fun `kugou parses real captured response with stable FileHash identity`() {
        val hits = KugouSongSearcher(mockClient(kugouRealCaptured)).parseLx(kugouRealCaptured)
        assertEquals(2, hits.size)
        assertEquals("B3A52A7A958BF0AED0EBFBA2E9A818B7", hits[0].songId)
        assertEquals("晴天", hits[0].title)
        assertEquals("周杰伦", hits[0].artist)
        assertEquals(269, hits[0].durationSeconds)
        assertEquals("1B56126A8A03924F1DD066259C095CBC", hits[0].rawFields["HQFileHash"])
        assertEquals("B3A52A7A958BF0AED0EBFBA2E9A818B7", hits[0].rawFields["FileHash"])
    }

    @Test
    fun `production default registry exposes kw and kg but not unverified platforms`() {
        val registry = LxSongSearchRegistry.default(mockClient("{}"))
        assertEquals(setOf("kw", "kg"), registry.searchablePlatforms)
        assertTrue(registry.canSearch("kw"))
        assertTrue(registry.canSearch("kg"))
        assertFalse(registry.canSearch("tx"), "tx 未做真实接口验证，不得默认暴露")
        assertFalse(registry.canSearch("wy"))
        assertFalse(registry.canSearch("mg"))
    }

    @Test
    fun `provider factory uses default kw+kg registry without explicit override`() {
        // 生产工厂默认 searchRegistry=null → useDefaultSearchRegistry=true → Provider 用自身
        // httpClient 构造 kw+kg。这里通过反射无关的行为验证：工厂产出的 Provider 能搜 kg。
        val factory = LxScriptProviderFactory(EmptyLxInstalledScriptSource)
        val provider = factory.create(
            org.feeluown.mobile.provider.core.ProviderRuntimeDependencies(
                http = org.feeluown.mobile.provider.core.network.ProviderHttpClient(),
                credentials = org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore(),
            ),
        )
        val lx = provider as LxScriptMusicProvider
        assertEquals(setOf("kw", "kg"), lx.searchablePlatforms())
    }
}
