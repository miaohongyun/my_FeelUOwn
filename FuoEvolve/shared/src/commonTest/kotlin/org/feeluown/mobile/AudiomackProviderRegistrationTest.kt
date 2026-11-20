package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 阶段 J2.3：Audiomack Provider 正式接入应用组合的静态验证。
 *
 * 真实验证（搜索/映射）在 provider:musicfree 的 PhaseJ2RealSearchTest 中完成；
 * 这里只验证"是否挂进应用组合、是否默认关闭、身份是否正确"，不改 feature/search 抽象。
 */
class AudiomackProviderRegistrationTest {

    private val audiomackId = "mf-audiomack"

    @Test
    fun j2_audiomackRegisteredInComposition() {
        val infos = ProviderComposition.providerInfos()
        val audiomack = infos.firstOrNull { it.providerId == audiomackId }
        assertNotNull(audiomack, "Audiomack 必须注册进 ProviderComposition：${infos.map { it.providerId }}")
        assertTrue(audiomack.providerName.contains("Audiomack"))
    }

    @Test
    fun j2_audiomackNotDefaultEnabled() {
        assertFalse(
            audiomackId in DEFAULT_ENABLED_PROVIDER_IDS,
            "J2 单一试点未经验证不得进入默认启用集（需用户显式开启）",
        )
        assertFalse(audiomackId in DEFAULT_PROVIDER_ORDER_IDS, "不得抢占默认搜索顺序")
    }

    @Test
    fun j2_audiomackFactoryCreatesProviderWithCorrectIdentity() {
        val http = org.feeluown.mobile.provider.core.network.ProviderHttpClient()
        val deps = org.feeluown.mobile.provider.core.ProviderRuntimeDependencies(
            http = http,
            credentials = org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore(),
        )
        val factories = ProviderComposition.createProviderFactories(http, deps.credentials)
        assertTrue(factories.containsKey(audiomackId), "组合必须能创建 Audiomack Provider")
        val provider = factories[audiomackId]!!()
        assertTrue(provider.id == audiomackId)
        assertTrue(provider.name.contains("Audiomack"))
        // 能力边界：只声明搜索；resolve/trackDetail 等由基类默认实现，不宣称播放能力。
        assertTrue(provider.features.isEmpty(), "J2 不向 UI 暴露播放/下载等未实现能力")
    }
}
