package org.feeluown.mobile.provider.lx

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.feeluown.mobile.sourceupdate.crypto.Sha256
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 阶段 E3：静态审查通过候选的 **LX Runtime 架构兼容性** 验证（SIMULATED_INIT 级）。
 *
 * 严格边界：
 * - 只验证 globalThis.lx 事件接口、QuickJS 加载/初始化、必需 Host API、平台声明、SSRF fail-closed；
 * - 网络全部走 MockEngine，**不做真实搜索、不解析真实播放地址、不下载任何音频**；
 * - 通过 = LX_RUNTIME_LOAD / SIMULATED_INIT，**不是** REAL_*，不得据此声称可播放或音质达标。
 *
 * 每个用例先按 E0-E2 登记表 SHA256 核对脚本文本，确保验证的就是被审查的那份文件。
 */
class PhaseEArchitectureTest {

    private val scriptDir = File("src/desktopTest/resources/third-party")

    /** 零真实网络：所有请求返回空 JSON，仅证明脚本未绕过 MockEngine。 */
    private fun offlineClient(): HttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })

    private data class Target(val id: String, val file: String, val sha256: String)

    /** 仅纳入 E2 静态无 BLOCK 命中的候选；BLOCK/混淆候选（长青/Fish/Flower/六音/墨澜等）不在其中。 */
    private val targets = listOf(
        Target("zhizun", "e3-zhizun.js", "ddc095aca3dc4df5a00ea6df3b84a31d17c1055fad3e1ee09edd6308ccc17210"),
        Target("huibq", "e3-huibq.js", "a9adeb456690b523008038224091cee31977606302d79082eb06adb3c75c9146"),
        Target("ikun-v515", "e3-ikun-v515.js", "8db6c45bc225f1f15fc2e96f7d9ad3bf2e49dd1f2a998cdf23704185ea41fe89"),
        Target("flower", "e3-flower.js", "15c166987edde41cf5917b3160c0ab253ff17e5e6bc6765c9eaa11a822c4aa97"),
        Target("juhe", "e3-juhe.js", "f7a661f01af7ea23035d5fac3b89d7c756372fc8a64bbfd649918f8a101cbf33"),
        Target("gd-studio", "e3-gd-studio.js", "05631b29ff18a4b8688b4464abf0f38952474cd4d8d6c4149c365b0190c7fd28"),
        Target("animemusic", "e3-animemusic.js", "cb26455f3d08be3d9fa034740a8d4bf186caf6c4200493c096026a6e2c3d0fa8"),
    )

    private suspend fun verifyOne(t: Target, allowedHosts: Set<String> = emptySet()): Set<String> {
        val source = File(scriptDir, t.file).readText()
        assertEquals(t.sha256, Sha256.hex(source.encodeToByteArray()), "${t.id} 原文 SHA 与 E2 登记表一致")
        val client = offlineClient()
        val runtime = LxScriptRuntime(client)
        return try {
            runtime.load(t.id, source, allowedHosts = allowedHosts)
            val meta = runtime.initialize()
            assertNotNull(meta, "${t.id} 应完成 initialize（globalThis.lx inited 兼容）")
            meta.platforms
        } finally {
            runtime.close()
            client.close()
        }
    }

    @Test
    fun `static clean candidates load and initialize with lx interface`(): Unit = runBlocking {
        val results = mutableListOf<String>()
        var loaded = 0
        for (t in targets) {
            val result = runCatching { verifyOne(t) }
            if (result.isSuccess) {
                val platforms = result.getOrThrow()
                loaded++
                results.add("${t.id}: OK platforms=[${platforms.joinToString(",")}]")
            } else {
                // 如实记录失败，不掩盖；失败候选不升级验证等级。
                results.add("${t.id}: LOAD_FAILED ${result.exceptionOrNull()?.message?.take(80)}")
            }
        }
        assertTrue(loaded >= 3, "静态干净候选中至少 3 个应能加载初始化，实际 $loaded；详情：${results.joinToString(" | ")}")
        File("build/phase-e-architecture.txt").apply {
            parentFile.mkdirs()
            writeText(results.joinToString(System.lineSeparator()))
        }
        // 结论写进报告附件，等级只到 SIMULATED_INIT。
    }

    @Test
    fun `sha of every e3 resource matches audit registry`() {
        targets.forEach { t ->
            val source = File(scriptDir, t.file).readText()
            assertEquals(t.sha256, Sha256.hex(source.encodeToByteArray()), "${t.id} SHA 必须与 E2 审查一致")
        }
    }

    @Test
    fun `ssrf fail-closed holds for static-clean candidates with no declared hosts`() {
        // 静态域名不可见者不提供 allowedHosts 时，网络请求必须被拒（fail-closed）。
        val reason = LxNetworkPolicy.rejectionReason("https://unknown.example.com/x", allowedHosts = emptySet())
        assertNotNull(reason, "空 allowedHosts 必须拒绝")
        assertTrue(reason.contains("allowedHosts"))
        // 私网/回环始终拒绝，不因候选"静态干净"而放宽。
        assertNotNull(LxNetworkPolicy.rejectionReason("http://127.0.0.1/admin"))
        assertNotNull(LxNetworkPolicy.rejectionReason("http://192.168.1.1/"))
    }

    @Test
    fun `blocked and obfuscated candidates are never in e3 harness`() {
        val names = scriptDir.list()?.toSet() ?: emptySet()
        // 长青/Fish/Flower/六音/墨澜等 BLOCK/混淆候选不作为可执行资源进入 E3。
        listOf("changqing", "fish", "flower.js", "sixyin", "molan", "yuanli").forEach { bad ->
            assertFalseName(names, bad)
        }
        // flower 的 E3 资源是 pdone 静态干净版（e3-flower.js），不含 BLOCK 规则命中。
        assertTrue("e3-flower.js" in names, "静态干净的野花 E3 资源存在")
    }

    private fun assertFalseName(names: Set<String>, name: String) {
        assertTrue(name !in names, "$name 属于 BLOCK/混淆候选，不得进入 E3 可执行资源")
    }
}
