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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 社区候选音源的 **LX 架构兼容性** 验证（阶段 D / D5）。
 *
 * 严格边界：
 * - 只验证 globalThis.lx 事件接口兼容、QuickJS 加载/初始化、必需 Host API、平台声明、
 *   SSRF 白名单行为；**不做真实网络请求、不解析真实播放地址、不下载任何音频**；
 * - 网络全部走 MockEngine，任何真实 URL 都返回占位，绝不连上游；
 * - 本测试通过 = LX_RUNTIME_LOAD / SIMULATED_INIT 级，**不是** REAL_*，不得据此声称可播放。
 */
class CommunitySourceArchitectureTest {

    private val scriptDir = File("src/desktopTest/resources/third-party")

    /** 零真实网络：所有请求返回空 JSON，仅用于证明"脚本没有绕过 MockEngine"。 */
    private val offlineClient: HttpClient
        get() = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })

    private fun script(name: String): String = File(scriptDir, name).readText()

    @Test
    fun `feichangdao loads and initializes with standard lx event interface`() = runBlocking {
        val source = script("feichangdao.js")
        val expectedSha = "49486cab064e64d5ad63723e670c44ee55eadadcc790ccf493351fbde64284d7"
        assertEquals(expectedSha, Sha256.hex(source.encodeToByteArray()), "原文 SHA256 与审查记录一致")

        val client = offlineClient
        val runtime = LxScriptRuntime(client)
        try {
            // allowedHosts 用脚本静态声明的域名；只用于验证初始化，不发真实请求。
            val hosts = setOf(
                "175.27.166.236", "api.chksz.top", "oiapi.net", "music.haitangw.cc",
                "musicapi.haitangw.net", "api.xcvts.cn", "music-api.gdstudio.xyz",
            )
            runtime.load("非常刀", source, allowedHosts = hosts)
            val meta = runtime.initialize()
            assertNotNull(meta, "非常刀应完成 initialize（globalThis.lx inited 事件兼容）")
            assertTrue(meta.platforms.isNotEmpty(), "应声明至少一个平台")
            // 必需 Host API 若缺失，initialize 会抛 LxProtocolException；走到这里即证明齐全。
            summary("feichangdao: OK platforms=${meta.platforms} sha=${expectedSha.take(12)}")
        } finally {
            runtime.close()
            client.close()
        }
    }

    @Test
    fun `yuningxi loads and initializes but empty allowedHosts blocks all network`() = runBlocking {
        val source = script("yuningxi.js")
        val expectedSha = "5dd4752c16c17f7bab45dccf87dcd3f3c2a6e251e62bad68bde9554bfd275f79"
        assertEquals(expectedSha, Sha256.hex(source.encodeToByteArray()), "原文 SHA256 与审查记录一致")

        // 静态域名不可见 → 不提供 allowedHosts；SSRF 策略必须拒绝一切请求（fail-closed）。
        val reason = LxNetworkPolicy.rejectionReason(
            "https://unknown.example.com/music?id=1",
            allowedHosts = emptySet(),
        )
        assertNotNull(reason, "空白名单必须拒绝请求")
        assertTrue(reason.contains("allowedHosts"), "拒绝原因应指向未声明域名")

        val client = offlineClient
        val runtime = LxScriptRuntime(client)
        try {
            runtime.load("玉宁熙", source, allowedHosts = emptySet())
            val meta = runtime.initialize()
            assertNotNull(meta, "玉宁熙应能加载并初始化（事件接口兼容）")
            summary("yuningxi: OK platforms=${meta.platforms} emptyHostsBlocked=true sha=${expectedSha.take(12)}")
        } finally {
            runtime.close()
            client.close()
        }
    }

    @Test
    fun `ssrf policy blocks private ip and bare ip needs explicit allowlist`() = runBlocking {
        assertNotNull(LxNetworkPolicy.rejectionReason("http://127.0.0.1/admin"), "私网地址必须被拒")
        assertNotNull(LxNetworkPolicy.rejectionReason("http://192.168.1.1/"), "局域网地址必须被拒")
        assertNotNull(LxNetworkPolicy.rejectionReason("http://[::1]/"), "IPv6 回环必须被拒")
        // 非常刀的裸 IP 只有在显式声明时才放行——证明不会自动扩大白名单。
        assertNotNull(
            LxNetworkPolicy.rejectionReason("http://175.27.166.236/x", allowedHosts = emptySet()),
            "裸 IP 未声明时必须被拒",
        )
        assertEquals(
            null,
            LxNetworkPolicy.rejectionReason("http://175.27.166.236/x", allowedHosts = setOf("175.27.166.236")),
            "显式声明后才放行",
        )
        summary("ssrf: private/blocked, bare-ip requires explicit allowlist")
    }

    @Test
    fun `blocked and quarantined candidates are never loaded by this harness`() = runBlocking {
        // 长青(BLOCK/SVIP)、Fish/Flower(重度混淆) 保持隔离：本 harness 的资源目录里没有它们的原文，
        // 也不加载 config/source-catalog 的候选。这里断言"隔离状态未被本轮改动解除"。
        val names = scriptDir.list()?.toSet() ?: emptySet()
        assertFalse("changqing.js" in names, "长青不得作为可执行资源进入测试")
        assertFalse("fish.js" in names, "Fish 不得作为可执行资源进入测试")
        assertFalse("flower.js" in names, "Flower 不得作为可执行资源进入测试")
        // 已隔离的野花(yahua)虽在目录里，仅用于既有模拟兼容回归，不作为社区候选执行。
        summary("isolation: changqing/fish/flower NOT loaded; kept quarantined")
    }

    private fun summary(line: String) {
        File("build/community-architecture.txt").apply {
            parentFile.mkdirs()
            appendText(line + System.lineSeparator())
        }
    }
}
