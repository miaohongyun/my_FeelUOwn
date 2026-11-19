package org.feeluown.mobile.sourceupdate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 桌面侧存储：`~/.FeelUOwn/source_catalog`。
 *
 * 每次写入先落 `.tmp` 再改名，崩溃时不会留下半个脚本文件
 * ——脚本文件被截断会直接导致 SHA-256 校验失败，进而让整个音源不可用。
 */
actual fun createPlatformSourceFileStore(): SourceFileStore =
    DesktopSourceFileStore(File(System.getProperty("user.home"), ".FeelUOwn/source_catalog"))

internal class DesktopSourceFileStore(private val root: File) : SourceFileStore {

    override suspend fun readText(path: String): String? = withContext(Dispatchers.IO) {
        val file = File(root, sanitizeStoragePath(path))
        if (!file.exists()) null else runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    override suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        val target = File(root, sanitizeStoragePath(path))
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            tmp.delete()
            error("无法替换旧文件: ${target.absolutePath}")
        }
        check(tmp.renameTo(target)) { "无法原子写入: ${target.absolutePath}" }
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val file = File(root, sanitizeStoragePath(path))
        if (file.exists()) file.delete()
        Unit
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        File(root, sanitizeStoragePath(path)).exists()
    }

    override suspend fun list(prefix: String): List<String> = withContext(Dispatchers.IO) {
        val directory = File(root, sanitizeStoragePath(prefix.removeSuffix("/")))
        val names = directory.list() ?: return@withContext emptyList()
        names.map { "$prefix$it" }.sorted()
    }
}

/** Desktop(JVM)：墙上时钟毫秒。 */
actual fun sourceUpdateNowMillis(): Long = System.currentTimeMillis()
