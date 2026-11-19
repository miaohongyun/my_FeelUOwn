package org.feeluown.mobile.sourceupdate.crypto

/**
 * 纯 Kotlin 的 SHA-256 / SHA-512。
 *
 * 为什么不用平台实现：`provider/sourceupdate` 要在 Android / 桌面 / iOS 三端校验同一个脚本哈希，
 * 任何一端的行为差异都会变成"同一个文件在不同设备上校验结果不同"。纯实现可被固定向量测试锁死，
 * 也不需要在 commonMain 引入 expect/actual。
 */
object Sha256 {

    fun digest(input: ByteArray): ByteArray {
        val state = intArrayOf(
            0x6a09e667L.toInt(), 0xbb67ae85L.toInt(), 0x3c6ef372L.toInt(), 0xa54ff53aL.toInt(),
            0x510e527fL.toInt(), 0x9b05688cL.toInt(), 0x1f83d9abL.toInt(), 0x5be0cd19L.toInt(),
        )
        val padded = pad(input, blockSize = 64, lengthBytes = 8)
        val schedule = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (index in 0 until 16) {
                val base = offset + index * 4
                schedule[index] =
                    ((padded[base].toInt() and 0xff) shl 24) or
                        ((padded[base + 1].toInt() and 0xff) shl 16) or
                        ((padded[base + 2].toInt() and 0xff) shl 8) or
                        (padded[base + 3].toInt() and 0xff)
            }
            for (index in 16 until 64) {
                val s0 = schedule[index - 15].rotateRight(7) xor
                    schedule[index - 15].rotateRight(18) xor
                    (schedule[index - 15] ushr 3)
                val s1 = schedule[index - 2].rotateRight(17) xor
                    schedule[index - 2].rotateRight(19) xor
                    (schedule[index - 2] ushr 10)
                schedule[index] = schedule[index - 16] + s0 + schedule[index - 7] + s1
            }

            var a = state[0]
            var b = state[1]
            var c = state[2]
            var d = state[3]
            var e = state[4]
            var f = state[5]
            var g = state[6]
            var h = state[7]

            for (index in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + K[index] + schedule[index]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            state[0] += a
            state[1] += b
            state[2] += c
            state[3] += d
            state[4] += e
            state[5] += f
            state[6] += g
            state[7] += h
            offset += 64
        }

        val out = ByteArray(32)
        for (index in 0 until 8) {
            out[index * 4] = (state[index] ushr 24).toByte()
            out[index * 4 + 1] = (state[index] ushr 16).toByte()
            out[index * 4 + 2] = (state[index] ushr 8).toByte()
            out[index * 4 + 3] = state[index].toByte()
        }
        return out
    }

    fun hex(input: ByteArray): String = digest(input).toHex()

    private fun pad(input: ByteArray, blockSize: Int, lengthBytes: Int): ByteArray {
        val totalLength = ((input.size + 1 + lengthBytes + blockSize - 1) / blockSize) * blockSize
        val padded = ByteArray(totalLength)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        val bitLength = input.size.toLong() * 8
        for (index in 0 until lengthBytes) {
            padded[totalLength - 1 - index] = ((bitLength ushr (8 * index)) and 0xff).toByte()
        }
        return padded
    }

    private val K: IntArray = intArrayOf(
        0x428a2f98L.toInt(), 0x71374491L.toInt(), 0xb5c0fbcfL.toInt(), 0xe9b5dba5L.toInt(),
        0x3956c25bL.toInt(), 0x59f111f1L.toInt(), 0x923f82a4L.toInt(), 0xab1c5ed5L.toInt(),
        0xd807aa98L.toInt(), 0x12835b01L.toInt(), 0x243185beL.toInt(), 0x550c7dc3L.toInt(),
        0x72be5d74L.toInt(), 0x80deb1feL.toInt(), 0x9bdc06a7L.toInt(), 0xc19bf174L.toInt(),
        0xe49b69c1L.toInt(), 0xefbe4786L.toInt(), 0x0fc19dc6L.toInt(), 0x240ca1ccL.toInt(),
        0x2de92c6fL.toInt(), 0x4a7484aaL.toInt(), 0x5cb0a9dcL.toInt(), 0x76f988daL.toInt(),
        0x983e5152L.toInt(), 0xa831c66dL.toInt(), 0xb00327c8L.toInt(), 0xbf597fc7L.toInt(),
        0xc6e00bf3L.toInt(), 0xd5a79147L.toInt(), 0x06ca6351L.toInt(), 0x14292967L.toInt(),
        0x27b70a85L.toInt(), 0x2e1b2138L.toInt(), 0x4d2c6dfcL.toInt(), 0x53380d13L.toInt(),
        0x650a7354L.toInt(), 0x766a0abbL.toInt(), 0x81c2c92eL.toInt(), 0x92722c85L.toInt(),
        0xa2bfe8a1L.toInt(), 0xa81a664bL.toInt(), 0xc24b8b70L.toInt(), 0xc76c51a3L.toInt(),
        0xd192e819L.toInt(), 0xd6990624L.toInt(), 0xf40e3585L.toInt(), 0x106aa070L.toInt(),
        0x19a4c116L.toInt(), 0x1e376c08L.toInt(), 0x2748774cL.toInt(), 0x34b0bcb5L.toInt(),
        0x391c0cb3L.toInt(), 0x4ed8aa4aL.toInt(), 0x5b9cca4fL.toInt(), 0x682e6ff3L.toInt(),
        0x748f82eeL.toInt(), 0x78a5636fL.toInt(), 0x84c87814L.toInt(), 0x8cc70208L.toInt(),
        0x90befffaL.toInt(), 0xa4506cebL.toInt(), 0xbef9a3f7L.toInt(), 0xc67178f2L.toInt(),
    )
}

object Sha512 {

    fun digest(input: ByteArray): ByteArray {
        val state = LongArray(8)
        for (index in 0 until 8) state[index] = H[index]
        val padded = pad(input)
        val schedule = LongArray(80)
        var offset = 0
        while (offset < padded.size) {
            for (index in 0 until 16) {
                val base = offset + index * 8
                var value = 0L
                for (byteIndex in 0 until 8) {
                    value = (value shl 8) or (padded[base + byteIndex].toLong() and 0xff)
                }
                schedule[index] = value
            }
            for (index in 16 until 80) {
                val s0 = schedule[index - 15].rotateRight(1) xor
                    schedule[index - 15].rotateRight(8) xor
                    (schedule[index - 15] ushr 7)
                val s1 = schedule[index - 2].rotateRight(19) xor
                    schedule[index - 2].rotateRight(61) xor
                    (schedule[index - 2] ushr 6)
                schedule[index] = schedule[index - 16] + s0 + schedule[index - 7] + s1
            }

            var a = state[0]
            var b = state[1]
            var c = state[2]
            var d = state[3]
            var e = state[4]
            var f = state[5]
            var g = state[6]
            var h = state[7]

            for (index in 0 until 80) {
                val s1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + K[index] + schedule[index]
                val s0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            state[0] += a
            state[1] += b
            state[2] += c
            state[3] += d
            state[4] += e
            state[5] += f
            state[6] += g
            state[7] += h
            offset += 128
        }

        val out = ByteArray(64)
        for (index in 0 until 8) {
            for (byteIndex in 0 until 8) {
                out[index * 8 + byteIndex] = (state[index] ushr (56 - byteIndex * 8)).toByte()
            }
        }
        return out
    }

    /** 128 字节分块、16 字节大端长度字段。 */
    private fun pad(input: ByteArray): ByteArray {
        val blockSize = 128
        val totalLength = ((input.size + 1 + 16 + blockSize - 1) / blockSize) * blockSize
        val padded = ByteArray(totalLength)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        val bitLength = input.size.toLong() * 8
        for (index in 0 until 8) {
            padded[totalLength - 1 - index] = ((bitLength ushr (8 * index)) and 0xff).toByte()
        }
        return padded
    }

    /**
     * 初始状态与 80 个常量都用十六进制字符串保存：其中一半的值超过 Long.MAX_VALUE，
     * 写成字面量会被 Kotlin 判为溢出，用 ULong 解析可避免手工换成负数。
     */
    private val H: LongArray = (
        "6a09e667f3bcc908,bb67ae8584caa73b,3c6ef372fe94f82b,a54ff53a5f1d36f1," +
            "510e527fade682d1,9b05688c2b3e6c1f,1f83d9abfb41bd6b,5be0cd19137e2179"
        ).split(',').map { it.trim().toULong(16).toLong() }.toLongArray()

    private val K: LongArray = (
        "428a2f98d728ae22,7137449123ef65cd,b5c0fbcfec4d3b2f,e9b5dba58189dbbc," +
            "3956c25bf348b538,59f111f1b605d019,923f82a4af194f9b,ab1c5ed5da6d8118," +
            "d807aa98a3030242,12835b0145706fbe,243185be4ee4b28c,550c7dc3d5ffb4e2," +
            "72be5d74f27b896f,80deb1fe3b1696b1,9bdc06a725c71235,c19bf174cf692694," +
            "e49b69c19ef14ad2,efbe4786384f25e3,0fc19dc68b8cd5b5,240ca1cc77ac9c65," +
            "2de92c6f592b0275,4a7484aa6ea6e483,5cb0a9dcbd41fbd4,76f988da831153b5," +
            "983e5152ee66dfab,a831c66d2db43210,b00327c898fb213f,bf597fc7beef0ee4," +
            "c6e00bf33da88fc2,d5a79147930aa725,06ca6351e003826f,142929670a0e6e70," +
            "27b70a8546d22ffc,2e1b21385c26c926,4d2c6dfc5ac42aed,53380d139d95b3df," +
            "650a73548baf63de,766a0abb3c77b2a8,81c2c92e47edaee6,92722c851482353b," +
            "a2bfe8a14cf10364,a81a664bbc423001,c24b8b70d0f89791,c76c51a30654be30," +
            "d192e819d6ef5218,d69906245565a910,f40e35855771202a,106aa07032bbd1b8," +
            "19a4c116b8d2d0c8,1e376c085141ab53,2748774cdf8eeb99,34b0bcb5e19b48a8," +
            "391c0cb3c5c95a63,4ed8aa4ae3418acb,5b9cca4f7763e373,682e6ff3d6b2b8a3," +
            "748f82ee5defb2fc,78a5636f43172f60,84c87814a1f0ab72,8cc702081a6439ec," +
            "90befffa23631e28,a4506cebde82bde9,bef9a3f7b2c67915,c67178f2e372532b," +
            "ca273eceea26619c,d186b8c721c0c207,eada7dd6cde0eb1e,f57d4f7fee6ed178," +
            "06f067aa72176fba,0a637dc5a2c898a6,113f9804bef90dae,1b710b35131c471b," +
            "28db77f523047d84,32caab7b40c72493,3c9ebe0a15c9bebc,431d67c49c100d4c," +
            "4cc5d4becb3e42b6,597f299cfc657e2a,5fcb6fab3ad6faec,6c44198c4a475817"
        ).split(',').map { it.trim().toULong(16).toLong() }.toLongArray()
}

fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}

fun String.hexToByteArray(): ByteArray {
    val text = trim()
    require(text.length % 2 == 0) { "hex 长度必须是偶数" }
    return ByteArray(text.length / 2) { index ->
        text.substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte()
            ?: throw IllegalArgumentException("非法 hex 字符: ${text.substring(index * 2, index * 2 + 2)}")
    }
}
