package org.feeluown.mobile.lossless

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.feeluown.mobile.AppLogger
import java.io.File

/**
 * 无损文件缓存。
 *
 * 文件统一放在 `filesDir/lossless`，索引写在同一目录的 `index.json`。
 * 所有公开方法都自行切到 IO 线程，避免调用方在主线程做磁盘操作。
 */
class LosslessCacheStore(context: Context) {
    private val rootDir = File(context.filesDir, "lossless")
    private val indexFile = File(rootDir, "index.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private var indexCache: LosslessCacheIndex? = null

    /** 返回已缓存且文件仍存在的条目；索引存在但文件被系统清理时会顺手删掉脏条目。 */
    suspend fun find(trackKey: String): LosslessCacheEntry? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entry = loadIndex().entries[trackKey] ?: return@withLock null
            val file = File(entry.filePath)
            if (!file.isFile || file.length() <= 0L) {
                AppLogger.w(TAG, "无损缓存文件缺失，移除索引 key=$trackKey path=${entry.filePath}")
                persistIndex(loadIndex().copy(entries = loadIndex().entries - trackKey))
                return@withLock null
            }
            entry
        }
    }

    /** 校验通过后写入索引，并按容量上限淘汰最旧条目。 */
    suspend fun save(entry: LosslessCacheEntry): LosslessCacheEntry = withContext(Dispatchers.IO) {
        mutex.withLock {
            var entries = loadIndex().entries + (entry.key to entry)
            var totalBytes = entries.values.sumOf { it.sizeBytes }
            if (totalBytes > LOSSLESS_CACHE_MAX_BYTES) {
                val ordered = entries.values.sortedBy { it.validatedAtMillis }
                for (candidate in ordered) {
                    if (totalBytes <= LOSSLESS_CACHE_MAX_BYTES) break
                    entries = entries - candidate.key
                    totalBytes -= candidate.sizeBytes
                    runCatching { File(candidate.filePath).delete() }
                    AppLogger.i(TAG, "无损缓存超限，淘汰 key=${candidate.key}")
                }
            }
            persistIndex(LosslessCacheIndex(entries = entries))
            entry
        }
    }

    /** 分配一个临时下载文件。 */
    fun temporaryFile(trackKey: String): File {
        rootDir.mkdirs()
        return File(rootDir, "$trackKey.part")
    }

    /** 把临时文件转正为最终缓存文件。 */
    fun finalFile(trackKey: String, extension: String = "flac"): File {
        rootDir.mkdirs()
        return File(rootDir, "$trackKey.$extension")
    }

    suspend fun remove(trackKey: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = loadIndex()
            val entry = index.entries[trackKey] ?: return@withLock
            runCatching { File(entry.filePath).delete() }
            persistIndex(index.copy(entries = index.entries - trackKey))
        }
    }

    suspend fun totalSizeBytes(): Long = withContext(Dispatchers.IO) {
        mutex.withLock { loadIndex().entries.values.sumOf { it.sizeBytes } }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            indexCache = LosslessCacheIndex()
            runCatching { rootDir.listFiles()?.forEach { it.delete() } }
            runCatching { rootDir.mkdirs() }
        }
    }

    private fun loadIndex(): LosslessCacheIndex {
        indexCache?.let { return it }
        val loaded = runCatching {
            if (!indexFile.isFile) {
                LosslessCacheIndex()
            } else {
                json.decodeFromString<LosslessCacheIndex>(indexFile.readText())
            }
        }.getOrElse { throwable ->
            AppLogger.w(TAG, "无损缓存索引损坏，重建空索引", throwable)
            LosslessCacheIndex()
        }
        indexCache = loaded
        return loaded
    }

    private fun persistIndex(index: LosslessCacheIndex) {
        indexCache = index
        rootDir.mkdirs()
        val temp = File(rootDir, "index.json.tmp")
        runCatching {
            temp.writeText(json.encodeToString(index))
            if (indexFile.exists()) indexFile.delete()
            temp.renameTo(indexFile)
        }.onFailure { throwable ->
            AppLogger.w(TAG, "无损缓存索引写入失败", throwable)
        }
    }

    private companion object {
        const val TAG = "LosslessCache"
    }
}
