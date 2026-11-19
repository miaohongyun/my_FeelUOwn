package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 音源默认集一次性迁移测试（纯函数）。
 *
 * 验证"老用户升级后 jamendo 开箱即用"与"迁移只跑一次、用户仍可主动关闭"两条语义。
 */
class ProviderDefaultsMigrationTest {

    @Test
    fun oldUserGetsNewlyDefaultedJamendoOnce() {
        // 老用户快照：v0、enabledProviderIds 不含 jamendo。
        val old = setOf("netease", "qqmusic", "bilibili", "modeb")
        val migrated = migrateProviderDefaults(old, fromVersion = 0)
        assertTrue("jamendo" in migrated.enabledProviderIds, "升级后应自动补入 jamendo")
        assertEquals(CURRENT_PROVIDER_DEFAULTS_MIGRATION_VERSION, migrated.migrationVersion)
        assertEquals(old + "jamendo", migrated.enabledProviderIds)
    }

    @Test
    fun migrationIsIdempotentSoUserCanDisableAfterwards() {
        // 迁移跑过一次后（v1），即使用户主动关掉 jamendo，也不再被补回。
        val userDisabled = setOf("netease", "qqmusic", "bilibili", "modeb")
        val again = migrateProviderDefaults(userDisabled, fromVersion = 1)
        assertEquals(userDisabled, again.enabledProviderIds, "v1 后不得再补入，用户可自由关闭")
        assertEquals(1, again.migrationVersion)
    }

    @Test
    fun futureVersionIsLeftAlone() {
        val custom = setOf("netease", "jamendo")
        val result = migrateProviderDefaults(custom, fromVersion = 99)
        assertEquals(custom, result.enabledProviderIds, "高于当前版本的快照不应被改写")
        assertEquals(99, result.migrationVersion)
    }

    @Test
    fun freshInstallAlreadyAtCurrentVersionNeedsNoMigration() {
        val fresh = DEFAULT_ENABLED_PROVIDER_IDS
        val result = migrateProviderDefaults(fresh, fromVersion = CURRENT_PROVIDER_DEFAULTS_MIGRATION_VERSION)
        assertEquals(fresh, result.enabledProviderIds)
    }
}
