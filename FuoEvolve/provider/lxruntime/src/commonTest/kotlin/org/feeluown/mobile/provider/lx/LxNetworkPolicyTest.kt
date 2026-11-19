package org.feeluown.mobile.provider.lx

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 出站白名单的边界测试。
 *
 * 覆盖两类历史缺陷：
 * 1. 空 allowedHosts 曾经放行一切请求（与目录解析器的提示语义相反）；
 * 2. 非标准 IPv4 表示（hex / 整数 / 短形式 / 八进制）曾经绕过字符串私网判断。
 */
class LxNetworkPolicyTest {

    private val allowApi = setOf("api.example.com")

    @Test
    fun emptyAllowlistRejectsEverything() {
        assertNotNull(
            LxNetworkPolicy.rejectionReason("https://api.example.com/url", emptySet()),
            "空白名单必须拒绝一切网络请求",
        )
        assertNotNull(LxNetworkPolicy.rejectionReason("https://音乐.example.com/x", emptySet()))
    }

    @Test
    fun allowlistedHttpsHostIsAccepted() {
        assertNull(LxNetworkPolicy.rejectionReason("https://api.example.com/v1/url", allowApi))
    }

    @Test
    fun wildcardsAndPlaintextHttpAreAllowed() {
        val wildcard = setOf("*.example.com")
        assertNull(LxNetworkPolicy.rejectionReason("http://cdn.example.com/a.flac", wildcard))
        assertNull(LxNetworkPolicy.rejectionReason("https://example.com/a.flac", wildcard))
        assertNotNull(LxNetworkPolicy.rejectionReason("https://example.org/a", wildcard))
        // 白名单前缀不能误当成任意后缀（evil-example.com 不得匹配 *.example.com）
        assertNotNull(LxNetworkPolicy.rejectionReason("https://evilexample.com/a", wildcard))
    }

    @Test
    fun rejectsLocalhostAndIpv6Literals() {
        for (url in listOf(
            "http://localhost/admin",
            "http://foo.localhost/x",
            "http://[::1]/admin",
            "http://[fe80::1]/admin",
            "http://[::ffff:127.0.0.1]/admin",
        )) {
            assertNotNull(LxNetworkPolicy.rejectionReason(url, allowApi + "localhost" + "[::1]"), url)
        }
    }

    @Test
    fun rejectsStandardPrivateRanges() {
        for (host in listOf(
            "127.0.0.1", "10.1.2.3", "192.168.1.1", "172.16.0.1", "172.31.255.255",
            "169.254.169.254", "100.64.0.1", "224.0.0.1", "0.0.0.0", "255.255.255.255",
        )) {
            assertNotNull(
                LxNetworkPolicy.rejectionReason("http://$host/x", allowApi + host),
                "私网/保留地址 $host 必须被拒绝",
            )
        }
    }

    @Test
    fun rejectsNonStandardIpv4NotationsOfLoopback() {
        // 这些写法在浏览器/内核里都会解析成 127.0.0.1，字符串比较挡不住，必须归一化后再判。
        for (host in listOf(
            "0x7f000001", "2130706433", "127.1", "0177.0.0.1", "0x7f.0x0.0x0.0x1",
        )) {
            assertNotNull(
                LxNetworkPolicy.rejectionReason("http://$host/x", allowApi + host),
                "非标准回环写法 $host 必须被拒绝",
            )
        }
    }

    @Test
    fun acceptsPublicIpWhenExplicitlyAllowlisted() {
        // 公网 IP 只要在目录里显式声明，就允许直连（有些国内接口就是 IP 直连）。
        assertNull(LxNetworkPolicy.rejectionReason("http://8.8.8.8/dns", setOf("8.8.8.8")))
    }

    @Test
    fun rejectsNonHttpSchemesAndCredentialsInUrl() {
        assertNotNull(LxNetworkPolicy.rejectionReason("file:///etc/passwd", allowApi))
        assertNotNull(LxNetworkPolicy.rejectionReason("ftp://api.example.com/x", allowApi))
        // userinfo 里的 host 不能伪装白名单：@ 之后才是真主机
        val reason = LxNetworkPolicy.rejectionReason("https://api.example.com@evil.example.net/x", allowApi)
        assertTrue(reason != null && reason.contains("未在目录声明"), "实际: $reason")
    }
}
