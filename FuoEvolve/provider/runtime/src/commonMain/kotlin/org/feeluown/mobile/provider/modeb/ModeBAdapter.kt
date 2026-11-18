package org.feeluown.mobile.provider.modeb

import com.feeluown.modeb.AllSourcesDownException
import com.feeluown.modeb.DEFAULT_MODE_B_CONFIG_JSON
import com.feeluown.modeb.ModeBPlayResult
import com.feeluown.modeb.ModeBRouter
import com.feeluown.modeb.ModeBSearchHit
import com.feeluown.modeb.ModeBStorage
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.ProviderAuthState
import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.TrackSourceType
import org.feeluown.mobile.provider.core.BaseKotlinProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.createProviderHttpClient
import org.feeluown.mobile.provider.core.network.currentTimeMillis

/**
 * FeelUOwn 桌面端模式 B 路由器的薄适配层。
 *
 * 设计要点：
 * - 使用独立的裸 [HttpClient]（而非 ProviderHttpClient 的 10s/30s 重型客户端）；ModeBRouter 内部
 *   已对每个请求包 withTimeout(requestTimeoutMillis, 默认 5s)，因此"取流快失败"与
 *   "ExoPlayer 慢拉流"两套超时严格隔离——本客户端只负责解析播放 URL，不负责拉流。
 * - search 结果统一标记 source=modeb，保证 KotlinProviderRepository.providerIdFor(track) 能路由回本 Provider。
 */
class ModeBMusicProvider(
    providerCredentials: ProviderCredentialStore,
    httpClient: HttpClient = createProviderHttpClient(),
    storage: ModeBStorage = createPlatformModeBStorage(),
) : BaseKotlinProvider(
    http = ProviderHttpClient(httpClient),
    credentials = providerCredentials,
    id = ID,
    name = NAME,
    info = INFO,
    capabilities = CAPABILITIES,
    features = FEATURES,
) {

    val router: ModeBRouter = ModeBRouter(
        httpClient = httpClient,
        storage = storage,
        defaultConfigJson = DEFAULT_MODE_B_CONFIG_JSON,
        nowMillis = { currentTimeMillis() },
    )

    /**
     * Repository 初始化时会调用每个启用 provider 的 initialize()。
     * 这里用独立的 SupervisorJob + Default 作用域触发一次后台配置同步（非阻塞、失败静默沿用本地缓存），
     * 避免在 search/resolve 热路径里顺便同步。用 Default 而非 IO：Dispatchers.IO 在 Kotlin/Native
     * (iOS) 是 internal，跨 iOS/Android 只能用 Default。默认配置地址为 example.com，会被 Router 跳过，
     * 因此首启不会发起无谓网络请求；填入真实国内地址后即自动热更新。
     */
    override suspend fun initialize() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        router.startupSyncAsync(scope)
    }

    override suspend fun search(keyword: String): ProviderSearchResults {
        val hits = runCatching { router.searchTracks(keyword) }.getOrDefault(emptyList())
        val tracks = hits.map { it.toMusicTrack() }
        if (tracks.isNotEmpty()) return ProviderSearchResults(tracks = tracks)

        // 直连电台测试兜底：这些直播源没有 searchPath，正常 search 返回空。
        // 匹配关键词时把已启用源包装成虚拟歌曲，点播即可拿到真实 FLAC / m3u8 流，便于整机链路测试。
        if (isRadioProbeKeyword(keyword)) {
            val liveTracks = runCatching { router.enabledCandidates() }
                .getOrDefault(emptyList())
                .map { src ->
                    track(
                        identifier = "$LIVE_ID_PREFIX${src.id}",
                        title = "[直播] ${src.name}",
                        artists = src.id,
                        album = "Mode B 直连电台",
                    ).copy(providerTags = listOf("live", "modeb"))
                }
            if (liveTracks.isNotEmpty()) return ProviderSearchResults(tracks = liveTracks)
        }
        return ProviderSearchResults()
    }

    override suspend fun resolve(track: MusicTrack, qualityPolicy: String): PlaybackPayload? {
        val rawId = track.providerId ?: track.id
        val identifier = rawId.removePrefix("$ID:")
        // qualityPolicy 作为运行时参数透传（电台源会忽略，标准 REST 源可据此选档）
        val qualityParams = if (qualityPolicy.isBlank()) emptyMap() else mapOf("quality" to qualityPolicy)

        val result = try {
            when {
                // 直播虚拟歌曲：live::<sourceId>，锁定源、不传 id（电台端点会忽略）
                identifier.startsWith(LIVE_ID_PREFIX) -> {
                    router.resolveFromSource(identifier.removePrefix(LIVE_ID_PREFIX))
                }
                // 搜索命中的普通歌曲：s::<sourceId>|<trackId>。必须同时锁定 sourceId 并传 id，
                // 否则会把 id 丢失、被高优先级的电台源（/get_block 忽略参数）错误命中造成串源。
                identifier.startsWith(SEARCH_ID_PREFIX) -> {
                    val body = identifier.removePrefix(SEARCH_ID_PREFIX)
                    val sep = body.indexOf('|')
                    if (sep > 0) {
                        val sid = body.substring(0, sep)
                        val tid = body.substring(sep + 1)
                        router.resolveFromSource(sid, qualityParams + ("id" to tid))
                    } else {
                        // identifier 畸形（缺分隔符）时退化到自动路由
                        router.resolvePlayUrl(qualityParams)
                    }
                }
                else -> router.resolvePlayUrl(qualityParams)
            }
        } catch (_: AllSourcesDownException) {
            return null
        } catch (e: Exception) {
            return null
        }
        return result.toPlaybackPayload(track)
    }

    override suspend fun authState(): ProviderAuthState = ProviderAuthState(
        providerId = ID,
        providerName = NAME,
        isLoggedIn = true,
        userName = null,
    )

    // ===== 手动切源 / 看状态：供设置页或调试菜单调用 =====

    /** 手动刷新远程配置（对应"手动刷新"按钮） */
    suspend fun syncRemoteConfig() = router.syncRemoteConfig()

    /** 一键指定源；传 null 恢复自动路由 */
    suspend fun setManualOverride(sourceId: String?) = router.setManualOverride(sourceId)

    /** 查看各源熔断/冷却状态 */
    suspend fun breakerSnapshot() = router.breaker.snapshot()

    /** 一键清零所有熔断状态 */
    suspend fun resetBreaker() = router.breaker.resetAll()

    /** 当前启用的音源（供 UI 展示可切换列表） */
    suspend fun enabledCandidates() = router.enabledCandidates()

    private fun ModeBSearchHit.toMusicTrack(): MusicTrack = track(
        identifier = "$SEARCH_ID_PREFIX$sourceId|$trackId",
        title = title,
        artists = artist,
        album = sourceId,
    ).copy(providerTags = listOf("modeb"))

    private fun ModeBPlayResult.toPlaybackPayload(source: MusicTrack): PlaybackPayload = PlaybackPayload(
        url = url,
        title = source.title.ifBlank { sourceName },
        artists = source.artists,
        album = source.album,
        source = ID,
        headers = headers,
        audioQuality = "modeb",
        providerName = NAME,
    )

    companion object {
        const val ID = "modeb"
        const val NAME = "模式 B 多源路由"
        const val LIVE_ID_PREFIX = "live::"
        const val SEARCH_ID_PREFIX = "s::"
        private val RADIO_PROBE_KEYWORDS = setOf("radio", "flac", "无损", "电台", "直播", "modeb", "无损电台", "radioparadise")

        val INFO = ProviderInfo(providerId = ID, providerName = NAME)
        val CAPABILITIES = ProviderCapabilities(providerId = ID, providerName = NAME)
        val FEATURES = emptyList<org.feeluown.mobile.ProviderFeature>()

        private fun isRadioProbeKeyword(keyword: String): Boolean {
            val q = keyword.trim().lowercase()
            return q.isEmpty() || RADIO_PROBE_KEYWORDS.any { q.contains(it) }
        }
    }
}
