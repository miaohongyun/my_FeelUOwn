package org.feeluown.mobile.lossless

import java.nio.file.Files
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.TrackSourceType
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopLosslessCacheStoreTest {
    @Test
    fun installsAndFindsOnlyAValidatedFlac() {
        val directory = Files.createTempDirectory("fuoevolve-lossless-cache-")
        try {
            val store = DesktopLosslessCacheStore(directory)
            val track = track("cache-hit")
            val temporary = store.temporaryFile(track)
            temporary.writeBytes(validHeader() + ByteArray(PAYLOAD_BYTES))

            assertNotNull(store.install(track, temporary))
            assertNotNull(store.find(track))

            store.find(track)?.writeBytes(ByteArray(128))
            assertNull(store.find(track), "corrupted cache entry should be removed")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsUnexpectedDownloadedSize() {
        val directory = Files.createTempDirectory("fuoevolve-lossless-size-")
        try {
            val store = DesktopLosslessCacheStore(directory)
            val temporary = store.temporaryFile(track("size-check"))
            temporary.writeBytes(validHeader() + ByteArray(PAYLOAD_BYTES))
            assertNull(store.install(track("size-check"), temporary, expectedSizeBytes = 999L))
            assertNull(store.find(track("size-check")))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun evictsLeastRecentlyUsedFilesWhenBoundIsExceeded() {
        val directory = Files.createTempDirectory("fuoevolve-lossless-evict-")
        try {
            val store = DesktopLosslessCacheStore(directory, maxBytes = 3_000L)
            val first = track("first")
            val second = track("second")
            val firstTemp = store.temporaryFile(first)
            firstTemp.writeBytes(validHeader() + ByteArray(PAYLOAD_BYTES))
            assertNotNull(store.install(first, firstTemp))
            val firstFile = store.find(first)
            assertNotNull(firstFile)
            firstFile.setLastModified(1L)

            val secondTemp = store.temporaryFile(second)
            secondTemp.writeBytes(validHeader() + ByteArray(PAYLOAD_BYTES))
            assertNotNull(store.install(second, secondTemp))

            assertNull(store.find(first))
            assertNotNull(store.find(second))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun track(id: String) = MusicTrack(
        id = id,
        title = "测试歌曲 $id",
        artists = "测试歌手",
        album = "测试专辑",
        source = "netease",
        sourceType = TrackSourceType.Provider,
    )

    private companion object {
        /** Header + payload must clear the 1 KiB "not a truncated stub" floor. */
        const val PAYLOAD_BYTES = 2 * 1024
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
