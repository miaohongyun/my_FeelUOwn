package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultProviderConfigurationTest {
    @Test
    fun freshInstallEnablesChineseMusicDiscoverySources() {
        assertEquals(
            setOf("netease", "qqmusic", "bilibili", "modeb"),
            DEFAULT_ENABLED_PROVIDER_IDS,
        )
        assertTrue(DEFAULT_PROVIDER_ORDER_IDS.containsAll(DEFAULT_ENABLED_PROVIDER_IDS))
        assertTrue("ytmusic" in DEFAULT_PROVIDER_ORDER_IDS)
        assertTrue("ytmusic" !in DEFAULT_ENABLED_PROVIDER_IDS)
    }

    @Test
    fun defaultSettingsUseTheConfiguredProviderSet() {
        assertEquals(DEFAULT_ENABLED_PROVIDER_IDS, AppSettings().enabledProviderIds)
    }
}
