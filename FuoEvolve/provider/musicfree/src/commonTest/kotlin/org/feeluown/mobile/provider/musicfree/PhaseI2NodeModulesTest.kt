package org.feeluown.mobile.provider.musicfree

import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.ExecResult
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.ModuleRegistry
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.RegisteredModule
import org.feeluown.mobile.sourceupdate.crypto.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 阶段 I2：Node 模块安全子集单元测试 + 真实调用向量。
 * 每个模块先登记到 allowlist（SHA 绑定），再在真实 QuickJS 沙箱内 require 并调用。
 */
class PhaseI2NodeModulesTest {

    private fun registerAll() {
        ModuleRegistry.clear()
        NodeCompatShims.all.forEach { (name, src) ->
            ModuleRegistry.register(
                RegisteredModule(
                    name = name,
                    source = src,
                    sha256 = Sha256.hex(src.encodeToByteArray()),
                    auditedAt = "2026-11-20",
                    note = "I2 安全子集：$name",
                    allowFunctionExports = true, // Node 兼容模块导出函数，走 JS 真实对象通道
                ),
            )
        }
    }

    private suspend fun eval(session: MusicFreeSandboxSession, script: String): String {
        val r = session.execute(script)
        assertTrue(r is ExecResult.Ok, "脚本应成功，实际=$r")
        return r.value.orEmpty()
    }

    @Test
    fun i2_specsCoverRealPluginRequires(): Unit {
        val required = NodeCompatModules.requiredModuleNames()
        assertTrue(required.containsAll(setOf("axios", "cheerio", "crypto-js", "dayjs")))
        // 每个已登记 shim 模块都有 supported/unsupported/security 说明
        NodeCompatModules.specs.forEach { spec ->
            assertTrue(spec.supported.isNotBlank() && spec.unsupported.isNotBlank() && spec.security.isNotBlank())
        }
        // bilibili 取证 sha 漂移，不纳入需求矩阵
        assertTrue(NodeCompatModules.auditedPluginRequires.none { it.pluginId == "mf-bilibili" })
    }

    @Test
    fun i2_cryptoMd5KnownVector(): Unit = runTest {
        registerAll()
        val s = MusicFreeSandboxSession("crypto-md5")
        try {
            // "abc" 的 MD5 = 900150983cd24fb0d6963f7d28e17f72
            val out = eval(s, "var C=require('crypto-js'); C.MD5('abc').toString(C.enc.Hex)")
            assertEquals("900150983cd24fb0d6963f7d28e17f72", out)
        } finally { s.close() }
    }

    @Test
    fun i2_cryptoHmacSha1Rfc2202Vector(): Unit = runTest {
        registerAll()
        val s = MusicFreeSandboxSession("crypto-hmac")
        try {
            // RFC 2202: key="key", data="The quick brown fox jumps over the lazy dog"
            // HMAC-SHA1 = de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9（Python hmac 实算核对）
            val out = eval(
                s,
                "var C=require('crypto-js'); C.HmacSHA1('The quick brown fox jumps over the lazy dog','key').toString(C.enc.Hex)",
            )
            assertEquals("de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9", out)
        } finally { s.close() }
    }

    @Test
    fun i2_cryptoHmacSha1Base64MatchesPython(): Unit = runTest {
        registerAll()
        val s = MusicFreeSandboxSession("crypto-hmac-b64")
        try {
            // 与 audiomack OAuth1 相同算法：HmacSHA1("hello","secret&") 的 Base64
            // Python 实算 = 3UdwPYFenLcwWyKXpH85mLmXAMw=
            val out = eval(s, "var C=require('crypto-js'); C.HmacSHA1('hello','secret&').toString(C.enc.Base64)")
            assertEquals("3UdwPYFenLcwWyKXpH85mLmXAMw=", out)
        } finally { s.close() }
    }

    @Test
    fun i2_querystringUrlPathUtil(): Unit = runTest {
        registerAll()
        val s = MusicFreeSandboxSession("str-modules")
        try {
            val qs = eval(s, "JSON.stringify(require('querystring').parse('a=1&b=hello%20world'))")
            assertTrue(qs.contains("\"a\":\"1\"") && qs.contains("hello world"), qs)
            val url = eval(s, "require('url').parse('https://api.audiomack.com/v1/search?q=x').host")
            assertEquals("api.audiomack.com", url)
            val usp = eval(s, "new (require('url').URLSearchParams)('q=晴天').get('q')")
            assertEquals("晴天", usp)
            val path = eval(s, "require('path').basename('/a/b/song.mp3')")
            assertEquals("song.mp3", path)
            val fmt = eval(s, "require('util').format('id=%d name=%s', 42, '晴天')")
            assertEquals("id=42 name=晴天", fmt)
        } finally { s.close() }
    }

    @Test
    fun i2_eventsAndHe(): Unit = runTest {
        registerAll()
        val s = MusicFreeSandboxSession("events-he")
        try {
            val ev = eval(
                s,
                "var E=require('events').EventEmitter; var e=new E(); var got=''; e.on('x',function(v){got=v;}); e.emit('x','ok'); got",
            )
            assertEquals("ok", ev)
            val dec = eval(s, "require('he').decode('晴天 &amp; 周杰伦 &lt;3')")
            assertEquals("晴天 & 周杰伦 <3", dec)
        } finally { s.close() }
    }

    @Test
    fun i2_dayjsUnixFormatUtc(): Unit = runTest {
        registerAll()
        val s = MusicFreeSandboxSession("dayjs")
        try {
            // 2020-01-02 00:00:00 UTC = 1577923200
            val out = eval(s, "require('dayjs').unix(1577923200).format('YYYY-MM-DD')")
            assertEquals("2020-01-02", out)
        } finally { s.close() }
    }

    @Test
    fun i2_bufferRoundTrip(): Unit = runTest {
        registerAll()
        val s = MusicFreeSandboxSession("buffer")
        try {
            val out = eval(s, "var B=require('buffer'); B.from('晴天','utf8').toString('base64')")
            assertTrue(out.isNotBlank() && out.length % 4 == 0, out)
        } finally { s.close() }
    }

    @Test
    fun i2_cheerioReadOnlyTextExtraction(): Unit = runTest {
        registerAll()
        val s = MusicFreeSandboxSession("cheerio")
        try {
            val out = eval(
                s,
                "var \$=require('cheerio').load('<html><div class=\"t\">晴天 - 周杰伦</div></html>'); \$('div').text()",
            )
            assertTrue(out.contains("晴天"), out)
        } finally { s.close() }
    }

    @Test
    fun i2_unlistedNodeModuleStillDenied(): Unit = runTest {
        registerAll()
        val s = MusicFreeSandboxSession("deny")
        try {
            for (mod in listOf("fs", "child_process", "net", "vm", "process")) {
                val r = s.execute("require('$mod')")
                assertTrue(r is ExecResult.Failed, "$mod 必须被拒绝：$r")
            }
        } finally { s.close() }
    }
}
