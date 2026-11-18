package org.feeluown.mobile.provider.netease

import kotlinx.serialization.json.JsonObject
import org.feeluown.mobile.AudioQualityPolicy
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.stringOrNull

/**
 * 从网易云歌曲详情里的 `privilege` 推导**当前账户**实际能拿到的档位。
 *
 * 关键点：`maxbr` 只描述文件本身存在何种码率，免费账户同样会看到 `maxbr = 999000`；
 * 真正代表权限的是账户维度的字段：
 * - `downloadMaxbr` / `dl`：当前账户允许下载的最高码率；
 * - `playMaxbr` / `pl`：当前账户允许播放的最高码率；
 * - `st`：版权状态，负数表示已下架；
 * - `flLevel` / `dlLevel` / `plLevel`：档位名（`lossless`、`hires`、`sky`…）。
 *
 * 返回 `null` 表示「无法判断」，调用方应退回通用档位列表；
 * 返回空列表表示「明确没有可用音频」。
 */
internal fun neteaseQualityPolicies(privilege: JsonObject?): List<String>? {
    if (privilege == null) return null

    val status = privilege.int("st") ?: 0
    if (status < 0) return emptyList()

    val levels = QUALITY_LEVEL_FIELDS.mapNotNull { field ->
        privilege.stringOrNull(field)?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
    }
    val levelReportsLossless = levels.any { level -> NETEAST_LOSSLESS_LEVELS.any(level::contains) }

    val scopedFieldsPresent = ACCOUNT_SCOPED_BITRATE_FIELDS.any(privilege::containsKey)
    val accountCeiling = firstPositive(privilege, ACCOUNT_SCOPED_BITRATE_FIELDS)
    // 字段存在但都是 0：账号确实拿不到任何码流，不能用 maxbr 假装可用。
    if (accountCeiling == null && scopedFieldsPresent) return emptyList()
    val ceiling = accountCeiling
        ?: firstPositive(privilege, DECLARED_BITRATE_FIELDS)
        ?: return if (levelReportsLossless) listOf(AudioQualityPolicy.Highest.policy) else null

    val losslessBitratePresent = LOSSLESS_BITRATE_FIELDS.any { field -> (privilege.int(field) ?: 0) > 0 }
    val hasLossless = levelReportsLossless || losslessBitratePresent || ceiling >= LOSSLESS_BITRATE_THRESHOLD

    val policies = linkedSetOf<String>()
    if (hasLossless) policies += AudioQualityPolicy.Highest.policy
    // A lossless stream implies every lower tier is also fetchable.
    if (hasLossless || ceiling >= HIGH_BITRATE_THRESHOLD) policies += AudioQualityPolicy.High.policy
    if (hasLossless || ceiling >= STANDARD_BITRATE_THRESHOLD) policies += AudioQualityPolicy.Standard.policy
    if (hasLossless || ceiling >= LOW_BITRATE_THRESHOLD) policies += AudioQualityPolicy.Low.policy
    return policies.toList()
}

private fun firstPositive(privilege: JsonObject, fields: List<String>): Int? =
    fields.asSequence()
        .mapNotNull { field -> privilege.int(field) }
        .firstOrNull { it > 0 }

private val ACCOUNT_SCOPED_BITRATE_FIELDS = listOf("downloadMaxbr", "dl", "playMaxbr", "pl")
private val DECLARED_BITRATE_FIELDS = listOf("maxbr", "br")
private val LOSSLESS_BITRATE_FIELDS = listOf("hires", "flac", "hiresSize", "flacSize", "fl")
private val QUALITY_LEVEL_FIELDS = listOf("dlLevel", "flLevel", "plLevel")

internal val NETEAST_LOSSLESS_LEVELS = listOf("lossless", "hires", "jymaster", "sky", "flac")

private const val LOSSLESS_BITRATE_THRESHOLD = 900_000
private const val HIGH_BITRATE_THRESHOLD = 320_000
private const val STANDARD_BITRATE_THRESHOLD = 192_000
private const val LOW_BITRATE_THRESHOLD = 128_000
