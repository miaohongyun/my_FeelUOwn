package org.feeluown.mobile.provider.musicfree

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.ExecResult
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.FailureLevel
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.ModuleRegistry
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 阶段 I3：异步任务与 timer 验证（真实 QuickJS，非 Mock）。
 *
 * 诚实前提（H0 §16.5 记录，I3 复核）：quickjs-kt 1.0.15 的 async binding 回调只在 JS
 * evaluate 期间被 pump，顶层 fire-and-forget 的 setTimeout 不会在求值返回后继续运行。
 * 可靠驱动方式是用 executeAwait（ESM 顶层 await）保持求值活跃——这与本项目 LX 沙箱
 * 已验证做法一致。本测试以此模式验证各 timer API，并对"跨求值后台 timer"如实标注限制。
 */
class PhaseI3TimerTest {

    @Test
    fun i3_promiseAndAsyncAwait(): Unit = runTest {
        ModuleRegistry.clear()
        val s = MusicFreeSandboxSession("promise")
        try {
            val r = s.executeAwait("return await Promise.resolve('async-ok');")
            assertTrue(r is ExecResult.Ok, "Promise/await 应成功：$r")
            assertTrue(r.value.orEmpty().contains("async-ok"), r.value.orEmpty())
        } finally {
            s.close()
        }
    }

    @Test
    fun i3_setTimeoutFiresWithinEvaluate(): Unit = runTest {
        ModuleRegistry.clear()
        val s = MusicFreeSandboxSession("timeout")
        try {
            val r = s.executeAwait(
                "var v = await new Promise(function(res){ setTimeout(function(){ res('fired'); }, 20); });" +
                    "return {v: v};",
            )
            assertTrue(r is ExecResult.Ok, "setTimeout 应在求值期间触发：$r")
            assertTrue(r.value.orEmpty().contains("fired"), r.value.orEmpty())
        } finally {
            s.close()
        }
    }

    @Test
    fun i3_clearTimeoutPreventsCallback(): Unit = runTest {
        ModuleRegistry.clear()
        val s = MusicFreeSandboxSession("clear")
        try {
            val r = s.executeAwait(
                "globalThis.__called = false;" +
                    "var id = setTimeout(function(){ globalThis.__called = true; }, 30);" +
                    "clearTimeout(id);" +
                    "await new Promise(function(res){ setTimeout(function(){ res(1); }, 60); });" +
                    "return {called: globalThis.__called};",
            )
            assertTrue(r is ExecResult.Ok, "clearTimeout 测试应完成：$r")
            assertTrue(r.value.orEmpty().contains("\"called\":false"), r.value.orEmpty())
        } finally {
            s.close()
        }
    }

    @Test
    fun i3_setIntervalFiresAndClears(): Unit = runTest {
        ModuleRegistry.clear()
        val s = MusicFreeSandboxSession("interval")
        try {
            val r = s.executeAwait(
                "globalThis.__n = 0;" +
                    "var id = setInterval(function(){ globalThis.__n++; }, 10);" +
                    "await new Promise(function(res){ setTimeout(function(){ res(1); }, 80); });" +
                    "clearInterval(id);" +
                    "return {n: globalThis.__n};",
            )
            assertTrue(r is ExecResult.Ok, "setInterval 测试应完成：$r")
            val n = r.value.orEmpty()
            val m = Regex("\"n\":(\\d+)").find(n)
            assertTrue(m != null, "应读到 tick 计数：$n")
            val ticks = m!!.groupValues[1].toInt()
            assertTrue(ticks >= 2 && ticks <= 10, "setInterval 应重复触发 2~10 次，实际=$ticks（跨求值仅 1 次说明 timer 不可靠）")
        } finally {
            s.close()
        }
    }

    @Test
    fun i3_closeCleansUpTimers(): Unit = runBlocking {
        // 用真实时间：timerScope 跑在 Dispatchers.Default + 真实 delay，
        // runTest 的虚拟调度器不会推进它，会误报 UncompletedCoroutinesError。
        ModuleRegistry.clear()
        val s = MusicFreeSandboxSession("cleanup")
        s.initialize()
        // 登记一个长延时 timer，让宿主侧保留信号（fire-and-forget）
        s.execute("setTimeout(function(){}, 60000); true")
        val before = s.activeTimerCount()
        s.close()
        val after = s.activeTimerCount()
        assertTrue(after == 0, "close 后 timer 应全部清理，before=$before after=$after")
    }

    @Test
    fun i3_multipleEvaluateIndependent(): Unit = runTest {
        ModuleRegistry.clear()
        val s = MusicFreeSandboxSession("multi")
        try {
            val a = s.execute("JSON.stringify({n: 1})")
            val b = s.execute("JSON.stringify({n: 2})")
            assertTrue(a is ExecResult.Ok && b is ExecResult.Ok)
            assertTrue(a.value.orEmpty().contains("\"n\":1"))
            assertTrue(b.value.orEmpty().contains("\"n\":2"))
        } finally {
            s.close()
        }
    }

    @Test
    fun i3_timeoutRecoveryAfterInterrupt(): Unit = runTest {
        ModuleRegistry.clear()
        val s = MusicFreeSandboxSession("recover", MusicFreeSandbox.Limits(evaluationTimeoutMs = 200L))
        try {
            val hang = s.execute("while(true){}")
            assertTrue(hang is ExecResult.Failed && hang.level == FailureLevel.TIMEOUT)
            val after = s.execute("JSON.stringify({ok: true})")
            assertTrue(after is ExecResult.Ok, "超时中断后应可恢复：$after")
        } finally {
            s.close()
        }
    }

    @Test
    fun i3_timerCapEnforced(): Unit = runTest {
        ModuleRegistry.clear()
        val s = MusicFreeSandboxSession("cap")
        try {
            // 注册超过 64 个 timer，超出部分应被 JS 侧拒绝（返回 id=0）
            val r = s.executeAwait(
                "var ids = []; for (var i=0;i<70;i++){ ids.push(setTimeout(function(){}, 5000)); }" +
                    "var zeros = 0; for (var j=0;j<ids.length;j++){ if (ids[j]===0) zeros++; }" +
                    "return {zeros: zeros};",
            )
            assertTrue(r is ExecResult.Ok, "timer 上限测试应完成：$r")
            val m = Regex("\"zeros\":(\\d+)").find(r.value.orEmpty())
            assertTrue(m != null && m.groupValues[1].toInt() > 0, "超过 64 个的 timer 应被拒绝(id=0)：${r.value}")
        } finally {
            s.close()
        }
    }
}
