package org.feeluown.mobile.sourceupdate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android 侧存储：`context.filesDir/source_catalog`。
 *
 * 与 Mode B 存储同一套约定：应用容器启动时注入目录，未注入时退到临时目录
 * （单测环境），保证读写不抛异常，只是不持久。
 */
@Volatile
private var installedDirectory: File? = null

fun installSourceStorageDirectory(directory: File) {
    installedDirectory = directory
}

actual fun createPlatformSourceFileStore(): SourceFileStore = AndroidSourceFileStore(installedDirectory)

internal class AndroidSourceFileStore(directory: File?) : SourceFileStore {

    private val root: File = directory ?: File(System.getProperty("java.io.tmpdir"), "fuo_sourceupdate")

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

/** Android：墙上时钟毫秒。 */
actual fun sourceUpdateNowMillis(): Long = System.currentTimeMillis()
