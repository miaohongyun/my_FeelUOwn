package org.feeluown.mobile

import com.feeluown.modeb.ModeBStorage
import org.feeluown.mobile.provider.modeb.DesktopModeBStorage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies the durable Mode B storage round-trips config + circuit-breaker state
 * across fresh provider instances (simulating an app process restart).
 */
class ModeBDurableStorageTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "fuo_modeb_test_${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun configAndBreakerStateSurviveFreshProviderInstance() {
        val dir = tempDir()
        val key = "modeb_health_state.json"

        // 1) first "process": write health state through a fresh durable storage
        val first = DesktopModeBStorage(dir)
        first.writeTextBlocking(key, """{"entries":{"src_a":{"failures":3,"cooldownUntilMillis":9999999999999}}}""")

        // 2) second "process": a brand-new storage instance must read the same bytes
        val second = DesktopModeBStorage(dir)
        val raw = second.readTextBlocking(key)
        assertEquals(
            """{"entries":{"src_a":{"failures":3,"cooldownUntilMillis":9999999999999}}}""",
            raw,
            "热更新/冷却状态必须在重建实例后仍然存在",
        )

        // 3) writing a second key must not clobber the first
        second.writeTextBlocking("modeb_config.json", """{"configVersion":"x"}""")
        assertEquals(
            """{"entries":{"src_a":{"failures":3,"cooldownUntilMillis":9999999999999}}}""",
            first.readTextBlocking(key),
        )
        dir.deleteRecursively()
    }

    @Test
    fun missingKeyReturnsNull() {
        val dir = tempDir()
        val storage = DesktopModeBStorage(dir)
        assertNull(storage.readTextBlocking("never_written"))
        dir.deleteRecursively()
    }
}

// Test helpers bridge the suspend storage API to the synchronous test body.
private fun ModeBStorage.writeTextBlocking(key: String, content: String) =
    kotlinx.coroutines.runBlocking { writeText(key, content) }

private fun ModeBStorage.readTextBlocking(key: String): String? =
    kotlinx.coroutines.runBlocking { readText(key) }
