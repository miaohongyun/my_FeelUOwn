package org.feeluown.mobile.provider.jamendo

/**
 * Jamendo 开放曲库的最小模型。
 *
 * 只保留"能搜到、能播、能显示"所需的字段。Jamendo 的曲目是 CC 授权音乐，
 * 与商业流行曲库不重叠——UI 必须明确这是"替代曲目"，不能假装成原曲。
 */
data class JamendoTrack(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int?,
    val audioUrl: String,
    val downloadUrl: String,
    val imageUrl: String,
    val shareUrl: String,
    /**
     * 授权元数据：Jamendo 返回的 CC 许可 URL（如 creativecommons.org/licenses/by-nc-nd/3.0）。
     * 用于端到端验证"这是开放授权曲库、可合法播放"，为空时不声称已确认授权。
     */
    val licenseCcUrl: String = "",
) {
    /**
     * CC 许可证类型（如 `BY-NC-ND`）。从 license_ccurl 路径解析，空 URL 返回空串。
     * 不同 CC 许可条件差异很大（NC 禁商用、ND 禁演绎、SA 要求同方式共享），
     * 不得把"属于 Creative Commons"等同于"无条件开放/可任意下载修改再分发"。
     */
    val licenseCode: String
        get() = Regex("""/licenses/([^/]+)/""", RegexOption.IGNORE_CASE)
            .find(licenseCcUrl)?.groupValues?.get(1)?.uppercase().orEmpty()

    /**
     * 可读的授权条件说明，供 UI 展示。
     *
     * 逐项解码 BY / NC / ND / SA，**列出全部适用限制**，不因组合而漏标：
     * - BY 署名、NC 仅非商业、ND 禁止演绎（不可改编再分发）、SA 相同方式共享。
     * 纯 BY 也必须显式写"须署名"，不能再退化成"请查看原始条款"这种含糊措辞。
     * 未知许可证保守提示"条件未知/请核对原始条款"，不声称无条件开放。
     */
    val licenseSummary: String
        get() {
            if (licenseCode.isEmpty()) return "授权条件未知（无 CC 链接）"
            // CC0 / 公有领域：无署名要求，但仍提示用户核对原始条款。
            if (licenseCode.contains("CC0") || licenseCode.contains("ZERO") || licenseCode == "PD") {
                return "$licenseCode：公有领域贡献，通常无署名要求（仍请核对原始条款）"
            }
            val conditions = buildList {
                if ("BY" in licenseCode) add("须署名(BY)")
                if ("NC" in licenseCode) add("仅限非商业使用(NC)")
                if ("ND" in licenseCode) add("禁止演绎/不可改编再分发(ND)")
                if ("SA" in licenseCode) add("演绎须相同方式共享(SA)")
            }
            val detail = if (conditions.isEmpty()) "请核对原始许可条款" else conditions.joinToString("、")
            return "$licenseCode：$detail"
        }
}

/**
 * 搜索结果与失败原因分离：
 * - `tracks` 为空且 `failureKind` 为 null —— 正常空结果（关键词无匹配），不提示错误；
 * - `failureKind` 非 null —— 真实的失败（无 client_id / 网络 / HTTP），UI 应给出明确提示。
 */
data class JamendoSearchResult(
    val tracks: List<JamendoTrack>,
    val totalCount: Int,
    val failureKind: JamendoFailureKind = JamendoFailureKind.None,
)

/** Jamendo 请求结果状态。定义在曲库模块内，由 Provider 映射到通用 ProviderFailureKind。 */
enum class JamendoFailureKind {
    /** 正常完成（可能为空结果）。 */
    None,
    /** 未配置 client_id（用户清空了设置）。 */
    MissingClientId,
    /** 网络错误或超时。 */
    Network,
    /** 上游返回 HTTP 错误或响应格式异常。 */
    Upstream,
}
