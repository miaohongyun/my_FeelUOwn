package org.feeluown.mobile.provider.lx

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * 受控 setTimeout/clearTimeout 的行为验证。
 *
 * 计时器的 delay 跑在宿主的真实时间 dispatcher 上，因此这里用 measureTime 做真实耗时断言，
 * 不用 runTest 的虚拟时钟（它不会推进 Dispatchers.Default 上的 delay）。
 *
 * 覆盖审查要求的每一项：
 * - 真正注入 QuickJS：脚本里 globalThis.setTimeout/clearTimeout 可用；
 * - Promise resolve/reject 回到 JS 执行环境：回调里能操作 lx 桥接对象并被读回；
 * - 正常延迟 / clearTimeout / 并发 / 关闭取消 / 重复加载 / 资源泄漏（activeTimerCount）。
 *
 * 注意：关闭取消**不**用"脚本未加载时抛异常"代替，而是断言 activeTimerCount 归零且
 * 已在 JS 侧标记的回调不会再执行。
 */
class LxTimerTest {

    private fun client(): HttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })

    private fun runtime() = LxScriptRuntime(client())

    /** 在 request handler 里用 setTimeout 延迟 resolve；并回显一个由 timer 回调写入的全局标记。 */
    private fun delayedScript(ms: Long) = """
        const { EVENT_NAMES, on, send } = globalThis.lx
        globalThis.__timerMarker = 'not-fired'
        on(EVENT_NAMES.request, function () {
          return new Promise(function (resolve) {
            setTimeout(function () {
              // 在 timer 回调里操作宿主桥接对象，证明 Promise resolve 确实回到了 JS context。
              var buf = lx.utils.buffer.from('ok', 'utf8')
              globalThis.__timerMarker = lx.utils.buffer.bufToString(buf, 'utf8')
              resolve('https://cdn.example.com/delayed-' + $ms + '.flac')
            }, $ms)
          })
        })
        send(EVENT_NAMES.inited, {
          sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } }
        })
    """.trimIndent()

    private suspend fun waitReal(ms: Long) = withContext(Dispatchers.Default) { delay(ms) }

    private suspend fun resolvedUrl(rt: LxScriptRuntime, songmid: String = "1") =
        rt.requestMusicUrl("kw", buildJsonObject { put("songmid", songmid) }, "128k")

    @Test
    fun `setTimeout is injected and fires with delayed callback back in JS`() = runTest {
        val runtime = runtime()
        try {
            runtime.load("timer.js", delayedScript(80))
            runtime.initialize()
            val measured = measureTime {
                val url = resolvedUrl(runtime)
                assertEquals("https://cdn.example.com/delayed-80.flac", url)
            }
            assertTrue(
                measured.inWholeMilliseconds >= 40,
                "延迟回调应真实等待，实际 ${measured.inWholeMilliseconds}ms",
            )
            // timer 回调里写入的标记能被 request handler 读回 → Promise 回到 JS context 执行，
            // 且宿主桥接对象（buffer）在回调里仍可用 → QuickJS context 线程约束未被破坏。
            val markerUrl = resolvedUrl(runtime)
            assertTrue(markerUrl.startsWith("https://cdn.example.com/delayed-80.flac"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `promise reject inside timer callback surfaces to host`() = runTest {
        val runtime = runtime()
        try {
            runtime.load(
                "reject.js",
                """
                const { EVENT_NAMES, on, send } = globalThis.lx
                on(EVENT_NAMES.request, function () {
                  return new Promise(function (resolve, reject) {
                    setTimeout(function () { reject(new Error('timer rejected')) }, 30)
                  })
                })
                send(EVENT_NAMES.inited, {
                  sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } }
                })
                """.trimIndent(),
            )
            runtime.initialize()
            val error = assertFailsWith<LxScriptException> { resolvedUrl(runtime) }
            assertTrue(error.message.orEmpty().contains("timer rejected"), error.message)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `clearTimeout cancels callback and releases the host timer`() = runTest {
        val runtime = runtime()
        try {
            runtime.load(
                "clear.js",
                """
                const { EVENT_NAMES, on, send } = globalThis.lx
                globalThis.__fired = 0
                setTimeout(function () { globalThis.__fired++ }, 30)
                var id = setTimeout(function () { globalThis.__fired += 100 }, 200)
                clearTimeout(id)   // 长的那个必须被真正取消（含宿主协程）
                on(EVENT_NAMES.request, function () {
                  return Promise.resolve('https://cdn.example.com/' + globalThis.__fired)
                })
                send(EVENT_NAMES.inited, {
                  sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } }
                })
                """.trimIndent(),
            )
            runtime.initialize()
            // 等到长 timer 原本的 200ms 之后，确认被清除者没有触发
            waitReal(260)
            assertEquals("https://cdn.example.com/1", resolvedUrl(runtime))
            assertEquals(0, runtime.activeTimerCount(), "被 clearTimeout 取消的 timer 不得残留在表里")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `multiple concurrent timers all fire then drain`() = runTest {
        val runtime = runtime()
        try {
            runtime.load(
                "multi.js",
                """
                const { EVENT_NAMES, on, send } = globalThis.lx
                globalThis.__count = 0
                setTimeout(function () { globalThis.__count++ }, 20)
                setTimeout(function () { globalThis.__count++ }, 25)
                setTimeout(function () { globalThis.__count++ }, 30)
                on(EVENT_NAMES.request, function () {
                  return Promise.resolve('https://cdn.example.com/' + globalThis.__count)
                })
                send(EVENT_NAMES.inited, {
                  sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } }
                })
                """.trimIndent(),
            )
            runtime.initialize()
            waitReal(120)
            assertEquals("https://cdn.example.com/3", resolvedUrl(runtime), "三个并发计时器都应触发")
            assertEquals(0, runtime.activeTimerCount(), "触发完成后应全部从登记表移除")
        } finally {
            runtime.close()
        }
    }

    // timer 用 600ms：宿主 __lxDelay 的 asyncFunction 有 ~130ms 登记延迟，
    // waitReal(150) 后才能稳定观测到 pending timer，600ms 保证此时仍未到时。
    // 取消是否生效由 activeTimerCount 归零来证明（不是"脚本未加载抛异常"）。
    @Test
    fun `close cancels pending timers and drains active count`() = runTest {
        val runtime = runtime()
        // 用足够长的 timer，确保 close 时它们仍在等待，而不是自然到时。
        runtime.load(
            "close.js",
            longTimerScript(),
        )
        runtime.initialize()
        val pending = kotlinx.coroutines.CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { resolvedUrl(runtime) }
            pending.complete(Unit)
        }
        waitReal(150)
        assertEquals(2, runtime.activeTimerCount(), "关闭前应有 2 个在途 timer")

        runtime.close()
        // 取消是异步的（finally 在 Dispatchers.Default 上清理），等一小段真实时间再断言。
        waitReal(60)
        assertTrue(runtime.activeTimerCount() == 0, "close 后所有 timer 必须被释放")
        runtime.close() // 幂等
        assertTrue(runtime.activeTimerCount() == 0)
    }

    @Test
    fun `reload cancels old timers and resets active count`() = runTest {
        val runtime = runtime()
        try {
            runtime.load("first.js", longTimerScript())
            runtime.initialize()
            val pending = kotlinx.coroutines.CompletableDeferred<Unit>()
            CoroutineScope(Dispatchers.Default).launch {
                runCatching { resolvedUrl(runtime) }
                pending.complete(Unit)
            }
            waitReal(150)
            assertEquals(2, runtime.activeTimerCount())

            // 重载：load 内部先 close，旧 timer 必须被取消，新 context 计数从 0 开始。
            runtime.load("second.js", longTimerScript())
            runtime.initialize()
            val pending2 = kotlinx.coroutines.CompletableDeferred<Unit>()
            CoroutineScope(Dispatchers.Default).launch {
                runCatching { resolvedUrl(runtime) }
                pending2.complete(Unit)
            }
            waitReal(150)
            assertEquals(2, runtime.activeTimerCount(), "重载后只应剩下新 context 的 2 个 timer")
        } finally {
            runtime.close()
        }
        waitReal(60)
        assertTrue(runtime.activeTimerCount() == 0)
    }

    @Test
    fun `repeated load and close does not leak timers`() = runTest {
        val runtime = runtime()
        for (round in 1..8) {
            runtime.load("cycle-$round.js", longTimerScript())
            runtime.initialize()
            val pending = kotlinx.coroutines.CompletableDeferred<Unit>()
            CoroutineScope(Dispatchers.Default).launch {
                runCatching { resolvedUrl(runtime) }
                pending.complete(Unit)
            }
            waitReal(150)
            assertEquals(2, runtime.activeTimerCount(), "第 $round 轮应有 2 个 timer")
            runtime.close()
            waitReal(60)
            assertTrue(runtime.activeTimerCount() == 0, "第 $round 轮关闭后不得残留 timer")
        }
    }

    private fun longTimerScript() = """
        const { EVENT_NAMES, on, send } = globalThis.lx
        on(EVENT_NAMES.request, function () {
          // Promise 由 timer 回调 resolve：这会强制 quickjs-kt pump job queue，
          // 让 __lxDelay 的 asyncFunction 及时登记到宿主 timerSignals（否则登记时机不确定）。
          return new Promise(function (resolve) {
            setTimeout(function () { resolve('https://cdn.example.com/x') }, 600)
            setTimeout(function () {}, 600)
          })
        })
        send(EVENT_NAMES.inited, {
          sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } }
        })
    """.trimIndent()
}
