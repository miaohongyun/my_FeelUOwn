package org.feeluown.mobile.provider.musicfree

import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.ExecResult
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.ModuleRegistry
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.RegisteredModule
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 阶段 I1：CommonJS require 完整往返（JS require → 宿主 allowlist → 模块加载 → JS 对象 → 插件继续调用）。
 *
 * 与 H1 的差异：H1 只验证 Kotlin 侧门禁与缓存等价语义；本测试实际在 JS 内调用
 * require() 并读取模块导出内容。I0 已证明当前 quickjs-kt 1.0.15 的字符串封送在同步
 * binding、异步 binding、ESM 模块内三种路径均可保真，因此不再以"封送差异"为跳过理由。
 *
 * 根因修复：H1 用 evaluate<String>(..., asModule = true) 读模块 exports，但该模式不回传
 * 求值结果，导致 modulesByName 值全为 null；I1 改为非 asModule 的 IIFE 求值 + globalThis 读回。
 */
class PhaseI1CommonJsTest {

    private fun setupRegistry(vararg extra: RegisteredModule) {
        ModuleRegistry.clear()
        ModuleRegistry.register(
            RegisteredModule(
                name = "audited-helper",
                source = "module.exports = { name: 'audited-helper', v: 1 };",
                sha256 = "c".repeat(64), auditedAt = "2026-11-20", note = "测试用纯数据模块",
            ),
        )
        extra.forEach { ModuleRegistry.register(it) }
    }

    private suspend fun run(source: String, providerId: String = "p"): ExecResult {
        val s = MusicFreeSandboxSession(providerId)
        return try {
            s.execute(source)
        } finally {
            s.close()
        }
    }

    @Test
    fun i1_moduleExportsInEntryScript(): Unit = runTest {
        setupRegistry()
        val r = run("module.exports = { title: '晴天' }; JSON.stringify(module.exports)")
        assertTrue(r is ExecResult.Ok, "module.exports 应可写，实际=$r")
        assertTrue(r.value.orEmpty().contains("晴天"))
    }

    @Test
    fun i1_exportsAliasInEntryScript(): Unit = runTest {
        setupRegistry()
        val r = run("exports.title = '叶惠美'; JSON.stringify(exports)")
        assertTrue(r is ExecResult.Ok, "exports 别名应可用，实际=$r")
        assertTrue(r.value.orEmpty().contains("叶惠美"))
    }

    @Test
    fun i1_requireRoundTripAndCallExportedValue(): Unit = runTest {
        setupRegistry()
        val r = run("var m = require('audited-helper'); JSON.stringify({n: m.name, v: m.v})")
        assertTrue(r is ExecResult.Ok, "JS 内 require 往返应成功，实际=$r")
        assertTrue(r.value.orEmpty().contains("audited-helper"), "插件应能读取模块导出内容：$r")
        assertTrue(r.value.orEmpty().contains("\"v\":1"), "插件应能读取导出数值：$r")
    }

    @Test
    fun i1_requireUnlistedModuleRejectedInJs(): Unit = runTest {
        setupRegistry()
        val r = run("require('axios')")
        assertTrue(r is ExecResult.Failed, "未登记模块 require 必须失败，实际=$r")
        assertTrue(r.reason.contains("未登记") || r.reason.contains("allowlist"), "拒绝原因应明确：$r")
    }

    @Test
    fun i1_requirePathTraversalRejectedByPreflight(): Unit = runTest {
        setupRegistry()
        val r = run("require('../etc/passwd')")
        assertTrue(r is ExecResult.Failed, "路径穿越必须被预检拒绝，实际=$r")
    }

    @Test
    fun i1_moduleCacheReturnsSameObject(): Unit = runTest {
        setupRegistry()
        val r = run("var a = require('audited-helper'); var b = require('audited-helper'); JSON.stringify({same: a === b})")
        assertTrue(r is ExecResult.Ok, "重复 require 应成功，实际=$r")
        assertTrue(r.value.orEmpty().contains("\"same\":true"), "模块缓存应返回同一对象：$r")
    }

    @Test
    fun i1_relativeModulePathRegisteredByName(): Unit = runTest {
        setupRegistry(
            RegisteredModule(
                name = "lib/helper",
                source = "module.exports = { v: 7 };",
                sha256 = "d".repeat(64), auditedAt = "2026-11-20", note = "测试用相对路径模块",
            ),
        )
        val r = run("JSON.stringify(require('lib/helper'))")
        assertTrue(r is ExecResult.Ok, "受控相对路径 require 应成功：$r")
        assertTrue(r.value.orEmpty().contains("\"v\":7"), "应读到相对路径模块导出：$r")
        assertTrue(!ModuleRegistry.isSafeModuleName("lib/../secret"), "带 .. 的相对路径仍须拒绝")
        assertTrue(!ModuleRegistry.isSafeModuleName("/abs"), "绝对路径仍须拒绝")
    }

    @Test
    fun i1_circularDependencyDoesNotHang(): Unit = runTest {
        setupRegistry(
            RegisteredModule(
                name = "circ-a", source = "module.exports = { name: 'a', peer: require('circ-b').name };",
                sha256 = "f".repeat(64), auditedAt = "2026-11-20", note = "循环依赖 A",
            ),
            RegisteredModule(
                name = "circ-b", source = "module.exports = { name: 'b', peer: require('circ-a').name };",
                sha256 = "0".repeat(64), auditedAt = "2026-11-20", note = "循环依赖 B",
            ),
        )
        val r = run("var a = require('circ-a'); JSON.stringify(a)")
        // 当前模块预载为 JSON exports 快照，预载期 require 用空对象桩断开循环；
        // 只要求不挂死并给出确定结果，不伪称已实现 Node 的循环依赖解析。
        assertTrue(r is ExecResult.Ok || r is ExecResult.Failed, "循环依赖探测必须有确定结果")
    }

    @Test
    fun i1_moduleIsolationBetweenProviders(): Unit = runTest {
        setupRegistry()
        val a = MusicFreeSandboxSession("isoA")
        val b = MusicFreeSandboxSession("isoB")
        try {
            a.initialize(); b.initialize()
            val ra = a.execute("var m = require('audited-helper'); m.name = 'tampered'; JSON.stringify(m)")
            val rb = b.execute("var m = require('audited-helper'); JSON.stringify(m)")
            assertTrue(ra is ExecResult.Ok, "A 应可篡改本 session 的模块对象：$ra")
            assertTrue(rb is ExecResult.Ok && rb.value.orEmpty().contains("audited-helper"), "B 不受 A 篡改影响：$rb")
            assertTrue(!rb.value.orEmpty().contains("tampered"), "跨 Provider 模块缓存必须隔离：$rb")
        } finally {
            a.close(); b.close()
        }
    }
}
