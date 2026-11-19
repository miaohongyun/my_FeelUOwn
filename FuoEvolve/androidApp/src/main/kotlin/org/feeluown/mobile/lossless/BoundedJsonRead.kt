package org.feeluown.mobile.lossless

import java.net.HttpURLConnection

/**
 * 无损增强客户端的 JSON 响应体有界读取。
 *
 * 背景：网络异常/被劫持时可能返回超大正文，`readText()` 会整块读入内存导致卡顿或 OOM。
 * 这里最多读 [MAX_JSON_BYTES] + 1 字节——多读的 1 字节用于识别"实际响应超限"，
 * 一旦读到第 MAX+1 字节立即返回 null（调用方按请求失败处理），不会把剩余数据读进内存。
 *
 * 只针对 JSON 元信息响应；音频流下载走 FlacHttpTransport 的专门通道，不受此上限影响。
 */
internal const val MAX_JSON_BYTES = 512 * 1024

internal fun HttpURLConnection.readBoundedJsonText(): String? {
    val declared = contentLengthLong
    if (declared > MAX_JSON_BYTES) return null
    val bytes = inputStream.use { it.readNBytes(MAX_JSON_BYTES + 1) }
    return boundedJsonText(bytes, declared)
}

/** 纯判断：已读取的字节（最多 MAX+1）与声明长度决定是否超限。 */
internal fun boundedJsonText(bytes: ByteArray, declaredLength: Long): String? {
    if (declaredLength > MAX_JSON_BYTES) return null
    if (bytes.size > MAX_JSON_BYTES) return null
    return String(bytes, Charsets.UTF_8)
}
