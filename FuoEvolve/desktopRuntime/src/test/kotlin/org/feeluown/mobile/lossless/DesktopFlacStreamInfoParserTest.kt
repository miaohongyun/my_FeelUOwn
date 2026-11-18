package org.feeluown.mobile.lossless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopFlacStreamInfoParserTest {
    @Test
    fun parsesStreamInfoFromFlacHeader() {
        val bytes = byteArrayOf(
            0x66, 0x4c, 0x61, 0x43, // fLaC
            0x00, 0x00, 0x00, 0x22, // STREAMINFO, 34 bytes
            0x00, 0x10, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x0a, 0xc4.toByte(), 0x42, 0xf0.toByte(), 0x00, 0x79, 0x1f, 0xd0.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val parsed = DesktopFlacStreamInfoParser.parse(bytes)
        assertEquals(44_100, parsed?.sampleRate)
        assertEquals(2, parsed?.channels)
        assertEquals(16, parsed?.bitsPerSample)
        assertEquals(7_938_000L, parsed?.totalSamples)
        assertEquals(180.0, parsed?.durationSeconds)
    }

    @Test
    fun rejectsNonFlacAndTruncatedHeaders() {
        assertNull(DesktopFlacStreamInfoParser.parse(ByteArray(64)))
        assertNull(DesktopFlacStreamInfoParser.parse(byteArrayOf(0x66, 0x4c, 0x61, 0x43)))
    }

    @Test
    fun rejectsNonStreamInfoAsFirstMetadataBlock() {
        val bytes = validHeader().also { it[4] = 0x81.toByte() }
        assertNull(DesktopFlacStreamInfoParser.parse(bytes))
    }

    @Test
    fun rejectsZeroSampleRateAndZeroSampleCount() {
        val noSampleRate = validHeader().also { it[18] = 0; it[19] = 0; it[20] = 0 }
        val noSamples = validHeader().also { bytes ->
            for (index in 21..25) bytes[index] = 0
        }
        assertNull(DesktopFlacStreamInfoParser.parse(noSampleRate))
        assertNull(DesktopFlacStreamInfoParser.parse(noSamples))
    }

    private fun validHeader() = byteArrayOf(
        0x66, 0x4c, 0x61, 0x43,
        0x00, 0x00, 0x00, 0x22,
        0x00, 0x10, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x0a, 0xc4.toByte(), 0x42, 0xf0.toByte(), 0x00, 0x79, 0x1f, 0xd0.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )
}
