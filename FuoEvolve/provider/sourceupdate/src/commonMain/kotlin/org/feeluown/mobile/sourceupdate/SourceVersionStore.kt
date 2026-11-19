package org.feeluown.mobile.sourceupdate

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/** 独立于 index.json 的反重放锚点备份；主索引损坏时用它恢复版本下限。 */
const val SOURCE_ANCHOR_PATH = "sources/anchor.json"

/**
 * 安装状态与版本仓库。
 *
 * 分层：宿主只提供"按路径读写文本"的 [SourceFileStore]（Android 走 filesDir、桌面走
 * `~/.FeelUOwn`），版本语义全部在 commonMain 实现，便于用内存实现做完整测试。
 *
 * 目录布局：
 * ```
 * sources/index.json                        版本索引与上次检查状态
 * sources/<id>/staging/<version>.js.tmp     下载中的临时文件
 * sources/<id>/versions/<version>.js        已校验的历史版本
 * ```
 */
interface SourceFileStore {
    suspend fun readText(path: String): String?
    suspend fun writeText(path: String, content: String)
    suspend fun delete(path: String)
    suspend fun exists(path: String): Boolean

    /** 返回以 [prefix] 开头的全部路径（前缀语义与实现相关，但必须是"路径前缀"）。 */
    suspend fun list(prefix: String): List<String>
}

class InMemorySourceFileStore(initial: Map<String, String> = emptyMap()) : SourceFileStore {
    private val files = initial.toMutableMap()

    override suspend fun readText(path: String): String? = files[path]

    override suspend fun writeText(path: String, content: String) {
        files[path] = content
    }

    override suspend fun delete(path: String) {
        files.remove(path)
    }

    override suspend fun exists(path: String): Boolean = files.containsKey(path)

    override suspend fun list(prefix: String): List<String> = files.keys.filter { it.startsWith(prefix) }.sorted()

    fun snapshot(): Map<String, String> = files.toMap()
}

@Serializable
data class InstalledSource(
    val scriptId: String,
    val name: String,
    val activeVersion: String,
    val sha256: String,
    val installedAtMillis: Long,
    val previousVersion: String? = null,
    val enabled: Boolean = false,
    val reviewStatus: String = SourceCatalogEntry.REVIEW_CANDIDATE,
    val allowedHosts: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val lastError: String? = null,
)

@Serializable
data class SourceIndex(
    val schemaVersion: Int = 1,
    /** 本地已接受的目录版本（单调不减；反重放的核心锚点）。 */
    val catalogVersion: Long = 0,
    val lastCheckMillis: Long = 0,
    val lastSuccessMillis: Long = 0,
    val consecutiveFailures: Int = 0,
    val etag: String? = null,
    val sources: Map<String, InstalledSource> = emptyMap(),
    /** 已接受目录原始字节的 SHA-256：同版本内容变化必须拒绝（内容变了应提升 catalogVersion）。 */
    val acceptedCatalogSha256: String? = null,
    /**
     * 主索引缺失/损坏、但反重放锚点仍存在时为 true（运行时标记，不持久化）。
     *
     * 这类状态**不是首次安装**：锚点保留了曾接受过的最高 catalogVersion，必须继续强制执行
     * 单调性，否则损坏的索引会被当成首次安装而接受旧目录，形成反重放缺口。
     */
    @Transient
    val recoveredFromAnchor: Boolean = false,
    /**
     * 主索引版本被锚点高水位校正过（写 index 失败/回滚导致 index 落后于 anchor）。
     * 与 [recoveredFromAnchor] 一样触发更严格的"同版本目录拒绝"策略。
     */
    @Transient
    val versionCorrectedByAnchor: Boolean = false,
) {
    /**
     * 是否为"首次安装"：既没有接受过任何目录版本，也没有已装脚本，且不是从锚点恢复的。
     *
     * 只有主索引与锚点都不存在（真正的应用重装 / 数据清除）才判为首次安装，从而允许接受
     * 当前合法目录并重新锚定。索引损坏但从锚点恢复了版本时，[recoveredFromAnchor] 为 true，
     * 不会被误判首次安装。
     */
    val isFirstInstall: Boolean
        get() = !recoveredFromAnchor &&
            catalogVersion == 0L && acceptedCatalogSha256 == null && sources.isEmpty()
}

/**
 * 独立的反重放锚点，单独存放在 [SOURCE_ANCHOR_PATH]。
 *
 * 只含"最高已接受目录版本 + 其内容哈希"两个字段，体积极小、写入频率低，因此比完整的
 * index.json 更难损坏。主索引损坏时用它恢复锚点，避免把损坏误判为首次安装。
 */
@Serializable
data class SourceAnchor(
    val catalogVersion: Long = 0,
    val acceptedCatalogSha256: String? = null,
)

class SourceVersionStore(
    private val files: SourceFileStore,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
) {

    /**
     * 读取版本索引，并守住反重放锚点的恢复边界：
     * - 主索引不存在且锚点也不存在 → 真正首次安装，返回默认 [SourceIndex]。
     * - 主索引存在但解析失败，或主索引不存在但锚点存在 → 从独立锚点恢复版本号与内容哈希，
     *   并置 [SourceIndex.recoveredFromAnchor]；**绝不**静默回退默认索引（那会被判首次安装，
     *   从而允许旧目录回放）。
     *
     * 无法从锚点恢复时（锚点本身也损坏/不存在）只能在默认索引上置 recoveredFromAnchor，
     * 让上层至少知道状态异常；此时没有可信版本号，属于无法完全封住的恢复边界，见文档。
     */
    suspend fun loadIndex(): SourceIndex {
        val anchor = loadAnchor()
        val raw = files.readText(INDEX_PATH)
        if (raw != null) {
            val parsed = runCatching { json.decodeFromString(SourceIndex.serializer(), raw) }.getOrNull()
            if (parsed != null) {
                // 原子性校正：saveIndex 先写锚点后写 index，两步之间若崩溃/失败，index 会
                // 落后于锚点。用锚点高水位校正版本下限，确保任何中断点都不倒退。
                if (anchor != null && anchor.catalogVersion > parsed.catalogVersion) {
                    return parsed.copy(
                        catalogVersion = anchor.catalogVersion,
                        acceptedCatalogSha256 = anchor.acceptedCatalogSha256
                            ?: parsed.acceptedCatalogSha256,
                        versionCorrectedByAnchor = true,
                    )
                }
                return parsed
            }
        }
        // 主索引缺失（raw == null）或损坏（解析失败）——两种情况都不能当首次安装。
        return if (anchor != null) {
            SourceIndex(
                catalogVersion = anchor.catalogVersion,
                acceptedCatalogSha256 = anchor.acceptedCatalogSha256,
                recoveredFromAnchor = true,
            )
        } else {
            SourceIndex(recoveredFromAnchor = raw != null)
        }
    }

    /**
     * 写入索引与反重放锚点。顺序与单调性是原子性的关键：
     * 1. 先写锚点，且只取"现有锚点版本 vs 新版本"的较大者——锚点是只增的高水位，
     *    即便调用方传入更低版本（回滚/重试）也不倒退；
     * 2. 再写完整 index。
     *
     * 任意一步失败的后果（由 [loadIndex] 的高水位校正兜底）：
     * - 只写成锚点：index 落后，loadIndex 用锚点校正版本，下限不倒退；
     * - 锚点写成 + index 写成：正常；
     * - 锚点写失败：index 可能领先，但下次崩溃恢复从旧锚点校正——所以必须先写锚点，
     *   避免"index 领先、锚点落后"这个无法从高水位恢复的组合。
     */
    suspend fun saveIndex(index: SourceIndex) {
        if (index.catalogVersion > 0 || index.acceptedCatalogSha256 != null) {
            val existing = loadAnchor()
            val highVersion = maxOf(index.catalogVersion, existing?.catalogVersion ?: 0L)
            val highHash = if (highVersion == index.catalogVersion) {
                index.acceptedCatalogSha256
            } else {
                existing?.acceptedCatalogSha256 ?: index.acceptedCatalogSha256
            }
            val anchor = SourceAnchor(highVersion, highHash)
            files.writeText(SOURCE_ANCHOR_PATH, json.encodeToString(SourceAnchor.serializer(), anchor))
        }
        files.writeText(INDEX_PATH, json.encodeToString(SourceIndex.serializer(), index))
    }

    /** 读取独立锚点；损坏时返回 null（此时没有可信版本锚点，如实降级）。 */
    private suspend fun loadAnchor(): SourceAnchor? {
        val raw = files.readText(SOURCE_ANCHOR_PATH) ?: return null
        return runCatching { json.decodeFromString(SourceAnchor.serializer(), raw) }.getOrNull()
    }

    suspend fun readActiveScript(scriptId: String): String? {
        val installed = loadIndex().sources[scriptId] ?: return null
        return files.readText(versionPath(scriptId, installed.activeVersion))
    }

    suspend fun readVersion(scriptId: String, version: String): String? =
        files.readText(versionPath(scriptId, version))

    suspend fun writeStaged(scriptId: String, version: String, content: String) {
        files.writeText(stagedPath(scriptId, version), content)
    }

    suspend fun deleteStaged(scriptId: String, version: String) {
        files.delete(stagedPath(scriptId, version))
    }

    suspend fun writeVersion(scriptId: String, version: String, content: String) {
        files.writeText(versionPath(scriptId, version), content)
    }

    suspend fun deleteVersion(scriptId: String, version: String) {
        files.delete(versionPath(scriptId, version))
    }

    suspend fun listVersions(scriptId: String): List<String> {
        val prefix = versionsDir(scriptId)
        return files.list(prefix)
            .mapNotNull { it.removePrefix(prefix).takeIf { name -> name.isNotEmpty() && !name.contains('/') } }
            .mapNotNull { it.removeSuffix(".js").takeIf { name -> name.isNotEmpty() } }
    }

    suspend fun listSourceIds(): List<String> {
        val prefix = "$ROOT/sources/"
        return files.list(prefix)
            .mapNotNull { it.removePrefix(prefix).substringBefore('/').takeIf { id -> id.isNotEmpty() } }
            .distinct()
    }

    fun versionPath(scriptId: String, version: String): String = "${versionsDir(scriptId)}$version.js"

    fun stagedPath(scriptId: String, version: String): String = "$ROOT/sources/$scriptId/staging/$version.js.tmp"

    private fun versionsDir(scriptId: String): String = "$ROOT/sources/$scriptId/versions/"

    private companion object {
        const val ROOT = "sources"
        const val INDEX_PATH = "$ROOT/index.json"
    }
}

sealed interface InstallOutcome {
    data class Installed(
        val scriptId: String,
        val version: String,
        val previousVersion: String?,
    ) : InstallOutcome

    data class AlreadyInstalled(val scriptId: String, val version: String) : InstallOutcome

    /** 校验、审核门槛或静态扫描未通过：**没有写入任何文件**，旧版本保持可用。 */
    data class Rejected(val scriptId: String, val reason: String) : InstallOutcome

    /**
     * 冒烟测试未通过：staging 已清理，**active 指针与已装版本都未被改动**。
     * 首次安装时 index 里没有这个 id；更新时旧版本依然是 active 且可直接回退。
     */
    data class SmokeTestFailed(val scriptId: String, val version: String, val reason: String) : InstallOutcome
}

/**
 * 安装事务。
 *
 * 顺序严格按"先验证、后激活"：
 * ```
 * 审核门槛 → SHA-256 → 静态扫描(BLOCK+WARN) → 写 staging
 *   → 独立实例冒烟 → 通过才 writeVersion + 切 active → 清理 staging
 * ```
 *
 * 关键不变量：**切 active 指针发生在冒烟通过之后**。因此"冒烟期间旧版本仍是 active"
 * 和"更新失败不破坏上一可用版本"都是结构性保证，而不是靠调用方记得回滚。
 *
 * 所有安全门槛都放在事务内部，而不是依赖上层调用方记得过滤——[install] 是公共入口，
 * 任何调用方（协调器、测试、将来的手动安装）都无法绕过。
 */
class SourceInstallTransaction(
    private val store: SourceVersionStore,
    private val verifier: SourceArtifactVerifier = SourceArtifactVerifier(),
    private val maxScriptBytes: Int = SourceStaticPolicy.DEFAULT_MAX_SCRIPT_BYTES,
    private val now: () -> Long = { 0L },
    /** 由宿主注入：在独立 QuickJS 实例里加载脚本并跑 initialize。返回 null 表示通过。 */
    private val smokeTest: suspend (SourceCatalogEntry, String) -> String? = { _, _ -> null },
) {

    suspend fun install(
        entry: SourceCatalogEntry,
        content: String,
        enable: Boolean = false,
    ): InstallOutcome {
        // 门槛 1：审核结论。事务自身强制，调用方传进来一个未批准条目也必须被挡下。
        val gateReason = reviewGateReason(entry)
        if (gateReason != null) {
            return InstallOutcome.Rejected(entry.id, "审核门禁未通过：$gateReason")
        }

        // 门槛 2：产物摘要。
        val bytes = content.encodeToByteArray()
        when (val verification = verifier.verify(entry, bytes)) {
            is ArtifactVerification.Rejected -> return InstallOutcome.Rejected(entry.id, verification.reason)
            is ArtifactVerification.Accepted -> Unit
        }

        // 门槛 3：静态扫描。BLOCK 与 NEEDS_REVIEW(WARN) 都不允许自动安装。
        val scan = SourceStaticPolicy.scan(content, entry.allowedHosts, maxScriptBytes)
        if (scan.blocked || scan.verdict == StaticVerdict.NEEDS_REVIEW) {
            val reasons = scan.findings.map { "${it.severity}/${it.ruleId}: ${it.message}" }
            return InstallOutcome.Rejected(entry.id, "静态扫描未通过：${reasons.joinToString("; ")}")
        }

        val index = store.loadIndex()
        val existing = index.sources[entry.id]
        if (existing != null &&
            existing.activeVersion == entry.version &&
            existing.sha256.equals(entry.sha256, ignoreCase = true)
        ) {
            return InstallOutcome.AlreadyInstalled(entry.id, entry.version)
        }

        // 先落到 staging：冒烟用的是暂存内容，绝不读一个可能已被别的安装改动过的 active 文件。
        store.writeStaged(entry.id, entry.version, content)
        val smokeFailure = try {
            smokeTest(entry, content)
        } catch (e: kotlinx.coroutines.CancellationException) {
            store.deleteStaged(entry.id, entry.version)
            throw e
        } catch (e: Exception) {
            "冒烟测试抛出异常：${e.message}"
        }
        if (smokeFailure != null) {
            store.deleteStaged(entry.id, entry.version)
            return InstallOutcome.SmokeTestFailed(entry.id, entry.version, smokeFailure)
        }

        // 冒烟通过后才落 versions 并切 active。此前 active 指针始终是旧版本（或不存在）。
        store.writeVersion(entry.id, entry.version, content)
        store.deleteStaged(entry.id, entry.version)

        val previousVersion = existing?.activeVersion?.takeIf { it != entry.version }
        val updated = InstalledSource(
            scriptId = entry.id,
            name = entry.name,
            activeVersion = entry.version,
            sha256 = entry.sha256.lowercase(),
            installedAtMillis = now(),
            previousVersion = previousVersion ?: existing?.previousVersion,
            enabled = enable || existing?.enabled == true,
            reviewStatus = entry.reviewStatus,
            allowedHosts = entry.allowedHosts,
            platforms = entry.platforms,
            lastError = null,
        )
        store.saveIndex(index.copy(sources = index.sources + (entry.id to updated)))
        pruneOldVersions(entry.id, updated)
        return InstallOutcome.Installed(entry.id, entry.version, previousVersion)
    }

    /**
     * 事务级的审核门槛，与 [SourceCatalogParser.validate] 保持一致但独立实现：
     * 目录解析是"入口校验"，这里是"写入前最后一道"，两者任一被绕过都不会导致未审脚本落盘。
     */
    private fun reviewGateReason(entry: SourceCatalogEntry): String? = when {
        entry.review != SourceReviewStatus.APPROVED ->
            "reviewStatus=${entry.reviewStatus} 不是 APPROVED"
        entry.license.isBlank() || entry.license.equals("unknown", ignoreCase = true) ->
            "未声明许可证（license=${entry.license}）"
        entry.allowedHosts.isEmpty() ->
            "未声明 allowedHosts，运行时网络会被全部拒绝"
        !entry.autoInstall ->
            "autoInstall=false，不允许自动安装"
        else -> null
    }

    /** 新版本首次运行失败时切回上一版本。返回是否真的发生了回滚。 */
    suspend fun rollback(scriptId: String, reason: String): Boolean {
        val index = store.loadIndex()
        val installed = index.sources[scriptId] ?: return false
        val previous = installed.previousVersion ?: return false
        if (store.readVersion(scriptId, previous) == null) return false

        val rolledBack = installed.copy(
            activeVersion = previous,
            previousVersion = null,
            lastError = reason,
        )
        store.saveIndex(index.copy(sources = index.sources + (scriptId to rolledBack)))
        return true
    }

    suspend fun markRuntimeFailure(scriptId: String, reason: String) {
        val index = store.loadIndex()
        val installed = index.sources[scriptId] ?: return
        store.saveIndex(
            index.copy(sources = index.sources + (scriptId to installed.copy(lastError = reason))),
        )
    }

    suspend fun setEnabled(scriptId: String, enabled: Boolean) {
        val index = store.loadIndex()
        val installed = index.sources[scriptId] ?: return
        store.saveIndex(
            index.copy(sources = index.sources + (scriptId to installed.copy(enabled = enabled))),
        )
    }

    suspend fun remove(scriptId: String) {
        val index = store.loadIndex()
        val installed = index.sources[scriptId]
        if (installed != null) {
            for (version in store.listVersions(scriptId)) store.deleteVersion(scriptId, version)
            store.saveIndex(index.copy(sources = index.sources - scriptId))
        }
    }

    private suspend fun pruneOldVersions(scriptId: String, installed: InstalledSource) {
        val keep = setOfNotNull(installed.activeVersion, installed.previousVersion)
        for (version in store.listVersions(scriptId)) {
            if (version !in keep) store.deleteVersion(scriptId, version)
        }
    }
}
