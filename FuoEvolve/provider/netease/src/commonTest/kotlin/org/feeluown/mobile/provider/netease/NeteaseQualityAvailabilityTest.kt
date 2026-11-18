package org.feeluown.mobile.provider.netease

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.feeluown.mobile.AudioQualityPolicy

class NeteaseQualityAvailabilityTest {
    @Test
    fun freeAccountOnlyGetsTheAccountScopedBitrate() {
        // Free accounts still see maxbr = 999000; only pl/dl describe real permissions.
        val policies = policies(
            """
            {"st":0,"pl":128000,"dl":128000,"maxbr":999000,"fl":0,
             "plLevel":"standard","dlLevel":"standard","flLevel":"none"}
            """,
        )
        assertEquals(listOf(AudioQualityPolicy.Low.policy), policies)
    }

    @Test
    fun memberAccountGetsEveryTier() {
        val policies = policies(
            """
            {"st":0,"pl":999000,"dl":999000,"maxbr":999000,"fl":999000,
             "plLevel":"lossless","dlLevel":"lossless","flLevel":"lossless"}
            """,
        )
        assertEquals(
            listOf(
                AudioQualityPolicy.Highest.policy,
                AudioQualityPolicy.High.policy,
                AudioQualityPolicy.Standard.policy,
                AudioQualityPolicy.Low.policy,
            ),
            policies,
        )
    }

    @Test
    fun hiresLevelImpliesLosslessEvenWithoutBitrate() {
        val policies = policies("""{"st":0,"dl":999000,"dlLevel":"hires"}""")
        assertEquals(
            listOf(
                AudioQualityPolicy.Highest.policy,
                AudioQualityPolicy.High.policy,
                AudioQualityPolicy.Standard.policy,
                AudioQualityPolicy.Low.policy,
            ),
            policies,
        )
    }

    @Test
    fun removedTrackReportsNothingAvailable() {
        assertEquals(emptyList(), policies("""{"st":-1,"pl":128000,"dl":128000}"""))
    }

    @Test
    fun zeroAccountFieldsMeanNoStreamInsteadOfFallingBackToMaxbr() {
        assertEquals(emptyList(), policies("""{"st":0,"pl":0,"dl":0,"maxbr":999000,"fl":999000}"""))
    }

    @Test
    fun legacyPayloadWithoutAccountFieldsUsesDeclaredBitrate() {
        val policies = policies("""{"st":0,"maxbr":320000}""")
        assertEquals(
            listOf(
                AudioQualityPolicy.High.policy,
                AudioQualityPolicy.Standard.policy,
                AudioQualityPolicy.Low.policy,
            ),
            policies,
        )
    }

    @Test
    fun missingPrivilegeIsUnknownRatherThanEmpty() {
        assertNull(neteaseQualityPolicies(null))
    }

    private fun policies(json: String): List<String>? =
        neteaseQualityPolicies(Json.parseToJsonElement(json).jsonObject)
}
