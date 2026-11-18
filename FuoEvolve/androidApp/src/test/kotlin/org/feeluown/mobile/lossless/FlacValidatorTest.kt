package org.feeluown.mobile.lossless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlacValidatorTest {
    @Test
    fun parsesStreamInfoSampleRateBitDepthChannelsAndDuration() {
        val info = FlacValidator.parseStreamInfo(validFlacHeader())

        assertEquals(44_100, info?.sampleRate)
        assertEquals(2, info?.channels)
        assertEquals(16, info?.bitsPerSample)
        assertEquals(7_938_000L, info?.totalSamples)
        assertEquals(180.0, info?.durationSeconds)
    }

    @Test
    fun rejectsNonFlacMagicAndTruncatedStreamInfo() {
        val valid = validFlacHeader()
        val invalidMagic = valid.copyOf().also { it[0] = 'X'.code.toByte() }

        assertNull(FlacValidator.parseStreamInfo(invalidMagic))
        assertNull(FlacValidator.parseStreamInfo(valid.copyOf(41)))
    }

    private fun validFlacHeader(): ByteArray = byteArrayOf(
        'f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(),
        0x80.toByte(), 0x00, 0x00, 0x22,
        // STREAMINFO min/max block size and min/max frame size.
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        // 44,100 Hz, stereo, 16-bit, 7,938,000 samples (180 seconds).
        0x0A, 0xC4.toByte(), 0x42, 0xF0.toByte(),
        0x00, 0x79, 0x1F, 0xD0.toByte(),
        // MD5 signature (not needed for STREAMINFO parsing).
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )
}
