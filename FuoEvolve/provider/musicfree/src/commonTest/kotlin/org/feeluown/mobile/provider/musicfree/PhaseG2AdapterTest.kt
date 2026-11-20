package org.feeluown.mobile.provider.musicfree

import org.feeluown.mobile.provider.musicfree.MusicFreeAdapter.AdaptResult
import org.feeluown.mobile.provider.musicfree.MusicFreePluginPolicy.PolicyDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 阶段 G2 适配层测试：门禁、SHA 绑定、SSRF/域名、协议映射、等级诚实。 */
class PhaseG2AdapterTest {

    private val audiomack = MusicFreePluginRegistry.find("mf-audiomack")!!
    private val navidrome = MusicFreePluginRegistry.find("mf-navidrome")!!
    private val bilibili = MusicFreePluginRegistry.find("mf-bilibili")!!

    @Test
    fun g2_manifestAndRegistryIntegrity(): Unit {
        assertEquals(12, MusicFreePluginRegistry.all.size)
        assertEquals(64, MusicFreePluginRegistry.OFFICIAL_MANIFEST_SHA256.length)
        assertTrue(MusicFreePluginRegistry.all.all { it.license == "GPL-3.0" })
        assertTrue(MusicFreePluginRegistry.all.all { it.sha256.length == 64 })
        assertTrue(MusicFreePluginRegistry.all.all { it.origin == MusicFreeOrigin.OFFICIAL })
        // 唯一带 cookie 命中的是 bilibili，且标注为匿名访客标识，不是会员凭据
        assertTrue(bilibili.staticFindings.containsKey("anonymous_visitor_cookie"))
    }

    @Test
    fun g2_fixedHostPluginAuthorizedWithSha(): Unit {
        val decision = MusicFreePluginPolicy.forPlugin(audiomack, audiomack.sha256)
        assertTrue(decision is PolicyDecision.Authorized)
        val policy = decision.policy
        assertEquals(setOf("api.audiomack.com"), policy.allowedHosts)
        assertEquals("1.0", policy.boundVersion)
    }

    @Test
    fun g2_shaChangeInvalidatesAuthorization(): Unit {
        val decision = MusicFreePluginPolicy.forPlugin(audiomack, "0".repeat(64))
        assertTrue(decision is PolicyDecision.Denied)
        assertTrue(decision.reason.contains("SHA"))
    }

    @Test
    fun g2_selfHostedAndDynamicEndpointDeniedNotRelaxed(): Unit {
        // Navidrome（用户自建）与 YouTube（动态端点）无静态域名 → deny-all，不放宽 SSRF
        val nav = MusicFreePluginPolicy.forPlugin(navidrome, navidrome.sha256)
        assertTrue(nav is PolicyDecision.Denied)
        assertTrue(nav.reason.contains("不放宽 SSRF"))
        val youtube = MusicFreePluginRegistry.find("mf-youtube")!!
        val yt = MusicFreePluginPolicy.forPlugin(youtube, youtube.sha256)
        assertTrue(yt is PolicyDecision.Denied)
    }

    @Test
    fun g2_urlPolicyRejectsUnauthorizedHostPrivateIpAndHttp(): Unit {
        val decision = MusicFreePluginPolicy.forPlugin(audiomack, audiomack.sha256) as PolicyDecision.Authorized
        assertTrue(MusicFreePluginPolicy.rejectionReason(decision, "https://api.audiomack.com/x") == null)
        assertTrue(MusicFreePluginPolicy.rejectionReason(decision, "https://evil.example.com/x") != null)
        assertTrue(MusicFreePluginPolicy.rejectionReason(decision, "http://api.audiomack.com/x") != null) // 明文
        assertTrue(MusicFreePluginPolicy.rejectionReason(decision, "https://127.0.0.1/x") != null) // 私网
        assertTrue(MusicFreePluginPolicy.rejectionReason(decision, "https://192.168.1.1/x") != null)
        assertTrue(MusicFreePluginPolicy.rejectionReason(decision, "https://api.audiomack.com@evil.com/x") != null)
    }

    @Test
    fun g2_searchParseAndMap(): Unit {
        val decision = MusicFreePluginPolicy.forPlugin(audiomack, audiomack.sha256)
        val json = """{"isEnd":true,"data":[{"id":"s1","title":"晴天","artist":"周杰伦","album":"叶惠美","artwork":"x"}]}"""
        val result = MusicFreeAdapter.adaptSearch(audiomack, decision, json, MusicFreeSearchType.MUSIC)
        assertTrue(result is AdaptResult.Mapped)
        val mapped = result.value
        assertEquals(1, mapped.size)
        assertEquals("晴天", mapped[0].title)
        assertEquals("周杰伦", mapped[0].artist)
        assertTrue(result.level.contains("未做真实搜索"))
    }

    @Test
    fun g2_mediaSourceMustBeHttpsAndAllowedHost(): Unit {
        val decision = MusicFreePluginPolicy.forPlugin(audiomack, audiomack.sha256)
        val okJson = """{"url":"https://api.audiomack.com/stream/aac","quality":"high","headers":{"Referer":"https://audiomack.com"}}"""
        val ok = MusicFreeAdapter.adaptMediaSource(audiomack, decision, okJson, "晴天", "周杰伦", "叶惠美")
        assertTrue(ok is AdaptResult.Mapped)
        assertEquals("high", ok.value.audioQuality)
        assertEquals("musicfree-adapter", ok.value.source)

        val httpJson = """{"url":"http://api.audiomack.com/stream"}"""
        val bad = MusicFreeAdapter.adaptMediaSource(audiomack, decision, httpJson, "t", "a", "b")
        assertTrue(bad is AdaptResult.Denied) // 明文被 parse 拒绝

        val evilJson = """{"url":"https://evil.example.com/stream"}"""
        val evil = MusicFreeAdapter.adaptMediaSource(audiomack, decision, evilJson, "t", "a", "b")
        assertTrue(evil is AdaptResult.Denied)
    }

    @Test
    fun g2_capabilityGateAndDeniedDecision(): Unit {
        // suno 无 search 能力
        val suno = MusicFreePluginRegistry.find("mf-suno")!!
        val sunoDecision = MusicFreePluginPolicy.forPlugin(suno, suno.sha256)
        val noSearch = MusicFreeAdapter.adaptSearch(suno, sunoDecision, """{"data":[]}""", MusicFreeSearchType.MUSIC)
        assertTrue(noSearch is AdaptResult.Denied)
        // deny-all 决策下即便 JSON 正常也拒绝
        val denied = MusicFreePluginPolicy.forPlugin(navidrome, navidrome.sha256)
        val blocked = MusicFreeAdapter.adaptSearch(navidrome, denied, """{"data":[]}""", MusicFreeSearchType.MUSIC)
        assertTrue(blocked is AdaptResult.Denied)
    }

    @Test
    fun g2_parseFailuresDoNotFabricate(): Unit {
        val bad = parseMusicFreeSearch("not json", MusicFreeSearchType.MUSIC)
        assertTrue(bad.isFailure)
        val noData = parseMusicFreeSearch("""{"isEnd":false}""", MusicFreeSearchType.MUSIC)
        assertTrue(noData.isFailure)
        val badUrl = parseMusicFreeMediaSource("""{"url":""}""")
        assertTrue(badUrl.isFailure)
    }

    @Test
    fun g2_lyricMapping(): Unit {
        val result = MusicFreeAdapter.adaptLyric("""{"lrc":"[00:00]测试","rawLrc":"raw"}""")
        assertTrue(result is AdaptResult.Mapped)
        assertEquals("[00:00]测试", result.value.lrc)
    }
}
