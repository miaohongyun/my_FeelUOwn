package org.feeluown.mobile.sourceupdate

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS 侧存储：`<ApplicationSupport>/FuoEvolve/source_catalog`。
 *
 * 用 NSString.writeToFile(atomically = true)，让系统做原子替换；
 * Kotlin/Native 把 NSString 映射成 String，writeToFile 是 NSString 的扩展，
 * 必须显式转换 receiver 才能解析（运行时同一对象）。
 */
actual fun createPlatformSourceFileStore(): SourceFileStore = IosSourceFileStore()

@OptIn(ExperimentalForeignApi::class)
internal class IosSourceFileStore : SourceFileStore {

    private val fileManager = NSFileManager.defaultManager

    private val root: String by lazy {
        val urls = fileManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
        val base = (urls.firstOrNull() as? NSURL)?.path ?: "/tmp"
        "$base/FuoEvolve/source_catalog"
    }

    override suspend fun readText(path: String): String? = withContext(Dispatchers.Default) {
        val ns = NSString.stringWithContentsOfFile(fullPath(path)) ?: return@withContext null
        ns as String
    }

    override suspend fun writeText(path: String, content: String) = withContext(Dispatchers.Default) {
        val target = fullPath(path)
        val parent = parentOf(target)
        if (parent.isNotEmpty()) {
            runCatching { fileManager.createDirectoryAtPath(parent, true, null, null) }
        }
        @Suppress("CAST_NEVER_SUCCEEDS")
        val ns = content as NSString
        check(ns.writeToFile(target, true)) { "无法原子写入音源存储: $target" }
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.Default) {
        runCatching { fileManager.removeItemAtPath(fullPath(path), null) }
        Unit
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.Default) {
        fileManager.fileExistsAtPath(fullPath(path))
    }

    override suspend fun list(prefix: String): List<String> = withContext(Dispatchers.Default) {
        val directory = fullPath(prefix.removeSuffix("/"))
        val names = fileManager.contentsOfDirectoryAtPath(directory, null) ?: return@withContext emptyList()
        names.mapNotNull { it as? String }.map { "$prefix$it" }.sorted()
    }

    private fun fullPath(path: String): String = "$root/${sanitizeStoragePath(path)}"
}


/**
 * iOS：用 POSIX `gettimeofday` 取毫秒。
 *
 * 不用 `NSDate().timeIntervalSince1970`：Kotlin/Native 里那是 NSString/NSDate 分类方法，
 * 解析不稳定；也不用 Kotlin 的 experimental Clock，避免为一个时间戳引入实验 API。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun sourceUpdateNowMillis(): Long = memScoped {
    val value = alloc<platform.posix.timeval>()
    platform.posix.gettimeofday(value.ptr, null)
    value.tv_sec * 1000L + value.tv_usec / 1000L
}
