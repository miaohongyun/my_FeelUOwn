package org.feeluown.mobile.sourceupdate

import org.feeluown.mobile.sourceupdate.crypto.Md5
import kotlin.test.Test
import kotlin.test.assertEquals

/** MD5 标准向量（RFC 1321 + 常见实现），锁定跨端一致性。 */
class Md5Test {
    @Test
    fun standardVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.hex(byteArrayOf()))
        assertEquals("0cc175b9c0f1b6a831c399e269772661", Md5.hex("a".encodeToByteArray()))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hex("abc".encodeToByteArray()))
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", Md5.hex("message digest".encodeToByteArray()))
        assertEquals("c3fcd3d76192e4007dfb496cca67e13b", Md5.hex("abcdefghijklmnopqrstuvwxyz".encodeToByteArray()))
        assertEquals("d174ab98d277d9f5a5611c2c9f419d9f", Md5.hex("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".encodeToByteArray()))
        assertEquals("57edf4a22be3c955ac49da2e2107b67a", Md5.hex("12345678901234567890123456789012345678901234567890123456789012345678901234567890".encodeToByteArray()))
    }

    @Test
    fun binaryInput() {
        // 覆盖多分块边界（56 字节处需要长度填充）。
        val input = ByteArray(200) { (it * 7).toByte() }
        // 期望值由外部 Python hashlib 独立计算，不是自证
        assertEquals("d96c89b9da43f6903ceaa14a7166a22e", Md5.hex(input))
    }
}
