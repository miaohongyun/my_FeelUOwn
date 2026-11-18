package org.feeluown.mobile.provider.modeb

import com.feeluown.modeb.ModeBStorage
import kotlinx.cinterop.ExperimentalForeignApi
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
 * iOS durable storage: `<ApplicationSupport>/FuoEvolve/mode_b/<key>`.
 * Uses NSString.writeToFile(atomically = true) so the OS performs an atomic replace.
 */
actual fun createPlatformModeBStorage(): ModeBStorage = IosModeBStorage()

@OptIn(ExperimentalForeignApi::class)
internal class IosModeBStorage : ModeBStorage {
    private val dir: String by lazy {
        val fm = NSFileManager.defaultManager
        val urls = fm.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
        val base = (urls.firstOrNull() as? NSURL)?.path ?: "/tmp"
        val target = "$base/FuoEvolve/mode_b"
        runCatching { fm.createDirectoryAtPath(target, true, null, null) }
        target
    }

    override suspend fun readText(key: String): String? = withContext(Dispatchers.Default) {
        val path = "$dir/${safeName(key)}"
        val ns = NSString.stringWithContentsOfFile(path) ?: return@withContext null
        ns as String
    }

    override suspend fun writeText(key: String, content: String) = withContext(Dispatchers.Default) {
        val path = "$dir/${safeName(key)}"
        // Kotlin/Native 把 NSString 映射为 String；writeToFile 是 NSString 的扩展，
        // 必须显式转换 receiver 才能解析。运行时二者同一对象，cast 必然成功（抑制静态误报警告）。
        @Suppress("CAST_NEVER_SUCCEEDS")
        val ns = content as NSString
        check(ns.writeToFile(path, true)) { "无法原子写入 Mode B 存储: $path" }
    }

    private fun safeName(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
