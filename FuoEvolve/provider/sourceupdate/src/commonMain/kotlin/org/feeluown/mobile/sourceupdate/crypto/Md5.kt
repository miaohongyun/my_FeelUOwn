package org.feeluown.mobile.sourceupdate.crypto

/**
 * 纯 Kotlin MD5（RFC 1321）。
 *
 * 为什么实现：LX Music 官方移动端文档列出 `lx.utils.crypto.md5`，社区脚本普遍用它做
 * **脚本自身完整性校验**（如 md5(name+description) 比对固定值）。缺少该原语会让这类脚本
 * 在初始化阶段直接失败。这是补齐官方 API，不是为某个脚本伪造返回值。
 *
 * 纯实现保证 Android / 桌面 / iOS 三端对同一输入给出一致结果，可被标准向量测试锁死。
 * 仅用于 LX 桥接；不用于安全签名（签名用 Ed25519，摘要用 SHA-256）。
 */
object Md5 {

    fun digest(input: ByteArray): ByteArray {
        val msg = pad(input)
        val state = intArrayOf(
            0x67452301.toInt(), 0xefcdab89.toInt(), 0x98badcfe.toInt(), 0x10325476.toInt(),
        )
        var offset = 0
        while (offset < msg.size) {
            val m = IntArray(16)
            for (i in 0..15) {
                val b = offset + i * 4
                m[i] = (msg[b].toInt() and 0xff) or
                    ((msg[b + 1].toInt() and 0xff) shl 8) or
                    ((msg[b + 2].toInt() and 0xff) shl 16) or
                    ((msg[b + 3].toInt() and 0xff) shl 24)
            }
            var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
            for (i in 0..63) {
                val f: Int
                val g: Int
                when {
                    i < 16 -> { f = (b and c) or (b.inv() and d); g = i }
                    i < 32 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) and 15 }
                    i < 48 -> { f = b xor c xor d; g = (3 * i + 5) and 15 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) and 15 }
                }
                val tmp = d
                d = c
                c = b
                b = b + leftRotate(a + f + K[i] + m[g], S[i])
                a = tmp
            }
            state[0] += a; state[1] += b; state[2] += c; state[3] += d
            offset += 64
        }
        val out = ByteArray(16)
        for (i in 0..3) {
            out[i * 4] = (state[i] and 0xff).toByte()
            out[i * 4 + 1] = ((state[i] ushr 8) and 0xff).toByte()
            out[i * 4 + 2] = ((state[i] ushr 16) and 0xff).toByte()
            out[i * 4 + 3] = ((state[i] ushr 24) and 0xff).toByte()
        }
        return out
    }

    fun hex(input: ByteArray): String = digest(input).toHex()

    private fun pad(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8L
        val zeros = ((55 - input.size) and 63) + 1
        val result = ByteArray(input.size + zeros + 8)
        input.copyInto(result)
        result[input.size] = 0x80.toByte()
        for (i in 0..7) {
            result[result.size - 8 + i] = ((bitLen ushr (8 * i)) and 0xff).toByte()
        }
        return result
    }

    private fun leftRotate(value: Int, count: Int): Int =
        (value shl count) or (value ushr (32 - count))

    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    // RFC 1321 标准 K 表（floor(abs(sin(i+1))·2^32)）。硬编码避免不同平台的浮点差异。
    private val K = listOf(
        "d76aa478", "e8c7b756", "242070db", "c1bdceee",
        "f57c0faf", "4787c62a", "a8304613", "fd469501",
        "698098d8", "8b44f7af", "ffff5bb1", "895cd7be",
        "6b901122", "fd987193", "a679438e", "49b40821",
        "f61e2562", "c040b340", "265e5a51", "e9b6c7aa",
        "d62f105d", "02441453", "d8a1e681", "e7d3fbc8",
        "21e1cde6", "c33707d6", "f4d50d87", "455a14ed",
        "a9e3e905", "fcefa3f8", "676f02d9", "8d2a4c8a",
        "fffa3942", "8771f681", "6d9d6122", "fde5380c",
        "a4beea44", "4bdecfa9", "f6bb4b60", "bebfbc70",
        "289b7ec6", "eaa127fa", "d4ef3085", "04881d05",
        "d9d4d039", "e6db99e5", "1fa27cf8", "c4ac5665",
        "f4292244", "432aff97", "ab9423a7", "fc93a039",
        "655b59c3", "8f0ccc92", "ffeff47d", "85845dd1",
        "6fa87e4f", "fe2ce6e0", "a3014314", "4e0811a1",
        "f7537e82", "bd3af235", "2ad7d2bb", "eb86d391",
    ).map { it.toULong(16).toLong().toInt() }.toIntArray()
}
