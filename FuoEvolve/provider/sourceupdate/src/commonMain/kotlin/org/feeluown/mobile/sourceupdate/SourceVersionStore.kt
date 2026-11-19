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
    val catalogVersion: Long = 0,
    val lastCheckMillis: Long = 0,
    val lastSuccessMillis: Long = 0,
    val consecutiveFailures: Int = 0,
    val etag: String? = null,
    val sources: Map<String, InstalledSource> = emptyMap(),
)

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

    /** 校验或静态扫描未通过：**没有写入任何文件**，旧版本保持可用。 */
    data class Rejected(val scriptId: String, val reason: String) : InstallOutcome
}

/**
 * 安装事务。
 *
 * 顺序严格按设计文档 4.3：下载 → 校验 → 静态检查 → 落盘到 versions → 切 active 指针 →
 * 失败回滚。任何一步失败都不动 active 指针，因此"更新失败不破坏上一可用版本"是结构性保证，
 * 而不是靠调用方记得处理异常。
 *
 * 刻意不在事务里执行脚本：更新器不执行代码（[smokeTest] 由协调器注入并在单独步骤调用）。
 */
class SourceInstallTransaction(
    private val store: SourceVersionStore,
    private val verifier: SourceArtifactVerifier = SourceArtifactVerifier(),
    private val maxScriptBytes: Int = SourceStaticPolicy.DEFAULT_MAX_SCRIPT_BYTES,
    private val now: () -> Long = { 0L },
) {

    suspend fun install(
        entry: SourceCatalogEntry,
        content: String,
        enable: Boolean = false,
    ): InstallOutcome {
        val bytes = content.encodeToByteArray()
        when (val verification = verifier.verify(entry, bytes)) {
            is ArtifactVerification.Rejected -> return InstallOutcome.Rejected(entry.id, verification.reason)
            is ArtifactVerification.Accepted -> Unit
        }

        val scan = SourceStaticPolicy.scan(content, entry.allowedHosts, maxScriptBytes)
        if (scan.blocked) {
            return InstallOutcome.Rejected(
                entry.id,
                "静态扫描未通过：${scan.blockReasons().joinToString("; ")}",
            )
        }

        val index = store.loadIndex()
        val existing = index.sources[entry.id]
        if (existing != null &&
            existing.activeVersion == entry.version &&
            existing.sha256.equals(entry.sha256, ignoreCase = true)
        ) {
            return InstallOutcome.AlreadyInstalled(entry.id, entry.version)
        }

        store.writeStaged(entry.id, entry.version, content)
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
