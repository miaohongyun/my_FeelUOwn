package org.feeluown.mobile.provider.modeb

import com.feeluown.modeb.ModeBStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs the application files directory used by durable Mode B storage.
 * Must be called once from the Android application container before any provider
 * is constructed. Until installed, storage falls back to the cache dir so the app
 * never crashes; durability is simply unavailable in that window.
 */
@Volatile
private var installedDirectory: File? = null

fun installModeBStorageDirectory(directory: File) {
    installedDirectory = directory
}

actual fun createPlatformModeBStorage(): ModeBStorage =
    AndroidModeBStorage(installedDirectory)

internal class AndroidModeBStorage(
    directory: File?,
) : ModeBStorage {
    // applicationContext.filesDir is always available; fall back to JVM tmp only if
    // install was not called (e.g. a bare unit test) so writes never throw.
    private val dir: File = directory ?: File(System.getProperty("java.io.tmpdir"), "fuo_modeb")

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
