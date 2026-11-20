package org.feeluown.mobile.provider.musicfree

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 阶段 J2 单元测试（STATIC_REVIEW / COMMONJS / 映射，非真实网络）。
 * 真实网络验证见 PhaseJ2RealSearchTest（desktopTest/androidHostTest）。
 */
class PhaseJ2ProviderUnitTest {

    @Test
    fun j2_embeddedScriptBytesMatchAuditedSha() {
        // 嵌入脚本字节必须与 G2/G3 审查版本逐字节一致，否则拒绝接入。
        val sha = MusicFreePluginCompatVerification.shaOfScriptBytes(AudiomackEmbeddedScript.scriptBytes)
        assertEquals("1b1f49fc1e0aa5933c3b45ce999d975e4f7fc5f69df2a487dd87830555cf01d5", sha)
        assertEquals(15789, AudiomackEmbeddedScript.scriptBytes.size)
        val record = MusicFreePluginRegistry.find(AudiomackMusicProvider.ID)
        assertNotNull(record)
        assertEquals(sha, record.sha256, "台账 SHA 必须与嵌入脚本一致")
        assertTrue(record.declaredHosts == setOf("api.audiomack.com"), "搜索域名必须是精确授权域名")
        assertTrue("music.audiomack.com" !in record.declaredHosts, "播放域名不得被顺手加入")
    }

    @Test
    fun j2_pluginPolicyAuthorizesSearchDomainOnly() {
        val record = MusicFreePluginRegistry.find(AudiomackMusicProvider.ID)!!
        val decision = MusicFreePluginPolicy.forPlugin(record, record.sha256)
        assertTrue(decision is MusicFreePluginPolicy.PolicyDecision.Authorized)
        val policy = decision.policy
        assertEquals(setOf("api.audiomack.com"), policy.allowedHosts)
        // 搜索域名放行、播放域名拒绝、明文 HTTP 拒绝、裸 IP 拒绝。
        assertEquals(null, policy.rejectionReason("https://api.audiomack.com/v1/search"))
        assertTrue(policy.rejectionReason("https://music.audiomack.com/x") != null)
        assertTrue(policy.rejectionReason("http://api.audiomack.com/x") != null)
        assertTrue(policy.rejectionReason("https://127.0.0.1/x") != null)
    }

    @Test
    fun j2_jsStringLiteralEscapesSpecialCharacters() {
        assertEquals("\"晴天\"", jsStringLiteral("晴天"))
        assertEquals("\"a\\\"b\\\\c\\nd\"", jsStringLiteral("a\"b\\c\nd"))
        // 注入保护：双引号必须被转义，不能逃逸出 JS 字符串字面量（单引号在双引号串内无害）。
        val injected = jsStringLiteral("x\"); throw new Error(\"pwn")
        assertEquals("\"x\\\"); throw new Error(\\\"pwn\"", injected)
        assertTrue(injected.count { it == '"' } == 4, "原始两个双引号 + 首尾包裹双引号共 4 个：$injected")
    }

    @Test
    fun j2_parseRealSearchJsonShapeAndMappingFields(): Unit = runTest {
        // 用 G3 记录形状的最小 JSON 验证字段解析（这是解析单元测试，非真实搜索）。
        val json = """{"isEnd":true,"data":[{"id":"10921920","title":"晴天","artist":"周杰伦","album":"叶惠美","coverImg":"https://x/c.jpg","duration":263000}]}"""
        val parsed = parseMusicFreeSearch(json, MusicFreeSearchType.MUSIC)
        assertTrue(parsed.isSuccess)
        val result = parsed.getOrThrow()
        assertEquals(1, result.items.size)
        val item = result.items.first()
        assertEquals("10921920", item.id)
        assertEquals("晴天", item.title)
        assertEquals("周杰伦", item.artist)
        assertEquals("叶惠美", item.album)
        assertEquals("https://x/c.jpg", item.artwork)
        assertEquals(263000L, item.durationMs)
    }

    @Test
    fun j2_parseRejectsInvalidSearchJson() {
        assertTrue(parseMusicFreeSearch("not-json", MusicFreeSearchType.MUSIC).isFailure)
        assertTrue(parseMusicFreeSearch("{}", MusicFreeSearchType.MUSIC).isFailure) // 缺 data
        // 缺 id/title 的条目应被丢弃而非伪造。
        val partial = parseMusicFreeSearch(
            """{"isEnd":true,"data":[{"title":"无id"},{"id":"ok","title":"合法"}]}""",
            MusicFreeSearchType.MUSIC,
        )
        assertTrue(partial.isSuccess)
        assertEquals(1, partial.getOrThrow().items.size)
        assertEquals("ok", partial.getOrThrow().items.first().id)
    }
}
