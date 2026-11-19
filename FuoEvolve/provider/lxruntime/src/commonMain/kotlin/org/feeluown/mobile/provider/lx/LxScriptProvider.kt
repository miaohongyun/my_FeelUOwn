package org.feeluown.mobile.provider.lx

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.feeluown.mobile.sourceupdate.crypto.Sha256
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

/** 一个已安装、已启用的脚本（由 sourceupdate 的版本仓库提供）。 */
data class LxInstalledScript(
    val id: String,
    val name: String,
    val version: String,
    val source: String,
    val allowedHosts: List<String>,
    val platforms: List<String>,
) {
    /**
     * 受控目录在审核时就写死了这个脚本"被批准服务哪些平台"。
     *
     * 脚本自己的 `inited` 声明可能比目录更大（例如更新后突然多声明一个 tx），
     * 那种情况必须按**目录**裁剪，否则审核范围形同虚设。只有目录没写平台时才回退到脚本自述。
     */
    fun allowsPlatform(platform: String): Boolean =
        platforms.isEmpty() || platform in platforms

    /** 目录批准 ∩ 脚本自述，搜索与解析都以此为准。 */
    fun grantedPlatforms(meta: LxScriptMeta): List<String> =
        meta.platforms.filter { allowsPlatform(it) }
}

interface LxInstalledScriptSource {
    suspend fun scripts(): List<LxInstalledScript>
}

object EmptyLxInstalledScriptSource : LxInstalledScriptSource {
    override suspend fun scripts(): List<LxInstalledScript> = emptyList()
}

/**
 * 把 LX 脚本包装成一个普通 Provider。
 *
 * 分工：
 * - **搜索**由 [LxSongSearcher] 完成（LX 协议没有搜索能力，脚本只负责解析）；
 * - **解析**由脚本完成，每个脚本一个独立 QuickJS context，解析失败就换下一个脚本；
 * - 音质按"目录声明 ∩ 脚本 inited 声明"取交集，再按用户策略排序，绝不把 320k 标成 FLAC。
 *
 * 失败语义与 Mode B 对齐：拿不到 URL 返回 null，由上层决定降级或换源。
 */
class LxScriptMusicProvider(
    providerCredentials: ProviderCredentialStore,
    private val scriptSource: LxInstalledScriptSource,
    private val httpClient: HttpClient = createSharedProviderHttpClient(),
    private val searcher: LxSongSearcher = KuwoSongSearcher(httpClient),
    private val runtimeFactory: () -> LxScriptRuntime = { LxScriptRuntime(httpClient) },
) : BaseKotlinProvider(
    http = ProviderHttpClient(httpClient),
    credentials = providerCredentials,
    id = ID,
    name = NAME,
    info = INFO,
    capabilities = CAPABILITIES,
    features = emptyList(),
) {

    private class Handle(val runtime: LxScriptRuntime, val meta: LxScriptMeta, val cacheKey: String)

    private val mutex = Mutex()
    private val handles = mutableMapOf<String, Handle>()

    /**
     * 搜索命中 → 平台原始字段。identifier（`platform:songId`）本身只携带稳定主 ID，
     * 各平台脚本读取的字段（酷我 MUSICRID、酷狗 hash、腾讯 strMediaMid）各不相同，
     * 这里按 identifier 缓存原始字段，供 [musicInfoFor] 原样透传给脚本。
     * 上限防止长期运行无限增长。
     */
    private val rawFields = mutableMapOf<String, Map<String, String>>()

    private fun rememberRawFields(identifier: String, fields: Map<String, String>) {
        if (fields.isEmpty()) return
        if (rawFields.size >= MAX_RAW_FIELD_ENTRIES) rawFields.clear()
        rawFields[identifier] = fields
    }

    override suspend fun search(keyword: String): ProviderSearchResults {
        val query = keyword.trim()
        if (query.isEmpty()) return ProviderSearchResults()
        val scripts = enabledScripts()
        pruneHandles(scripts)
        if (scripts.isEmpty()) return ProviderSearchResults()

        val results = LinkedHashMap<String, MusicTrack>()
        for (script in scripts) {
            val handle = handleFor(script) ?: continue
            for (platform in script.grantedPlatforms(handle.meta)) {
                if (platform !in searcher.supportedPlatforms) continue
                val hits = try {
                    searcher.search(query, platform)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                for (hit in hits) {
                    val identifier = "$platform:${hit.songId}"
                    rememberRawFields(identifier, hit.rawFields)
                    if (results.containsKey(identifier)) continue
                    results[identifier] = track(
                        identifier = identifier,
                        title = hit.title,
                        artists = hit.artist,
                        album = hit.album,
                        durationMs = hit.durationSeconds?.let { it * 1000L },
                    ).copy(providerTags = listOf(TAG, script.id))
                }
            }
        }
        return ProviderSearchResults(tracks = results.values.toList())
    }

    override suspend fun resolve(track: MusicTrack, qualityPolicy: String): PlaybackPayload? {
        val identifier = (track.providerId ?: track.id).removePrefix("$ID:")
        val platform = identifier.substringBefore(':', "")
        val songId = identifier.substringAfter(':', "")
        if (platform.isEmpty() || songId.isEmpty()) return null

        val musicInfo = musicInfoFor(platform, songId, track, rawFields[identifier].orEmpty())
        val scripts = enabledScripts()
        pruneHandles(scripts)
        for (script in scripts) {
            if (!script.allowsPlatform(platform)) continue
            val handle = handleFor(script) ?: continue
            val declared = handle.meta.qualitiesOf(platform)
            if (declared.isEmpty()) continue
            for (quality in LxQuality.candidates(qualityPolicy, declared)) {
                val url = try {
                    handle.runtime.requestMusicUrl(platform, musicInfo, quality)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: continue
                markHealthy(script.id)
                return PlaybackPayload(
                    url = url,
                    title = track.title,
                    artists = track.artists,
                    album = track.album,
                    source = ID,
                    audioQuality = quality,
                    providerName = NAME,
                )
            }
            markFailing(script.id)
        }
        return null
    }

    /**
     * 关闭所有常驻脚本实例。设置页停用脚本、或应用退出时调用，
     * 避免 QuickJS context 一直占内存。
     */
    suspend fun releaseAll() {
        mutex.withLock {
            handles.values.forEach { runCatching { it.runtime.close() } }
            handles.clear()
        }
    }

    private suspend fun enabledScripts(): List<LxInstalledScript> =
        try {
            scriptSource.scripts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * 回收已经不该存在的脚本实例。
     *
     * 用户停用脚本、脚本被目录移除、或版本变化时，旧 QuickJS context 必须关掉：
     * 否则每次启停都会多留一个常驻 context，长期运行会持续吃内存。
     */
    private suspend fun pruneHandles(active: List<LxInstalledScript>) {
        mutex.withLock {
            if (handles.isEmpty()) return@withLock
            val byId = active.associateBy { it.id }
            val stale = handles.keys.filter { id ->
                val script = byId[id]
                script == null || handles[id]?.cacheKey != cacheKeyOf(script)
            }
            stale.forEach { id -> handles.remove(id)?.let { runCatching { it.runtime.close() } } }
        }
    }

    /**
     * 缓存身份 = id + version + 源码 SHA-256。
     *
     * 只用 `version:source.length` 会在"版本号没升、源码改了但长度相同"时命中旧 context，
     * 用户看到的版本和实际执行的代码不一致。内容摘要必须参与身份判断。
     */
    private fun cacheKeyOf(script: LxInstalledScript): String =
        "${script.id}:${script.version}:${Sha256.hex(script.source.encodeToByteArray())}"

    private suspend fun handleFor(script: LxInstalledScript): Handle? = mutex.withLock {
        val cacheKey = cacheKeyOf(script)
        handles[script.id]?.let { if (it.cacheKey == cacheKey) return@withLock it else runCatching { it.runtime.close() } }

        val runtime = runtimeFactory()
        val meta = try {
            runtime.load(
                name = script.name.ifBlank { script.id },
                source = script.source,
                allowedHosts = script.allowedHosts.toSet(),
            )
            runtime.initialize()
        } catch (e: CancellationException) {
            runCatching { runtime.close() }
            throw e
        } catch (e: Exception) {
            runCatching { runtime.close() }
            handles.remove(script.id)
            return@withLock null
        }
        val handle = Handle(runtime, meta, cacheKey)
        handles[script.id] = handle
        handle
    }

    private suspend fun markHealthy(scriptId: String) {
        mutex.withLock { failures.remove(scriptId) }
    }

    private suspend fun markFailing(scriptId: String) {
        mutex.withLock {
            val count = (failures[scriptId] ?: 0) + 1
            failures[scriptId] = count
            if (count >= MAX_CONSECUTIVE_FAILURES) {
                // 连续失败达到阈值就不再让这个脚本拖慢每次播放；重新启用或重启后恢复。
                handles.remove(scriptId)?.let { runCatching { it.runtime.close() } }
                failures.remove(scriptId)
            }
        }
    }

    private val failures = mutableMapOf<String, Int>()

    /**
     * 组装脚本需要的 `musicInfo`。
     *
     * LX 各平台脚本字段并不统一（kw 用 songmid、kg 用 hash、tx 用 songmid+strMediaMid），
     * 而协议只给了 `musicInfo` 一个笼统的约定。这里传入一个**超集**，脚本取自己需要的字段；
     * 这也是 LX 桌面端把平台搜索结果整包透传给脚本的做法。
     */
    private fun musicInfoFor(
        platform: String,
        songId: String,
        track: MusicTrack,
        raw: Map<String, String>,
    ): JsonObject = buildJsonObject {
        // 优先用搜索响应里的原始字段；没有时才回退到 songId。
        // 过去把所有键都赋成 songId，会导致酷狗/腾讯脚本按 hash/strMediaMid 取到错误的值，
        // 进而把《晴天》解析成另一首歌。raw 是搜索命中时记录的同一条记录，身份一致。
        fun field(key: String): String = raw[key]?.takeIf { it.isNotBlank() } ?: songId
        put("songmid", field("songmid").ifBlank { songId })
        put("songId", field("songId"))
        put("musicId", field("musicId"))
        put("hash", field("hash"))
        put("strMediaMid", field("strMediaMid"))
        // 搜索返回的原始 ID 字段（酷我 MUSICRID、DC_TARGETID 等）原样透传。
        raw.forEach { (key, value) -> if (key !in CORE_INFO_KEYS) put(key, value) }
        put("name", raw["SONGNAME"]?.takeIf { it.isNotBlank() } ?: track.title)
        put("singer", raw["ARTIST"]?.takeIf { it.isNotBlank() } ?: track.artists)
        put("albumName", raw["ALBUM"]?.takeIf { it.isNotBlank() } ?: track.album)
        put("source", platform)
        track.durationMs?.let { put("interval", (it / 1000).toString()) }
    }

    companion object {
        const val ID = "lxscript"
        const val NAME = "LX 脚本音源"
        const val TAG = "lxscript"
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val MAX_RAW_FIELD_ENTRIES = 500

        /** 已经由显式字段承载的键，透传原始字段时跳过，避免重复 put。 */
        private val CORE_INFO_KEYS = setOf(
            "songmid", "songId", "musicId", "hash", "strMediaMid",
            "name", "SONGNAME", "singer", "ARTIST", "albumName", "ALBUM", "source", "interval",
        )

        val INFO = ProviderInfo(providerId = ID, providerName = NAME)
        val CAPABILITIES = ProviderCapabilities(providerId = ID, providerName = NAME)
    }
}

/**
 * 音质选择：用户策略 → 脚本声明 → 实际尝试顺序。
 *
 * 刻意不使用 `flac24bit`：社区脚本常把它当营销标签，而客户端无法验证码率，
 * 因此"母带/24bit"不作为默认尝试档位。
 */
object LxQuality {

    fun candidates(policy: String, declared: List<String>): List<String> {
        val available = declared.map { it.trim().lowercase() }.filter { it in LxProtocol.QUALITIES }
        if (available.isEmpty()) return emptyList()
        val preferred = when (policy.trim().lowercase()) {
            "highest", "lossless", "sq" -> listOf("flac", "320k", "128k")
            "high", "hq" -> listOf("320k", "128k")
            else -> listOf("128k")
        }
        val ordered = preferred.filter { it in available } + available.filter { it !in preferred }
        return ordered.distinct().take(2)
    }
}

class LxScriptProviderFactory(
    private val scriptSource: LxInstalledScriptSource,
) : KotlinProviderFactory {

    override val providerId: String = LxScriptMusicProvider.ID
    override val info: ProviderInfo = LxScriptMusicProvider.INFO

    override fun create(dependencies: ProviderRuntimeDependencies): KotlinMusicProvider =
        LxScriptMusicProvider(
            providerCredentials = dependencies.credentials,
            scriptSource = scriptSource,
        )
}
