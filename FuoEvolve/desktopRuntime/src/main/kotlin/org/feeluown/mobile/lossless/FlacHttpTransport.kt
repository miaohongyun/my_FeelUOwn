package org.feeluown.mobile.lossless

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.feeluown.mobile.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * FLAC 远端文件的 HTTP 读取器。
 *
 * 多个开放曲库共用同一套「只读文件头」和「限制大小下载」逻辑；
 * 网络层失败通过 [lastNetworkOperationFailed] 暴露给上层，用于来源级退避。
 */
class FlacHttpTransport {
    @Volatile
    var lastNetworkOperationFailed: Boolean = false
        private set

    /**
     * 只读取文件头部的 STREAMINFO，用极小代价确认“确实是 FLAC 容器”。
     *
     * 支持 Range 的服务器返回 206 时直接解析；不支持 Range 的服务器会返回完整文件，
     * 这里读到前 64KB 就主动断开，不会把整个文件拉下来。
     */
    suspend fun probeFlacHeader(url: String): FlacStreamInfo? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-65535")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = connection.responseCode
            lastNetworkOperationFailed = false
            if (code !in 200..299) return@withContext null
            val bytes = connection.inputStream.use { input ->
                val buffer = ByteArray(HEADER_PROBE_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val count = input.read(buffer, read, buffer.size - read)
                    if (count < 0) break
                    read += count
                }
                buffer.copyOf(read)
            }
            DesktopFlacStreamInfoParser.parse(bytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Exception) {
            lastNetworkOperationFailed = true
            AppLogger.w(TAG, "FLAC 头部探测失败 url=$url", throwable)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** 下载候选文件；超过大小上限或网络失败时返回 false。 */
    suspend fun download(url: String, target: File): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        target.delete()
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = connection.responseCode
            lastNetworkOperationFailed = false
            if (code !in 200..299) {
                AppLogger.w(TAG, "下载失败 HTTP $code url=$url")
                return@withContext false
            }
            val contentLength = connection.contentLengthLong
            if (contentLength > LOSSLESS_MAX_FILE_BYTES) {
                target.delete()
                AppLogger.w(TAG, "文件过大，跳过 ${contentLength / 1024 / 1024}MB url=$url")
                return@withContext false
            }
            target.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) return@withContext false
            }
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > LOSSLESS_MAX_FILE_BYTES) {
                            target.delete()
                            AppLogger.w(TAG, "下载超过上限，放弃 url=$url")
                            return@withContext false
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            val valid = written >= MINIMUM_FLAC_FILE_BYTES &&
                (contentLength <= 0L || contentLength == written) &&
                DesktopFlacFileValidator.validate(target) != null
            if (!valid) {
                target.delete()
                AppLogger.w(TAG, "下载文件未通过 FLAC 容器校验，已丢弃 url=$url")
            }
            valid
        } catch (cancelled: CancellationException) {
            target.delete()
            throw cancelled
        } catch (throwable: Exception) {
            target.delete()
            lastNetworkOperationFailed = true
            AppLogger.w(TAG, "下载异常 url=$url", throwable)
            false
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TAG = "LosslessTransport"
        const val USER_AGENT = "FuoEvolve-Desktop/1.0 (lossless enhancer)"
        /** 连接超时压到 5 秒，保证首次播放最多等 10 秒时还能及时回退。 */
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 12_000
        const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
        const val HEADER_PROBE_BYTES = 64 * 1024
        const val MINIMUM_FLAC_FILE_BYTES = 42L
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    }
}
