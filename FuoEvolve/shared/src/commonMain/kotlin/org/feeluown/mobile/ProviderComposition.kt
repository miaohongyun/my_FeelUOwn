package org.feeluown.mobile

import org.feeluown.mobile.provider.bilibili.BilibiliProviderFactory
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.KotlinProviderFactory
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.jamendo.JamendoProviderFactory
import org.feeluown.mobile.provider.lx.EmptyLxInstalledScriptSource
import org.feeluown.mobile.provider.lx.LxInstalledScriptSource
import org.feeluown.mobile.provider.lx.LxScriptProviderFactory
import org.feeluown.mobile.provider.modeb.ModeBProviderFactory
import org.feeluown.mobile.provider.netease.NeteaseProviderFactory
import org.feeluown.mobile.provider.qqmusic.QQMusicProviderFactory
import org.feeluown.mobile.provider.ytmusic.YtMusicProviderFactory

/**
 * 目录驱动的音源依赖。
 *
 * 两个字段都不给默认实现"假装能用"的值：
 * - 没有脚本仓库时 LX Provider 只是空壳（搜索为空、解析返回 null），不会报错；
 * - 没有 Jamendo client_id 时该 Provider 静默返回空，不伪造结果。
 */
class DynamicProviderDependencies(
    val scriptSource: LxInstalledScriptSource = EmptyLxInstalledScriptSource,
    val jamendoClientId: () -> String? = { null },
)

/** Single compile-time composition point that knows concrete provider plugins. */
internal object ProviderComposition {

    /**
     * 顺序即优先级（设计文档 9）：原生 Provider → 已审核 LX 脚本 → 开放曲库。
     * 搜索结果按 provider 顺序合并，因此这个列表同时决定了"先给谁的结果"。
     */
    private val staticFactories: List<KotlinProviderFactory> = listOf(
        NeteaseProviderFactory,
        QQMusicProviderFactory,
        BilibiliProviderFactory,
        YtMusicProviderFactory,
        ModeBProviderFactory,
    )

    fun providerInfos(dynamic: DynamicProviderDependencies = DynamicProviderDependencies()): List<ProviderInfo> =
        factories(dynamic).map { it.info }

    fun createProviderFactories(
        http: ProviderHttpClient,
        credentials: ProviderCredentialStore,
        dynamic: DynamicProviderDependencies = DynamicProviderDependencies(),
    ): Map<String, () -> KotlinMusicProvider> {
        val dependencies = ProviderRuntimeDependencies(http = http, credentials = credentials)
        return factories(dynamic).associate { factory ->
            factory.providerId to { factory.create(dependencies) }
        }
    }

    fun createBilibiliContentProvider(
        http: ProviderHttpClient,
        credentials: ProviderCredentialStore,
    ): KotlinMusicProvider = BilibiliProviderFactory.createContentProvider(
        ProviderRuntimeDependencies(
            http = http,
            credentials = credentials,
        ),
    )

    private fun factories(dynamic: DynamicProviderDependencies): List<KotlinProviderFactory> =
        staticFactories +
            LxScriptProviderFactory(dynamic.scriptSource) +
            JamendoProviderFactory(dynamic.jamendoClientId)
}
