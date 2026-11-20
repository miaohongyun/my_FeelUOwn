package org.feeluown.mobile

import android.app.Application
import org.feeluown.mobile.provider.modeb.installModeBStorageDirectory
import org.feeluown.mobile.source.SourceUpdateHost
import org.feeluown.mobile.sourceupdate.installSourceStorageDirectory

/**
 * Thin Android process host.
 *
 * Runtime dependency construction lives in [AndroidAppContainer] so the Application class only
 * exposes process-scoped entry points required by activities and services.
 */
class FuoEvolveApplication : Application() {
    private var containerHolder: AndroidAppContainer? = null

    override fun onCreate() {
        super.onCreate()
        installAndroidAppLogger(this)
        // Mode B config cache + circuit-breaker cooldown must survive process restarts.
        installModeBStorageDirectory(java.io.File(filesDir, "mode_b"))
        // 受控音源目录：staging/active/backup 版本仓库落在 filesDir，卸载才清空。
        installSourceStorageDirectory(java.io.File(filesDir, "source_catalog"))
        AndroidSourceUpdateWorker.enqueue(this)
        // 社区音源发现：每日后台发现候选（只登记、不安装、不批准）。
        AndroidCommunityDiscoveryWorker.enqueue(this)
        installPlaylistMigrationBackgroundScheduler { request ->
            AndroidPlaylistMigrationWorker.enqueue(applicationContext, request)
        }
        // Admit this app into the ColorOS lock-screen lyric module's player union. The module reads
        // the admission long before playback starts (media history, AOD panel, lyric entrance), so
        // it must not wait for the playback service.
        ColorOsBridgeBindings.publish(this, "application-start")
    }

    private fun container(): AndroidAppContainer =
        containerHolder ?: AndroidAppContainer(this).also { containerHolder = it }

    /** Playback-service provider surface; smart replacement policy is owned by :feature:playback. */
    internal val providerRepository: PlaybackProviderPort
        get() = container().playbackProvider

    internal val providerCredentialBackup: AndroidProviderCredentialBackup
        get() = container().providerCredentialBackup

    internal val settingsRepository: AppSettingsRepository
        get() = container().settingsRepository

    internal val sourceUpdateHost: SourceUpdateHost
        get() = container().sourceUpdateHost

    internal val communityDiscoveryController: org.feeluown.mobile.feature.community.RealCommunityDiscoveryController
        get() = container().communityDiscoveryController

    /** 后台发现 Worker 直接调用其 suspend refresh()，与 UI 共用同一实例。 */
    internal val communityDiscoveryRepository: org.feeluown.mobile.sourceupdate.CommunityDiscoveryRepository
        get() = container().communityDiscoveryRepository

    internal val appUiGraph: AppUiGraph
        get() = container().appUiGraph

    val appViewModel: FuoAppViewModel
        get() = container().appViewModel

    override fun onTerminate() {
        installPlaylistMigrationBackgroundScheduler(null)
        containerHolder?.close()
        containerHolder = null
        super.onTerminate()
    }
}
