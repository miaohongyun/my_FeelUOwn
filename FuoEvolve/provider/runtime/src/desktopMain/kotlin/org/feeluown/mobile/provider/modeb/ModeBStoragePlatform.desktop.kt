package org.feeluown.mobile.provider.modeb

import com.feeluown.modeb.ModeBStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop durable storage: `~/.FeelUOwn/mode_b/<key>`.
 * Each write goes to a temp file first and is then atomically renamed into place,
 * so a crash mid-write can never leave a half-written config/health snapshot.
 */
actual fun createPlatformModeBStorage(): ModeBStorage = DesktopModeBStorage()

internal class DesktopModeBStorage(
    directory: File = File(System.getProperty("user.home"), ".FeelUOwn/mode_b"),
) : ModeBStorage {
    private val dir: File = directory

    override suspend fun readText(key: String): String? = withContext(Dispatchers.IO) {
        val file = File(dir, safeName(key))
        if (!file.exists()) null else runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    override suspend fun writeText(key: String, content: String) = withContext(Dispatchers.IO) {
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, safeName(key))
        val tmp = File(dir, "${safeName(key)}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "无法原子写入 Mode B 存储: ${target.absolutePath}" }
    }

    private fun safeName(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
