package org.feeluown.mobile.sourceupdate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 受控音源目录的数据模型与校验。
 *
 * 设计原则（对应设计文档第 5、6 章）：
 * - 客户端只信任"项目自己发布并签名"的目录，社区仓库只是候选来源；
 * - 任何一个字段都可能在传输中被篡改，所以这里做的是**结构校验**，
 *   内容可信度由 SHA-256 与 Ed25519 签名保证，二者缺一不可；
 * - 校验失败一律抛异常而不是"跳过坏条目继续装"——目录是机器生成的，
 *   出现异常说明发布流程有问题，宁可这一轮不更新，也不能装半份。
 */
@Serializable
data class SourceCatalog(
    val schemaVersion: Int = 1,
    val catalogVersion: Long = 0,
    val generatedAt: String? = null,
    val minAppVersion: String? = null,
    val signatureAlgorithm: String? = null,
    val keyId: String? = null,
    val sources: List<SourceCatalogEntry> = emptyList(),
)

@Serializable
data class SourceCatalogEntry(
    val id: String,
    val name: String,
    val version: String,
    val scriptUrl: String,
    val sha256: String,
    val sourceRepo: String = "",
    val sourcePath: String = "",
    val license: String = "unknown",
    val reviewStatus: String = REVIEW_CANDIDATE,
    val reviewedAt: String? = null,
    val allowedHosts: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val permissions: List<String> = listOf(PERMISSION_NETWORK),
    val autoInstall: Boolean = false,
    val autoEnable: Boolean = false,
    val sizeBytes: Long? = null,
    val notes: String? = null,
) {
    val review: SourceReviewStatus get() = SourceReviewStatus.parse(reviewStatus)

    companion object {
        const val REVIEW_CANDIDATE = "REVIEW_CANDIDATE"
        const val PERMISSION_NETWORK = "network"
    }
}

/** 审核状态。未知取值按最保守的 [REVIEW_CANDIDATE] 处理，绝不当成已批准。 */
enum class SourceReviewStatus(val wireName: String, val installable: Boolean) {
    APPROVED("APPROVED", true),
    REVIEW_CANDIDATE("REVIEW_CANDIDATE", false),
    QUARANTINE_CREDENTIAL_REVIEW("QUARANTINE_CREDENTIAL_REVIEW", false),
    QUARANTINE_OBFUSCATED("QUARANTINE_OBFUSCATED", false),
    QUARANTINE_API_REVIEW("QUARANTINE_API_REVIEW", false),
    QUARANTINE_LICENSE_AND_API_REVIEW("QUARANTINE_LICENSE_AND_API_REVIEW", false),
    REJECT_AUTO_CATALOG("REJECT_AUTO_CATALOG", false),
    REJECT_PUBLIC_DISTRIBUTION("REJECT_PUBLIC_DISTRIBUTION", false),
    ;

    companion object {
        fun parse(raw: String): SourceReviewStatus =
            entries.firstOrNull { it.wireName.equals(raw.trim(), ignoreCase = true) } ?: REVIEW_CANDIDATE
    }
}

sealed interface CatalogParseResult {
    data class Valid(val catalog: SourceCatalog, val warnings: List<String>) : CatalogParseResult
    data class Invalid(val reason: String) : CatalogParseResult
}

object SourceCatalogParser {

    const val SUPPORTED_SCHEMA_VERSION = 1
    const val MAX_CATALOG_BYTES = 1024 * 1024
    const val MAX_ENTRIES = 200
    const val MAX_NAME_LENGTH = 64

    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    private val HOST_PATTERN = Regex("^[a-z0-9.-]+$")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    }

    fun parse(rawText: String): CatalogParseResult {
        if (rawText.encodeToByteArray().size > MAX_CATALOG_BYTES) {
            return CatalogParseResult.Invalid("目录体积超过 ${MAX_CATALOG_BYTES} 字节上限")
        }
        val catalog = runCatching { json.decodeFromString(SourceCatalog.serializer(), rawText) }
            .getOrElse { return CatalogParseResult.Invalid("目录不是合法 JSON: ${it.message}") }
        return validate(catalog)
    }

    fun validate(catalog: SourceCatalog): CatalogParseResult {
        if (catalog.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return CatalogParseResult.Invalid(
                "不支持的目录 schemaVersion ${catalog.schemaVersion}，本版本只认 $SUPPORTED_SCHEMA_VERSION",
            )
        }
        if (catalog.catalogVersion < 0) {
            return CatalogParseResult.Invalid("catalogVersion 不能为负数")
        }
        if (catalog.sources.size > MAX_ENTRIES) {
            return CatalogParseResult.Invalid("目录条目数 ${catalog.sources.size} 超过上限 $MAX_ENTRIES")
        }

        val warnings = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (entry in catalog.sources) {
            if (!ID_PATTERN.matches(entry.id)) {
                return CatalogParseResult.Invalid("非法条目 id「${entry.id}」：只允许小写字母、数字与 . _ -，长度 1..64")
            }
            if (!seen.add(entry.id)) {
                return CatalogParseResult.Invalid("条目 id 重复：${entry.id}")
            }
            if (entry.name.isBlank() || entry.name.length > MAX_NAME_LENGTH) {
                return CatalogParseResult.Invalid("条目 ${entry.id} 的 name 为空或超过 $MAX_NAME_LENGTH 字符")
            }
            if (entry.version.isBlank()) {
                return CatalogParseResult.Invalid("条目 ${entry.id} 缺少 version")
            }
            if (!SHA256_PATTERN.matches(entry.sha256.lowercase())) {
                return CatalogParseResult.Invalid("条目 ${entry.id} 的 sha256 不是 64 位小写十六进制")
            }
            val urlProblem = urlProblem(entry.scriptUrl)
            if (urlProblem != null) {
                return CatalogParseResult.Invalid("条目 ${entry.id} 的 scriptUrl 非法：$urlProblem")
            }
            for (host in entry.allowedHosts) {
                if (!HOST_PATTERN.matches(host.lowercase()) || host.contains("/")) {
                    return CatalogParseResult.Invalid("条目 ${entry.id} 的 allowedHosts 含非法主机「$host」")
                }
            }
            if (entry.autoInstall && !entry.review.installable) {
                return CatalogParseResult.Invalid(
                    "条目 ${entry.id} 标记 autoInstall=true，但 reviewStatus=${entry.reviewStatus} 未通过审核",
                )
            }
            if (entry.autoInstall && entry.allowedHosts.isEmpty()) {
                // 与运行时 LxNetworkPolicy 的"空名单拒绝一切网络"对齐：
                // 允许一个没有网络权限的自动安装脚本，等于装上一个必然跑不起来的空壳。
                return CatalogParseResult.Invalid(
                    "条目 ${entry.id} 标记 autoInstall=true，但未声明 allowedHosts；" +
                        "运行时会拒绝其全部网络请求，不允许进入目录",
                )
            }
            if (entry.autoEnable && !entry.autoInstall) {
                return CatalogParseResult.Invalid("条目 ${entry.id} 标记 autoEnable=true 但没有 autoInstall")
            }
            if (entry.permissions.any { it != SourceCatalogEntry.PERMISSION_NETWORK }) {
                warnings += "条目 ${entry.id} 声明了 network 之外的权限，本版本会忽略"
            }
            if (entry.license.isBlank() || entry.license.equals("unknown", ignoreCase = true)) {
                warnings += "条目 ${entry.id} 未声明许可证，只能进入隔离区"
            }
            if (entry.allowedHosts.isEmpty() && !entry.autoInstall) {
                warnings += "条目 ${entry.id} 未声明 allowedHosts，若将来自动安装，运行时所有网络请求都会被拒绝"
            }
        }
        return CatalogParseResult.Valid(catalog, warnings)
    }

    /** 目录里的下载地址必须是 https 且不能指向内网，避免把 SSRF 引到更新器上。 */
    fun urlProblem(rawUrl: String): String? {
        val url = rawUrl.trim()
        if (!url.startsWith("https://", ignoreCase = true)) return "只允许 https"
        val host = url.removePrefix("https://").removePrefix("HTTPS://")
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase()
        if (host.isBlank()) return "缺少主机名"
        if (host == "localhost" || host.endsWith(".localhost")) return "禁止 localhost"
        if (isPrivateIpv4(host)) return "禁止私网地址"
        return null
    }

    fun isPrivateIpv4(host: String): Boolean {
        val segments = host.split('.')
        if (segments.size != 4) return false
        val octets = IntArray(4)
        for (index in 0..3) {
            octets[index] = segments[index].toIntOrNull() ?: return false
            if (octets[index] !in 0..255) return false
        }
        return when {
            octets[0] == 0 -> true
            octets[0] == 10 -> true
            octets[0] == 127 -> true
            octets[0] == 169 && octets[1] == 254 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            else -> false
        }
    }
}
