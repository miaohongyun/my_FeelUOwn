package org.feeluown.mobile.provider.jamendo

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.TrackLicense
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.ProviderFailure
import org.feeluown.mobile.ProviderFailureKind
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
            return ProviderSearchResults(
                failure = ProviderFailure(
                    kind = ProviderFailureKind.Unknown,
                    providerId = ID,
                    technicalMessage = e.message,
                ),
            )
        }
        // 真实失败（无 client_id / 网络 / HTTP）映射到通用 failure，让 UI 给出明确提示；
        // 正常空结果（failureKind=None）保持空列表且无 failure，不污染统一搜索。
        val failure = result.failureKind.toProviderFailure()
        return ProviderSearchResults(
            tracks = result.tracks.map { it.toMusicTrack() },
            failure = failure,
        )
    }

    private fun JamendoFailureKind.toProviderFailure(): ProviderFailure? = when (this) {
        JamendoFailureKind.None -> null
        JamendoFailureKind.MissingClientId -> ProviderFailure(
            kind = ProviderFailureKind.ContentUnavailable,
            providerId = ID,
            technicalMessage = "Jamendo client_id 未配置，请在设置中填写",
        )
        JamendoFailureKind.Network -> ProviderFailure(
            kind = ProviderFailureKind.Network,
            providerId = ID,
        )
        JamendoFailureKind.Upstream -> ProviderFailure(
            kind = ProviderFailureKind.UpstreamContractChanged,
            providerId = ID,
        )
    }

    override suspend fun resolve(track: MusicTrack, qualityPolicy: String): PlaybackPayload? {
        val identifier = (track.providerId ?: track.id).removePrefix("$ID:")
        if (identifier.isBlank()) return null
        // 重新取地址：搜索时拿到的 audio URL 可能已过期。
        // K1 实测 Jamendo 公开 API 的 /tracks/?id= 端点偶发返回空（同一 id 有时 results_count=0），
        // 单次尝试会让用户点播放偶发失败。这里做有限重试（与 E2E 取证一致的容忍度）；
        // 仍重取、不使用旧 URL，不改变权限/来源/默认策略。取消立即传播，全部失败才返回 null。
        var fresh: org.feeluown.mobile.provider.jamendo.JamendoTrack? = null
        for (attempt in 1..TRACK_BY_ID_MAX_ATTEMPTS) {
            try {
                fresh = api.trackById(identifier)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fresh = null
            }
            if (fresh != null && fresh.audioUrl.isNotBlank()) break
            if (attempt < TRACK_BY_ID_MAX_ATTEMPTS) delay(TRACK_BY_ID_RETRY_DELAY_MS)
        }
        if (fresh == null || fresh.audioUrl.isBlank()) return null
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
    ).copy(
        // 不笼统标 "open-catalog"：保留具体 CC 许可证类型与原始链接，供 UI 展示适用条件。
        providerTags = buildList {
            add(TAG)
            if (licenseCode.isNotEmpty()) add("cc:$licenseCode")
            if (licenseCcUrl.isNotBlank()) add("license-url:$licenseCcUrl")
            add(licenseSummary)
        },
        // K2 结构化许可：从 API 的 license_ccurl 逐曲构造，替代"仅靠 cc: 字符串展示"。
        // Jamendo 允许在线播放，但本阶段不开放下载：downloadLicense 恒为 false（默认拒绝），
        // ND 曲目 derivativesAllowed=false，NC 曲目 commercialUseAllowed=false，全部如实编码。
        license = TrackLicense(
            licenseType = if (licenseCode.isNotEmpty()) "CC ${licenseCode.uppercase()}" else "UNKNOWN",
            licenseUrl = licenseCcUrl.takeIf { it.isNotBlank() },
            attribution = licenseSummary.takeIf { it.isNotBlank() },
            playLicense = true,
            downloadLicense = false,
            commercialUseAllowed = licenseCode.isNotEmpty() && "NC" !in licenseCode.uppercase(),
            derivativesAllowed = licenseCode.isNotEmpty() && "ND" !in licenseCode.uppercase(),
            redistributionAllowed = false,
            licenseVerifiedAt = null,
            licenseSource = "jamendo-api:license_ccurl",
        ),
    )

    companion object {
        const val ID = "jamendo"
        const val NAME = "Jamendo 开放曲库"
        const val TAG = "jamendo"

        /** 不标具体码率：Jamendo 返回的是 mp3 流，客户端无法据此宣称 320k 或无损。 */
        const val QUALITY_LABEL = "jamendo-mp3"

        /** K1 最小修复：/tracks/?id= 偶发空结果，重取有限重试，仍不使用旧 URL。 */
        const val TRACK_BY_ID_MAX_ATTEMPTS = 3
        const val TRACK_BY_ID_RETRY_DELAY_MS = 300L

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
            // 复用应用统一网络层的底层 client：代理/超时/测试注入都经由 ProviderHttpClient，
            // 不再各自 createSharedProviderHttpClient() 绕过配置。
            httpClient = dependencies.http.client,
        )
}
