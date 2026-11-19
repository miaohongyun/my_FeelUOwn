package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultProviderConfigurationTest {
    @Test
    fun freshInstallEnablesChineseMusicDiscoverySources() {
        assertEquals(
            setOf("netease", "qqmusic", "bilibili", "modeb", "jamendo"),
            DEFAULT_ENABLED_PROVIDER_IDS,
        )
        assertTrue(DEFAULT_PROVIDER_ORDER_IDS.containsAll(DEFAULT_ENABLED_PROVIDER_IDS))
        assertTrue("ytmusic" in DEFAULT_PROVIDER_ORDER_IDS)
        assertTrue("ytmusic" !in DEFAULT_ENABLED_PROVIDER_IDS)
    }

    @Test
    fun jamendoIsEnabledByDefaultForOutOfBoxUse() {
        assertTrue("jamendo" in DEFAULT_ENABLED_PROVIDER_IDS,
            "开放曲库应默认启用，家人安装后无需进设置页找开关")
        assertTrue(DEFAULT_ENABLED_PROVIDER_IDS.containsAll(PROVIDER_DEFAULTS_ADDED_IN_V1))
    }

    @Test
    fun defaultSettingsUseTheConfiguredProviderSet() {
        assertEquals(DEFAULT_ENABLED_PROVIDER_IDS, AppSettings().enabledProviderIds)
    }
}
