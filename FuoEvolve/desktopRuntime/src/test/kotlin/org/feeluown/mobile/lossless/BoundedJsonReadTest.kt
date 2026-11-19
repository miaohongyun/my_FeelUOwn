package org.feeluown.mobile.lossless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 无损增强 JSON 响应体有界读取测试（纯判断逻辑）。
 *
 * 验证超大响应被拒绝（Content-Length 预判 + 实际读取 N+1 字节识别两条路径），
 * 正常响应不受影响，且 UTF-8 多字节内容正确解码。
 */
class BoundedJsonReadTest {

    @Test
    fun normalJsonPasses() {
        val bytes = """{"results":[]}""".encodeToByteArray()
        assertEquals("""{"results":[]}""", boundedJsonText(bytes, bytes.size.toLong()))
    }

    @Test
    fun oversizedContentLengthIsRejectedBeforeReading() {
        val bytes = "{}".encodeToByteArray()
        assertNull(boundedJsonText(bytes, (MAX_JSON_BYTES + 1).toLong()),
            "Content-Length 超限应拒绝")
    }

    @Test
    fun oversizedActualBytesAreRejectedEvenWithoutContentLength() {
        // 读到 MAX+1 字节说明实际响应超限（多读的 1 字节是探针）。
        val bytes = ByteArray(MAX_JSON_BYTES + 1)
        assertNull(boundedJsonText(bytes, -1L), "实际读取超限应拒绝")
    }

    @Test
    fun utf8IsDecodedCorrectly() {
        val bytes = """{"name":"周杰伦"}""".encodeToByteArray()
        assertEquals("""{"name":"周杰伦"}""", boundedJsonText(bytes, bytes.size.toLong()))
    }
}
