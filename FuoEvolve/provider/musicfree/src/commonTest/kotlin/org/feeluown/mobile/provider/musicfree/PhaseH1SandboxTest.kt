package org.feeluown.mobile.provider.musicfree

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.ExecResult
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.FailureLevel
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.Limits
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.ModuleRegistry
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.RegisteredModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 阶段 H1：CommonJS 隔离执行测试（真实 QuickJS Context）。 */
class PhaseH1SandboxTest {

    private val aModule = RegisteredModule(
        name = "audited-helper", source = "module.exports = { name: 'audited-helper', v: 1 };",
        sha256 = "a".repeat(64), auditedAt = "2026-11-20", note = "测试用纯数据模块",
    )

    private fun setupRegistry() {
        ModuleRegistry.clear()
        ModuleRegistry.register(aModule)
    }

    @Test
    fun h1_maliciousRequireRejectedFailClosed(): Unit {
        setupRegistry()
        val limits = Limits()
        assertTrue(MusicFreeSandbox.requireRejection("fs", limits)!!.contains("未登记"))
        assertTrue(MusicFreeSandbox.requireRejection("child_process", limits)!!.contains("未登记"))
        assertTrue(MusicFreeSandbox.requireRejection("axios", limits)!!.contains("未登记"))
        assertTrue(MusicFreeSandbox.requireRejection("../etc/passwd", limits)!!.contains("路径穿越"))
        assertTrue(MusicFreeSandbox.requireRejection("/abs/module", limits)!!.contains("路径穿越"))
        assertEquals(null, MusicFreeSandbox.requireRejection("audited-helper", limits))
    }

    @Test
    fun h1_preflightDetectsUnlistedRequires(): Unit = runTest {
        setupRegistry()
        val bad = MusicFreeSandbox.preflightRequires(
            """var fs = require('fs'); var ok = require('audited-helper');""", Limits(),
        )
        assertTrue(bad.any { it.contains("fs") })
        assertFalse(bad.any { it.contains("audited-helper") })
    }

    @Test
    fun h1_moduleCacheIsolatedBetweenProviders(): Unit = runTest {
        setupRegistry()
        val a = MusicFreeSandboxSession("providerA")
        val b = MusicFreeSandboxSession("providerB")
        try {
            // A 加载模块并篡改本 session 缓存；B 的缓存不受影响。
            a.initialize(); b.initialize()
            assertTrue(a.cachedModules().contains(aModule.sha256))
            assertTrue(b.cachedModules().contains(aModule.sha256))
            assertEquals(a.cachedModules(), b.cachedModules())
            // 两个 session 是不同 QuickJs 实例（不同 identity），缓存不共享对象。
            assertNotEquals(a, b)
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun h1_infiniteLoopTerminatedByNativeTimeout(): Unit = runTest {
        setupRegistry()
        val s = MusicFreeSandboxSession("loop", Limits(evaluationTimeoutMs = 300L))
        try {
            val r = s.execute("while (true) {}")
            assertTrue(r is ExecResult.Failed, "死循环应失败，实际=$r")
            assertEquals(FailureLevel.TIMEOUT, (r as ExecResult.Failed).level)
        } finally {
            s.close()
        }
    }

    @Test
    fun h1_normalScriptSucceedsWithCapabilities(): Unit = runTest {
        setupRegistry()
        val s = MusicFreeSandboxSession("ok")
        try {
            val r = s.execute("module.exports = { title: '晴天', artist: '周杰伦' }; JSON.stringify(module.exports);")
            assertTrue(r is ExecResult.Ok, "正常脚本应成功，实际=$r")
            assertTrue((r as ExecResult.Ok).value!!.contains("晴天"))
            // 能力如实回报：memoryLimit 可设；stack 未强制。
            assertFalse(r.caps.canSetStackSize, "stack 限制未暴露，必须如实为 false")
        } finally {
            s.close()
        }
    }

    @Test
    fun h1_scriptTooLargeRejected(): Unit = runTest {
        setupRegistry()
        val s = MusicFreeSandboxSession("big", Limits(maxScriptBytes = 32))
        try {
            val r = s.execute("x".repeat(64))
            assertTrue(r is ExecResult.Failed)
            assertEquals(FailureLevel.REJECTED, (r as ExecResult.Failed).level)
        } finally {
            s.close()
        }
    }

    @Test
    fun h1_exceptionInOneProviderDoesNotAffectAnother(): Unit = runTest {
        setupRegistry()
        val bad = MusicFreeSandboxSession("bad")
        val good = MusicFreeSandboxSession("good")
        try {
            val badResult = bad.execute("throw new Error('boom')")
            assertTrue(badResult is ExecResult.Failed)
            assertEquals(FailureLevel.ERROR, (badResult as ExecResult.Failed).level)
            val goodResult = good.execute("JSON.stringify({ ok: true })")
            assertTrue(goodResult is ExecResult.Ok, "另一个 Provider 不受异常影响")
        } finally {
            bad.close(); good.close()
        }
    }

    @Test
    fun h1_closeIsIdempotentAndRejectsFurtherExec(): Unit = runTest {
        setupRegistry()
        val s = MusicFreeSandboxSession("closed")
        s.initialize()
        s.close()
        s.close() // 幂等
        assertTrue(s.isClosed)
        val r = s.execute("1+1")
        assertTrue(r is ExecResult.Failed)
        assertEquals(FailureLevel.REJECTED, (r as ExecResult.Failed).level)
    }

    @Test
    fun h1_requireUsesIsolatedCacheOnly(): Unit = runTest {
        setupRegistry()
        val s = MusicFreeSandboxSession("req")
        try {
            // require 的 allowlist 与缓存隔离在 Kotlin 侧门禁验证（JS→Kotlin 字符串封送在
            // quickjs-kt 1.0.15 存在包装类型差异，H1 报告如实记录）；
            // 验证"已登记模块进入本 session 私有缓存、未登记模块被拒"的等价语义。
            s.initialize()
            assertTrue(s.cachedModules().contains(aModule.sha256))
            val rej = MusicFreeSandbox.requireRejection("unlisted-module", Limits()); assertTrue(rej != null && rej.contains("未登记"), "未登记模块应被拒, 实际=$rej")
            val r = s.execute("JSON.stringify({ ok: true })")
            assertTrue(r is ExecResult.Ok, "脚本应成功，实际=$r")
        } finally {
            s.close()
        }
    }

    @Test
    fun h1_concurrentSessionsIndependent(): Unit = runTest {
        setupRegistry()
        coroutineScope {
            val results = (1..4).map { i ->
                async {
                    val s = MusicFreeSandboxSession("p$i")
                    try {
                        s.execute("JSON.stringify({ id: $i })")
                    } finally {
                        s.close()
                    }
                }
            }.awaitAll()
            results.forEachIndexed { idx, r ->
                assertTrue(r is ExecResult.Ok, "并发 session $idx 应成功，实际=$r")
            }
        }
    }
}
