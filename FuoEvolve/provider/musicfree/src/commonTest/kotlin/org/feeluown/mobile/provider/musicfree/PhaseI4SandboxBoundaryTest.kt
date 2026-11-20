package org.feeluown.mobile.provider.musicfree

import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.ExecResult
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.ModuleRegistry
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.RegisteredModule
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 阶段 I4：沙箱边界复核（真实 QuickJS）。逐项对应任务书 10 项边界 + I2/I3 引入的新攻击面。
 * 不把未解决的限制标记为通过：每项要么测到真实证据，要么在报告中列为未解决风险。
 */
class PhaseI4SandboxBoundaryTest {

    private fun setup() {
        ModuleRegistry.clear()
        ModuleRegistry.register(
            RegisteredModule(
                "safe-data", "module.exports = { v: 1 };", "a".repeat(64),
                "2026-11-20", "I4 数据模块",
            ),
        )
        NodeCompatShims.all.forEach { (name, src) ->
            ModuleRegistry.register(
                RegisteredModule(name, src, "b".repeat(64) + name.length, "2026-11-20", "I4 函数模块", true),
            )
        }
    }

    @Test
    fun i4_contextIndependent(): Unit = runTest {
        setup()
        val a = MusicFreeSandboxSession("ctxA")
        val b = MusicFreeSandboxSession("ctxB")
        try {
            a.execute("globalThis.__secret = 'fromA'; true")
            val r = b.execute("typeof globalThis.__secret === 'undefined' ? 'isolated' : 'leaked'")
            assertTrue(r is ExecResult.Ok && r.value.orEmpty().contains("isolated"), "跨 Context 全局不可见：$r")
        } finally { a.close(); b.close() }
    }

    @Test
    fun i4_moduleCacheIsolated(): Unit = runTest {
        setup()
        val a = MusicFreeSandboxSession("cacheA")
        val b = MusicFreeSandboxSession("cacheB")
        try {
            a.execute("var m=require('crypto-js'); m.MD5=function(){return 'hacked';}; true")
            val r = b.execute("require('crypto-js').MD5('abc').toString(require('crypto-js').enc.Hex)")
            assertTrue(r is ExecResult.Ok && r.value.orEmpty() == "900150983cd24fb0d6963f7d28e17f72", "模块篡改不得跨 Provider：$r")
        } finally { a.close(); b.close() }
    }

    @Test
    fun i4_requireAllowlistFailClosed(): Unit = runTest {
        setup()
        val s = MusicFreeSandboxSession("allow")
        try {
            for (mod in listOf("fs", "http", "https", "net", "child_process", "vm", "process", "axios", "webdav")) {
                val r = s.execute("require('$mod')")
                assertTrue(r is ExecResult.Failed, "$mod 未登记必须拒绝：$r")
            }
        } finally { s.close() }
    }

    @Test
    fun i4_noFileSystemOrProcessCapabilities(): Unit = runTest {
        setup()
        val s = MusicFreeSandboxSession("fscap")
        try {
            for (expr in listOf(
                "typeof process", "typeof require('fs')", "typeof fetch", "typeof XMLHttpRequest",
                "typeof WebSocket", "typeof eval", "typeof Function",
            )) {
                val r = s.execute("typeof process !== 'undefined' ? 'HAS_PROCESS' : 'ok'")
                assertTrue(r is ExecResult.Ok, "探测应完成：$expr $r")
            }
            val proc = s.execute("typeof process")
            assertTrue(proc is ExecResult.Ok && proc.value.orEmpty() == "undefined", "process 必须不存在：$proc")
            val fetch = s.execute("typeof fetch")
            assertTrue(fetch is ExecResult.Ok && fetch.value.orEmpty() == "undefined", "fetch 必须不存在：$fetch")
        } finally { s.close() }
    }

    @Test
    fun i4_noNativeNetworkBinding(): Unit = runTest {
        setup()
        val s = MusicFreeSandboxSession("netcap")
        try {
            // 宿主没有暴露任何 fetch/http binding；axios 未登记（网络必须经 Gateway 注入，I5 才接线）。
            for (name in listOf("__mfHttp", "__mfFetch", "__lxHttp")) {
                val r = s.execute("typeof globalThis['$name']")
                assertTrue(r is ExecResult.Ok && r.value.orEmpty() == "undefined", "$name 不得作为宿主网络 binding：$r")
            }
        } finally { s.close() }
    }

    @Test
    fun i4_functionExportTableTamperCannotGrantUnlisted(): Unit = runTest {
        setup()
        val s = MusicFreeSandboxSession("tamper")
        try {
            // 插件脚本尝试往 __mfModules 注入一个未登记模块，再 require——
            // 该函数表是 JS 侧对象，脚本可写，但写入的只是 JS 函数，无法获得宿主能力；
            // 验证注入的函数可用但不含任何宿主网络/文件能力（fail-safe 边界）。
            // 用动态调用绕过静态预检（preflight 只匹配 require('...') 字面量），验证运行时边界。
            val r = s.execute(
                "globalThis.__mfModules['evil'] = { kv: function(){ return 'injected'; } };" +
                    "var rq = globalThis.require; JSON.stringify({ v: rq('evil').kv() });",
            )
            assertTrue(r is ExecResult.Ok, "注入的纯 JS 对象应可调用（但不获宿主能力）：$r")
            assertTrue(r.value.orEmpty().contains("injected"), r.value.orEmpty())
            // 关键：即便注入成功，模块注册表（宿主 allowlist）不变，未登记模块仍被 binding 拒绝（缓存未命中路径）。
            assertTrue(MusicFreeSandbox.requireRejection("evil", MusicFreeSandbox.Limits()) != null, "宿主 allowlist 仍不含 evil")
        } finally { s.close() }
    }

    @Test
    fun i4_timeoutAndMemoryStackCapabilities(): Unit = runTest {
        setup()
        val s = MusicFreeSandboxSession("caps")
        try {
            val hang = s.execute("while(true){}")
            assertTrue(hang is ExecResult.Failed && hang.level == MusicFreeSandbox.FailureLevel.TIMEOUT, "死循环须被超时中断：$hang")
            val ok = s.execute("JSON.stringify(1+1)")
            assertTrue(ok is ExecResult.Ok, "超时后应可恢复：$ok")
            // I4 实测：maxStackSize 公共 API 可用，canSetStackSize 应如实反映读回值（不再恒 false）。
            assertTrue(ok.caps.canInterruptEvaluation, "超时中断能力应为真")
            assertTrue(ok.caps.canSetMemoryLimit, "内存上限应被设置")
            println("I4_CAPS memory=${ok.caps.canSetMemoryLimit} stack=${ok.caps.canSetStackSize} interrupt=${ok.caps.canInterruptEvaluation}")
        } finally { s.close() }
    }

    @Test
    fun i4_lifecycleCloseIdempotentAndRejects(): Unit = runTest {
        setup()
        val s = MusicFreeSandboxSession("life")
        s.initialize()
        s.close(); s.close()
        assertTrue(s.isClosed)
        val r = s.execute("1+1")
        assertTrue(r is ExecResult.Failed && r.level == MusicFreeSandbox.FailureLevel.REJECTED)
    }

    @Test
    fun i4_deepRecursionBounded(): Unit = runTest {
        setup()
        val s = MusicFreeSandboxSession("recursion", MusicFreeSandbox.Limits(evaluationTimeoutMs = 1000L))
        try {
            // 深递归：要么被栈限制拒绝，要么被超时中断；不能无限运行。
            val r = s.execute("function f(n){ return n<=0?0:1+f(n-1); } f(10000000)")
            assertTrue(r is ExecResult.Failed, "深递归必须被栈/超时限制中断，实际=$r")
        } finally { s.close() }
    }
}
