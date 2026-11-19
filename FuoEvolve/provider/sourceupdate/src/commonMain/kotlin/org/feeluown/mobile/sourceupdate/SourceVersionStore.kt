package org.feeluown.mobile.sourceupdate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
) {
    /**
     * 是否为"首次安装"：既没有接受过任何目录版本，也没有已装脚本。
     *
     * 应用重装 / 数据恢复后索引回到默认值，会被判为首次安装，从而允许接受当前合法目录；
     * 这是明确的版本恢复规则（无法从丢失的本地状态恢复旧版本号，只能重新锚定）。
     */
    val isFirstInstall: Boolean
        get() = catalogVersion == 0L && acceptedCatalogSha256 == null && sources.isEmpty()
}

class SourceVersionStore(
    private val files: SourceFileStore,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
) {

    suspend fun loadIndex(): SourceIndex {
        val raw = files.readText(INDEX_PATH) ?: return SourceIndex()
        return runCatching { json.decodeFromString(SourceIndex.serializer(), raw) }
            .getOrElse { SourceIndex() }
    }

    suspend fun saveIndex(index: SourceIndex) {
        files.writeText(INDEX_PATH, json.encodeToString(SourceIndex.serializer(), index))
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
