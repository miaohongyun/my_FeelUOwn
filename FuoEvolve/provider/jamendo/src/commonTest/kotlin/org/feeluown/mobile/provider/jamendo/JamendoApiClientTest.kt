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
        assertTrue(api.search("anything").tracks.isEmpty())
        assertNull(api.trackById("1859761"))
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
        assertTrue(track.providerTags.contains("open-catalog"))
        assertEquals("jamendo", track.id.substringBefore(':'))

        val payload = provider.resolve(track, "highest")
        assertEquals(JamendoMusicProvider.QUALITY_LABEL, payload?.audioQuality)
        assertTrue(payload?.url?.startsWith("https://prod-1.storage.jamendo.com/") == true)
    }
}
