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
    }

    /** 隔离一个脚本：立刻停用，且自动更新不会再启用它。 */
    suspend fun setScriptQuarantined(scriptId: String, quarantined: Boolean) {
        if (quarantined) runtime.setScriptEnabled(scriptId, false)
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
}
