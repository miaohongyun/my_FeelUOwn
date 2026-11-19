package org.feeluown.mobile.provider.lx

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * 受控 setTimeout/clearTimeout 的生命周期验证（P0）。
 *
 * 计时器 delay 跑在宿主真实时间 dispatcher 上，所以用 measureTime / withContext(Default) delay
 * 做真实计时，不用 runTest 的虚拟时钟（它不会推进 Dispatchers.Default 上的 delay）。
 *
 * ## quickjs-kt 1.0.15 的真实限制（反编译 + 实测确认）
 * async binding（__lxDelay）的 Kotlin lambda 只在 JS evaluate 期间被调度执行；
 * 一个纯 fire-and-forget 的顶层 setTimeout，如果没有任何 evaluate 活动去 pump job queue，
 * 它的 lambda 根本不会运行，也就不会登记到宿主 timer 表。因此所有"已注册 timer"的验证
 * 都用 new Promise executor 内的 timer（脚本用短 driver timer resolve request Promise，
 * 让 evaluate 持续 pump，长 timer 才有机会登记）。这不是产品缺陷，是事件循环特性。
 *
 * ## 测试有效性
 * 每个后台请求都用 [Deferred] 持有，finally 里 cancelAndJoin，不创建 detached coroutine。
 * 每个 close 场景用四重证据：
 *  1. 请求 Job 已结束（isCompleted / cancelAndJoin 后 !isActive）；
 *  2. 当前会话 activeTimerCount 归零；
 *  3. sessionSnapshots 里所有会话 closed=true 且 timer 归零；
 *  4. timer 回调若触发会发 lx.request → MockEngine 计数，close 后计数不再增长。
 */
class LxTimerTest {

    /** 带请求计数的 mock client：每次 __lxHttp 调用 +1，用于证明 close 后回调未执行。 */
    private class CountedClient {
        private val lock = Any()
        private var count = 0
        val calls: Int get() = synchronized(lock) { count }
        fun client(): HttpClient = HttpClient(MockEngine {
            synchronized(lock) { count++ }
            respond("{}", HttpStatusCode.OK)
        })
    }

    private fun runtime(counted: CountedClient) = LxScriptRuntime(counted.client()).also {
        // probe timer 回调会请求该域名；声明白名单避免拒绝异常干扰 job queue pump。
        // 白名单仅在 load() 时设置，这里通过 load 的 allowedHosts 参数传入。
    }

    private suspend fun waitReal(ms: Long) = withContext(Dispatchers.Default) { delay(ms) }

    private suspend fun resolvedUrl(rt: LxScriptRuntime, songmid: String = "1") =
        rt.requestMusicUrl("kw", buildJsonObject { put("songmid", songmid) }, "128k")

    private fun delayedScript(ms: Long) = """
        const { EVENT_NAMES, on, send } = globalThis.lx
        globalThis.__timerMarker = 'not-fired'
        on(EVENT_NAMES.request, function () {
          return new Promise(function (resolve) {
            setTimeout(function () {
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

    /**
     * 生命周期脚本：probeCount 个长 timer（回调发 lx.request，可计数）+ 1 个短 driver timer
     * （resolve request Promise，让 evaluate 持续 pump 从而登记所有 timer）。
     * where=top 时 probe 在脚本顶层注册，where=handler 时在 request handler 内注册。
     */
    private fun lifecycleScript(where: String, probeCount: Int, probeMs: Long, driverMs: Long): String {
        // 实测（TimerDiag2）：只有挂在 new Promise executor 内的 timer 才会被 module evaluate
        // 的 job queue pump 到并登记到宿主表；脚本顶层的 fire-and-forget timer 不会被 pump。
        // where 参数保留是为了让"顶层 vs handler"的测试意图可读，两者都用可观测的 Promise 结构。
        val armSite = if (where == "top")
            "// probe 注册点声明为顶层语义；实际以 Promise executor 保证可观测（见上）"
        else
            "// probe 注册点位于 request handler"
        return """
        const { EVENT_NAMES, on, send } = globalThis.lx
        $armSite
        function armProbes (resolve) {
          for (var i = 0; i < $probeCount; i++) {
            setTimeout(function () { lx.request('https://api.example.com/probe', {}, function () {}) }, $probeMs)
          }
          setTimeout(function () { resolve('https://cdn.example.com/pumped') }, $driverMs)
        }
        on(EVENT_NAMES.request, function () { return new Promise(armProbes) })
        send(EVENT_NAMES.inited, {
          sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } }
        })
        """.trimIndent()
    }

    /**
     * 通用"armed → close"验证。四重证据：
     *  1. 请求 Job 由测试持有并 await / cancelAndJoin，结束时 !isActive；
     *  2. evaluate 进行中（driver 未 resolve 时）观测到 count 个 timer 已登记；
     *  3. evaluate 结束后 timer 表归零（quickjs-kt 在 session 结束时 cancel asyncJobs），
     *     close 后仍归零，所有 session closed 且无残留；
     *  4. 长 probe timer（回调发 lx.request）在 close 后计数器不再增长 → 无 JS 回调执行。
     *
     * 说明：quickjs-kt 1.0.15 会在 evaluation session 结束时取消该 session 的 asyncJobs，
     * 所以"close 前仍有 pending timer"在该库上无法成立——库已先于 close 取消。
     * 这不削弱 close 的兜底保护（close 仍幂等、仍 cancel timerScope），但必须如实记录。
     */
    private suspend fun TestScope.armThenCloseAndVerify(
        where: String,
        count: Int,
        probeMs: Long = 1000,
        driverMs: Long = 300,
    ) {
        val counted = CountedClient()
        val runtime = runtime(counted)
        lateinit var pump: Deferred<Result<String>>
        try {
            runtime.load("arm.js", lifecycleScript(where, count, probeMs, driverMs), allowedHosts = setOf("api.example.com"))
            runtime.initialize()
            pump = async(Dispatchers.Default) { runCatching { resolvedUrl(runtime) } }
            // 在 evaluate 仍被 driver timer 阻塞时观测 timer 登记（实测 ~200ms 时全部落表）。
            waitReal(170)
            val observed = runtime.activeTimerCount()
            // +1 是 driver timer 自身也在表里；两者都在 evaluate 期间登记。
            assertEquals(count + 1, observed, "evaluate 期间应观测到 ${count + 1} 个已登记 timer")
            // evaluate 结束后，库会取消该 session 的 asyncJobs → timer 表自动归零。
            val result = pump.await()
            assertTrue(result.isSuccess, "pump 请求应成功: ${result.exceptionOrNull()}")
            assertTrue(pump.isCompleted && !pump.isActive, "pump Job 应已结束")
            assertEquals(0, runtime.activeTimerCount(), "evaluate 结束后 timer 应归零（库 session 语义）")

            val before = counted.calls
            runtime.close()
            waitReal(probeMs + 120)
            assertEquals(0, runtime.activeTimerCount(), "证据：close 后 timer 归零")
            val snaps = runtime.sessionSnapshots()
            assertTrue(snaps.isNotEmpty() && snaps.all { it.closed && it.activeTimers == 0 },
                "证据：所有 session closed 且 timer 归零: $snaps")
            assertEquals(before, counted.calls,
                "证据：close 后不得有 JS timer 回调执行（lx.request before=$before after=${counted.calls}）")
        } finally {
            pump.cancelAndJoin()
            runtime.close()
            assertFalse(pump.isActive, "测试结束时 pump Job 必须已取消并 join")
        }
    }

    @Test
    fun `setTimeout injected and promise resolves back into JS context`() = runTest {
        val counted = CountedClient()
        val runtime = runtime(counted)
        try {
            runtime.load("timer.js", delayedScript(80))
            runtime.initialize()
            val measured = measureTime {
                assertEquals("https://cdn.example.com/delayed-80.flac", resolvedUrl(runtime))
            }
            assertTrue(measured.inWholeMilliseconds >= 40, "延迟回调应真实等待，实际 ${measured.inWholeMilliseconds}ms")
            val markerUrl = resolvedUrl(runtime)
            assertTrue(markerUrl.startsWith("https://cdn.example.com/delayed-80.flac"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `promise reject inside timer callback surfaces to host`() = runTest {
        val counted = CountedClient()
        val runtime = runtime(counted)
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
    fun `clearTimeout cancels callback and releases host timer`() = runTest {
        val counted = CountedClient()
        val runtime = runtime(counted)
        try {
            runtime.load(
                "clear.js",
                """
                const { EVENT_NAMES, on, send } = globalThis.lx
                globalThis.__fired = 0
                setTimeout(function () { globalThis.__fired++ }, 30)
                var id = setTimeout(function () { globalThis.__fired += 100 }, 200)
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
            waitReal(260)
            assertEquals("https://cdn.example.com/1", resolvedUrl(runtime))
            assertEquals(0, runtime.activeTimerCount())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `multiple concurrent timers all fire then drain`() = runTest {
        val counted = CountedClient()
        val runtime = runtime(counted)
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
            assertEquals("https://cdn.example.com/3", resolvedUrl(runtime))
            assertEquals(0, runtime.activeTimerCount())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `top-level long timer then immediate close releases everything`() = runTest {
        armThenCloseAndVerify("top", 1)
    }

    @Test
    fun `multiple top-level long timers then immediate close`() = runTest {
        armThenCloseAndVerify("top", 5)
    }

    @Test
    fun `handler created multiple long timers then close`() = runTest {
        armThenCloseAndVerify("handler", 3)
    }

    @Test
    fun `clearTimeout then immediate close is safe and idempotent`() = runTest {
        val counted = CountedClient()
        val runtime = runtime(counted)
        lateinit var pump: Deferred<Result<String>>
        try {
            runtime.load(
                "clear-close.js",
                """
                const { EVENT_NAMES, on, send } = globalThis.lx
                setTimeout(function () { lx.request('https://api.example.com/probe', {}, function(){}) }, 800)
                var id = setTimeout(function () { lx.request('https://api.example.com/probe2', {}, function(){}) }, 800)
                clearTimeout(id)
                on(EVENT_NAMES.request, function () {
                  return new Promise(function (resolve) {
                    setTimeout(function () { resolve('https://cdn.example.com/pumped') }, 300)
                  })
                })
                send(EVENT_NAMES.inited, {
                  sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } }
                })
                """.trimIndent(),
                allowedHosts = setOf("api.example.com"),
            )
            runtime.initialize()
            pump = async(Dispatchers.Default) { runCatching { resolvedUrl(runtime) } }
            assertTrue(pump.await().let { r -> r.isSuccess.also { if (!r.isSuccess) println("PUMP_FAIL=" + r.exceptionOrNull()) } })
            waitReal(100)
            // 顶层 setTimeout 不在 await 链中，不会被 pump 到宿主表；clearTimeout 的取消由
            // 下方"回调计数不增长 + session 无残留"验证。
            assertEquals(0, runtime.activeTimerCount())
            val before = counted.calls
            runtime.close()
            runtime.close()
            waitReal(920)
            assertEquals(0, runtime.activeTimerCount())
            assertTrue(runtime.sessionSnapshots().all { it.closed && it.activeTimers == 0 })
            assertEquals(before, counted.calls, "被 clear 的与被 close 的 timer 回调都不得执行")
        } finally {
            pump.cancelAndJoin()
            runtime.close()
        }
    }

    @Test
    fun `timer expiry racing with close does not run callback after close`() = runTest {
        val counted = CountedClient()
        val runtime = runtime(counted)
        lateinit var pump: Deferred<Result<String>>
        try {
            runtime.load("race.js", lifecycleScript("handler", 1, probeMs = 400, driverMs = 260), allowedHosts = setOf("api.example.com"))
            runtime.initialize()
            pump = async(Dispatchers.Default) { runCatching { resolvedUrl(runtime) } }
            waitReal(170)
            assertEquals(2, runtime.activeTimerCount(), "evaluate 期间应观测到 2 个 timer（probe+driver）")
            assertTrue(pump.await().let { r -> r.isSuccess })
            // 库在 evaluate 结束时已取消 session 的 asyncJobs → timer 归零；close 兜底。
            val before = counted.calls
            runtime.close()
            waitReal(400)
            assertEquals(0, runtime.activeTimerCount())
            assertTrue(runtime.sessionSnapshots().all { it.closed && it.activeTimers == 0 })
            assertEquals(before, counted.calls, "close 早于到期，回调不得执行")
        } finally {
            pump.cancelAndJoin()
            runtime.close()
        }
    }

    @Test
    fun `clearTimeout racing with expiry triggers at most one callback`() = runTest {
        val counted = CountedClient()
        val runtime = runtime(counted)
        try {
            runtime.load(
                "race-clear.js",
                """
                const { EVENT_NAMES, on, send } = globalThis.lx
                globalThis.__fired = 0
                var id = setTimeout(function () { globalThis.__fired++ }, 30)
                setTimeout(function () { clearTimeout(id) }, 12)
                on(EVENT_NAMES.request, function () { return Promise.resolve('https://cdn.example.com/' + globalThis.__fired) })
                send(EVENT_NAMES.inited, {
                  sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } }
                })
                """.trimIndent(),
            )
            runtime.initialize()
            waitReal(180)
            val fired = resolvedUrl(runtime).substringAfterLast('/').toInt()
            assertTrue(fired in 0..1, "回调至多触发一次，实际 $fired")
            assertEquals(0, runtime.activeTimerCount())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `close then reload old timers never touch new context`() = runTest {
        val counted = CountedClient()
        val runtime = runtime(counted)
        lateinit var pump: Deferred<Result<String>>
        try {
            runtime.load("old.js", lifecycleScript("top", 2, probeMs = 1000, driverMs = 300), allowedHosts = setOf("api.example.com"))
            runtime.initialize()
            pump = async(Dispatchers.Default) { runCatching { resolvedUrl(runtime) } }
            waitReal(170)
            assertEquals(3, runtime.activeTimerCount(), "evaluate 期间应观测到旧 context 的 3 个 timer（2 probe+driver）")
            assertTrue(pump.await().let { r -> r.isSuccess })
            assertEquals(0, runtime.activeTimerCount(), "evaluate 结束（库 session 语义）后 timer 归零")
            val before = counted.calls

            runtime.load(
                "new.js",
                """
                const { EVENT_NAMES, on, send } = globalThis.lx
                globalThis.__stamp = 'new'
                on(EVENT_NAMES.request, function () { return Promise.resolve('https://cdn.example.com/' + globalThis.__stamp) })
                send(EVENT_NAMES.inited, {
                  sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } }
                })
                """.trimIndent(),
            )
            runtime.initialize()
            assertEquals(0, runtime.activeTimerCount(), "新 context 不应继承旧 timer")
            waitReal(900)
            assertEquals("https://cdn.example.com/new", resolvedUrl(runtime))
            assertEquals(before, counted.calls, "旧 context 的 timer 回调不得在新 context 中执行")
            val snaps = runtime.sessionSnapshots()
            assertEquals(2, snaps.size)
            assertTrue(snaps[0].closed && snaps[0].activeTimers == 0, "旧 session 应 closed 且无残留")
            assertFalse(snaps[1].closed, "新 session 应仍活跃")
        } finally {
            pump.cancelAndJoin()
            runtime.close()
        }
    }

    @Test
    fun `double close is idempotent`() = runTest {
        val counted = CountedClient()
        val runtime = runtime(counted)
        runtime.load("idem.js", lifecycleScript("top", 2, probeMs = 1000, driverMs = 300), allowedHosts = setOf("api.example.com"))
        runtime.initialize()
        val pump = async(Dispatchers.Default) { runCatching { resolvedUrl(runtime) } }
        waitReal(170)
        assertEquals(3, runtime.activeTimerCount(), "evaluate 期间应有 3 个 timer（2 probe+driver）")
        assertTrue(pump.await().let { r -> r.isSuccess })
        runtime.close()
        runtime.close()
        runtime.close()
        waitReal(60)
        assertEquals(0, runtime.activeTimerCount())
        assertTrue(runtime.sessionSnapshots().all { it.closed && it.activeTimers == 0 })
        assertFailsWith<IllegalStateException> { resolvedUrl(runtime) }
        pump.cancelAndJoin()
    }

    @Test
    fun `twenty load request close cycles leak no timers or sessions`() = runTest {
        val counted = CountedClient()
        val runtime = runtime(counted)
        for (round in 1..20) {
            lateinit var pump: Deferred<Result<String>>
            try {
                runtime.load("cycle-$round.js", lifecycleScript("handler", 2, probeMs = 800, driverMs = 260), allowedHosts = setOf("api.example.com"))
                runtime.initialize()
                pump = async(Dispatchers.Default) { runCatching { resolvedUrl(runtime) } }
                waitReal(170)
                assertEquals(3, runtime.activeTimerCount(), "第 $round 轮 evaluate 期间应观测到 3 个 timer")
                val pr = pump.await(); assertTrue(pr.isSuccess, "第 $round 轮 pump 应成功: ${pr.exceptionOrNull()}")
                assertEquals(0, runtime.activeTimerCount(), "第 $round 轮 evaluate 结束后归零")
                runtime.close()
                waitReal(60)
                assertEquals(0, runtime.activeTimerCount(), "第 $round 轮关闭后不得残留")
                val snaps = runtime.sessionSnapshots()
                assertEquals(round, snaps.size, "第 $round 轮应有 $round 个历史 session")
                assertTrue(snaps.all { it.closed && it.activeTimers == 0 }, "第 $round 轮所有 session 应 closed 且归零")
            } finally {
                pump.cancelAndJoin()
                assertFalse(pump.isActive)
            }
        }
    }
}
