package org.feeluown.mobile.provider.jamendo

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FIXTURE = """
{
  "headers": { "status": "success", "code": 0, "results_count": 2 },
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
      "shareurl": "https://www.jamendo.com/track/1859761"
    },
    {
      "id": 42,
      "name": "Broken Entry",
      "artist_name": "Nobody",
      "audio": "",
      "image": ""
    }
  ]
}
"""

class JamendoApiClientTest {

    private fun clientReturning(body: String, onRequest: (String) -> Unit = {}): HttpClient {
        val engine = MockEngine { request ->
            onRequest(request.url.toString())
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return HttpClient(engine)
    }

    private fun clientThatMustNotBeCalled(): HttpClient {
        val engine = MockEngine { error("没有 client_id 时不应该发起请求") }
        return HttpClient(engine)
    }

    @Test
    fun parsesSearchResultsAndSkipsBrokenEntries() = runTest {
        var requestedUrl = ""
        val api = JamendoApiClient(
            client = clientReturning(FIXTURE) { requestedUrl = it },
            clientIdProvider = { "test-client" },
        )
        val result = api.search("sunrise drive", limit = 5)

        assertEquals(1, result.tracks.size)
        assertEquals(2, result.totalCount)
        val track = result.tracks.first()
        assertEquals("1859761", track.id)
        assertEquals("Sunrise Drive", track.name)
        assertEquals("Kai Engel", track.artist)
        assertEquals("Open Roads", track.album)
        assertEquals(214, track.durationSeconds)
        assertTrue(track.audioUrl.startsWith("https://prod-1.storage.jamendo.com/"))
        assertTrue(requestedUrl.contains("client_id=test-client"))
        assertTrue(requestedUrl.contains("search=sunrise%20drive"))
        assertTrue(requestedUrl.contains("audioformat=mp32"))
    }

    @Test
    fun returnsEmptyWithoutClientId() = runTest {
        val api = JamendoApiClient(client = clientThatMustNotBeCalled(), clientIdProvider = { null })
        val result = api.search("anything")
        assertTrue(result.tracks.isEmpty())
        assertEquals(JamendoFailureKind.MissingClientId, result.failureKind,
            "无 client_id 应给出明确失败原因，供 UI 提示去设置页配置")
        assertNull(api.trackById("1859761"))
    }

    @Test
    fun reportsNetworkAndHttpFailuresSeparatelyFromEmptyResult() = runTest {
        // HTTP 500 → Upstream，不是"正常空结果"。
        val httpError = HttpClient(MockEngine {
            respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val upstream = JamendoApiClient(client = httpError, clientIdProvider = { "test-client" })
        val upstreamResult = upstream.search("x")
        assertTrue(upstreamResult.tracks.isEmpty())
        assertEquals(JamendoFailureKind.Upstream, upstreamResult.failureKind,
            "HTTP 错误必须报告 Upstream，不能被当成空搜索结果")

        // 网络抛异常 → Network（MockEngine 抛 RuntimeException，被 fetch 的 catch(Exception) 捕获）。
        val netError = HttpClient(MockEngine { throw RuntimeException("network down") })
        val network = JamendoApiClient(client = netError, clientIdProvider = { "test-client" })
        val networkResult = network.search("x")
        assertTrue(networkResult.tracks.isEmpty())
        assertEquals(JamendoFailureKind.Network, networkResult.failureKind,
            "网络异常必须报告 Network")

        // 正常成功但无匹配 → None（不是失败）。
        val empty = JamendoApiClient(
            client = clientReturning("""{"headers":{"results_count":0},"results":[]}"""),
            clientIdProvider = { "test-client" },
        )
        assertEquals(JamendoFailureKind.None, empty.search("nomatch").failureKind,
            "正常空结果不应报告失败")
    }

    @Test
    fun trackByIdRefreshesPlaybackUrl() = runTest {
        val api = JamendoApiClient(client = clientReturning(FIXTURE), clientIdProvider = { "test-client" })
        val track = api.trackById("1859761")
        assertEquals("1859761", track?.id)
        assertTrue(track?.audioUrl?.isNotBlank() == true)
    }

    @Test
    fun toleratesInvalidJson() = runTest {
        val api = JamendoApiClient(client = clientReturning("<html>502</html>"), clientIdProvider = { "test-client" })
        assertTrue(api.search("x").tracks.isEmpty())
        assertNull(api.trackById("1"))
    }

    @Test
    fun providerMarksOpenCatalogTracks() = runTest {
        val provider = JamendoMusicProvider(
            providerCredentials = org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore(),
            clientIdProvider = { "test-client" },
            httpClient = clientReturning(FIXTURE),
        )
        val results = provider.search("sunrise")
        val track = results.tracks.single()
        assertEquals(JamendoMusicProvider.ID, track.source)
        assertTrue(track.providerTags.contains(JamendoMusicProvider.TAG))
        assertEquals("jamendo", track.id.substringBefore(':'))

        val payload = provider.resolve(track, "highest")
        assertEquals(JamendoMusicProvider.QUALITY_LABEL, payload?.audioQuality)
        assertTrue(payload?.url?.startsWith("https://prod-1.storage.jamendo.com/") == true)
    }

    @Test
    fun providerSurfacesFailureForUiMessage() = runTest {
        // 无 client_id → Provider 返回 ContentUnavailable failure，UI 提示去设置页配置。
        val noCreds = JamendoMusicProvider(
            providerCredentials = org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore(),
            clientIdProvider = { null },
            httpClient = clientThatMustNotBeCalled(),
        )
        val noCredsResult = noCreds.search("x")
        assertTrue(noCredsResult.tracks.isEmpty())
        assertEquals(org.feeluown.mobile.ProviderFailureKind.ContentUnavailable,
            noCredsResult.failure?.kind, "无凭据应映射为 ContentUnavailable")
        assertEquals(JamendoMusicProvider.ID, noCredsResult.failure?.providerId)

        // HTTP 500 → UpstreamContractChanged，UI 提示接口响应异常。
        val httpError = JamendoMusicProvider(
            providerCredentials = org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore(),
            clientIdProvider = { "test-client" },
            httpClient = HttpClient(MockEngine {
                respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }),
        )
        assertEquals(org.feeluown.mobile.ProviderFailureKind.UpstreamContractChanged,
            httpError.search("x").failure?.kind)

        // 正常成功 → 无 failure。
        val healthy = JamendoMusicProvider(
            providerCredentials = org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore(),
            clientIdProvider = { "test-client" },
            httpClient = clientReturning(FIXTURE),
        )
        assertEquals(null, healthy.search("sunrise").failure, "正常结果不应带 failure")
    }

    @Test
    fun licenseIsPreservedNotTreatedAsUnconditionalOpen() = runTest {
        // 带真实 CC BY-NC-ND 3.0 许可的响应：Provider 必须保留许可证类型、原始链接与条件，
        // 不得笼统标为无条件开放资源。
        val ccUrl = "http://creativecommons.org/licenses/by-nc-nd/3.0/"
        val raw = FIXTURE.replace(""""id": "1859761"""", "\"id\": \"1859761\", \"license_ccurl\": \"$ccUrl\"")
        val provider = JamendoMusicProvider(
            providerCredentials = org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore(),
            clientIdProvider = { "test-client" },
            httpClient = clientReturning(raw),
        )
        val track = provider.search("sunrise").tracks.single()
        assertTrue(track.providerTags.contains("cc:BY-NC-ND"), "应保留 CC 许可证类型: ${track.providerTags}")
        assertTrue(track.providerTags.any { it.startsWith("license-url:http://creativecommons.org/") },
            "应保留原始许可链接: ${track.providerTags}")
        assertTrue(track.providerTags.any { it.contains("非商业") && it.contains("禁止演绎") },
            "应保留适用条件说明: ${track.providerTags}")
        assertTrue(!track.providerTags.contains("open-catalog"), "不得笼统标为无条件开放资源")
    }

    @Test
    fun licenseCodeParsesCommonCcVariants() {
        fun code(url: String) = JamendoTrack(
            id = "1", name = "n", artist = "a", album = "", durationSeconds = 1,
            audioUrl = "https://x", downloadUrl = "", imageUrl = "", shareUrl = "",
            licenseCcUrl = url,
        ).licenseCode
        assertEquals("BY", code("http://creativecommons.org/licenses/by/4.0/"))
        assertEquals("BY-SA", code("https://creativecommons.org/licenses/by-sa/3.0/"))
        assertEquals("BY-NC-ND", code("http://creativecommons.org/licenses/by-nc-nd/3.0/"))
        assertEquals("", code(""))
    }

    @Test
    fun licenseSummaryLabelsEveryCcCondition() {
        fun summary(codePath: String) = JamendoTrack(
            id = "1", name = "n", artist = "a", album = "", durationSeconds = 1,
            audioUrl = "https://x", downloadUrl = "", imageUrl = "", shareUrl = "",
            licenseCcUrl = "https://creativecommons.org/licenses/$codePath/4.0/",
        ).licenseSummary

        // 纯 BY 也必须显式写"须署名"，不能退化成含糊的"请查看条款"。
        val by = summary("by")
        assertTrue(by.contains("须署名") && by.contains("BY"), "BY 应标注署名: $by")
        assertTrue(!by.contains("非商业") && !by.contains("禁止演绎"), "BY 不应标注它不具备的限制: $by")

        // 组合条件必须全部列出，不因分支短路而漏标。
        val bySa = summary("by-sa")
        assertTrue(bySa.contains("须署名") && bySa.contains("相同方式共享") && bySa.contains("SA"),
            "BY-SA 应同时标注署名与相同方式共享: $bySa")

        val byNcNd = summary("by-nc-nd")
        assertTrue(byNcNd.contains("须署名"), "BY-NC-ND 应标注署名: $byNcNd")
        assertTrue(byNcNd.contains("非商业") && byNcNd.contains("NC"), "BY-NC-ND 应标注非商业: $byNcNd")
        assertTrue(byNcNd.contains("禁止演绎") && byNcNd.contains("ND"), "BY-NC-ND 应标注禁止演绎: $byNcNd")

        // 无 CC 链接时保守提示，不声称开放。
        val none = JamendoTrack(
            id = "1", name = "n", artist = "a", album = "", durationSeconds = 1,
            audioUrl = "https://x", downloadUrl = "", imageUrl = "", shareUrl = "",
        ).licenseSummary
        assertTrue(none.contains("未知"), "无许可证应保守提示未知: $none")
    }
}
