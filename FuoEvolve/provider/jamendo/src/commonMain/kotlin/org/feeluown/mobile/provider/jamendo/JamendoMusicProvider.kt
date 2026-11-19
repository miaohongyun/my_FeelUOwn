package org.feeluown.mobile.provider.jamendo

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.provider.core.BaseKotlinProvider
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.KotlinProviderFactory
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.createSharedProviderHttpClient

/**
 * Jamendo 开放曲库 Provider。
 *
 * 定位：**不是**商业曲库的替代品，而是"搜不到正版时给一个能听的合法结果"。
 * 因此：
 * - 搜索结果打 `open-catalog` 标签，UI 可以据此提示"开放曲库结果"；
 * - 不参与"智能替换"把原曲悄悄换成翻唱/同人曲的判断，只作为独立音源出现在结果里；
 * - 没有 client_id 时静默返回空，不报错、不伪造结果。
 *
 * 与旧的无损增强路径（`lossless/JamendoClient`）的关系：那一条是"给已有歌曲找 FLAC 替代"，
 * 这一条是"直接按关键词搜开放曲库"，两者共用同一个 client_id，但互不调用。
 */
class JamendoMusicProvider(
    providerCredentials: ProviderCredentialStore,
    clientIdProvider: suspend () -> String?,
    httpClient: HttpClient = createSharedProviderHttpClient(),
) : BaseKotlinProvider(
    http = ProviderHttpClient(httpClient),
    credentials = providerCredentials,
    id = ID,
    name = NAME,
    info = INFO,
    capabilities = CAPABILITIES,
    features = emptyList(),
) {

    private val api = JamendoApiClient(httpClient, clientIdProvider)

    override suspend fun search(keyword: String): ProviderSearchResults {
        val result = try {
            api.search(keyword)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ProviderSearchResults()
        }
        return ProviderSearchResults(tracks = result.tracks.map { it.toMusicTrack() })
    }

    override suspend fun resolve(track: MusicTrack, qualityPolicy: String): PlaybackPayload? {
        val identifier = (track.providerId ?: track.id).removePrefix("$ID:")
        if (identifier.isBlank()) return null
        // 重新取一次地址：搜索时拿到的 audio URL 可能已过期。
        val fresh = try {
            api.trackById(identifier)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } ?: return null
        if (fresh.audioUrl.isBlank()) return null
        return PlaybackPayload(
            url = fresh.audioUrl,
            title = fresh.name.ifBlank { track.title },
            artists = fresh.artist.ifBlank { track.artists },
            album = fresh.album.ifBlank { track.album },
            source = ID,
            audioQuality = QUALITY_LABEL,
            providerName = NAME,
        )
    }

    private fun JamendoTrack.toMusicTrack(): MusicTrack = track(
        identifier = id,
        title = name.ifBlank { "Untitled" },
        artists = artist,
        album = album,
        coverUrl = imageUrl.takeIf { it.isNotBlank() },
        durationMs = durationSeconds?.let { it * 1000L },
        providerUrl = shareUrl.takeIf { it.isNotBlank() },
    ).copy(providerTags = listOf(TAG, "open-catalog"))

    companion object {
        const val ID = "jamendo"
        const val NAME = "Jamendo 开放曲库"
        const val TAG = "jamendo"

        /** 不标具体码率：Jamendo 返回的是 mp3 流，客户端无法据此宣称 320k 或无损。 */
        const val QUALITY_LABEL = "jamendo-mp3"

        val INFO = ProviderInfo(providerId = ID, providerName = NAME)
        val CAPABILITIES = ProviderCapabilities(providerId = ID, providerName = NAME)
    }
}

class JamendoProviderFactory(
    private val clientIdProvider: suspend () -> String?,
) : KotlinProviderFactory {

    override val providerId: String = JamendoMusicProvider.ID
    override val info: ProviderInfo = JamendoMusicProvider.INFO

    override fun create(dependencies: ProviderRuntimeDependencies): KotlinMusicProvider =
        JamendoMusicProvider(
            providerCredentials = dependencies.credentials,
            clientIdProvider = clientIdProvider,
        )
}
