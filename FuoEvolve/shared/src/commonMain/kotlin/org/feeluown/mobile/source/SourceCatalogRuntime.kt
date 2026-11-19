package org.feeluown.mobile.source

import io.ktor.client.HttpClient
import org.feeluown.mobile.provider.core.network.createSharedProviderHttpClient
import org.feeluown.mobile.provider.lx.LxInstalledScript
import org.feeluown.mobile.provider.lx.LxInstalledScriptSource
import org.feeluown.mobile.provider.lx.LxScriptRuntime
import org.feeluown.mobile.sourceupdate.CatalogFetcher
import org.feeluown.mobile.sourceupdate.InstalledSource
import org.feeluown.mobile.sourceupdate.KtorCatalogFetcher
import org.feeluown.mobile.sourceupdate.SourceCatalogEntry
import org.feeluown.mobile.sourceupdate.SourceCatalogUpdater
import org.feeluown.mobile.sourceupdate.SourceInstallTransaction
import org.feeluown.mobile.sourceupdate.SourceReviewStatus
import org.feeluown.mobile.sourceupdate.SourceUpdateCoordinator
import org.feeluown.mobile.sourceupdate.SourceUpdateDefaults
import org.feeluown.mobile.sourceupdate.SourceUpdatePolicy
import org.feeluown.mobile.sourceupdate.SourceVersionStore
import org.feeluown.mobile.sourceupdate.UpdateReport
import org.feeluown.mobile.sourceupdate.createPlatformSourceFileStore
import org.feeluown.mobile.sourceupdate.sourceUpdateNowMillis

/**
 * 目录驱动的音源接入：把 `provider/sourceupdate` 的安装结果接到 `provider/lxruntime` 的执行上。
 *
 * 这一层只做三件事，不承载任何信任判断（信任判断在 `sourceupdate` 里）：
 * 1. 把 index.json 里"已启用 + 已批准"的脚本读成 [LxInstalledScript]；
 * 2. 跑一次自动更新（含冒烟测试）；
 * 3. 给设置页一个只读状态快照。
 */
class SourceCatalogRuntime(
    httpClient: HttpClient = createSharedProviderHttpClient(),
    val store: SourceVersionStore = SourceVersionStore(createPlatformSourceFileStore()),
    private val clock: () -> Long = { sourceUpdateNowMillis() },
    /**
     * 与目录 `minAppVersion` 比较的**能力版本**，不是构建号。
     *
     * 构建号来自 git describe，个人分支上可能是 `v0.9.x-3-gabc123-dirty` 这种非规范串，
     * 拿它做门槛判断只会让更新莫名其妙地被跳过。真正决定"能不能用新目录"的是协议能力，
     * 因此这里用一个显式常量，改协议时才提升。
     */
    private val catalogAppVersion: String = CATALOG_APP_VERSION,
    private val quarantine: suspend () -> Set<String> = { emptySet() },
) {

    /**
     * 目录下载与脚本下载专用客户端：`followRedirects = false`。
     *
     * 跨域 302 会把请求引到 allowlist 之外的地址，那等于绕过镜像白名单；
     * 直接关掉重定向，让 `KtorCatalogFetcher` 把它当成一次失败并换下一个通道。
     */
    private val fetchClient: HttpClient = httpClient.config { followRedirects = false }

    private val fetcher: CatalogFetcher = KtorCatalogFetcher(fetchClient)
    private val mirrors = SourceUpdateDefaults.mirrorPolicy()
    private val updater = SourceCatalogUpdater(
        fetcher = fetcher,
        mirrors = mirrors,
        signatureVerifier = SourceUpdateDefaults.signatureVerifier(),
        clock = clock,
    )
    /**
     * 冒烟测试下沉到事务内部：只有初始化通过，新版本才会被写成 active。
     * [smokeTest] 在独立 QuickJS 实例里加载脚本，不触碰正在被 Provider 使用的实例。
     */
    private val transaction = SourceInstallTransaction(
        store,
        now = clock,
        smokeTest = { entry, content -> smokeTest(entry, content) },
    )

    /** 供 [org.feeluown.mobile.provider.lx.LxScriptMusicProvider] 读取的脚本集合。 */
    val scripts: LxInstalledScriptSource = StoreLxInstalledScriptSource(store, quarantine)

    /**
     * 执行一次检查。
     *
     * 关掉自动更新时仍然允许 [force]（设置页的"立即检查"按钮），否则用户无法手动补一次。
     */
    suspend fun checkForUpdates(
        config: SourceUpdateConfig,
        force: Boolean = false,
    ): UpdateReport {
        if (!config.enabled && !force) {
            return UpdateReport.Skipped("自动更新已关闭")
        }
        val coordinator = SourceUpdateCoordinator(
            catalogUrls = listOf(SourceUpdateDefaults.CATALOG_URL),
            updater = updater,
            fetcher = fetcher,
            store = store,
            transaction = transaction,
            policy = SourceUpdatePolicy(config.intervalHours),
            clock = clock,
            appVersion = catalogAppVersion,
        )
        return runCatching { coordinator.checkForUpdates(force) }
            .getOrElse { error -> UpdateReport.Failed(error.message ?: "更新失败") }
    }

    /** 设置页展示用的快照；不做任何网络请求。 */
    suspend fun status(): SourceCatalogStatus {
        val index = store.loadIndex()
        val quarantined = runCatching { quarantine() }.getOrDefault(emptySet())
        return SourceCatalogStatus(
            catalogVersion = index.catalogVersion,
            lastCheckMillis = index.lastCheckMillis,
            lastSuccessMillis = index.lastSuccessMillis,
            consecutiveFailures = index.consecutiveFailures,
            installed = index.sources.values
                .sortedBy { it.scriptId }
                .map { installed -> installed.toStatus(quarantined) },
        )
    }

    /** 用户在设置页手动启用/停用某个已安装脚本；[SourceInstallTransaction] 负责落盘。 */
    suspend fun setScriptEnabled(scriptId: String, enabled: Boolean) {
        transaction.setEnabled(scriptId, enabled)
    }

    /**
     * 冒烟测试：在独立 QuickJS context 里加载并初始化一遍脚本。
     *
     * 这是"新版初始化失败自动回滚"的唯一判据——只做静态检查无法发现语法错误、
     * 顶层 await 卡死或 inited 声明的源和目录不符。
     */
    private suspend fun smokeTest(entry: SourceCatalogEntry, content: String): String? {
        val runtime = LxScriptRuntime(fetchClient)
        return try {
            runtime.load(
                name = entry.name.ifBlank { entry.id },
                source = content,
                allowedHosts = entry.allowedHosts.toSet(),
            )
            val meta = runtime.initialize()
            if (entry.platforms.isNotEmpty() && meta.platforms.none { it in entry.platforms }) {
                "脚本自述平台 ${meta.platforms} 与目录批准平台 ${entry.platforms} 无交集"
            } else {
                null
            }
        } catch (error: Exception) {
            error.message ?: "脚本初始化失败"
        } finally {
            runCatching { runtime.close() }
        }
    }

    companion object {
        /** 目录 `minAppVersion` 比较基线（协议能力版本）。 */
        const val CATALOG_APP_VERSION = "1.0.0"
    }
}

/** 自动更新的运行时配置，由设置仓库投影而来，避免 runtime 直接依赖设置模块。 */
data class SourceUpdateConfig(
    val enabled: Boolean = true,
    val intervalHours: Int = SourceUpdateDefaults.UPDATE_INTERVAL_HOURS,
    val quarantinedIds: Set<String> = emptySet(),
)

data class SourceCatalogStatus(
    val catalogVersion: Long = 0,
    val lastCheckMillis: Long = 0,
    val lastSuccessMillis: Long = 0,
    val consecutiveFailures: Int = 0,
    val installed: List<InstalledScriptStatus> = emptyList(),
) {
    val hasInstalled: Boolean get() = installed.isNotEmpty()
}

data class InstalledScriptStatus(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
    val quarantined: Boolean,
    val lastError: String?,
)

private fun InstalledSource.toStatus(quarantined: Set<String>): InstalledScriptStatus {
    val isQuarantined = scriptId in quarantined
    return InstalledScriptStatus(
        id = scriptId,
        name = name,
        version = activeVersion,
        // 隔离优先于启用标记：用户隔离后即使 index 里 enabled=true 也不参与执行。
        enabled = enabled && !isQuarantined,
        quarantined = isQuarantined,
        lastError = lastError,
    )
}

/**
 * index.json → [LxInstalledScript]。
 *
 * 只有同时满足"已启用"和"目录状态 APPROVED"的脚本才会被执行：
 * - 已启用是用户意图；
 * - APPROVED 是审核结论（`InstalledSource.reviewStatus` 是安装时从目录抄下来的）。
 * 两者缺一不可，所以即使有人手工把 index 改成 enabled=true，未批准的脚本也不会跑起来。
 */
class StoreLxInstalledScriptSource(
    private val store: SourceVersionStore,
    private val quarantinedIds: suspend () -> Set<String> = { emptySet() },
) : LxInstalledScriptSource {

    override suspend fun scripts(): List<LxInstalledScript> {
        val index = store.loadIndex()
        val quarantined = runCatching { quarantinedIds() }.getOrDefault(emptySet())
        // 注意用 List（内联 lambda）而不是 Sequence：readActiveScript 是挂起函数，
        // 在 Sequence 的惰性 lambda 里调不到协程上下文。
        val candidates = index.sources.values
            .filter { it.enabled && it.scriptId !in quarantined }
            .filter { SourceReviewStatus.parse(it.reviewStatus) == SourceReviewStatus.APPROVED }
            .sortedBy { it.scriptId }
        return candidates.mapNotNull { installed ->
            // 直接按已知版本号读文件，不再走 readActiveScript（那会为每个脚本重读一次 index）。
            val source = store.readVersion(installed.scriptId, installed.activeVersion)
                ?: return@mapNotNull null
            LxInstalledScript(
                id = installed.scriptId,
                name = installed.name,
                version = installed.activeVersion,
                source = source,
                allowedHosts = installed.allowedHosts,
                platforms = installed.platforms,
            )
        }
    }
}
