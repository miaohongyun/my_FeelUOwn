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
/**
 * K5-D 下载权限状态。个人下载必须逐曲判断，不能用单一布尔值表达全部法律/平台条件。
 *
 * - [ALLOWED_PERSONAL]：来源与作品许可均核实，允许个人非商业原样保存。
 * - [CONDITIONAL_NONCOMMERCIAL]：作品许可允许非商业复制，但需满足署名/禁止演绎等条件；
 *   仅在条件核验通过且来源无额外下载限制时才放行，且绝不推导商业授权。
 * - [UNKNOWN]：许可未知、缺失、无法解析，或来源下载接口/平台条款未核实。默认拒绝。
 * - [DENIED]：来源或许可明确禁止下载，或存在平台技术/条款限制。拒绝且不尝试绕开。
 */
enum class DownloadPermissionStatus {
    ALLOWED_PERSONAL,
    CONDITIONAL_NONCOMMERCIAL,
    UNKNOWN,
    DENIED,
}

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
    /** 是否允许原样再分发（不等于允许演绎）。 */
    val redistributionAllowed: Boolean = false,
    /** 许可证据的核验时间（ISO-8601 字符串），未核验为 null。 */
    val licenseVerifiedAt: String? = null,
    /** 许可证据来源，例如 "jamendo-api:license_ccurl" 或 "providerTags"。 */
    val licenseSource: String? = null,

    // ---- K5-D.1：下载权限多维字段（默认均为保守值，未知按 UNKNOWN）----
    /** 是否允许个人非商业原样复制保存。默认 false，须来源逐曲核实后显式放行。 */
    val personalCopyAllowed: Boolean = false,
    /** 来源平台是否提供官方下载接口且条款允许当前下载方式。默认 false（未核实）。 */
    val platformDownloadAllowed: Boolean = false,
    /** 下载权限状态；默认 UNKNOWN，不可自行推测为允许。 */
    val downloadPermissionStatus: DownloadPermissionStatus = DownloadPermissionStatus.UNKNOWN,
    /** 下载权限证据（许可链接/API 字段/条款出处），无证据为 null。 */
    val downloadPermissionEvidence: String? = null,
    /** 拒绝或未核实的简短原因，供 UI 展示。 */
    val downloadPermissionReason: String? = null,
) {
    /** 是否为明确许可（而非 UNKNOWN）。 */
    val isKnown: Boolean get() = licenseType != "UNKNOWN" && licenseType.isNotBlank()
}

/**
 * K5-D 统一下载许可决策结果。Feature 层与 Repository 层必须共用此唯一权威判断。
 *
 * [allowed] 只代表"允许个人非商业原样保存"，不代表商业授权、可再分发或可演绎。
 * [status] 与 [reason] 供 UI 区分"可下载/待核实/来源禁止"三种状态。
 */
data class DownloadPermissionDecision(
    val allowed: Boolean,
    val status: DownloadPermissionStatus,
    val reason: String?,
)

/** K5-D.5 面向普通用户的三态下载标签，不含版权术语。 */
enum class DownloadAvailabilityUiLabel {
    /** 可以个人下载。 */
    CAN_DOWNLOAD,
    /** 下载权限待核实。 */
    VERIFYING,
    /** 来源明确禁止下载。 */
    NOT_AVAILABLE,
}

data class DownloadPermissionUi(
    val label: DownloadAvailabilityUiLabel,
    val reason: String?,
)

/** 把统一决策映射为 UI 三态；普通用户无需理解 CC/NC/ND 等术语。 */
fun DownloadPermissionDecision.toUi(): DownloadPermissionUi = DownloadPermissionUi(
    label = when {
        allowed -> DownloadAvailabilityUiLabel.CAN_DOWNLOAD
        status == DownloadPermissionStatus.DENIED -> DownloadAvailabilityUiLabel.NOT_AVAILABLE
        else -> DownloadAvailabilityUiLabel.VERIFYING
    },
    reason = if (allowed) null else reason,
)

/**
 * K5-D 统一下载许可决策：逐曲判断，未知默认拒绝，且个人非商业与商业/演绎/再分发相互独立。
 *
 * 判断顺序（任一不满足即拒绝，绝不通过播放地址推导下载权）：
 * 1. 无结构化许可 → UNKNOWN 拒绝。
 * 2. 明确禁止（DENIED）→ 拒绝，不尝试备用地址或重定向绕开。
 * 3. 允许下载必须同时满足：来源显式 [downloadLicense]、允许个人复制 [personalCopyAllowed]、
 *    来源平台下载许可 [platformDownloadAllowed]，且状态为 ALLOWED_PERSONAL 或 CONDITIONAL_NONCOMMERCIAL。
 * 4. CONDITIONAL_NONCOMMERCIAL 分支额外要求非商业（!commercialUseAllowed）且保留署名；
 *    ND 只限制演绎，不因 redistributionAllowed=false 之外的原因额外放行演绎权。
 * 5. commercialUseAllowed=false 既不能推出可下载，也不能推出不可下载——它只影响商业用途判定。
 *
 * 注意：本函数是下载许可的**唯一权威判断**。它刻意不读取 playLicense——可播放与可下载彻底分离。
 */
fun evaluateDownloadPermission(track: MusicTrack): DownloadPermissionDecision {
    val license = track.license
        ?: return DownloadPermissionDecision(
            false, DownloadPermissionStatus.UNKNOWN, "该曲目未提供下载许可，暂不支持下载",
        )
    if (license.downloadPermissionStatus == DownloadPermissionStatus.DENIED) {
        return DownloadPermissionDecision(
            false, DownloadPermissionStatus.DENIED,
            license.downloadPermissionReason ?: "来源明确禁止下载",
        )
    }
    val statusOk = license.downloadPermissionStatus == DownloadPermissionStatus.ALLOWED_PERSONAL ||
        license.downloadPermissionStatus == DownloadPermissionStatus.CONDITIONAL_NONCOMMERCIAL
    val conditionalOk = license.downloadPermissionStatus != DownloadPermissionStatus.CONDITIONAL_NONCOMMERCIAL ||
        (!license.commercialUseAllowed && !license.attribution.isNullOrBlank())
    val allConditions = license.downloadLicense && license.personalCopyAllowed &&
        license.platformDownloadAllowed && statusOk && conditionalOk
    if (allConditions) {
        return DownloadPermissionDecision(true, license.downloadPermissionStatus, null)
    }
    val reason = license.downloadPermissionReason
        ?: if (license.downloadPermissionStatus == DownloadPermissionStatus.UNKNOWN) {
            "该曲目下载权限尚未核实，暂不支持下载"
        } else {
            "该曲目许可不允许下载，仅可在线播放"
        }
    return DownloadPermissionDecision(false, license.downloadPermissionStatus, reason)
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
fun canDownloadTrack(track: MusicTrack): Boolean = evaluateDownloadPermission(track).allowed

/** 下载被拒时的可读原因，供 UI 展示，不涉及技术细节。 */
fun downloadDenialReason(track: MusicTrack): String? = evaluateDownloadPermission(track).reason
