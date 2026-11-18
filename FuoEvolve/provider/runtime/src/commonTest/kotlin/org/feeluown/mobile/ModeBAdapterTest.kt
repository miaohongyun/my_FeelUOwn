package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.modeb.InMemoryModeBStorage
import org.feeluown.mobile.provider.modeb.ModeBMusicProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 端到端验证「搜 flac → 虚拟电台条目 → resolve 真实流」链路，确保打 APK 后开箱可测。 */
class ModeBAdapterTest {

    // Ktor MockEngine 在真实线程执行，runTest 虚拟时间会快进 withTimeout 造成误超时；用真实时间。
    private fun blockingTest(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking { block() }

    private fun liveTrack(sourceId: String, title: String) = MusicTrack(
        id = "modeb:${ModeBMusicProvider.LIVE_ID_PREFIX}$sourceId",
        title = title,
        artists = sourceId,
        album = "Mode B 直连电台",
        source = ModeBMusicProvider.ID,
        sourceType = TrackSourceType.Provider,
    ).copy(
        providerId = "modeb:${ModeBMusicProvider.LIVE_ID_PREFIX}$sourceId",
        providerTags = listOf("live", "modeb"),
    )

    private fun mockProvider(streamResponse: String): Pair<ModeBMusicProvider, MockEngine> {
        val engine = MockEngine { req ->
            when {
                req.url.encodedPath.contains("get_block") -> respond(
                    streamResponse,
                    HttpStatusCode.OK,
                    headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
                )
                req.url.encodedPath.contains("bycountryexact") -> respond(
                    """[{"name":"测试电台","url_resolved":"https://cdn.test/radio.m3u8","codec":"MP3","bitrate":128,"tags":"音乐","country":"CN"}]""",
                    HttpStatusCode.OK,
                    headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("{}", HttpStatusCode.OK)
            }
        }
        return ModeBMusicProvider(
            providerCredentials = InMemoryProviderCredentialStore(),
            httpClient = HttpClient(engine),
            storage = InMemoryModeBStorage(),
        ) to engine
    }

    @Test
    fun searchFlacReturnsLiveRadioTracksTaggedModeB() = blockingTest {
        val (provider, _) = mockProvider("""{"url":"https://cdn.test/flac"}""")
        val results = provider.search("flac")
        assertTrue(results.tracks.isNotEmpty(), "搜 flac 应返回直连电台兜底条目")
        assertTrue(results.tracks.all { it.source == ModeBMusicProvider.ID }, "所有结果 source 必须是 modeb")
        assertTrue(results.tracks.first().id.startsWith("${ModeBMusicProvider.ID}:"))
    }

    @Test
    fun ordinaryKeywordReturnsNoTracksAndNoPollution() = blockingTest {
        val (provider, _) = mockProvider("""{"url":"https://cdn.test/flac"}""")
        val results = provider.search("周杰伦")
        assertTrue(results.tracks.isEmpty(), "普通歌名不应命中电台兜底，避免污染正常搜索")
    }

    @Test
    fun resolveLiveTrackReturnsRealStreamUrl() = blockingTest {
        val (provider, _) = mockProvider("""{"url":"https://cdn.test/radio.flac"}""")
        val track = liveTrack("radioparadise_now", "[直播] Radio Paradise")
        val payload = provider.resolve(track, ">>>")
        assertNotNull(payload, "live 歌曲应能解析出播放地址")
        assertEquals("https://cdn.test/radio.flac", payload.url)
        assertEquals(ModeBMusicProvider.ID, payload.source, "PlaybackPayload.source 必须是 modeb，保证路由回本 Provider")
        assertEquals("modeb", payload.audioQuality)
    }

    @Test
    fun resolveLiveTrackFromRadioBrowserSource() = blockingTest {
        val (provider, _) = mockProvider("""{"url":"https://cdn.test/flac"}""")
        val rbTrack = liveTrack("radiobrowser_cn_de1", "[直播] 中国电台")
        val payload = provider.resolve(rbTrack, ">>>")
        assertNotNull(payload)
        assertEquals("https://cdn.test/radio.m3u8", payload.url)
    }

    @Test
    fun resolveSearchedTrackLocksSourceAndPassesId() = blockingTest {
        // 搜索命中的普通歌曲 identifier = s::<sourceId>|<trackId>。resolve 必须锁定该源并带上 id，
        // 否则会被高优先级电台源（get_block 忽略参数）错误命中造成串源。
        val seenUrls = mutableListOf<String>()
        val engine = MockEngine { req ->
            seenUrls += req.url.toString()
            when {
                req.url.host.contains("itunes.apple.com") -> respond(
                    """{"resultCount":1,"results":[{"previewUrl":"https://audio-ssl.itunes.apple.com/preview.m4a"}]}""",
                    HttpStatusCode.OK,
                    headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
                )
                req.url.encodedPath.contains("get_block") -> respond(
                    """{"url":"https://cdn.test/WRONG_RADIO.flac"}""",
                    HttpStatusCode.OK,
                    headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("{}", HttpStatusCode.OK)
            }
        }
        val provider = ModeBMusicProvider(
            providerCredentials = InMemoryProviderCredentialStore(),
            httpClient = HttpClient(engine),
            storage = InMemoryModeBStorage(),
        )
        val track = MusicTrack(
            id = "modeb:s::itunes_preview|1441133277",
            title = "Hey Jude",
            artists = "The Beatles",
            album = "itunes_preview",
            source = ModeBMusicProvider.ID,
            sourceType = TrackSourceType.Provider,
        ).copy(providerId = "modeb:s::itunes_preview|1441133277")

        val payload = provider.resolve(track, ">>>")
        assertNotNull(payload, "搜索歌曲应能解析出播放地址")
        assertEquals("https://audio-ssl.itunes.apple.com/preview.m4a", payload.url)
        val itunesReq = seenUrls.firstOrNull { it.contains("itunes.apple.com") }
        assertNotNull(itunesReq, "必须命中 itunes 源，不能被电台源串走")
        assertTrue("id=1441133277" in itunesReq, "请求必须带 id=1441133277：$itunesReq")
    }
}
