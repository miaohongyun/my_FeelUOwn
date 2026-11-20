package org.feeluown.mobile.provider.lx

import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阶段 F：候选级网络权限测试（离线，纯逻辑）。
 * 覆盖：隔离/SHA 绑定失效/未授权域名/私网 localhost/重定向/超时/取消/体积/并发。
 */
class CandidateNetworkPolicyTest {

    private val gd = CandidateNetworkPolicyRegistry.gdStudio
    private val huibq = CandidateNetworkPolicyRegistry.huibq
    private val zhizun = CandidateNetworkPolicyRegistry.zhizunDenied

    @Test
    fun candidateHostIsolation_noInheritance() {
        assertNull(gd.rejectionReason("https://music-api.gdstudio.xyz/api.php?types=search"))
        // gd 的域名不能用于 huibq，反之亦然（不存在继承）。
        assertNotNull(huibq.rejectionReason("https://music-api.gdstudio.xyz/api.php"))
        assertNotNull(gd.rejectionReason("https://lxmusicapi.onrender.com/url/wy/x/128k"))
    }

    @Test
    fun shaChangeInvalidatesApproval() {
        assertNull(CandidateNetworkPolicyRegistry.validateAgainstRegistry(gd))
        val tampered = gd.copy(boundSha256 = "0".repeat(64))
        val reason = CandidateNetworkPolicyRegistry.validateAgainstRegistry(tampered)
        assertNotNull(reason, "SHA 变化后审批必须失效")
        assertTrue(reason.contains("SHA"))
        // 未登记的候选 id 同样失效。
        assertNotNull(CandidateNetworkPolicyRegistry.validateAgainstRegistry(gd.copy(candidateId = "ghost")))
    }

    @Test
    fun deniedCandidatesRejectEverything() {
        assertTrue(zhizun.isDenyAll)
        assertNotNull(zhizun.rejectionReason("http://110.42.36.53:1314/url/wy/x/128k"))
        assertNotNull(zhizun.rejectionReason("https://example.com/"))
        assertNotNull(CandidateNetworkPolicyRegistry.flowerDenied.rejectionReason("http://97.64.37.235/"))
    }

    @Test
    fun ssrf_wildcardAndUnofficialPortsRejected() {
        assertNotNull(gd.rejectionReason("http://music-api.gdstudio.xyz/api.php"), "默认仅 HTTPS")
        assertNotNull(gd.rejectionReason("https://music-api.gdstudio.xyz:8443/api.php"), "仅 443")
        assertNotNull(gd.rejectionReason("https://127.0.0.1/admin"))
        assertNotNull(gd.rejectionReason("https://192.168.1.1/"))
        assertNotNull(gd.rejectionReason("https://169.254.169.254/latest/meta-data/"))
        assertNotNull(gd.rejectionReason("http://localhost/"))
        assertNotNull(gd.rejectionReason("https://user:pw@music-api.gdstudio.xyz/"), "userinfo 拒绝")
        assertNotNull(gd.rejectionReason("https://music-api.gdstudio.xyz./"), "尾随点拒绝")
        assertNotNull(gd.rejectionReason("https://%6dusic-api.gdstudio.xyz/"), "编码 host 拒绝")
    }

    @Test
    fun wildcardPolicyCannotBeConstructed() {
        val err = runCatching {
            CandidateNetworkPolicy(
                candidateId = "x", boundSha256 = "a".repeat(64),
                allowedHosts = setOf("*.example.com"), authorizationBasis = "test",
            )
        }.exceptionOrNull()
        assertNotNull(err, "通配符白名单禁止构造")
    }

    @Test
    fun redirectPolicyDefaultsToDenyAll() {
        assertEquals(CandidateRedirectPolicy.DENY_ALL, gd.redirectPolicy)
        assertEquals(CandidateCredentialPolicy.NONE, gd.credentialPolicy)
    }

    @Test
    fun timeoutAndSizeLimitsAreBounded() {
        assertTrue(gd.maxResponseBytes in 1..(4 * 1024 * 1024))
        assertTrue(gd.connectTimeoutMs in 1..30_000)
        assertTrue(gd.requestTimeoutMs in 1..30_000)
    }

    @Test
    fun evaluateIsPureUnderConcurrency(): Unit = runBlocking {
        // rejectionReason 只读 policy 不可变字段，无共享可变状态，可跨协程并发调用。
        val url = "https://music-api.gdstudio.xyz/api.php?types=url&id=1"
        val results = coroutineScope {
            (1..64).map {
                async(kotlinx.coroutines.Dispatchers.Default) { gd.rejectionReason(url) }
            }.map { it.await() }
        }
        assertEquals(64, results.size)
        results.forEach { reason -> assertNull(reason) }
    }

    @Test
    fun runtimeLoadWithPolicyEnforcesShaAndHosts(): Unit = kotlinx.coroutines.runBlocking {
        val source = "console.log('x')"
        val sha = org.feeluown.mobile.sourceupdate.crypto.Sha256.hex(source.encodeToByteArray())
        val policy = gd.copy(boundSha256 = sha)
        // 不在登记表里的自定义 policy：登记校验失败（SHA 与登记不一致），不会加载。
        val runtime = LxScriptRuntime(io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine {
            respond("{}", io.ktor.http.HttpStatusCode.OK)
        }))
        val err = runCatching { runtime.loadWithCandidatePolicy("gd-studio", source, policy) }.exceptionOrNull()
        assertNotNull(err, "SHA 与登记表不一致必须拒绝加载")
        runtime.close()
    }
}
