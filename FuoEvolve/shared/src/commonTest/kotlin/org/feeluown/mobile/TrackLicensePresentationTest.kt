package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 开放曲库许可证在 UI 层的解析测试（纯逻辑，无 Compose）。
 *
 * 验证"只在有 cc: 标签时才标注开放曲库"以及 BY/NC/ND/SA 条件、原始链接被正确提取，
 * 防止商业来源被误标为开放资源，或 CC 限制条件在展示链路中丢失。
 */
class TrackLicensePresentationTest {

    @Test
    fun commercialTrackWithoutCcTagIsNotMarked() {
        assertNull(licenseInfoFromTags(listOf("qqmusic", "flac")), "无 cc: 标签不得标为开放曲库")
        assertNull(licenseInfoFromTags(emptyList()))
        assertNull(licenseInfoFromTags(listOf("jamendo")), "只有曲库名、无许可证代码时也不标注")
    }

    @Test
    fun parsesCcCodeSummaryAndLicenseUrl() {
        val info = licenseInfoFromTags(
            listOf(
                "jamendo",
                "cc:BY-NC-ND",
                "license-url:http://creativecommons.org/licenses/by-nc-nd/3.0/",
                "BY-NC-ND：须署名(BY)、仅限非商业使用(NC)、禁止演绎/不可改编再分发(ND)",
            ),
        )
        assertNotNull(info)
        assertEquals("BY-NC-ND", info.code)
        assertEquals("CC BY-NC-ND", info.shortLabel)
        assertEquals("http://creativecommons.org/licenses/by-nc-nd/3.0/", info.licenseUrl)
        assertTrue(info.summary.contains("须署名"), "署名条件不得丢失: ${info.summary}")
        assertTrue(info.summary.contains("非商业"), "NC 条件不得丢失: ${info.summary}")
        assertTrue(info.summary.contains("禁止演绎"), "ND 条件不得丢失: ${info.summary}")
    }

    @Test
    fun handlesCaseInsensitiveCcPrefixAndMissingUrl() {
        val info = licenseInfoFromTags(listOf("CC:by-sa", "by-sa：署名、相同方式共享"))
        assertNotNull(info)
        assertEquals("by-sa", info.code, "大小写前缀被剥离，代码按原样保留")
        assertEquals("", info.licenseUrl, "无链接时为空串，UI 据此不显示链接行")
        assertTrue(info.summary.contains("相同方式共享"))
    }

    @Test
    fun fallsBackToCodeWhenSummaryTagMissing() {
        val info = licenseInfoFromTags(listOf("cc:BY", "license-url:https://creativecommons.org/licenses/by/4.0/"))
        assertNotNull(info)
        assertEquals("BY", info.code)
        assertEquals("BY", info.summary, "缺摘要 tag 时至少回退到 code，绝不留空")
    }
}
