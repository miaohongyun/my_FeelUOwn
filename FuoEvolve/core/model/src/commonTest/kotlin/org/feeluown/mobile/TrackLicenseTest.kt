package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** K2 结构化许可模型与下载门控测试。 */
class TrackLicenseTest {

    private fun track(
        tags: List<String> = emptyList(),
        license: TrackLicense? = null,
    ) = MusicTrack(
        id = "jamendo:1",
        title = "t",
        artists = "a",
        album = "b",
        source = "jamendo",
        sourceType = TrackSourceType.Provider,
        providerTags = tags,
        license = license,
    )

    // ---------- 解析：从 providerTags ----------

    @Test
    fun parsesCcByNcNdAsNonCommercialNoDerivatives() {
        val parsed = trackLicenseFromTags(
            listOf("jamendo", "cc:BY-NC-ND", "license-url:https://creativecommons.org/licenses/by-nc-nd/3.0/", "BY-NC-ND：须署名(BY)、仅限非商业使用(NC)、禁止演绎/不可改编再分发(ND)"),
            "jamendo-api:license_ccurl",
        )
        assertNotNull(parsed)
        assertEquals("CC BY NC ND", parsed.licenseType)
        assertTrue(parsed.licenseUrl!!.contains("by-nc-nd"))
        assertTrue(parsed.playLicense)
        assertFalse(parsed.commercialUseAllowed)
        assertFalse(parsed.derivativesAllowed)
        assertTrue(parsed.redistributionAllowed)
        assertFalse(parsed.downloadLicense, "CC 标签本身不得推断出下载许可")
        assertEquals("jamendo-api:license_ccurl", parsed.licenseSource)
        assertTrue(parsed.attribution!!.contains("须署名"))
    }

    @Test
    fun parsesCcByAsCommercialAndDerivativesAllowed() {
        val parsed = trackLicenseFromTags(listOf("cc:BY", "license-url:https://creativecommons.org/licenses/by/4.0/"))
        assertNotNull(parsed)
        assertTrue(parsed.commercialUseAllowed)
        assertTrue(parsed.derivativesAllowed)
        assertFalse(parsed.downloadLicense)
    }

    @Test
    fun explicitDownloadAllowIsHonored() {
        val parsed = trackLicenseFromTags(listOf("cc:BY", "download-license:allow"))
        assertNotNull(parsed)
        assertTrue(parsed.downloadLicense)
    }

    @Test
    fun noCcTagReturnsNull() {
        assertNull(trackLicenseFromTags(listOf("jamendo", "some-other-tag")))
        assertNull(trackLicenseFromTags(emptyList()))
    }

    // ---------- 门控：默认拒绝 ----------

    @Test
    fun unknownLicenseIsDeniedByDefault() {
        assertFalse(canDownloadTrack(track()), "无许可信息默认拒绝下载")
        assertEquals("该曲目未提供下载许可，暂不支持下载", downloadDenialReason(track()))
    }

    @Test
    fun openCatalogCcTagWithoutStructuredLicenseIsDenied() {
        val t = track(tags = listOf("cc:BY-NC-ND"))
        assertFalse(canDownloadTrack(t), "开放曲库未知许可保守拒绝")
        assertNotNull(downloadDenialReason(t))
    }

    @Test
    fun structuredLicenseDeniesDownloadByDefault() {
        val t = track(license = TrackLicense(licenseType = "CC BY-NC-ND 3.0", playLicense = true))
        assertFalse(canDownloadTrack(t))
        assertEquals("该曲目许可不允许下载，仅可在线播放", downloadDenialReason(t))
    }

    @Test
    fun explicitDownloadLicenseAllowsDownload() {
        val t = track(license = TrackLicense(licenseType = "LICENSED", playLicense = true, downloadLicense = true))
        assertTrue(canDownloadTrack(t))
        assertNull(downloadDenialReason(t))
    }

    @Test
    fun sourceWithoutStructuredLicenseIsDeniedEvenIfCommercial() {
        val t = track(tags = listOf("netease"))
        assertFalse(canDownloadTrack(t), "无结构化许可一律默认拒绝，不靠 tags 猜测商业来源授权")
        assertEquals("该曲目未提供下载许可，暂不支持下载", downloadDenialReason(t))
    }

    @Test
    fun playableDoesNotImplyDownloadable() {
        val t = track(license = TrackLicense(licenseType = "CC BY-NC-ND 3.0", playLicense = true))
        assertTrue(t.license!!.playLicense)
        assertFalse(canDownloadTrack(t), "可播放不得推断为可下载")
    }
}
