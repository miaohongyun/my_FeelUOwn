package org.feeluown.mobile.sourceupdate.crypto

/**
 * Ed25519 验签（RFC 8032）。
 *
 * 为什么自己实现而不调平台 API：Android 的 `Signature("Ed25519")` 要到 API 33 才有，
 * JVM 需要 15+，而 iOS 的 CryptoKit 是纯 Swift、Kotlin/Native 看不到。三端各自实现会让
 * "同一个签名在不同设备上结果不同"，所以这里用一份纯 Kotlin 实现，并由 RFC 8032 官方
 * 测试向量在单元测试里锁死。
 *
 * 实现来源：TweetNaCl 的公开域参考实现，域元素用 16 个小端 limb（radix 2^16）表示，
 * 只做验签（不做签名）——签名由仓库外的 Node 工具完成，避免把私钥逻辑带进 App 包。
 */
object Ed25519 {

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        if (signature.size != 64) return false

        val q = Array(4) { LongArray(16) }
        if (unpackNeg(q, publicKey) != 0) return false

        // h = SHA-512(R || A || M)，再对 L 取模。注意顺序：A 在 M 之前，
        // 写成 R||M||A 时只有空消息能碰巧通过，非空消息会全部验签失败。
        val buffer = ByteArray(64 + message.size)
        signature.copyInto(buffer, 0, 0, 32)
        publicKey.copyInto(buffer, 32)
        message.copyInto(buffer, 64)
        val h = reduce(Sha512.digest(buffer))

        val p = Array(4) { LongArray(16) }
        scalarmult(p, q, h)

        val base = Array(4) { LongArray(16) }
        scalarbase(base, signature.copyOfRange(32, 64))
        add(p, base)

        val check = ByteArray(32)
        pack(check, p)
        return verify32(signature, check)
    }

    // ---- 域运算 ----

    private fun set25519(target: LongArray, source: LongArray) {
        for (index in 0 until 16) target[index] = source[index]
    }

    private fun car25519(o: LongArray) {
        var carry = 1L
        for (index in 0 until 16) {
            val value = o[index] + carry + 65535L
            carry = floorDiv(value, 65536L)
            o[index] = value - carry * 65536L
        }
        o[0] += carry - 1 + 37 * (carry - 1)
    }

    private fun sel25519(p: LongArray, q: LongArray, bit: Int) {
        val mask = (bit - 1).inv().toLong()
        for (index in 0 until 16) {
            val swap = mask and (p[index] xor q[index])
            p[index] = p[index] xor swap
            q[index] = q[index] xor swap
        }
    }

    private fun pack25519(o: ByteArray, n: LongArray) {
        val t = LongArray(16)
        val m = LongArray(16)
        for (index in 0 until 16) t[index] = n[index]
        car25519(t)
        car25519(t)
        car25519(t)
        for (round in 0 until 2) {
            m[0] = t[0] - 0xffedL
            for (index in 1 until 15) {
                m[index] = t[index] - 0xffffL - ((m[index - 1] shr 16) and 1L)
                m[index - 1] = m[index - 1] and 0xffffL
            }
            m[15] = t[15] - 0x7fffL - ((m[14] shr 16) and 1L)
            val b = ((m[15] shr 16) and 1L).toInt()
            m[14] = m[14] and 0xffffL
            sel25519(t, m, 1 - b)
        }
        for (index in 0 until 16) {
            o[index * 2] = (t[index] and 0xffL).toByte()
            o[index * 2 + 1] = ((t[index] shr 8) and 0xffL).toByte()
        }
    }

    private fun unpack25519(o: LongArray, n: ByteArray) {
        for (index in 0 until 16) {
            o[index] = (n[index * 2].toLong() and 0xffL) + ((n[index * 2 + 1].toLong() and 0xffL) shl 8)
        }
        o[15] = o[15] and 0x7fffL
    }

    private fun addField(o: LongArray, a: LongArray, b: LongArray) {
        for (index in 0 until 16) o[index] = a[index] + b[index]
    }

    private fun subField(o: LongArray, a: LongArray, b: LongArray) {
        for (index in 0 until 16) o[index] = a[index] - b[index]
    }

    private fun mulField(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(16)
        for (i in 0 until 16) {
            val ai = a[i]
            if (ai == 0L) continue
            for (j in 0 until 16) {
                val product = ai * b[j]
                val k = i + j
                if (k < 16) {
                    t[k] += product
                } else {
                    t[k - 16] += 38 * product
                }
            }
        }
        car25519(t)
        car25519(t)
        for (index in 0 until 16) o[index] = t[index]
    }

    private fun squareField(o: LongArray, a: LongArray) = mulField(o, a, a)

    private fun inv25519(o: LongArray, i: LongArray) {
        val c = LongArray(16)
        for (index in 0 until 16) c[index] = i[index]
        for (power in 253 downTo 0) {
            squareField(c, c)
            if (power != 2 && power != 4) mulField(c, c, i)
        }
        for (index in 0 until 16) o[index] = c[index]
    }

    private fun pow2523(o: LongArray, i: LongArray) {
        val c = LongArray(16)
        for (index in 0 until 16) c[index] = i[index]
        for (power in 250 downTo 0) {
            squareField(c, c)
            if (power != 1) mulField(c, c, i)
        }
        for (index in 0 until 16) o[index] = c[index]
    }

    private fun neq25519(a: LongArray, b: LongArray): Boolean {
        val c = ByteArray(32)
        val d = ByteArray(32)
        pack25519(c, a)
        pack25519(d, b)
        return verify32(c, d).not()
    }

    private fun par25519(a: LongArray): Int {
        val d = ByteArray(32)
        pack25519(d, a)
        return d[0].toInt() and 1
    }

    internal fun pack(r: ByteArray, p: Array<LongArray>) {
        val tx = LongArray(16)
        val ty = LongArray(16)
        val zi = LongArray(16)
        inv25519(zi, p[2])
        mulField(tx, p[0], zi)
        mulField(ty, p[1], zi)
        pack25519(r, ty)
        r[31] = (r[31].toInt() xor (par25519(tx) shl 7)).toByte()
    }

    private fun add(p: Array<LongArray>, q: Array<LongArray>) {
        val a = LongArray(16)
        val b = LongArray(16)
        val c = LongArray(16)
        val d = LongArray(16)
        val e = LongArray(16)
        val f = LongArray(16)
        val g = LongArray(16)
        val h = LongArray(16)
        val t = LongArray(16)

        subField(a, p[1], p[0])
        subField(t, q[1], q[0])
        mulField(a, a, t)
        addField(b, p[0], p[1])
        addField(t, q[0], q[1])
        mulField(b, b, t)
        mulField(c, p[3], q[3])
        mulField(c, c, D2)
        mulField(d, p[2], q[2])
        addField(d, d, d)
        subField(e, b, a)
        subField(f, d, c)
        addField(g, d, c)
        addField(h, b, a)

        mulField(p[0], e, f)
        mulField(p[1], h, g)
        mulField(p[2], g, f)
        mulField(p[3], e, h)
    }

    private fun cswap(p: Array<LongArray>, q: Array<LongArray>, bit: Int) {
        for (index in 0 until 4) sel25519(p[index], q[index], bit)
    }

    private fun scalarmult(p: Array<LongArray>, q: Array<LongArray>, s: ByteArray) {
        set25519(p[0], GF0)
        set25519(p[1], GF1)
        set25519(p[2], GF1)
        set25519(p[3], GF0)
        for (index in 255 downTo 0) {
            val byteValue = (s[index ushr 3].toInt() and 0xff) ushr (index and 7)
            val bit = byteValue and 1
            cswap(p, q, bit)
            add(q, p)
            add(p, p)
            cswap(p, q, bit)
        }
    }

    internal fun scalarbase(p: Array<LongArray>, s: ByteArray) {
        val q = Array(4) { LongArray(16) }
        set25519(q[0], X)
        set25519(q[1], Y)
        set25519(q[2], GF1)
        mulField(q[3], X, Y)
        scalarmult(p, q, s)
    }

    internal fun unpackNeg(r: Array<LongArray>, p: ByteArray): Int {
        val t = LongArray(16)
        val chk = LongArray(16)
        val num = LongArray(16)
        val den = LongArray(16)
        val den2 = LongArray(16)
        val den4 = LongArray(16)
        val den6 = LongArray(16)

        set25519(r[2], GF1)
        unpack25519(r[1], p)
        squareField(num, r[1])
        mulField(den, num, D)
        subField(num, num, r[2])
        addField(den, r[2], den)

        squareField(den2, den)
        squareField(den4, den2)
        mulField(den6, den4, den2)
        mulField(t, den6, num)
        mulField(t, t, den)

        pow2523(t, t)
        mulField(t, t, num)
        mulField(t, t, den)
        mulField(t, t, den)
        mulField(r[0], t, den)

        squareField(chk, r[0])
        mulField(chk, chk, den)
        if (neq25519(chk, num)) mulField(r[0], r[0], I)

        squareField(chk, r[0])
        mulField(chk, chk, den)
        if (neq25519(chk, num)) return -1

        if (par25519(r[0]) == ((p[31].toInt() and 0xff) ushr 7)) subField(r[0], GF0, r[0])

        mulField(r[3], r[0], r[1])
        return 0
    }

    // ---- 标量归约 ----

    internal fun reduce(input: ByteArray): ByteArray {
        val x = LongArray(64) { input[it].toLong() and 0xffL }
        val r = ByteArray(32)
        modL(r, x)
        return r
    }

    private fun modL(r: ByteArray, x: LongArray) {
        var carry: Long
        for (i in 63 downTo 32) {
            carry = 0
            var j = i - 32
            val k = i - 12
            while (j < k) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)]
                carry = floorDiv(x[j] + 128, 256)
                x[j] -= carry * 256
                j++
            }
            x[j] += carry
            x[i] = 0
        }
        carry = 0
        for (j in 0 until 32) {
            x[j] += carry - (x[31] shr 4) * L[j]
            carry = x[j] shr 8
            x[j] = x[j] and 255
        }
        for (j in 0 until 32) x[j] -= carry * L[j]
        for (j in 0 until 31) {
            x[j + 1] += x[j] shr 8
            r[j] = (x[j] and 255).toByte()
        }
        r[31] = (x[31] and 255).toByte()
    }

    private fun verify32(a: ByteArray, b: ByteArray): Boolean {
        if (a.size < 32 || b.size < 32) return false
        var difference = 0
        for (index in 0 until 32) difference = difference or (a[index].toInt() xor b[index].toInt())
        return difference == 0
    }

    private fun floorDiv(value: Long, divisor: Long): Long {
        val quotient = value / divisor
        return if (value % divisor != 0L && ((value < 0) != (divisor < 0))) quotient - 1 else quotient
    }

    // ---- 常量 ----

    private val GF0 = LongArray(16)
    private val GF1 = LongArray(16).also { it[0] = 1L }
    private val D = longArrayOf(
        0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070,
        0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203,
    )
    private val D2 = longArrayOf(
        0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0,
        0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406,
    )
    private val X = longArrayOf(
        0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c,
        0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169,
    )
    private val Y = longArrayOf(
        0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666,
        0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666,
    )
    private val I = longArrayOf(
        0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43,
        0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83,
    )
    private val L = longArrayOf(
        0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58,
        0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
        0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0x10,
    )
}
