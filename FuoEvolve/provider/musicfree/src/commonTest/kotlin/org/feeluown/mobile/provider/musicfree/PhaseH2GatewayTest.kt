package org.feeluown.mobile.provider.musicfree

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.provider.lx.CandidateNetworkPolicy
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.GatewayResult
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.ProviderNetworkPermission
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 阶段 H2：ProviderNetworkGateway 网络代理与默认拒绝测试（假执行器，零真实网络）。 */
class PhaseH2GatewayTest {

    private val sha = "b".repeat(64)
    private val policy = CandidateNetworkPolicy(
        candidateId = "mf-audiomack",
        boundSha256 = sha,
        boundVersion = "1.0",
        allowedHosts = setOf("api.audiomack.com"),
        authorizationBasis = "H2 测试：固定具名域名",
    )
    private val permission = ProviderNetworkPermission(
        providerId = "mf-audiomack",
        providerVersion = "1.0",
        scriptSha256 = sha,
        candidatePolicy = policy,
        authorizationBasis = "H2 测试授权",
    )

    private fun gateway(calls: MutableList<Request> = mutableListOf()) =
        gateway(calls) { req ->
            calls += req
            ProviderNetworkGateway.Response(200, """{"ok":true}""")
        }

    private fun gateway(
        calls: MutableList<Request>,
        exec: suspend (Request) -> ProviderNetworkGateway.Response,
    ) = ProviderNetworkGateway.fromStringExecutor(exec)

    @Test
    fun h2_authorizedRequestPasses(): Unit = runTest {
        val calls = mutableListOf<Request>()
        val r = gateway(calls).get(permission, Request("https://api.audiomack.com/v1/search?q=x"), sha)
        assertTrue(r is GatewayResult.Ok)
        assertEquals(1, calls.size)
    }

    @Test
    fun h2_unauthorizedHostDeniedBeforeNetwork(): Unit = runTest {
        val calls = mutableListOf<Request>()
        val r = gateway(calls).get(permission, Request("https://evil.example.com/x"), sha)
        assertTrue(r is GatewayResult.Denied)
        assertEquals(0, calls.size, "被拒请求不得触达 executor（零网络）")
    }

    @Test
    fun h2_defaultDenyPlaintextPrivateIpBareIpAndWildcard(): Unit = runTest {
        val calls = mutableListOf<Request>()
        val g = gateway(calls)
        assertTrue(g.get(permission, Request("http://api.audiomack.com/x"), sha) is GatewayResult.Denied)
        assertTrue(g.get(permission, Request("https://127.0.0.1/x"), sha) is GatewayResult.Denied)
        assertTrue(g.get(permission, Request("https://192.168.1.1/x"), sha) is GatewayResult.Denied)
        assertTrue(g.get(permission, Request("https://169.254.169.254/x"), sha) is GatewayResult.Denied) // metadata
        assertTrue(g.get(permission, Request("https://110.42.36.53/x"), sha) is GatewayResult.Denied) // 裸IP
        assertTrue(g.get(permission, Request("//api.audiomack.com/x"), sha) is GatewayResult.Denied)
        assertEquals(0, calls.size)
        // 通配符域名在 CandidateNetworkPolicy 构造期即被拒
        var wildcardRejected = false
        try {
            CandidateNetworkPolicy("x", sha, allowedHosts = setOf("*.audiomack.com"), authorizationBasis = "t")
        } catch (_: IllegalArgumentException) {
            wildcardRejected = true
        }
        assertTrue(wildcardRejected)
    }

    @Test
    fun h2_credentialHeadersDenied(): Unit = runTest {
        val g = gateway()
        assertTrue(g.get(permission, Request("https://api.audiomack.com/x", headers = mapOf("Cookie" to "a=b")), sha) is GatewayResult.Denied)
        assertTrue(g.get(permission, Request("https://api.audiomack.com/x", headers = mapOf("Authorization" to "Bearer x")), sha) is GatewayResult.Denied)
        assertTrue(g.get(permission, Request("https://api.audiomack.com@evil.com/x"), sha) is GatewayResult.Denied) // userinfo
    }

    @Test
    fun h2_shaChangeInvalidatesPermission(): Unit = runTest {
        val calls = mutableListOf<Request>()
        val g = gateway(calls)
        val r = g.get(permission, Request("https://api.audiomack.com/x"), "c".repeat(64))
        assertTrue(r is GatewayResult.Denied)
        assertTrue(r.reason.contains("SHA"))
        assertEquals(0, calls.size)
    }

    @Test
    fun h2_redirectDenyAllAndRevalidate(): Unit = runTest {
        // J0：重定向控制内聚到 get()，底层 executor 返回 3xx 原样上交（不跟随）。
        val calls = mutableListOf<Request>()
        val denyAllG = gateway(calls) { req ->
            calls += req
            ProviderNetworkGateway.Response(302, "", mapOf("Location" to "https://evil.example.com/x"))
        }
        val denyAll = denyAllG.get(permission, Request("https://api.audiomack.com/start"), sha)
        assertTrue(denyAll is GatewayResult.Denied, "DENY_ALL 应拒绝 3xx：$denyAll")
        assertTrue(denyAll.reason.contains("DENY_ALL"))
        assertEquals(1, calls.size, "只请求首跳，绝不跟随到 evil 域名")

        val revalidate = permission.copy(
            redirectPolicy = ProviderNetworkGateway.RedirectPolicy.REVALIDATE_EACH_HOP,
            maxRedirects = 2,
        )
        // 同域跳转：逐跳校验后跟随，第二跳返回 200。
        val hostCalls = mutableListOf<Request>()
        val followG = gateway(hostCalls) { req ->
            hostCalls += req
            if (req.url.endsWith("/start")) ProviderNetworkGateway.Response(302, "", mapOf("Location" to "/next"))
            else ProviderNetworkGateway.Response(200, """{"ok":true}""")
        }
        val followed = followG.get(revalidate, Request("https://api.audiomack.com/start"), sha)
        assertTrue(followed is GatewayResult.Ok, "同域逐跳跟随应成功：$followed")
        assertEquals(2, hostCalls.size)
        assertTrue(hostCalls[1].url == "https://api.audiomack.com/next")
    }

    @Test
    fun h2_responseBodyLimitEnforced(): Unit = runTest {
        val small = CandidateNetworkPolicy(
            candidateId = "mf-audiomack", boundSha256 = sha, allowedHosts = setOf("api.audiomack.com"),
            maxResponseBytes = 4, authorizationBasis = "t",
        )
        val perm = permission.copy(candidatePolicy = small)
        val g = ProviderNetworkGateway.fromStringExecutor { ProviderNetworkGateway.Response(200, "x".repeat(100)) }
        val r = g.get(perm, Request("https://api.audiomack.com/x"), sha)
        assertTrue(r is GatewayResult.Denied)
        assertTrue(r.reason.contains("响应体"))
    }

    @Test
    fun h2_cancellationPropagates(): Unit = runTest {
        val g = ProviderNetworkGateway.fromStringExecutor { throw CancellationException("cancelled") }
        try {
            g.get(permission, Request("https://api.audiomack.com/x"), sha)
            assertTrue(false, "取消应上抛")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    @Test
    fun h2_noAlternateNetworkApiInSandbox(): Unit = runTest {
        // 沙箱宿主能力只有 md5；fetch/XHR/WebSocket/process/fs 均无 binding（静态契约检查）。
        val abilities = MusicFreeSandbox.HostAbility.entries.map { it.jsName }
        assertTrue(abilities.none { it.contains("fetch", true) || it.contains("http", true) })
        assertEquals(3, abilities.size)
    }
}
