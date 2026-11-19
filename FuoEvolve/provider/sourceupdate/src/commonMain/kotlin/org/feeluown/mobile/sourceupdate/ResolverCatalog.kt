package org.feeluown.mobile.sourceupdate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 解析器目录。
 *
 * 只允许三类：官方授权 API、开放曲库（如 Jamendo）、用户自托管服务。
 * 目录本身**只描述协议**，不下发任何可执行代码——这是与"远程脚本"最根本的区别。
 */
@Serializable
data class ResolverCatalog(
    val schemaVersion: Int = 1,
    val resolvers: List<ResolverEntry> = emptyList(),
)

@Serializable
data class ResolverEntry(
    val id: String,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val authMode: String = "none",
    val timeoutMs: Int = 8_000,
    val allowedHosts: List<String> = emptyList(),
    val enabledByDefault: Boolean = false,
    val priority: Int = 100,
    val healthCheckPath: String? = null,
    val notes: String? = null,
) {
    val resolverKind: ResolverKind? get() = ResolverKind.parse(kind)
}

enum class ResolverKind(val wireName: String) {
    OFFICIAL_API("official-api"),
    OPEN_CATALOG("open-catalog"),
    SELF_HOSTED("self-hosted"),
    ;

    companion object {
        fun parse(raw: String): ResolverKind? =
            entries.firstOrNull { it.wireName.equals(raw.trim(), ignoreCase = true) }
    }
}

sealed interface ResolverValidation {
    data class Valid(val entries: List<ResolverEntry>, val warnings: List<String>) : ResolverValidation
    data class Invalid(val reason: String) : ResolverValidation
}

object ResolverCatalogParser {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun parse(rawText: String): ResolverValidation {
        val catalog = runCatching { json.decodeFromString(ResolverCatalog.serializer(), rawText) }
            .getOrElse { return ResolverValidation.Invalid("解析器目录不是合法 JSON: ${it.message}") }
        if (catalog.schemaVersion != 1) {
            return ResolverValidation.Invalid("不支持的解析器目录 schemaVersion ${catalog.schemaVersion}")
        }
        if (catalog.resolvers.size > 50) {
            return ResolverValidation.Invalid("解析器条目过多：${catalog.resolvers.size}")
        }

        val warnings = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (entry in catalog.resolvers) {
            if (!seen.add(entry.id)) return ResolverValidation.Invalid("解析器 id 重复：${entry.id}")
            val kind = entry.resolverKind
                ?: return ResolverValidation.Invalid("解析器 ${entry.id} 的 kind「${entry.kind}」不受支持")
            SourceCatalogParser.urlProblem(entry.baseUrl)?.let {
                return ResolverValidation.Invalid("解析器 ${entry.id} 的 baseUrl 非法：$it")
            }
            if (entry.timeoutMs !in 1_000..60_000) {
                return ResolverValidation.Invalid("解析器 ${entry.id} 的 timeoutMs 必须在 1000..60000")
            }
            if (kind == ResolverKind.SELF_HOSTED) {
                warnings += "解析器 ${entry.id} 为自托管服务，需用户自行保证内容授权"
            }
            if (entry.allowedHosts.isEmpty()) {
                warnings += "解析器 ${entry.id} 未声明 allowedHosts"
            }
        }
        return ResolverValidation.Valid(catalog.resolvers.sortedBy { it.priority }, warnings)
    }
}

data class ResolverHealth(
    val failures: Int = 0,
    val circuitOpenUntilMillis: Long = 0L,
    val lastError: String? = null,
)

/** 连续失败熔断 + 冷却后探测恢复。与 Mode B 的熔断器同构，但状态独立。 */
class ResolverHealthMonitor(
    private val failureThreshold: Int = 3,
    private val circuitMillis: Long = 30L * 60L * 1000L,
) {
    private val entries = mutableMapOf<String, ResolverHealth>()

    fun isAvailable(resolverId: String, nowMillis: Long): Boolean {
        val entry = entries[resolverId] ?: return true
        return nowMillis >= entry.circuitOpenUntilMillis
    }

    fun recordFailure(resolverId: String, nowMillis: Long, reason: String? = null) {
        val entry = entries[resolverId] ?: ResolverHealth()
        val failures = entry.failures + 1
        entries[resolverId] = entry.copy(
            failures = failures,
            lastError = reason,
            circuitOpenUntilMillis = if (failures >= failureThreshold) nowMillis + circuitMillis else entry.circuitOpenUntilMillis,
        )
    }

    fun recordSuccess(resolverId: String) {
        entries[resolverId] = ResolverHealth()
    }

    fun snapshot(): Map<String, ResolverHealth> = entries.toMap()
}
