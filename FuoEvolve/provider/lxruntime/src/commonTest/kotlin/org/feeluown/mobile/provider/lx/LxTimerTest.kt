package org.feeluown.mobile.provider.lx

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 */
class LxTimerTest {

    private fun client(): HttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })

    private fun runtime() = LxScriptRuntime(client())

    /** 一个在 request handler 里用 setTimeout 延迟 resolve 的脚本。 */
    private fun delayedScript(ms: Long) = """
        const { EVENT_NAMES, on, send } = globalThis.lx
        on(EVENT_NAMES.request, function () {
          return new Promise(function (resolve) {
            setTimeout(function () {
              resolve('https://cdn.example.com/delayed-' + $ms + '.flac')
            }, $ms)
          })
        })
        send(EVENT_NAMES.inited, {
          sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } }
        })
    """.trimIndent()

    @Test
    fun `setTimeout fires and resolves delayed callback`() = runTest {
        val runtime = runtime()
        try {
            runtime.load("timer.js", delayedScript(80))
            runtime.initialize()
            val measured = measureTime {
                val url = runtime.requestMusicUrl(
                    "kw",
                    buildJsonObject { put("songmid", "1") },
                    "128k",
                )
                assertEquals("https://cdn.example.com/delayed-80.flac", url)
            }
            assertTrue(
                measured.inWholeMilliseconds >= 40,
                "延迟回调应真实等待，实际 ${measured.inWholeMilliseconds}ms",
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `clearTimeout cancels a pending callback`() = runTest {
        val runtime = runtime()
        try {
            runtime.load(
                "clear.js",
                """
                const { EVENT_NAMES, on, send } = globalThis.lx
                globalThis.__fired = 0
                setTimeout(function () { globalThis.__fired++ }, 30)
                var id = setTimeout(function () { globalThis.__fired += 100 }, 30)
                clearTimeout(id)
                on(EVENT_NAMES.request, function () {
                  return Promise.resolve('https://cdn.example.com/' + globalThis.__fired)
                })
                send(EVENT_NAMES.inited, {
                  sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } }
                })
                """.trimIndent(),
            )
            runtime.initialize()
            // 等真实时间让未取消的计时器跑完
            withContext(Dispatchers.Default) { delay(120) }
            val url = runtime.requestMusicUrl(
                "kw", buildJsonObject { put("songmid", "1") }, "128k",
            )
            assertEquals("https://cdn.example.com/1", url, "被 clearTimeout 的回调不应执行")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `multiple timers all fire`() = runTest {
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
            withContext(Dispatchers.Default) { delay(120) }
            val url = runtime.requestMusicUrl(
                "kw", buildJsonObject { put("songmid", "1") }, "128k",
            )
            assertEquals("https://cdn.example.com/3", url, "三个并发计时器都应触发")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `close cancels pending timers and invalidates the context`() = runTest {
        val runtime = runtime()
        runtime.load("close.js", delayedScript(60))
        runtime.initialize()
        // 计时器还挂着就关闭；close 必须取消协程并销毁 context。
        runtime.close()
        assertFailsWith<IllegalStateException> {
            runtime.requestMusicUrl("kw", buildJsonObject { put("songmid", "1") }, "128k")
        }
        // 再次关闭应幂等，不抛异常
        runtime.close()
    }

    @Test
    fun `reload resets timer scope`() = runTest {
        val runtime = runtime()
        try {
            runtime.load("first.js", delayedScript(40))
            runtime.initialize()
            // 重新加载会重建 timerScope；新 context 不应残留旧计时器副作用。
            runtime.load("second.js", delayedScript(40))
            runtime.initialize()
            val url = runtime.requestMusicUrl(
                "kw", buildJsonObject { put("songmid", "1") }, "128k",
            )
            assertEquals("https://cdn.example.com/delayed-40.flac", url)
        } finally {
            runtime.close()
        }
    }
}
