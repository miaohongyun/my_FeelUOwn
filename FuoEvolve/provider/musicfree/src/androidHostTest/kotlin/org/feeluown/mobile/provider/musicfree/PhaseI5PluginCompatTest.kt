package org.feeluown.mobile.provider.musicfree

import kotlinx.coroutines.runBlocking
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.ExecResult
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.ModuleRegistry
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.RegisteredModule
import org.feeluown.mobile.provider.musicfree.MusicFreePluginCompatVerification.Level
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.GatewayResult
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.Request
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 阶段 I5：真实 MusicFree 插件兼容性测试（AndroidHost，真实 QuickJS + 真实插件正文）。
 * 插件正文 SHA 已核对与 G2 台账一致；网络经 ProviderNetworkGateway，默认 deny-all，不扩大白名单。
 * 搜索请求因域名未授权被 Gateway 拒绝属**预期真实结果**，如实记录，不伪造 REAL_SEARCH。
 */
class PhaseI5PluginCompatTest {

    private val pluginDir = File("src/androidHostTest/resources/plugins")

    private fun setupRegistry() {
        ModuleRegistry.clear()
        // I2 纯计算模块（含 cheerio/crypto-js/dayjs/he）
        NodeCompatShims.all.forEach { (name, src) ->
            ModuleRegistry.register(
                RegisteredModule(name, src, "i2".repeat(32) + name.length, "2026-11-20", "I5 依赖", true),
            )
        }
        // axios：真实对象由沙箱 __mfModules 注入（走 Gateway），此处仅登记 allowlist 使静态预检通过。
        ModuleRegistry.register(
            RegisteredModule("axios", "// gateway-backed axios", "ax".repeat(32), "2026-11-20", "I5 网络模块", true),
        )
        // webdav：登记但不启用（动态端点 deny-all）
        ModuleRegistry.register(
            RegisteredModule("webdav", "module.exports = {};", "wd".repeat(32), "2026-11-20", "I5 webdav 不启用", true),
        )
    }

    private fun plugin(name: String): String = File(pluginDir, "$name.js").readText()

    /** 假执行器：记录所有触达的网络请求。deny-all 权限下 Gateway 不会调用它。 */
    private fun recordingExecutor(calls: MutableList<Request>) = { req: Request ->
        calls += req
        ProviderNetworkGateway.Response(200, """{"ok":true,"data":[]}""")
    }

    @Test
    fun i5_geciwangLoadsAndRequires(): Unit = runBlocking {
        setupRegistry()
        val src = plugin("geciwang")
        val session = MusicFreeSandboxSession("mf-geciwang")
        try {
            // 真实插件作为 entry 脚本执行；追加导出键读取，验证初始化 + require 完成。
            val probe = src + "\nJSON.stringify({platform: module.exports.platform, keys: Object.keys(module.exports), ver: module.exports.version});"
            val r = session.execute(probe)
            assertTrue(r is ExecResult.Ok, "geciwang 应能在沙箱初始化 + require：$r")
            val v = r.value.orEmpty()
            assertTrue(v.contains("歌词网"), "module.exports.platform 应可读：$v")
            assertTrue(v.contains("search") && v.contains("getLyric"), "导出应含 search/getLyric：$v")
            println("I5_GECIWANG_INIT=$v")
        } finally {
            session.close()
        }
        assertTrue(session.isClosed, "资源应释放")
    }

    @Test
    fun i5_geciwangSearchDeniedByGateway(): Unit = runBlocking {
        setupRegistry()
        val src = plugin("geciwang")
        val attempts = mutableListOf<Request>()   // bridge 记录的尝试（含被拒）
        val executorCalls = mutableListOf<Request>() // 真正触达 executor 的请求（应为 0）
        val gateway = ProviderNetworkGateway.fromStringExecutor(recordingExecutor(executorCalls))
        // J0：SHA 从实际加载的脚本字节现场计算，禁止硬编码常量冒充当前脚本。
        val scriptBytes = src.encodeToByteArray()
        val sha = MusicFreePluginCompatVerification.shaOfScriptBytes(scriptBytes)
        assertEquals("b953571441c4d2ef3216d505fdaac2577dad1695e7009382bd4b83f1dd09c4f2", sha)
        val permission = MusicFreePluginCompatVerification.denyAllPermission("mf-geciwang", sha)
        val bridge = MusicFreePluginCompatVerification.gatewayBridge(gateway, permission, scriptBytes, attempts)

        val session = MusicFreeSandboxSession("mf-geciwang-net", networkBridge = bridge)
        try {
            val setup = src + "\nJSON.stringify({ok:true});"
            val init = session.execute(setup)
            assertTrue(init is ExecResult.Ok, "插件应先能初始化：$init")
            val probeAxios = session.execute("typeof require('axios').default?.get")
            assertTrue((probeAxios as? ExecResult.Ok)?.value == "function", "axios.default.get 应可用：${probeAxios}")
            // 真实调用 search('晴天',1,'lyric')：其域名 zh.followlyrics.com 未授权 → Gateway DENIED。
            val search = session.executeAwait(
                "try { var r = await module.exports.search('晴天', 1, 'lyric'); return {ok:true, hasData: !!r}; }" +
                    "catch(e) { return {ok:false, reason: String(e.message||e)}; }",
            )
            assertTrue(search is ExecResult.Ok, "search 调用应返回确定结果：$search")
            val out = search.value.orEmpty()
            assertTrue(out.contains("\"ok\":false") && out.contains("Gateway"), "未授权域名应被 Gateway 拒绝：$out")
            println("I5_GECIWANG_SEARCH=$out")
            // 关键红线：Gateway 拒绝 = 零真实网络（executor 从未触达）
            assertTrue(executorCalls.isEmpty(), "Gateway 拒绝的请求不得触达 executor（零真实网络），实际=${executorCalls.size}")
            assertTrue(attempts.any { it.url.contains("followlyrics") }, "应记录到被拒的搜索尝试（台账可审计）")
        } finally {
            session.close()
        }
    }

    @Test
    fun i5_audiomackLoadsAndRequires(): Unit = runBlocking {
        setupRegistry()
        val src = plugin("audiomack")
        val session = MusicFreeSandboxSession("mf-audiomack")
        try {
            val probe = src + "\nJSON.stringify({platform: module.exports.platform, keys: Object.keys(module.exports)});"
            val r = session.execute(probe)
            assertTrue(r is ExecResult.Ok, "audiomack 应能初始化 + require：$r")
            val v = r.value.orEmpty()
            assertTrue(v.contains("Audiomack") || v.contains("audiomack"), "应读到 platform：$v")
            assertTrue(v.contains("search") && v.contains("getMediaSource"), "导出应含 search/getMediaSource：$v")
            println("I5_AUDIOMACK_INIT=$v")
        } finally {
            session.close()
        }
    }

    @Test
    fun i5_navidromeLoadsAndRequires(): Unit = runBlocking {
        setupRegistry()
        val src = plugin("navidrome")
        val session = MusicFreeSandboxSession("mf-navidrome")
        try {
            val probe = src + "\nJSON.stringify({platform: module.exports.platform, keys: Object.keys(module.exports)});"
            val r = session.execute(probe)
            assertTrue(r is ExecResult.Ok, "navidrome 应能初始化 + require：$r")
            println("I5_NAVIDROME_INIT=" + r.value.orEmpty())
        } finally {
            session.close()
        }
    }

    @Test
    fun i5_sourceShaMatchesLedger(): Unit {
        // 取证正文 SHA 必须与 G2 台账一致（版本漂移即拒绝）
        val expected = mapOf(
            "geciwang" to "b953571441c4d2ef3216d505fdaac2577dad1695e7009382bd4b83f1dd09c4f2",
            "audiomack" to "1b1f49fc1e0aa5933c3b45ce999d975e4f7fc5f69df2a487dd87830555cf01d5",
            "navidrome" to "2f1e930e417a72e2a0c870bde45359c442ad139bcf37bfe444df5141fd0cdeb3",
        )
        expected.forEach { (name, sha) ->
            val actual = java.security.MessageDigest.getInstance("SHA-256")
                .digest(plugin(name).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            assertTrue(actual.equals(sha, ignoreCase = true), "$name SHA 应与台账一致：$actual")
        }
    }
}
