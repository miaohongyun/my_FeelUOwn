package org.feeluown.mobile.lossless

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNull

class DesktopFlacDecodeVerifierTest {
    @Test
    fun reportsDecoderUnavailableWithoutTreatingItAsDecodeSuccess() {
        val file = Files.createTempFile("fuoevolve-decoder-", ".flac").toFile()
        val previous = System.getProperty("fuoevolve.ffmpeg.path")
        try {
            file.writeBytes(byteArrayOf(0x66, 0x4c, 0x61, 0x43))
            System.setProperty("fuoevolve.ffmpeg.path", file.resolveSibling("missing-ffmpeg-executable").absolutePath)
            assertNull(DesktopFlacDecodeVerifier.verify(file))
        } finally {
            if (previous == null) System.clearProperty("fuoevolve.ffmpeg.path")
            else System.setProperty("fuoevolve.ffmpeg.path", previous)
            file.delete()
        }
    }
}
