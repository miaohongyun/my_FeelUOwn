package org.feeluown.mobile

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopDownloadRepositoryTest {
    @Test
    fun flacQualityLabelOverridesSubsonicViewEndpointExtension() {
        assertEquals(
            "flac",
            mediaExtension(testPayload("https://music.example/rest/stream.view?id=123", "FLAC 44.1kHz/16bit")),
        )
    }

    @Test
    fun ordinaryDownloadKeepsItsUrlExtension() {
        assertEquals(
            "mp3",
            mediaExtension(testPayload("https://music.example/audio/song.mp3?token=abc")),
        )
    }

    @Test
    fun downloadPersistsReloadsAndDeletesCompletedFile() = runBlocking {
        val root = Files.createTempDirectory("fuoevolve-desktop-download-test")
        val source = Files.createTempFile("fuoevolve-source", ".mp3")
        val bytes = ByteArray(128 * 1024) { index -> (index % 251).toByte() }
        Files.write(source, bytes)
        val track = providerTrack("provider:test")
        val payload = PlaybackPayload(
            url = source.toUri().toString(),
            title = track.title,
            artists = track.artists,
            album = track.album,
            source = track.source,
            durationMs = track.durationMs,
            lyrics = "[00:00.00]Desktop lyrics",
        )
        var resolverCalls = 0
        val repository = DesktopDownloadRepository(
            resolvePayload = {
                resolverCalls += 1
                payload
            },
            storageRoot = root,
        )

        try {
            repository.load()
            repository.download(track)
            val completed = withTimeout(5_000) {
                repository.tasks.first { tasks ->
                    tasks.any { it.id == track.id && it.status == DownloadTaskStatus.Completed }
                }.first { it.id == track.id }
            }

            assertEquals(1, resolverCalls)
            assertEquals(bytes.size.toLong(), completed.downloadedBytes)
            val completedUri = requireNotNull(completed.completedUri)
            val completedPath = Path.of(URI(completedUri))
            assertTrue(Files.isRegularFile(completedPath))
            assertContentEquals(bytes, Files.readAllBytes(completedPath))
            assertEquals(
                "[00:00.00]Desktop lyrics",
                Files.readString(completedPath.resolveSibling("${completedPath.fileName}.lrc")),
            )
            val downloadedState = withTimeout(5_000) {
                repository.states.first { states -> states[track.id] is DownloadState.Downloaded }
            }.getValue(track.id)
            assertEquals(completedUri, assertIs<DownloadState.Downloaded>(downloadedState).uri)

            repository.close()

            val reloaded = DesktopDownloadRepository(
                resolvePayload = { error("completed download must not re-resolve") },
                storageRoot = root,
            )
            try {
                reloaded.load()
                val restored = reloaded.tasks.value.single { it.id == track.id }
                assertEquals(DownloadTaskStatus.Completed, restored.status)
                assertEquals(completedUri, restored.completedUri)
                assertIs<DownloadState.Downloaded>(reloaded.states.value.getValue(track.id))

                reloaded.deleteTask(track.id, deleteFile = true)
                assertTrue(reloaded.tasks.value.none { it.id == track.id })
                assertFalse(Files.exists(completedPath))
                assertFalse(Files.exists(completedPath.resolveSibling("${completedPath.fileName}.lrc")))
            } finally {
                reloaded.close()
            }
        } finally {
            repository.close()
            Files.deleteIfExists(source)
            root.toFile().deleteRecursively()
        }
    }


    @Test
    fun reloadRejectsCompletedFlacWhoseContainerWasCorruptedAfterDownload() = runBlocking {
        val root = Files.createTempDirectory("fuoevolve-desktop-flac-reload-test")
        val source = Files.createTempFile("fuoevolve-source", ".flac")
        Files.write(source, ByteArray(128) { 0x55 })
        val track = providerTrack("provider:flac-reload")
        val payload = PlaybackPayload(
            url = source.toUri().toString(),
            title = track.title,
            artists = track.artists,
            album = track.album,
            source = track.source,
            audioQuality = "FLAC 44.1kHz/16bit",
        )
        val repository = DesktopDownloadRepository(resolvePayload = { payload }, storageRoot = root)
        try {
            repository.load()
            repository.download(track, payload)
            val completed = withTimeout(5_000) {
                repository.tasks.first { tasks ->
                    tasks.any { it.id == track.id && it.status == DownloadTaskStatus.Completed }
                }.first { it.id == track.id }
            }
            val completedPath = Path.of(URI(requireNotNull(completed.completedUri)))
            repository.close()

            // Simulate bit rot after the download was originally marked complete.
            Files.write(completedPath, ByteArray(128))
            installDesktopDownloadedMediaValidator { file, _ ->
                if (file.fileName.toString().endsWith(".flac", ignoreCase = true) &&
                    (!Files.isRegularFile(file) || Files.size(file) < 42L ||
                        !Files.readAllBytes(file).take(4).toByteArray().contentEquals(byteArrayOf(0x66, 0x4c, 0x61, 0x43)))
                ) "FLAC 下载文件未通过容器校验，已拒绝保存" else null
            }
            val reloaded = DesktopDownloadRepository(
                resolvePayload = { error("invalid completed download must not be reused") },
                storageRoot = root,
            )
            try {
                reloaded.load()
                val restored = reloaded.tasks.value.single { it.id == track.id }
                assertEquals(DownloadTaskStatus.Failed, restored.status)
                assertEquals(null, restored.completedUri)
                assertTrue(restored.failureMessage.orEmpty().contains("FLAC"))
                assertTrue(Files.exists(completedPath), "invalid file is retained for manual recovery")
            } finally {
                reloaded.close()
            }
        } finally {
            repository.close()
            installDesktopDownloadedMediaValidator { _, _ -> null }
            Files.deleteIfExists(source)
            root.toFile().deleteRecursively()
        }
    }

    private fun testPayload(url: String, quality: String? = null) = PlaybackPayload(
        url = url,
        title = "Desktop Track",
        artists = "Desktop Artist",
        album = "Desktop Album",
        source = "provider",
        audioQuality = quality,
    )

    private fun providerTrack(id: String): MusicTrack = MusicTrack(
        id = id,
        title = "Desktop Track",
        artists = "Desktop Artist",
        album = "Desktop Album",
        source = "provider",
        sourceType = TrackSourceType.Provider,
        durationMs = 180_000,
        providerId = id,
        providerName = "Provider",
    )
}
