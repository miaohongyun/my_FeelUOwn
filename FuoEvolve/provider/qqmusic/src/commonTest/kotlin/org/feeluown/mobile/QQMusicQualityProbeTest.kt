package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.qqmusic.QQMusicProvider

class QQMusicQualityProbeTest {
    @Test
    fun freeAccountIsNotOfferedLosslessWhenTheVkeyRejectsIt() = runTest {
        val requests = mutableListOf<String>()
        val provider = provider { data ->
            requests += data
            vkeyResponse(data)
        }

        val policies = provider.availableQualities(track())

        assertEquals(listOf(AudioQualityPolicy.High.policy, AudioQualityPolicy.Low.policy), policies)
        assertEquals(2, requests.size, "lossless probe should fail once, then stop at 320k")
    }

    @Test
    fun verifiedProbeIsCachedForRepeatedPickerOpens() = runTest {
        var vkeyCalls = 0
        val provider = provider { data ->
            vkeyCalls += 1
            vkeyResponse(data)
        }

        provider.availableQualities(track())
        val callsAfterFirstProbe = vkeyCalls
        provider.availableQualities(track())

        assertEquals(callsAfterFirstProbe, vkeyCalls, "second open must hit the cache")
    }

    @Test
    fun accountWithoutAnyPlayableStreamReportsEmpty() = runTest {
        val provider = provider { data -> vkeyResponse(data, allow = false) }
        assertEquals(emptyList(), provider.availableQualities(track()))
    }

    private fun vkeyResponse(data: String, allow: Boolean = true): String {
        val playable = allow && data.contains("M800")
        val purl = if (playable) "M800abc.mp3" else ""
        return """{"req_0":{"data":{"midurlinfo":[{"purl":"$purl"}]}}}"""
    }

    private fun provider(onVkey: (String) -> String): QQMusicProvider {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val path = request.url.encodedPath
                    when {
                        path.contains("fcg_play_single_song") -> respond(SONG_DETAIL)
                        path.contains("musicu.fcg") -> respond(onVkey(request.url.parameters["data"].orEmpty()))
                        else -> respond("{}")
                    }
                }
            }
        }
        return QQMusicProvider(ProviderHttpClient(client), InMemoryProviderCredentialStore())
    }

    private fun track() = MusicTrack(
        id = "qqmusic:abc",
        title = "晴天",
        artists = "周杰伦",
        album = "叶惠美",
        source = "qqmusic",
        sourceType = TrackSourceType.Provider,
        providerId = "qqmusic:abc",
        durationMs = 269_000L,
    )

    private companion object {
        /** FLAC and 320k exist as files; only the 128k fallback is declared too. */
        const val SONG_DETAIL = """
            {"data":[{"songmid":"abc","songname":"晴天","interval":269,
              "singer":[{"name":"周杰伦"}],"albummid":"alb",
              "file":{"media_mid":"abc","size_flac":"30000000","size_320":"10000000","size_128":"4000000"}}]}
        """
    }
}
