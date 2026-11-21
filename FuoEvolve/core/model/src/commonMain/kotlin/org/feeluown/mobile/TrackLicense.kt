package org.feeluown.mobile

/**
 * K2 结构化曲目许可模型。
 *
 * 在此之前，许可信息只以字符串约定存放在 [MusicTrack.providerTags]（`cc:` / `license-url:`），
 * 仅供 UI 展示；播放与下载两套许可没有分离，也没有任何门控，BY-NC-ND 等曲目仅"提示不可演绎"
 * 而不做技术拦截。本模型把许可变成可机器判断的结构化数据，并坚持以下原则：
 *
 * 1. **默认拒绝未知许可的下载**：[downloadLicense] 默认 `false`，只有来源显式给出许可代码且
 *    该许可真正允许下载时才为 `true`。
 * 2. **可播放 ≠ 可下载**：[playLicense] 与 [downloadLicense] 是两个独立字段，互不相推。
 * 3. **不夸大 CC 授权**：即使存在 CC 标签，也只能推导出该许可代码明示的条件；ND 不允许演绎，
 *    NC 不允许商用，署名要求单独保留。不得把"有 CC 标签"解释成"所有使用权均已满足"。
 * 4. **保留来源与核验时间**：[licenseSource] 与 [licenseVerifiedAt] 让每条许可可追溯到证据。
 *
 * 该模型为纯领域类（无序列化注解），可安全地作为 [MusicTrack] 的带默认值字段加入，
 * 不破坏既有构造点与新生代序列化布局。
 */
data class TrackLicense(
    /** 许可类型及版本，例如 "CC BY-NC-ND 3.0"；未知时为 "UNKNOWN"。 */
    val licenseType: String = "UNKNOWN",
    /** 原始许可条款链接。 */
    val licenseUrl: String? = null,
    /** 署名文本、作者与要求的链接。 */
    val attribution: String? = null,
    /** 是否允许 App 播放。默认 false，来源显式声明后才放行。 */
    val playLicense: Boolean = false,
    /** 是否允许下载。默认 false——未知许可一律拒绝下载。 */
    val downloadLicense: Boolean = false,
    /** 是否允许商业使用。 */
    val commercialUseAllowed: Boolean = false,
    /** 是否允许演绎（改编/混音/铃声制作）。 */
    val derivativesAllowed: Boolean = false,
    /** 是否允许原样再分发。 */
    val redistributionAllowed: Boolean = false,
    /** 许可证据的核验时间（ISO-8601 字符串），未核验为 null。 */
    val licenseVerifiedAt: String? = null,
    /** 许可证据来源，例如 "jamendo-api:license_ccurl" 或 "providerTags"。 */
    val licenseSource: String? = null,
) {
    /** 是否为明确许可（而非 UNKNOWN）。 */
    val isKnown: Boolean get() = licenseType != "UNKNOWN" && licenseType.isNotBlank()
}

/**
 * 从 [MusicTrack.providerTags] 解析结构化许可。
 *
 * 兼容既有 CC 标签约定：
 * - `cc:BY-NC-ND` / `cc:CC-BY` —— 许可代码
 * - `license-url:<url>` —— 原始条款链接
 * - `<CODE>：可读摘要` —— 署名/条件说明
 *
 * 解析坚持"只按许可代码明示的条件放行"：
 * - 含 `NC` → 不允许商业使用；不含 `NC` → 允许商业使用。
 * - 含 `ND` → 不允许演绎；不含 `ND` → 允许演绎。
 * - 任何 CC 许可都要求署名、允许原样再分发（Share），因此 attribution 非空、redistributionAllowed=true。
 * - **下载许可不因 CC 标签自动放行**：CC 许可本身不保证来源 API 允许下载，downloadLicense 始终
 *   保持 false，必须来源另行显式写入 `download-license:allow` 才放行。
 * - **播放许可**：CC 许可允许播放，playLicense=true；无 cc: 标签（商业来源/未知）返回 null 由调用方决定。
 */
fun trackLicenseFromTags(tags: List<String>, source: String = "providerTags"): TrackLicense? {
    val code = tags.firstNotNullOfOrNull { tag ->
        tag.takeIf { it.startsWith("cc:", ignoreCase = true) }
            ?.removePrefix("cc:")
            ?.removePrefix("CC:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    } ?: return null

    val licenseUrl = tags.firstNotNullOfOrNull { tag ->
        tag.takeIf { it.startsWith("license-url:", ignoreCase = true) }
            ?.removePrefix("license-url:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    val summary = tags.firstOrNull { tag ->
        tag.startsWith(code, ignoreCase = true) && !tag.startsWith("cc:", ignoreCase = true)
    }

    val downloadAllowed = tags.any { it.equals("download-license:allow", ignoreCase = true) }

    val normalizedCode = code.uppercase().replace("-", " ")
    val nonCommercial = "NC" in code.uppercase()
    val noDerivatives = "ND" in code.uppercase()

    return TrackLicense(
        licenseType = "CC $normalizedCode",
        licenseUrl = licenseUrl,
        attribution = summary ?: "须署名（BY）：$normalizedCode",
        playLicense = true,
        downloadLicense = downloadAllowed,
        commercialUseAllowed = !nonCommercial,
        derivativesAllowed = !noDerivatives,
        // CC 许可允许原样 Share（署名前提下），ND 限制的是演绎而非原样分享。
        redistributionAllowed = true,
        licenseVerifiedAt = null,
        licenseSource = source,
    )
}

/**
 * 下载许可门控：只有来源通过结构化许可显式允许下载的曲目才能进入下载路径。
 *
 * 严格遵循任务书"未知许可默认拒绝下载"：
 * - [TrackLicense] 为空（来源未声明任何许可）→ 拒绝。
 * - 许可存在但 [TrackLicense.downloadLicense] 为 false（默认）→ 拒绝。
 * - 仅当许可存在且 downloadLicense=true 时放行。
 *
 * 不因"可播放"、存在 CC 标签、或"商业来源应有会员权益"等猜测放行——这类推断缺乏证据，
 * 正是 K2 要消除的风险。商业来源若确实允许下载，须由该来源 Provider 在映射曲目时显式
 * 写入 `license = TrackLicense(downloadLicense = true, ...)`；在此之前其下载同样被默认拒绝，
 * 这是"默认拒绝未知许可"的必然结果，而非功能退化（没有证据证明那些来源允许下载）。
 */
fun canDownloadTrack(track: MusicTrack): Boolean = track.license?.downloadLicense == true

/** 下载被拒时的可读原因，供 UI 展示，不涉及技术细节。 */
fun downloadDenialReason(track: MusicTrack): String? = when {
    track.license?.downloadLicense == false -> "该曲目许可不允许下载，仅可在线播放"
    track.license == null -> "该曲目未提供下载许可，暂不支持下载"
    else -> null
}
