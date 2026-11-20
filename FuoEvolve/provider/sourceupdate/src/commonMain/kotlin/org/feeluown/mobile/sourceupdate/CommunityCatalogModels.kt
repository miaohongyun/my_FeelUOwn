package org.feeluown.mobile.sourceupdate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 社区音源目录「自动发现」的数据模型（阶段 C 框架）。
 *
 * 设计红线（与 SourceCatalogModels 的受控目录严格区分）：
 * - 社区清单(shellsec/pdone 的 sources.json) **不是信任根**，只是候选线索；
 * - 本框架发现的条目只写进「候选区」并产出审核报告，**绝不**直接执行脚本、安装、
 *   扩大网络白名单、自动批准 BLOCK 项，或覆盖受控目录 source-catalog.json；
 * - 只有人工审核通过后，才由发布流程把条目写进受控目录并签名（见 config/source-catalog/README.md）。
 *
 * 客户端验收标准：解析/去重/扫描/状态流转全部可离线测试，不依赖网络与私钥。
 */

/** 社区清单原始 JSON 的顶层结构：{"sources":[{"id","name","url"}, ...]}。 */
@Serializable
data class CommunitySourceList(
    val sources: List<CommunitySourceEntry> = emptyList(),
)

@Serializable
data class CommunitySourceEntry(
    val id: String,
    val name: String,
    val url: String,
)

/**
 * 候选审核状态。与受控目录的 [SourceReviewStatus] 分开：
 * 这里是发现框架的生命周期，未知取值一律按最保守的 [DISCOVERED] 处理。
 */
enum class CandidateReviewState(val wireName: String) {
    DISCOVERED("DISCOVERED"),
    QUARANTINED("QUARANTINED"),
    NEEDS_REVIEW("NEEDS_REVIEW"),
    APPROVED("APPROVED"),
    REJECTED("REJECTED"),
    STALE("STALE"),
    ;

    companion object {
        fun parse(raw: String?): CandidateReviewState =
            entries.firstOrNull { it.wireName.equals(raw?.trim(), ignoreCase = true) } ?: DISCOVERED
    }
}

/**
 * 真实验证等级（单调递进）。Mock / 静态 / URL 解析 **不得**标成 REAL_*。
 * 与 config/source-catalog/candidates.json 的 testLevelDefinitions 语义一致，
 * 这里是运行时发现框架自己的等级枚举，不读取人工审核文件。
 */
enum class VerificationLevel(val rank: Int) {
    STATIC_REVIEW(0),
    LX_RUNTIME_LOAD(1),
    REAL_SEARCH(2),
    REAL_URL_RESOLVE(3),
    REAL_AUDIO_RESPONSE(4),
    REAL_PLAYBACK(5),
    REAL_TRACK_IDENTITY_VERIFIED(6),
    REAL_LOSSLESS_VERIFIED(7),
    ;

    val isReal: Boolean get() = rank >= REAL_SEARCH.rank
}

/** 风险等级：发现框架据静态扫描给出，仅用于排序人工审核队列。 */
enum class CandidateRisk { LOW, MEDIUM, HIGH, CRITICAL }

/**
 * 一条候选记录。持久化在候选区（隔离区），不进受控目录。
 *
 * [rawUrl] 保留清单原始地址；[normalizedUrl] 用于去重；[repoPath] 用于同仓库路径去重。
 * [sha256]/[sizeBytes] 只有下载到隔离区后才填充；[staticVerdict] 来自 [SourceStaticPolicy]。
 * [reviewState] 永远是人工流转的结果——发现框架只写 DISCOVERED/QUARANTINED，绝不写 APPROVED。
 */
@Serializable
data class DiscoveredCandidate(
    val id: String,
    val name: String,
    val rawUrl: String,
    val normalizedUrl: String,
    val repoPath: String,
    val sourceRepo: String = "",
    /** 同一脚本的镜像地址（规范化后），用于归并。 */
    val mirrorUrls: List<String> = emptyList(),
    /**
     * 被归并进本记录的「其他 ID / 别名」。
     * 去重时不同 id 的同脚本会合并到一条主记录，若不保存别名，下一次清单只剩别名时
     * STALE 检查会误判主 id 已消失。STALE 判定必须把主 id 与别名一起计算。
     */
    val aliasIds: List<String> = emptyList(),
    val sha256: String? = null,
    /** true = 隔离区脚本的哈希未经来源确认（候选无预期 SHA256 时下载，仅作参考不可信）。 */
    val hashUnverified: Boolean = false,
    val sizeBytes: Long? = null,
    val version: String? = null,
    val discoveredAtMillis: Long = 0,
    val lastCheckedAtMillis: Long = 0,
    /** 最近一次隔离区静态扫描的时间；与 [sha256]/[rawUrl]/[version] 一起构成审查证据。 */
    val scannedAtMillis: Long = 0,
    val discoveredDomains: List<String> = emptyList(),
    /** SourceStaticPolicy 的判定字符串：CLEAN / NEEDS_REVIEW / BLOCKED。 */
    val staticVerdict: String? = null,
    val staticBlockReasons: List<String> = emptyList(),
    val risk: String = CandidateRisk.MEDIUM.name,
    val reviewState: String = CandidateReviewState.DISCOVERED.wireName,
    val verificationLevel: String = VerificationLevel.STATIC_REVIEW.name,
    val rejectReason: String? = null,
) {
    val review: CandidateReviewState get() = CandidateReviewState.parse(reviewState)
    val verification: VerificationLevel get() = runCatching { VerificationLevel.valueOf(verificationLevel) }
        .getOrDefault(VerificationLevel.STATIC_REVIEW)
}

/** 候选区快照（持久化到应用私有目录）。 */
@Serializable
data class CandidateStore(
    val schemaVersion: Int = 1,
    val updatedAtMillis: Long = 0,
    /** 上次成功读取的清单原文，供缓存回退；清单失败时不得清空候选。 */
    val lastGoodListRaw: String? = null,
    val lastGoodListUrl: String? = null,
    val candidates: List<DiscoveredCandidate> = emptyList(),
    /**
     * 墓碑：用户主动删除的候选 id（含别名）。删除=永久忽略，后续发现命中这些 id 时跳过，
     * 避免"删除后一条在途的旧刷新结果把刚删的候选复活"。用默认值保证旧数据迁移安全。
     */
    val ignoredIds: List<String> = emptyList(),
)

/** 去重决策：一条新发现条目如何归并进已有候选。 */
sealed interface DedupDecision {
    /** 全新候选。 */
    data object New : DedupDecision
    /** 与已有候选同 id：按版本/内容决定是否更新记录。 */
    data class SameId(val existingIndex: Int) : DedupDecision
    /** 与已有候选同规范化 URL 或同仓库路径（不同 id）：归并镜像，不重复登记。 */
    data class DuplicateUrl(val existingIndex: Int, val reason: String) : DedupDecision
}

object CommunityCatalogNormalizer {
    /**
     * 规范化 URL：只规范化「协议 + 主机名」（二者大小写不敏感），去掉 fragment、末尾斜杠、
     * 解码常见百分号编码。**路径大小写原样保留**——部分服务器路径区分大小写，
     * 无条件 lowercase 会把两个不同的脚本判为同一个。
     *
     * 只用于去重比较，不用于实际请求（实际请求保留 [CommunitySourceEntry.url]）。
     */
    fun normalizeUrl(raw: String): String {
        var value = raw.trim()
        // 剥掉 gh-proxy 之类前缀：https://gh-proxy.com/https://raw.githubusercontent.com/...
        // 统一成内层 https://raw.githubusercontent.com/...，让镜像与原始地址可去重。
        val nested = Regex("""^https?://[^/]+/(https?://.+)$""", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)
        if (nested != null) value = nested
        value = value.substringBefore("#").trimEnd('/')
        val decoded = runCatching { percentDecode(value) }.getOrDefault(value)
        return lowercaseSchemeAndHost(decoded)
    }

    /** 仅把 scheme 与 host 转小写，路径/查询串保持原样。 */
    private fun lowercaseSchemeAndHost(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val scheme = url.substring(0, schemeEnd).lowercase()
        val rest = url.substring(schemeEnd + 3)
        val hostEnd = rest.indexOf('/').let { if (it < 0) rest.length else it }
        val host = rest.substring(0, hostEnd).lowercase()
        return scheme + "://" + host + rest.substring(hostEnd)
    }

    private fun percentDecode(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            if (value[i] == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3)
                val byte = hex.toIntOrNull(16)
                if (byte != null) {
                    append(byte.toChar())
                    i += 3
                    continue
                }
            }
            append(value[i])
            i++
        }
    }

    /**
     * 仓库身份：<owner>/<repo>。仅当 URL 明确是 GitHub raw（含代理前缀）时才返回非空值。
     * 拿不到身份就说明无法确认「是同一个仓库」，此时绝不能只按末尾路径合并。
     */
    fun repoIdentity(raw: String): String? {
        val decoded = runCatching { percentDecode(raw.trim()) }.getOrDefault(raw.trim())
        val marker = "raw.githubusercontent.com/"
        val idx = decoded.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null
        val segments = decoded.substring(idx + marker.length).split('/')
        if (segments.size < 2 || segments[0].isBlank() || segments[1].isBlank()) return null
        return "${segments[0]}/${segments[1]}"
    }

    /**
     * 完整仓库内路径：<owner>/<repo>/<path>（保留路径大小写）。
     * 必须是可识别的 GitHub raw 地址才返回非空；无法识别时返回空串，
     * [dedup] 据此**不**按路径合并——不同仓库恰好有相同末尾路径时宁可保留两条候选。
     */
    fun repoPath(raw: String): String {
        val decoded = runCatching { percentDecode(raw.trim()) }.getOrDefault(raw.trim())
        val marker = "raw.githubusercontent.com/"
        val idx = decoded.indexOf(marker, ignoreCase = true)
        if (idx >= 0) {
            val segments = decoded.substring(idx + marker.length).split('/')
            // segments = [owner, repo, branch, path...]
            if (segments.size >= 4) {
                val path = segments.drop(3).joinToString("/")
                if (path.isNotBlank()) return "${segments[0]}/${segments[1]}/$path"
            }
        }
        return ""
    }

    /** 从 URL 提取主机；代理前缀下取内层真实主机。 */
    fun hostOf(raw: String): String? {
        val decoded = runCatching { percentDecode(raw.trim()) }.getOrDefault(raw.trim())
        return Regex("""https?://([A-Za-z0-9][A-Za-z0-9.-]*)""")
            .findAll(decoded)
            .lastOrNull()
            ?.groupValues?.getOrNull(1)?.lowercase()
    }

    /** 判断一条新发现条目是否指向已有候选。 */
    fun dedup(existing: List<DiscoveredCandidate>, entry: CommunitySourceEntry): DedupDecision {
        val norm = normalizeUrl(entry.url)
        val path = repoPath(entry.url)
        // 主 id 命中：同一条记录的最新线索。
        val byId = existing.indexOfFirst { it.id.equals(entry.id, ignoreCase = true) }
        if (byId >= 0) return DedupDecision.SameId(byId)
        // 别名 id 命中：该 id 之前因 URL/路径相同被归并进某条主记录，仍是同一脚本。
        val byAlias = existing.indexOfFirst { c -> c.aliasIds.any { it.equals(entry.id, ignoreCase = true) } }
        if (byAlias >= 0) return DedupDecision.DuplicateUrl(byAlias, "别名 id 相同")
        val byUrl = existing.indexOfFirst { it.normalizedUrl == norm }
        if (byUrl >= 0) return DedupDecision.DuplicateUrl(byUrl, "规范化 URL 相同")
        // 仓库路径非空才参与去重；repoPath 已含 owner/repo，不同仓库撞名末尾路径不会误合并。
        if (path.isNotBlank()) {
            val byPath = existing.indexOfFirst { it.repoPath == path }
            if (byPath >= 0) return DedupDecision.DuplicateUrl(byPath, "完整仓库路径相同")
        }
        return DedupDecision.New
    }
}

/** 社区清单解析结果，严格区分成功与各类失败，供调用方决定是否保留上次成功缓存。 */
sealed interface CommunityListParse {
    data class Valid(val list: CommunitySourceList, val raw: String) : CommunityListParse
    data class Invalid(val reason: String) : CommunityListParse
}

object CommunityListParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): CommunityListParse {
        if (raw.isBlank()) return CommunityListParse.Invalid("清单为空")
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return CommunityListParse.Invalid("清单不是合法 JSON")
        val sources = runCatching {
            json.decodeFromJsonElement(CommunitySourceList.serializer(), root).sources
        }.getOrNull() ?: return CommunityListParse.Invalid("清单结构不符合 {sources:[{id,name,url}]}")
        if (sources.isEmpty()) return CommunityListParse.Invalid("清单 sources 为空（不得据此清空本地候选）")
        val bad = sources.firstOrNull { it.id.isBlank() || it.name.isBlank() || !isHttpUrl(it.url) }
        if (bad != null) return CommunityListParse.Invalid("条目字段非法: id=${bad.id}, url=${bad.url}")
        val dupIds = sources.groupingBy { it.id.lowercase() }.eachCount().filter { it.value > 1 }
        if (dupIds.isNotEmpty()) return CommunityListParse.Invalid("清单内 id 重复: ${dupIds.keys}")
        return CommunityListParse.Valid(CommunitySourceList(sources), raw)
    }

    private fun isHttpUrl(url: String): Boolean {
        val v = url.trim()
        return v.startsWith("http://", ignoreCase = true) || v.startsWith("https://", ignoreCase = true)
    }
}
