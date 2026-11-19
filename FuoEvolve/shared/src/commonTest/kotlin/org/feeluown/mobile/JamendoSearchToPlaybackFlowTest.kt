package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Jamendo 从统一搜索到播放地址解析的接线测试（**Mock 验证，非真实网络**）。
 *
 * 目的：证明 Jamendo 已接进现有的 searchAll → resolveTrack 链路——
 * track.source="jamendo" 能被路由回 Jamendo Provider，CC 许可标签随结果一路带到 track，
 * resolve 能拿到可用的播放 URL。真实网络的端到端验证由 JamendoEndToEndTest（opt-in）负责，
 * 两者职责不同，不互相冒充。
 */
class JamendoSearchToPlaybackFlowTest {

    private val fixture = """
        {
          "headers": { "status": "success", "code": 0, "results_count": 1 },
          "results": [
            {
              "id": "1859761",
              "name": "Sunrise Drive",
              "artist_name": "Kai Engel",
              "album_name": "Open Roads",
              "duration": 214,
              "audio": "https://prod-1.storage.jamendo.com/?trackid=1859761&format=mp32",
              "audiodownload": "https://prod-1.storage.jamendo.com/?trackid=1859761&format=mp32&from=download",
              "image": "https://usercontent.jamendo.com/?type=album&id=1&width=400",
              "shareurl": "https://www.jamendo.com/track/1859761",
              "license_ccurl": "http://creativecommons.org/licenses/by-nc-nd/3.0/"
            }
          ]
        }
    """.trimIndent()

    private fun repositoryReturning(body: String): Pair<KotlinProviderRepository, HttpClient> {
        val raw = HttpClient(MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repo = createKotlinProviderRepository(
            http = ProviderHttpClient(raw),
            credentials = InMemoryProviderCredentialStore(),
            dynamicProviders = DynamicProviderDependencies(jamendoClientId = { "test-client" }),
        )
        return repo to raw
    }

    @Test
    fun jamendoTrackFlowsFromUnifiedSearchToResolvedPlaybackUrl() = runTest {
        val (repo, client) = repositoryReturning(fixture)
        try {
            // 1) 统一搜索：显式指定 jamendo，验证结果进入通用 ProviderSearchResults。
            val searchResult = repo.searchAll("sunrise", "jamendo")
            assertEquals(1, searchResult.tracks.size)
            val track = searchResult.tracks.single()
            assertEquals("jamendo", track.source)
            assertEquals("Jamendo 开放曲库", track.providerName)
            // 2) CC 许可标签随结果一路带到 track（供详情/播放页展示）。
            val license = licenseInfoFromTags(track.providerTags)
            assertNotNull(license, "搜索结果应携带 CC 许可信息")
            assertEquals("BY-NC-ND", license.code)
            assertTrue(license.licenseUrl.contains("creativecommons.org"))
            // 3) resolve：按 track.source 路由回 Jamendo Provider，重取播放地址。
            val payload = repo.resolveTrack(track)
            assertNotNull(payload, "resolve 应返回可用 PlaybackPayload")
            assertTrue(payload.url.startsWith("https://"), "播放地址应为 https: ${payload.url}")
            assertEquals("jamendo", payload.source)
            assertTrue(payload.url.contains("trackid=1859761"), "应是该曲目的播放地址: ${payload.url}")
        } finally {
            client.close()
        }
    }

    @Test
    fun jamendoFailureFromSearchIsSurfacedAsErrorMessage() = runTest {
        // 无 client_id：搜索失败原因应转成 UI 可读消息，而不是静默空结果。
        val raw = HttpClient(MockEngine { error("不应发起请求") })
        val repo = createKotlinProviderRepository(
            http = ProviderHttpClient(raw),
            credentials = InMemoryProviderCredentialStore(),
            dynamicProviders = DynamicProviderDependencies(jamendoClientId = { null }),
        )
        try {
            val result = repo.searchAll("anything", "jamendo")
            assertTrue(result.tracks.isEmpty())
            assertNotNull(result.failure, "失败应被带到结果里")
            assertTrue(result.errorMessage!!.isNotBlank(), "应有可读的错误提示")
            assertTrue(result.errorMessage!!.contains("不可用"), "ContentUnavailable 应有明确提示: ${result.errorMessage}")
        } finally {
            raw.close()
        }
    }
}
