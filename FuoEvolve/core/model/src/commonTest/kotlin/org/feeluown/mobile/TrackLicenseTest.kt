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
        // K5-D：有 CC 作品许可但下载权限未逐曲核实 → UNKNOWN，原因如实显示"待核实"。
        assertEquals("该曲目下载权限尚未核实，暂不支持下载", downloadDenialReason(t))
    }

    @Test
    fun explicitDownloadLicenseAllowsDownload() {
        // K5-D：显式授权必须逐曲声明完整条件，单一下载布尔值不再足以放行。
        val t = track(license = TrackLicense(
            licenseType = "LICENSED", playLicense = true, downloadLicense = true,
            personalCopyAllowed = true, platformDownloadAllowed = true,
            downloadPermissionStatus = DownloadPermissionStatus.ALLOWED_PERSONAL,
            downloadPermissionEvidence = "official-download-api",
        ))
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

    // ---------- K5-D：逐曲下载许可决策 ----------

    @Test
    fun k5dSingleBooleanIsNotEnoughToAllow() {
        // 仅 downloadLicense=true，缺少个人复制/平台接口/状态，仍拒绝。
        val t = track(license = TrackLicense(
            licenseType = "LICENSED", downloadLicense = true, playLicense = true,
        ))
        val decision = evaluateDownloadPermission(t)
        assertFalse(decision.allowed, "单一布尔值不得放行下载")
        assertEquals(DownloadPermissionStatus.UNKNOWN, decision.status)
    }

    @Test
    fun k5dUnknownStatusIsDenied() {
        val t = track(license = TrackLicense(
            licenseType = "LICENSED", downloadLicense = true, personalCopyAllowed = true,
            platformDownloadAllowed = true,
            // status 默认 UNKNOWN
        ))
        val decision = evaluateDownloadPermission(t)
        assertFalse(decision.allowed)
        assertEquals(DownloadPermissionStatus.UNKNOWN, decision.status)
    }

    @Test
    fun k5dExplicitDeniedNeverAllows() {
        val t = track(license = TrackLicense(
            downloadLicense = true, personalCopyAllowed = true, platformDownloadAllowed = true,
            downloadPermissionStatus = DownloadPermissionStatus.DENIED,
            downloadPermissionReason = "来源平台禁止该下载方式",
        ))
        val decision = evaluateDownloadPermission(t)
        assertFalse(decision.allowed)
        assertEquals(DownloadPermissionStatus.DENIED, decision.status)
        assertEquals("来源平台禁止该下载方式", decision.reason)
    }

    @Test
    fun k5dNonCommercialConditionalRequiresAttributionAndNonCommercial() {
        val base = TrackLicense(
            licenseType = "CC BY-NC-ND 3.0", downloadLicense = true, personalCopyAllowed = true,
            platformDownloadAllowed = true,
            downloadPermissionStatus = DownloadPermissionStatus.CONDITIONAL_NONCOMMERCIAL,
            commercialUseAllowed = false, attribution = "Artist, CC BY-NC-ND 3.0",
        )
        assertTrue(evaluateDownloadPermission(track(license = base)).allowed, "满足署名+非商业的原样保存允许")

        // 商业用途标记为真时，条件分支不放行。
        val commercial = base.copy(commercialUseAllowed = true)
        assertFalse(evaluateDownloadPermission(track(license = commercial)).allowed)

        // 缺署名时，条件分支不放行。
        val noAttribution = base.copy(attribution = null)
        assertFalse(evaluateDownloadPermission(track(license = noAttribution)).allowed)
    }

    @Test
    fun k5dCommercialFalseNeitherAllowsNorDeniesByItself() {
        // commercialUseAllowed=false 单独不得推导可下载，也不得作为唯一拒绝理由。
        val t = track(license = TrackLicense(licenseType = "CC BY-NC-ND 3.0", commercialUseAllowed = false))
        val decision = evaluateDownloadPermission(t)
        assertFalse(decision.allowed)
        assertEquals(DownloadPermissionStatus.UNKNOWN, decision.status, "未知许可保持 UNKNOWN，不自行推测")
    }

    @Test
    fun k5dAllowedPersonalRequiresPlatformDownloadPermission() {
        val t = track(license = TrackLicense(
            licenseType = "LICENSED", downloadLicense = true, personalCopyAllowed = true,
            platformDownloadAllowed = false, // 来源无官方下载接口
            downloadPermissionStatus = DownloadPermissionStatus.ALLOWED_PERSONAL,
        ))
        assertFalse(evaluateDownloadPermission(t).allowed, "无平台下载接口许可不得放行")
    }

    @Test
    fun k5dDecisionReasonForUiHasThreeStates() {
        // 待核实（UNKNOWN）
        val unknown = evaluateDownloadPermission(track())
        assertEquals(DownloadPermissionStatus.UNKNOWN, unknown.status)
        assertNotNull(unknown.reason)
        // 可下载（ALLOWED_PERSONAL）无拒绝原因
        val allowed = evaluateDownloadPermission(track(license = TrackLicense(
            licenseType = "LICENSED", downloadLicense = true, personalCopyAllowed = true,
            platformDownloadAllowed = true,
            downloadPermissionStatus = DownloadPermissionStatus.ALLOWED_PERSONAL,
        )))
        assertTrue(allowed.allowed)
        assertNull(allowed.reason)
        // 来源禁止（DENIED）
        val denied = evaluateDownloadPermission(track(license = TrackLicense(
            downloadPermissionStatus = DownloadPermissionStatus.DENIED,
        )))
        assertEquals(DownloadPermissionStatus.DENIED, denied.status)
        assertNotNull(denied.reason)
    }
}
