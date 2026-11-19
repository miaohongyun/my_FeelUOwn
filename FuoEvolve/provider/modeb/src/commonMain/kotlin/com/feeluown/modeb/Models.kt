package com.feeluown.modeb

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

class ConfigValidationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Serializable
data class SourceEntry(
    val id: String,
    val name: String,
    val apiBase: String,
    val urlPath: String,
    val params: Map<String, String> = emptyMap(),
    val priority: Int = 0,
    val enabled: Boolean = true,
    val urlField: String = "url",
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    // 可选搜索端点配置（用于接入 KotlinMusicProvider.search）
    val searchPath: String? = null,
    val searchKeywordParam: String = "q",
    val searchListField: String = "data",
    val searchIdField: String = "id",
    val searchTitleField: String = "title",
    val searchArtistField: String = "artist",
)

@Serializable
data class HealthPolicy(
    val failureThreshold: Int = 3,
    val cooldownSeconds: Long = 600,
    val requestTimeoutMillis: Long = 5000,
    val probeOnTrip: Boolean = false,
    val probeParams: Map<String, String> = emptyMap(),
)

@Serializable
data class ModeBConfig(
    val schemaVersion: Int,
    val configVersion: String,
    val sources: List<SourceEntry>,
    val remoteConfigUrls: List<String> = emptyList(),
    val manualOverride: String? = null,
    val healthPolicy: HealthPolicy = HealthPolicy(),
)

internal val ModeBJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

object ConfigValidator {
    fun parseAndValidate(rawJson: String): ModeBConfig {
        val config = try {
            val element = ModeBJson.parseToJsonElement(rawJson)
            ModeBJson.decodeFromJsonElement(ModeBConfig.serializer(), normalizeTimeoutKey(element))
        } catch (e: SerializationException) {
            throw ConfigValidationException("JSON 反序列化或字段类型校验失败: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ConfigValidationException("JSON 格式非法: ${e.message}", e)
        }

        if (config.schemaVersion < 1) {
            throw ConfigValidationException("schemaVersion 必须 >= 1")
        }
        if (config.configVersion.isBlank()) {
            throw ConfigValidationException("configVersion 不能为空")
        }
        if (config.sources.isEmpty()) {
            throw ConfigValidationException("sources 不能为空数组")
        }

        val seenIds = mutableSetOf<String>()
        for (src in config.sources) {
            if (src.id.isBlank()) throw ConfigValidationException("存在空的 source.id")
            if (!seenIds.add(src.id)) throw ConfigValidationException("存在重复 source.id: ${src.id}")
            if (src.name.isBlank()) throw ConfigValidationException("${src.id} 的 name 不能为空")
            if (!src.apiBase.startsWith("http://") && !src.apiBase.startsWith("https://")) {
                throw ConfigValidationException("${src.id} 的 apiBase 必须以 http:// 或 https:// 开头")
            }
            if (src.urlField.isBlank()) throw ConfigValidationException("${src.id} 的 urlField 不能为空")
            val m = src.method.uppercase()
            if (m != "GET" && m != "POST") {
                throw ConfigValidationException("${src.id} 的 method 仅支持 GET/POST")
            }
        }

        if (config.healthPolicy.failureThreshold < 1) {
            throw ConfigValidationException("failureThreshold 必须 >= 1")
        }
        if (config.healthPolicy.cooldownSeconds < 1) {
            throw ConfigValidationException("cooldownSeconds 必须 >= 1")
        }
        if (config.healthPolicy.requestTimeoutMillis <= 0) {
            throw ConfigValidationException("requestTimeoutMillis 必须 > 0")
        }
        return config
    }

    /**
     * Python 侧（mode_b_sources/models.py HealthPolicy）历史字段名是 `requestTimeoutSeconds`，
     * 本模块用的是 `requestTimeoutMillis`。两端共用同一份 sources.json 时，只写了 seconds 的
     * 配置在 Kotlin 端会静默退回默认 5000ms —— 值一旦不是 5 秒，两端超时就分叉了。
     * 这里在反序列化前做一次换算补齐；已显式提供 millis 时保持原样，不覆盖手写值。
     */
    private fun normalizeTimeoutKey(element: JsonElement): JsonElement {
        val root = element as? JsonObject ?: return element
        val policy = root["healthPolicy"] as? JsonObject ?: return element
        if (policy.containsKey("requestTimeoutMillis")) return element
        val seconds = (policy["requestTimeoutSeconds"] as? JsonPrimitive)?.doubleOrNull ?: return element
        if (seconds <= 0.0) return element
        val patchedPolicy = JsonObject(
            policy + ("requestTimeoutMillis" to JsonPrimitive((seconds * 1000).toLong())),
        )
        return JsonObject(root + ("healthPolicy" to patchedPolicy))
    }

    /** 递归排序 JSON Key，用于跨平台无依赖的内容一致性和变更比对 */
    fun canonicalFingerprint(rawJson: String): String {
        val element = ModeBJson.parseToJsonElement(rawJson)
        return ModeBJson.encodeToString(JsonElement.serializer(), sortElement(element))
    }

    private fun sortElement(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> JsonObject(
            el.entries.sortedBy { it.key }.associate { (k, v) -> k to sortElement(v) }
        )
        is JsonArray -> JsonArray(el.map { sortElement(it) })
        else -> el
    }
}
