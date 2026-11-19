package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchProviderSelectionTest {
    @Test
    fun freshInstallSearchesAllEnabledChineseDiscoverySources() {
        assertEquals(
            listOf("netease", "qqmusic", "bilibili", "modeb", "jamendo"),
            AppSettings().searchProviderIdsForFeature(),
        )
    }

    @Test
    fun explicitSearchProviderSelectionIsRespected() {
        val settings = AppSettings(
            enabledProviderIds = setOf("netease", "qqmusic", "bilibili"),
            providerOrderIds = listOf("qqmusic", "netease", "bilibili"),
            searchProviderIds = setOf("qqmusic", "bilibili"),
        )

        assertEquals(
            listOf("qqmusic", "bilibili"),
            settings.searchProviderIdsForFeature(),
        )
    }

    @Test
    fun disabledProviderIsNeverIncludedInSearch() {
        val settings = AppSettings(
            enabledProviderIds = setOf("netease", "qqmusic"),
            providerOrderIds = listOf("netease", "qqmusic", "bilibili"),
            searchProviderIds = setOf("netease", "qqmusic", "bilibili"),
        )

        assertEquals(
            listOf("netease", "qqmusic"),
            settings.searchProviderIdsForFeature(),
        )
    }
}
