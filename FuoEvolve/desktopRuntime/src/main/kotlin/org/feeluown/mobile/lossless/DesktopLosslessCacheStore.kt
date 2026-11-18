package org.feeluown.mobile.lossless

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.feeluown.mobile.MusicTrack

/** Disk-backed, bounded cache for desktop FLAC files. Only container-validated files are published. */
internal class DesktopLosslessCacheStore(
    private val directory: Path,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    fun find(track: MusicTrack): File? {
        val file = targetFile(track)
        if (!file.isFile) return null
        if (DesktopFlacFileValidator.validate(file) == null) {
            runCatching { Files.deleteIfExists(file.toPath()) }
            return null
        }
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return file
    }

    fun temporaryFile(track: MusicTrack): File = directory.resolve("${losslessTrackKey(track)}.part").toFile()

    fun install(track: MusicTrack, downloaded: File, expectedSizeBytes: Long = 0L): File? {
        if (!downloaded.isFile || (expectedSizeBytes > 0L && downloaded.length() != expectedSizeBytes)) {
            runCatching { Files.deleteIfExists(downloaded.toPath()) }
            return null
        }
        if (DesktopFlacFileValidator.validate(downloaded) == null) {
            runCatching { Files.deleteIfExists(downloaded.toPath()) }
            return null
        }
        return try {
            Files.createDirectories(directory)
            val target = targetFile(track)
            try {
                Files.move(downloaded.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(downloaded.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            evictIfNeeded()
            target.takeIf { it.isFile }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { Files.deleteIfExists(downloaded.toPath()) }
        }
    }

    private fun targetFile(track: MusicTrack): File = directory.resolve("${losslessTrackKey(track)}.flac").toFile()

    private fun evictIfNeeded() {
        val files = runCatching {
            Files.list(directory).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".flac") }
                    .map { it.toFile() }
                    .toList()
            }
        }.getOrDefault(emptyList())
        var total = files.sumOf(File::length)
        if (total <= maxBytes) return
        files.sortedBy(File::lastModified).forEach { file ->
            if (total <= maxBytes) return@forEach
            val size = file.length()
            if (runCatching { Files.deleteIfExists(file.toPath()) }.getOrDefault(false)) total -= size
        }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 1_536L * 1024L * 1024L
    }
}
