package org.feeluown.mobile.lossless

/** Minimal, strict FLAC STREAMINFO parser shared by desktop playback and download validation. */
internal object DesktopFlacStreamInfoParser {
    fun parse(bytes: ByteArray): FlacStreamInfo? {
        if (bytes.size < FLAC_MARKER_SIZE + METADATA_HEADER_SIZE + STREAMINFO_SIZE) return null
        if (!bytes.copyOfRange(0, FLAC_MARKER_SIZE).contentEquals(FLAC_MARKER)) return null

        // FLAC requires STREAMINFO to be the first metadata block and its length is exactly 34.
        val headerOffset = FLAC_MARKER_SIZE
        val header = bytes[headerOffset].toInt() and 0xff
        val blockType = header and 0x7f
        val blockLength = ((bytes[headerOffset + 1].toInt() and 0xff) shl 16) or
            ((bytes[headerOffset + 2].toInt() and 0xff) shl 8) or
            (bytes[headerOffset + 3].toInt() and 0xff)
        if (blockType != STREAMINFO_BLOCK_TYPE || blockLength != STREAMINFO_SIZE) return null

        val base = headerOffset + METADATA_HEADER_SIZE + 10
        val b0 = bytes[base].toInt() and 0xff
        val b1 = bytes[base + 1].toInt() and 0xff
        val b2 = bytes[base + 2].toInt() and 0xff
        val b3 = bytes[base + 3].toInt() and 0xff
        val b4 = bytes[base + 4].toInt() and 0xff
        val b5 = bytes[base + 5].toInt() and 0xff
        val b6 = bytes[base + 6].toInt() and 0xff
        val b7 = bytes[base + 7].toInt() and 0xff
        val sampleRate = (b0 shl 12) or (b1 shl 4) or (b2 ushr 4)
        val channels = ((b2 ushr 1) and 7) + 1
        val bitsPerSample = (((b2 and 1) shl 4) or (b3 ushr 4)) + 1
        val totalSamples = ((b3 and 15).toLong() shl 32) or (b4.toLong() shl 24) or
            (b5.toLong() shl 16) or (b6.toLong() shl 8) or b7.toLong()

        if (sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) return null
        if (channels !in 1..8 || bitsPerSample !in 4..32 || totalSamples <= 0L) return null
        return FlacStreamInfo(sampleRate, channels, bitsPerSample, totalSamples)
    }

    private const val FLAC_MARKER_SIZE = 4
    private const val METADATA_HEADER_SIZE = 4
    private const val STREAMINFO_SIZE = 34
    private const val STREAMINFO_BLOCK_TYPE = 0
    private const val MIN_SAMPLE_RATE = 1
    private const val MAX_SAMPLE_RATE = 655_350
    private val FLAC_MARKER = byteArrayOf(0x66, 0x4c, 0x61, 0x43)
}
