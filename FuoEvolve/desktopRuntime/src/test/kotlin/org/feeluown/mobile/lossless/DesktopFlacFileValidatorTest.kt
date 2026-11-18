package org.feeluown.mobile.lossless

import java.nio.file.Files
import org.feeluown.mobile.PlaybackPayload
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopFlacFileValidatorTest {
    @Test
    fun acceptsAFileWithAValidStreamInfoHeader() {
        val file = Files.createTempFile("fuoevolve-valid-", ".flac").toFile()
        try {
            file.writeBytes(validHeader() + ByteArray(PAYLOAD_BYTES))
            assertNotNull(DesktopFlacFileValidator.validate(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsTruncatedAndNonFlacFiles() {
        val file = Files.createTempFile("fuoevolve-invalid-", ".flac").toFile()
        try {
            file.writeBytes(ByteArray(128))
            assertNull(DesktopFlacFileValidator.validate(file))
            // A header without any audio payload is a truncated download, not a song.
            file.writeBytes(validHeader())
            assertNull(DesktopFlacFileValidator.validate(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsFlacLabelledDownloadWithInvalidContainer() {
        val file = Files.createTempFile("fuoevolve-flac-download-", ".part").toFile()
        try {
            file.writeBytes(ByteArray(128))
            val failure = validateDesktopDownloadedMediaWith(
                file.toPath(),
                PlaybackPayload(
                    url = "https://music.example/rest/stream.view?id=track",
                    title = "测试歌曲",
                    artists = "测试歌手",
                    album = "测试专辑",
                    source = "subsonic",
                    audioQuality = "FLAC 44.1kHz/16bit（容器检查通过）",
                ),
                decodeVerifier = { null },
            )
            assertNotNull(failure)
        } finally {
            file.delete()
        }
    }

    @Test
    fun acceptsFlacLabelledDownloadWithValidContainerHeader() {
        val file = Files.createTempFile("fuoevolve-flac-download-", ".part").toFile()
        try {
            file.writeBytes(validHeader() + ByteArray(PAYLOAD_BYTES))
            val failure = validateDesktopDownloadedMediaWith(
                file.toPath(),
                PlaybackPayload(
                    url = "https://music.example/rest/stream.view?id=track",
                    title = "测试歌曲",
                    artists = "测试歌手",
                    album = "测试专辑",
                    source = "subsonic",
                    audioQuality = "FLAC 44.1kHz/16bit",
                ),
                decodeVerifier = { null },
            )
            assertNull(failure)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsDownloadWhenFullDecodeFails() {
        val file = Files.createTempFile("fuoevolve-flac-decode-", ".part").toFile()
        try {
            file.writeBytes(validHeader() + ByteArray(PAYLOAD_BYTES))
            val failure = validateDesktopDownloadedMediaWith(
                file.toPath(),
                PlaybackPayload(
                    url = "https://music.example/song.flac",
                    title = "测试歌曲",
                    artists = "测试歌手",
                    album = "测试专辑",
                    source = "subsonic",
                    audioQuality = "FLAC",
                ),
                decodeVerifier = { false },
            )
            assertNotNull(failure)
            kotlin.test.assertTrue(failure.contains("解码"))
        } finally {
            file.delete()
        }
    }

    private fun validHeader() = byteArrayOf(
        0x66, 0x4c, 0x61, 0x43,
        0x00, 0x00, 0x00, 0x22,
        0x00, 0x10, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x0a, 0xc4.toByte(), 0x42, 0xf0.toByte(), 0x00, 0x79, 0x1f, 0xd0.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    private companion object {
        const val PAYLOAD_BYTES = 2 * 1024
    }
}
