package com.feeluown.modeb

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class SourceConfigException(message: String) : RuntimeException(message)
class TrackNotFoundException(message: String) : RuntimeException(message)
class SourceUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class AllSourcesDownException(message: String) : RuntimeException(message)

data class ModeBPlayResult(
    val sourceId: String,
    val sourceName: String,
    val url: String,
    val headers: Map<String, String>,
    val rawPayload: JsonElement,
)

data class ModeBSearchHit(
    val sourceId: String,
    val trackId: String,
    val title: String,
    val artist: String,
)

data class SyncResult(
    val updated: Boolean,
    val message: String,
)

/**
 * 纯 KMP Mode B 路由器（无 Android Context / JVM IO 硬依赖）：
 * 1. initialize(): 缓存优先读取本地，坏配置自动回退默认配置；
 * 2. startupSyncAsync(): 后台限时单次同步远端配置（不轮询、校验不通过丢弃保底）；
 * 3. resolvePlayUrl() / searchTracks(): 懒检查 + 失败驱动降级 + manualOverride 调试入口。
 */
class ModeBRouter(
    private val httpClient: HttpClient,
    private val storage: ModeBStorage,
    private val defaultConfigJson: String,
    private val cacheKey: String = "modeb_sources_cache.json",
    private val healthKey: String = "modeb_health_state.json",
    private val tokenProvider: () -> String? = { null },
    private val nowMillis: () -> Long,
) {
    private val configMutex = Mutex()
    private lateinit var activeConfig: ModeBConfig
    private var activeFingerprint: String = ""
    private var initialized = false

    val breaker: CircuitBreaker = CircuitBreaker(
        storage = storage,
        storageKey = healthKey,
        nowMillis = nowMillis,
    )

    suspend fun initialize(): ModeBConfig = configMutex.withLock {
        if (initialized) return@withLock activeConfig

        val cachedRaw = storage.readText(cacheKey)
        val (cfg, rawToKeep) = if (!cachedRaw.isNullOrBlank()) {
            runCatching { ConfigValidator.parseAndValidate(cachedRaw) to cachedRaw }
                .getOrElse {
                    val defCfg = ConfigValidator.parseAndValidate(defaultConfigJson)
                    storage.writeText(cacheKey, defaultConfigJson)
                    defCfg to defaultConfigJson
                }
        } else {
            val defCfg = ConfigValidator.parseAndValidate(defaultConfigJson)
            storage.writeText(cacheKey, defaultConfigJson)
            defCfg to defaultConfigJson
        }

        activeConfig = cfg
        activeFingerprint = ConfigValidator.canonicalFingerprint(rawToKeep)
        breaker.updatePolicy(cfg.healthPolicy.failureThreshold, cfg.healthPolicy.cooldownSeconds)
        initialized = true
        cfg
    }

    fun startupSyncAsync(
        scope: CoroutineScope,
        onFinished: ((SyncResult) -> Unit)? = null,
    ): Job = scope.launch {
        val result = syncRemoteConfig()
        onFinished?.invoke(result)
    }

    suspend fun currentConfig(): ModeBConfig {
        initialize()
        return configMutex.withLock { activeConfig }
    }

    suspend fun syncRemoteConfig(): SyncResult {
        initialize()
        val (urls, timeoutMs, curFp, curVer) = configMutex.withLock {
            Quad(
                activeConfig.remoteConfigUrls,
                activeConfig.healthPolicy.requestTimeoutMillis,
                activeFingerprint,
                activeConfig.configVersion,
            )
        }

        if (urls.isEmpty()) {
            return SyncResult(false, "未配置 remoteConfigUrls，继续使用本地缓存 ($curVer)")
        }

        val token = tokenProvider()?.trim().orEmpty()
        var lastReason = "无可用远程配置地址"

        for (url in urls) {
            if ("example.com" in url) continue
            val rawText = try {
                withTimeout(timeoutMs) {
                    val resp = httpClient.request(url) {
                        method = HttpMethod.Get
                        headers {
                            append("User-Agent", "FuoEvolve-ModeB/1.0")
                            append("Accept", "application/json")
                            if (token.isNotEmpty()) append("Authorization", "Bearer $token")
                        }
                    }
                    if (resp.status.value >= 400) {
                        throw SourceUnavailableException("HTTP ${resp.status.value}")
                    }
                    resp.bodyAsText()
                }
            } catch (e: TimeoutCancellationException) {
                lastReason = "远程拉取超时 (${timeoutMs}ms)"
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastReason = "远程拉取失败: ${e.message}"
                continue
            }

            val parsed = try {
                ConfigValidator.parseAndValidate(rawText)
            } catch (e: ConfigValidationException) {
                lastReason = "远程配置校验失败，已丢弃并保底旧缓存: ${e.message}"
                continue
            }

            val newFp = ConfigValidator.canonicalFingerprint(rawText)
            if (newFp == curFp && parsed.configVersion == curVer) {
                return SyncResult(false, "远程配置无变化 ($curVer)")
            }

            configMutex.withLock {
                activeConfig = parsed
                activeFingerprint = newFp
                storage.writeText(cacheKey, rawText)
            }
            breaker.updatePolicy(parsed.healthPolicy.failureThreshold, parsed.healthPolicy.cooldownSeconds)
            return SyncResult(true, "远程配置已热更新至 ${parsed.configVersion}")
        }

        return SyncResult(false, lastReason)
    }

    suspend fun setManualOverride(sourceId: String?) {
        initialize()
        configMutex.withLock {
            if (sourceId != null && activeConfig.sources.none { it.id == sourceId }) {
                throw SourceConfigException("未知的 sourceId: $sourceId")
            }
            val updated = activeConfig.copy(manualOverride = sourceId)
            val jsonText = ModeBJson.encodeToString(ModeBConfig.serializer(), updated)
            activeConfig = updated
            activeFingerprint = ConfigValidator.canonicalFingerprint(jsonText)
            storage.writeText(cacheKey, jsonText)
        }
    }

    suspend fun enabledCandidates(): List<SourceEntry> {
        initialize()
        return configMutex.withLock {
            activeConfig.sources.filter { it.enabled }.sortedByDescending { it.priority }
        }
    }

    /**
     * 按指定 sourceId 直接取流（供"直连电台测试兜底"的虚拟歌曲点击播放时使用）。
     * 不走 manualOverride，避免污染全局配置；但同样经过熔断器的成功/失败记账。
     */
    suspend fun resolveFromSource(sourceId: String, runtimeParams: Map<String, String> = emptyMap()): ModeBPlayResult {
        initialize()
        val cfg = configMutex.withLock { activeConfig }
        val target = cfg.sources.firstOrNull { it.id == sourceId && it.enabled }
            ?: throw SourceConfigException("源 [$sourceId] 不存在或已禁用")
        return try {
            val res = requestPlayUrl(target, runtimeParams, cfg.healthPolicy.requestTimeoutMillis)
            breaker.recordSuccess(target.id)
            res
        } catch (e: SourceUnavailableException) {
            breaker.recordFailure(target.id, e.message ?: "unavailable")
            throw e
        }
    }

    suspend fun resolvePlayUrl(runtimeParams: Map<String, String> = emptyMap()): ModeBPlayResult {
        initialize()
        val cfg = configMutex.withLock { activeConfig }
        val overrideId = cfg.manualOverride

        if (!overrideId.isNullOrBlank()) {
            val target = cfg.sources.firstOrNull { it.id == overrideId }
                ?: throw SourceConfigException("manualOverride 指向不存在的源: $overrideId")
            return try {
                val res = requestPlayUrl(target, runtimeParams, cfg.healthPolicy.requestTimeoutMillis)
                breaker.recordSuccess(target.id)
                res
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) {
                    throw AllSourcesDownException("手动指定的源 [${target.id}] 请求超时")
                }
                throw e
            } catch (e: Exception) {
                throw AllSourcesDownException("手动指定的源 [${target.id}] 请求失败: ${e.message}")
            }
        }

        val candidates = cfg.sources.filter { it.enabled }.sortedByDescending { it.priority }
        if (candidates.isEmpty()) {
            throw AllSourcesDownException("当前没有任何启用的音乐源")
        }

        val errors = mutableListOf<String>()
        var sawTrackNotFound = false

        for (idx in candidates.indices) {
            val entry = candidates[idx]
            if (breaker.acquirePermission(entry.id) == GatePermission.OPEN) {
                errors += "[${entry.id}] 冷却中，已跳过"
                continue
            }

            try {
                val res = requestPlayUrl(entry, runtimeParams, cfg.healthPolicy.requestTimeoutMillis)
                breaker.recordSuccess(entry.id)
                return res
            } catch (e: TrackNotFoundException) {
                breaker.recordSuccess(entry.id)
                sawTrackNotFound = true
                errors += "[${entry.id}] 未找到该曲目: ${e.message}"
            } catch (e: SourceUnavailableException) {
                val trippedNow = breaker.recordFailure(entry.id, e.message ?: "unavailable")
                errors += "[${entry.id}] 请求失败: ${e.message}"
                if (trippedNow && cfg.healthPolicy.probeOnTrip && idx + 1 < candidates.size) {
                    probeCandidate(candidates[idx + 1], cfg.healthPolicy)
                }
            }
        }

        if (sawTrackNotFound) {
            throw TrackNotFoundException("所有可用源均未找到该曲目:\n" + errors.joinToString("\n"))
        }
        throw AllSourcesDownException("所有音乐源均不可用，请检查网络或手动切换源:\n" + errors.joinToString("\n"))
    }

    suspend fun searchTracks(keyword: String): List<ModeBSearchHit> {
        initialize()
        val cfg = configMutex.withLock { activeConfig }
        val searchable = cfg.sources
            .filter { it.enabled && !it.searchPath.isNullOrBlank() }
            .sortedByDescending { it.priority }

        if (searchable.isEmpty()) return emptyList()

        val errors = mutableListOf<String>()
        for (entry in searchable) {
            if (breaker.acquirePermission(entry.id) == GatePermission.OPEN) continue
            try {
                val hits = requestSearch(entry, keyword, cfg.healthPolicy.requestTimeoutMillis)
                breaker.recordSuccess(entry.id)
                if (hits.isNotEmpty()) return hits
            } catch (e: TrackNotFoundException) {
                breaker.recordSuccess(entry.id)
            } catch (e: SourceUnavailableException) {
                breaker.recordFailure(entry.id, e.message ?: "search unavailable")
                errors += "[${entry.id}] ${e.message}"
            }
        }
        return emptyList()
    }

    private suspend fun probeCandidate(entry: SourceEntry, policy: HealthPolicy) {
        if (breaker.acquirePermission(entry.id) == GatePermission.OPEN) return
        try {
            requestPlayUrl(entry, policy.probeParams, policy.requestTimeoutMillis)
            breaker.recordSuccess(entry.id)
        } catch (_: TrackNotFoundException) {
            breaker.recordSuccess(entry.id)
        } catch (e: SourceUnavailableException) {
            breaker.recordFailure(entry.id, "故障切换探针失败: ${e.message}")
        }
    }

    private suspend fun requestPlayUrl(
        entry: SourceEntry,
        runtimeParams: Map<String, String>,
        timeoutMs: Long,
    ): ModeBPlayResult {
        val merged = entry.params + runtimeParams
        val payload = executeJsonRequest(
            apiBase = entry.apiBase,
            path = entry.urlPath,
            methodName = entry.method,
            params = merged,
            extraHeaders = entry.headers,
            timeoutMs = timeoutMs,
        )
        val url = extractStringByPath(payload, entry.urlField)?.trim().orEmpty()
        if (url.isEmpty()) {
            throw TrackNotFoundException("响应中未提取到有效 URL 字段 '${entry.urlField}'")
        }
        return ModeBPlayResult(
            sourceId = entry.id,
            sourceName = entry.name,
            url = url,
            headers = entry.headers,
            rawPayload = payload,
        )
    }

    private suspend fun requestSearch(
        entry: SourceEntry,
        keyword: String,
        timeoutMs: Long,
    ): List<ModeBSearchHit> {
        val searchPath = entry.searchPath ?: return emptyList()
        val params = entry.params + mapOf(entry.searchKeywordParam to keyword)
        val payload = executeJsonRequest(
            apiBase = entry.apiBase,
            path = searchPath,
            methodName = "GET",
            params = params,
            extraHeaders = entry.headers,
            timeoutMs = timeoutMs,
        )
        val arr = extractElementByPath(payload, entry.searchListField) as? JsonArray
            ?: return emptyList()

        return arr.mapNotNull { item ->
            val id = extractStringByPath(item, entry.searchIdField)?.trim().orEmpty()
            val title = extractStringByPath(item, entry.searchTitleField)?.trim().orEmpty()
            val artist = extractStringByPath(item, entry.searchArtistField)?.trim().orEmpty()
            if (id.isEmpty() || title.isEmpty()) null
            else ModeBSearchHit(sourceId = entry.id, trackId = id, title = title, artist = artist)
        }
    }

    private suspend fun executeJsonRequest(
        apiBase: String,
        path: String,
        methodName: String,
        params: Map<String, String>,
        extraHeaders: Map<String, String>,
        timeoutMs: Long,
    ): JsonElement {
        val isPost = methodName.equals("POST", ignoreCase = true)
        val requestUrl = buildUrl(apiBase, path, if (isPost) emptyMap() else params)

        val bodyText = try {
            withTimeout(timeoutMs) {
                val resp = httpClient.request(requestUrl) {
                    method = if (isPost) HttpMethod.Post else HttpMethod.Get
                    headers {
                        append("User-Agent", "FuoEvolve-ModeB/1.0")
                        append("Accept", "application/json")
                        extraHeaders.forEach { (k, v) -> append(k, v) }
                    }
                    if (isPost) {
                        contentType(ContentType.Application.Json)
                        val obj = JsonObject(params.mapValues { JsonPrimitive(it.value) })
                        setBody(ModeBJson.encodeToString(JsonObject.serializer(), obj))
                    }
                }
                val code = resp.status.value
                if (code == 404) throw TrackNotFoundException("HTTP 404")
                if (code >= 400) throw SourceUnavailableException("HTTP $code")
                resp.bodyAsText()
            }
        } catch (e: TrackNotFoundException) {
            throw e
        } catch (e: SourceUnavailableException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            throw SourceUnavailableException("请求超时 (${timeoutMs}ms)", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SourceUnavailableException("网络请求异常: ${e.message}", e)
        }

        return try {
            ModeBJson.parseToJsonElement(bodyText)
        } catch (e: Exception) {
            throw SourceUnavailableException("响应 JSON 解析失败: ${e.message}", e)
        }
    }

    private fun buildUrl(apiBase: String, path: String, queryParams: Map<String, String>): String {
        val base = apiBase.trimEnd('/')
        // 支持路径参数占位符 {id}：runtimeParams 里的 id 已 merge 进 queryParams。
        // 若 path 含 {id}，用 id 替换并从 query 中移除 id，避免同时出现在路径和查询串。
        // 无 {id} 时行为与原来完全一致（现有 ?id= 源不受影响）。
        val idForPath = queryParams["id"]
        val cleanPath = if (idForPath != null && path.contains("{id}")) {
            path.replace("{id}", idForPath)
        } else {
            path
        }.trimStart('/')
        val combined = if (cleanPath.isEmpty()) base else "$base/$cleanPath"
        return URLBuilder(combined).apply {
            queryParams.forEach { (k, v) ->
                if (!(idForPath != null && path.contains("{id}") && k == "id")) {
                    parameters[k] = v
                }
            }
        }.buildString()
    }

    private fun extractStringByPath(root: JsonElement, path: String): String? {
        val el = extractElementByPath(root, path) ?: return null
        return when (el) {
            is JsonPrimitive -> el.contentOrNull ?: el.content
            else -> null
        }
    }

    private fun extractElementByPath(root: JsonElement, path: String): JsonElement? {
        var cur: JsonElement = root
        for (part in path.split(".")) {
            cur = when (cur) {
                is JsonObject -> cur[part] ?: return null
                is JsonArray -> {
                    val idx = part.toIntOrNull() ?: return null
                    if (idx !in cur.indices) return null
                    cur[idx]
                }
                else -> return null
            }
        }
        return cur
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
