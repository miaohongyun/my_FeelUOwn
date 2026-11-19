package org.feeluown.mobile.source

import org.feeluown.mobile.AppSettingsRepository
import org.feeluown.mobile.ProviderRegistryRepository
import org.feeluown.mobile.provider.lx.LxScriptMusicProvider
import org.feeluown.mobile.MAX_SOURCE_UPDATE_INTERVAL_HOURS
import org.feeluown.mobile.MIN_SOURCE_UPDATE_INTERVAL_HOURS
import org.feeluown.mobile.sourceupdate.UpdateReport

/**
 * 自动更新的应用层编排：设置 → 检查 → 把结果写回设置里的展示字段。
 *
 * 为什么不让 [SourceCatalogRuntime] 直接吃设置：runtime 属于音源层，设置属于应用层，
 * 让下层依赖上层会让单测必须拉起整条设置链路。这里只做投影，真正的安装状态仍在
 * `sources/index.json`。
 */
class SourceUpdateHost(
    val runtime: SourceCatalogRuntime,
    private val settings: AppSettingsRepository,
    /**
     * 用于"目录装上第一个脚本后自动打开 lxscript 音源"。
     *
     * 只写设置是不够的：`KotlinProviderRepository` 在内存里另有一份启用集合，
     * 不同时更新它的话，用户得重启应用才能看到新音源。
     */
    private val providerRegistry: ProviderRegistryRepository? = null,
) {

    /**
     * 启动/每日任务都走这里。返回报告供日志和设置页使用。
     *
     * [force] 用于设置页的"立即检查"：用户手动点时必须绕过"距上次不足 24 小时"的判断，
     * 否则按钮会静默无反应。
     */
    suspend fun checkIfDue(force: Boolean = false): UpdateReport {
        val current = settings.awaitSettings()
        val report = runtime.checkForUpdates(
            config = SourceUpdateConfig(
                enabled = current.sourceAutoUpdateEnabled,
                intervalHours = current.sourceUpdateIntervalHours,
                quarantinedIds = current.quarantinedScriptIds,
            ),
            force = force,
        )
        // 被跳过时状态没有任何变化，不必回写（也避免每次启动都写一次设置文件）。
        if (report !is UpdateReport.Skipped) {
            val status = runtime.status()
            if (status.installed.any { it.enabled }) enableLxProviderIfNeeded()
            // 更新不在这里主动 close：checkForUpdates 由启动/每日任务在后台触发，可能正好撞上
            // 用户解析中的 resolve()，close 会把它的 QuickJS context 抽走导致当次解析失败。
            // 脚本 handle 的身份含源码 SHA-256，版本一变下次 search/resolve 自然淘汰旧 context，
            // 这已经满足"更新自动淘汰旧 runtime"，无需主动打断正在进行的解析。
            settings.update { settings ->
                settings.copy(
                    sourceCatalogVersion = status.catalogVersion.takeIf { it > 0 },
                    installedScriptVersions = status.installed.associate { it.id to it.version },
                )
            }
        }
        return report
    }

    suspend fun status(): SourceCatalogStatus = runtime.status()

    /**
     * 目录第一次真正装上已批准脚本时，把 lxscript 音源打开。
     *
     * 默认启用集合里没有它（空目录时它只会让搜索多一个永远为空的来源），
     * 但"自动启用脚本"如果还要用户自己去音源列表点一下，就等于没实现。
     */
    private suspend fun enableLxProviderIfNeeded() {
        val registry = providerRegistry ?: return
        val current = settings.awaitSettings().enabledProviderIds
        if (LxScriptMusicProvider.ID in current) return
        val next = current + LxScriptMusicProvider.ID
        registry.updateEnabledProviders(next)
        settings.update { it.copy(enabledProviderIds = next) }
    }

    suspend fun setAutoUpdateEnabled(enabled: Boolean) {
        settings.update { it.copy(sourceAutoUpdateEnabled = enabled) }
    }

    suspend fun setIntervalHours(hours: Int) {
        val clamped = hours.coerceIn(MIN_SOURCE_UPDATE_INTERVAL_HOURS, MAX_SOURCE_UPDATE_INTERVAL_HOURS)
        settings.update { it.copy(sourceUpdateIntervalHours = clamped) }
    }

    suspend fun setScriptEnabled(scriptId: String, enabled: Boolean) {
        runtime.setScriptEnabled(scriptId, enabled)
        if (!enabled) releaseRuntime(scriptId)
    }

    /**
     * 回滚脚本到上一版本。
     *
     * 回滚成功后立即释放对应 QuickJS context：handle 的身份含源码 SHA-256，旧 context 不能
     * 在下次搜索前继续服务。返回是否真的发生了回滚（无上一版本时为 false，不触发释放）。
     */
    suspend fun rollbackScript(scriptId: String, reason: String): Boolean {
        val changed = runtime.rollbackScript(scriptId, reason)
        if (changed) releaseRuntime(scriptId)
        return changed
    }

    /** 隔离一个脚本：立刻停用并释放运行时，且自动更新不会再启用它。 */
    suspend fun setScriptQuarantined(scriptId: String, quarantined: Boolean) {
        if (quarantined) {
            runtime.setScriptEnabled(scriptId, false)
            releaseRuntime(scriptId)
        }
        settings.update { settings ->
            settings.copy(
                quarantinedScriptIds = if (quarantined) {
                    settings.quarantinedScriptIds + scriptId
                } else {
                    settings.quarantinedScriptIds - scriptId
                },
            )
        }
    }

    /**
     * 主动回收脚本运行时。
     *
     * Provider 尚未实例化时其 Lazy 内没有 handle，本调用是安全的空操作；之后重新启用
     * 会按需新建。若已实例化，则立即关闭对应 QuickJS context，不必等下一次搜索。
     */
    private suspend fun releaseRuntime(scriptId: String) {
        providerRegistry?.releaseDynamicScriptRuntime(scriptId)
    }
}
