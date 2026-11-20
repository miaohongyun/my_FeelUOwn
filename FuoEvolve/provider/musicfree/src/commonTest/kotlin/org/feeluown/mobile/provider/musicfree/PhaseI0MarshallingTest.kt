package org.feeluown.mobile.provider.musicfree

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 阶段 I0：quickjs-kt 1.0.15 的 JS→Kotlin 字符串封送差异独立取证。
 *
 * 全程使用真实 QuickJs Context（非 MockEngine / 非假 binding），逐项验证：
 * 普通字符串、Unicode 中文、特殊字符、JSON 字符串、大字符串、null/undefined、
 * 异常返回、多次调用、跨 Context 调用。
 *
 * 本报告不宣称"差异已消除"：测试只取证"toString() 文本归一后能否可靠使用"这一
 * 实际可用的安全通道，并如实记录 raw 对象是否等于 Kotlin String、能否直接作 Map key。
 */
class PhaseI0MarshallingTest {

    /** 单次 binding 调用的封送观测值，全部来自真实 JS→Kotlin 边界。 */
    data class Probe(
        val runtimeType: String,
        val isString: Boolean,
        val text: String,
        val equalsLiteral: Boolean,
        val hashCodeMatches: Boolean,
        val rawMapLookup: Boolean,
        val textMapLookup: Boolean,
    )

    /** 生成 JS 字符串字面量；不依赖 commonMain JVM-only API。 */
    private fun jsQuote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u")
                    sb.append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** 真实 QuickJs：注册 binding → JS 侧传入字符串 → Kotlin 侧记录封送特征。 */
    private suspend fun probeWith(expected: String): Probe = withContext(Dispatchers.Default) {
        val js = QuickJs.create(Dispatchers.Default)
        val seen = mutableListOf<Probe>()
        try {
            js.evaluationTimeoutMillis = 5_000L
            js.function<String>("__mfI0Probe") { args ->
                val raw = args.firstOrNull()
                val text = raw?.toString().orEmpty()
                val map: Map<Any?, String> = mapOf(expected to "HIT")
                seen += Probe(
                    runtimeType = raw?.let { it::class.qualifiedName ?: "unknown" } ?: "null",
                    isString = raw is String,
                    text = text,
                    equalsLiteral = raw == expected,
                    hashCodeMatches = raw?.hashCode() == expected.hashCode(),
                    rawMapLookup = map[raw] == "HIT",
                    textMapLookup = map[text] == "HIT",
                )
                "ok"
            }
            js.evaluate<String>("__mfI0Probe(${jsQuote(expected)})")
            seen.single()
        } finally {
            js.close()
        }
    }

    @Test
    fun i0_plainString(): Unit = runTest {
        val r = probeWith("hello")
        assertEquals("hello", r.text)
        assertTrue(r.textMapLookup, "toString() 归一后按文本查 Map 必须命中：$r")
    }

    @Test
    fun i0_unicodeChinese(): Unit = runTest {
        val value = "晴天 / 周杰伦 / 叶惠美 / 🎵"
        val r = probeWith(value)
        assertEquals(value, r.text)
        assertTrue(r.textMapLookup, "Unicode 中文/emoji 必须保真：$r")
    }

    @Test
    fun i0_specialCharacters(): Unit = runTest {
        val value = "quote\" backslash\\ newline\n carriage\r tab\t ctrl\u0001"
        val r = probeWith(value)
        assertEquals(value, r.text)
        assertTrue(r.textMapLookup, "特殊字符必须保真：$r")
    }

    @Test
    fun i0_jsonString(): Unit = runTest {
        val value = """{"id":10921920,"title":"晴天","artist":"周杰伦","n":null}"""
        val r = probeWith(value)
        assertEquals(value, r.text)
        assertTrue(r.textMapLookup, "JSON 字符串必须逐字符保真：$r")
    }

    @Test
    fun i0_largeString(): Unit = runTest {
        val value = "x".repeat(20_000) + "末端"
        val r = probeWith(value)
        assertEquals(value.length, r.text.length)
        assertTrue(r.text.endsWith("末端"))
        assertTrue(r.textMapLookup, "20KiB 字符串必须完整封送：$r")
    }

    @Test
    fun i0_nullAndUndefined(): Unit = runTest {
        withContext(Dispatchers.Default) {
            val js = QuickJs.create(Dispatchers.Default)
            val seen = mutableListOf<String>()
            try {
                js.function<String>("__mfI0Null") { args ->
                    seen += args.firstOrNull()?.toString() ?: "<null>"
                    "ok"
                }
                js.evaluate<String>("__mfI0Null(null); __mfI0Null(undefined); __mfI0Null('')")
                assertEquals(listOf("<null>", "<null>", ""), seen)
            } finally {
                js.close()
            }
        }
    }

    @Test
    fun i0_bindingExceptionSurfacesToJs(): Unit = runTest {
        withContext(Dispatchers.Default) {
            val js = QuickJs.create(Dispatchers.Default)
            try {
                js.function<String>("__mfI0Boom") { throw IllegalStateException("boom from host") }
                val error = runCatching { js.evaluate<String>("__mfI0Boom('x')") }.exceptionOrNull()
                assertTrue(error != null, "宿主 binding 抛异常必须上抛，不得静默吞掉")
                assertTrue(error.message?.contains("boom") == true, "异常信息应到达调用方：$error")
            } finally {
                js.close()
            }
        }
    }

    @Test
    fun i0_repeatedCallsInSameContext(): Unit = runTest {
        withContext(Dispatchers.Default) {
            val js = QuickJs.create(Dispatchers.Default)
            val seen = mutableListOf<String>()
            try {
                js.function<String>("__mfI0Echo") { args ->
                    val text = args.firstOrNull()?.toString().orEmpty()
                    seen += text
                    text
                }
                repeat(50) { i -> js.evaluate<String>("__mfI0Echo('v$i')") }
                assertEquals((0 until 50).map { "v$it" }, seen)
            } finally {
                js.close()
            }
        }
    }

    @Test
    fun i0_crossContextBindingsAreIndependent(): Unit = runTest {
        withContext(Dispatchers.Default) {
            val a = QuickJs.create(Dispatchers.Default)
            val b = QuickJs.create(Dispatchers.Default)
            val aSeen = mutableListOf<String>()
            val bSeen = mutableListOf<String>()
            try {
                a.function<String>("__mfI0Tag") { args ->
                    aSeen += args.firstOrNull()?.toString().orEmpty()
                    "A"
                }
                b.function<String>("__mfI0Tag") { args ->
                    bSeen += args.firstOrNull()?.toString().orEmpty()
                    "B"
                }
                assertEquals("A", a.evaluate<String>("__mfI0Tag('fromA')"))
                assertEquals("B", b.evaluate<String>("__mfI0Tag('fromB')"))
                assertEquals(listOf("fromA"), aSeen)
                assertEquals(listOf("fromB"), bSeen)
            } finally {
                a.close()
                b.close()
            }
        }
    }

    @Test
    fun i0_bindingReturnValueRoundTrip(): Unit = runTest {
        withContext(Dispatchers.Default) {
            val js = QuickJs.create(Dispatchers.Default)
            try {
                js.function<String>("__mfI0Ret") { args ->
                    val text = args.firstOrNull()?.toString().orEmpty()
                    """{"echo":"$text","n":42}"""
                }
                val out = js.evaluate<String>("JSON.stringify(JSON.parse(__mfI0Ret('中文返回值')))")
                assertTrue(out.contains("中文返回值"), "Kotlin→JS 字符串返回必须保真：$out")
                assertTrue(out.contains("42"))
            } finally {
                js.close()
            }
        }
    }

    @Test
    fun i0_asyncFunctionMarshalling(): Unit = runTest {
        withContext(Dispatchers.Default) {
            val js = QuickJs.create(Dispatchers.Default)
            val seen = mutableListOf<String>()
            try {
                js.asyncFunction<String>("__mfI0Async") { args ->
                    seen += args.firstOrNull()?.toString().orEmpty()
                    "async-ok"
                }
                // quickjs-kt 1.0.15 asModule 不回传求值结果，写回 globalThis 再读。
                js.evaluate<Any?>(
                    "globalThis.__mfI0AsyncOut = await __mfI0Async('异步中文');",
                    "mf_i0_async.mjs", asModule = true,
                )
                assertEquals("async-ok", js.evaluate<String>("globalThis.__mfI0AsyncOut"))
                assertEquals(listOf("异步中文"), seen)
            } finally {
                js.close()
            }
        }
    }

    @Test
    fun i0_bindingInsideEsmModule(): Unit = runTest {
        withContext(Dispatchers.Default) {
            val js = QuickJs.create(Dispatchers.Default)
            val seen = mutableListOf<String>()
            try {
                js.function<String>("__mfI0EsmProbe") { args ->
                    seen += args.firstOrNull()?.toString().orEmpty()
                    "ok"
                }
                js.addModule("mf_i0_mod", "__mfI0EsmProbe('模块内中文');")
                js.evaluate<Any?>("import 'mf_i0_mod';", "mf_i0_entry.mjs", asModule = true)
                // 实测：quickjs-kt 1.0.15 的 addModule+import 可能触发同一模块多次求值，
                // 因此这里只断言"每次调用参数都保真且至少调用一次"，不假设调用次数。
                assertTrue(seen.isNotEmpty(), "ESM 模块内应至少调用一次宿主 binding")
                assertTrue(seen.all { it == "模块内中文" }, "ESM 模块内每次调用参数都必须保真：$seen")
            } finally {
                js.close()
            }
        }
    }

    @Test
    fun i0_diagnosticsSummary(): Unit = runTest {
        val cases = listOf("hello", "晴天", "a\"b\\c", """{"k":1}""", "x".repeat(5_000))
        val reports = cases.map { probeWith(it) }
        // 唯一硬断言：文本归一通道在所有已测输入上可用；raw 差异只记录、不伪称消除。
        assertTrue(reports.all { it.textMapLookup }, "文本归一通道必须在所有输入上可查")
        val rawIsString = reports.count { it.isString }
        val rawEqualsLiteral = reports.count { it.equalsLiteral }
        val rawMapHits = reports.count { it.rawMapLookup }
        println(
            "I0_MARSHALLING_DIAGNOSTICS runtimeType=${reports.first().runtimeType} " +
                "isString=$rawIsString/${reports.size} " +
                "equalsLiteral=$rawEqualsLiteral/${reports.size} " +
                "rawMapHit=$rawMapHits/${reports.size} " +
                "textMapHit=${reports.count { it.textMapLookup }}/${reports.size}",
        )
    }
}
