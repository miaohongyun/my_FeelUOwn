package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 受控音源目录相关设置的默认值与迁移。
 *
 * 这些字段会随目录一起演进，因此必须保证：老设置文件（没有这些键）能正常解码，
 * 且不会因为"没设置过"就先报一次检查失败。
 */
class SourceUpdateSettingsTest {

    @Test
    fun defaultsAreConservativeAndNullableSiblingsDecodeToThem() {
        val defaults = AppSettings()
        assertTrue(defaults.sourceAutoUpdateEnabled)
        assertEquals(24, defaults.sourceUpdateIntervalHours)
        assertEquals("hybrid", defaults.sourceMirrorPolicy)
        assertNull(defaults.sourceCatalogVersion)
        assertTrue(defaults.installedScriptVersions.isEmpty())
        assertTrue(defaults.quarantinedScriptIds.isEmpty())

        // 老版本写下的 JSON 里没有这些键：全部回落到默认值，而不是 null/0。
        val legacy = PersistedSettingsV1().toAppSettings()
        assertTrue(legacy.sourceAutoUpdateEnabled)
        assertEquals(24, legacy.sourceUpdateIntervalHours)
        assertEquals("hybrid", legacy.sourceMirrorPolicy)
        assertNull(legacy.sourceCatalogVersion)
    }

    @Test
    fun settingsRoundTripThroughPersistence() {
        val settings = AppSettings(
            sourceAutoUpdateEnabled = false,
            sourceUpdateIntervalHours = 48,
            sourceMirrorPolicy = "hybrid",
            sourceCatalogVersion = 42L,
            installedScriptVersions = mapOf("demo" to "1.2.3"),
            quarantinedScriptIds = setOf("sketchy"),
        )

        val restored = settings.toPersistedSettings().toAppSettings()

        assertFalse(restored.sourceAutoUpdateEnabled)
        assertEquals(48, restored.sourceUpdateIntervalHours)
        assertEquals(42L, restored.sourceCatalogVersion)
        assertEquals(mapOf("demo" to "1.2.3"), restored.installedScriptVersions)
        assertEquals(setOf("sketchy"), restored.quarantinedScriptIds)
    }

    @Test
    fun intervalIsClampedIntoTheSupportedRange() {
        // 0 会让"距上次超过 0 小时"永远为真，等于每次启动都打上游；必须被夹到最小值。
        assertEquals(1, PersistedSettingsV1(sourceUpdateIntervalHours = 0).toAppSettings().sourceUpdateIntervalHours)
        assertEquals(
            MAX_SOURCE_UPDATE_INTERVAL_HOURS,
            PersistedSettingsV1(sourceUpdateIntervalHours = 10_000).toAppSettings().sourceUpdateIntervalHours,
        )
    }

    @Test
    fun blankMirrorPolicyFallsBackToDefault() {
        // 手工编辑设置文件留空时不能变成 "" 这种无法识别的策略。
        assertEquals("hybrid", PersistedSettingsV1(sourceMirrorPolicy = "  ").toAppSettings().sourceMirrorPolicy)
    }
}
