package org.feeluown.mobile

/**
 * 同一首歌的不同录音/编曲版本。
 *
 * 版本信息既可能出现在标题里（`晴天 (Live)`），也可能只出现在专辑名或平台标签里
 * （专辑《演唱会》、B 站投稿标签「翻唱」）。跨音源匹配必须把这些标记看成
 * 「换了一版录音」，而不是同一首歌的细微差异。
 */
enum class SongVersionKind(val label: String) {
    LIVE("现场 / Live"),
    COVER("翻唱 / Cover"),
    REMIX("混音 / Remix"),
    INSTRUMENTAL("伴奏 / Instrumental"),
    ACOUSTIC("不插电 / Acoustic"),
    DEMO("小样 / Demo"),
    KARAOKE("卡拉 OK"),
    SOLO("独唱 / Solo"),
    SPEED_CHANGED("变速版"),
    REMASTER("重制版"),
}

/**
 * 中英文版本标记的统一识别器。
 *
 * 设计约束：
 * - 只做「有没有某种版本标记」的判断，不猜测版本之间的相似度；
 * - 拉丁标记按整词匹配，避免 `delivery`、`album` 这类单词里的 `live` 被误判；
 * - 中文标记按子串匹配，同时覆盖简繁与常见日文写法；
 * - 纯 Kotlin 实现，Android / Windows / iOS 使用同一套规则。
 */
object SongVersionMatcher {
    /** 合并多段文本（标题、专辑、平台标签）里的版本标记。 */
    fun kindsOf(texts: Iterable<String?>): Set<SongVersionKind> {
        val normalized = texts
            .filterNotNull()
            .map(::normalize)
            .filter(String::isNotBlank)
            .joinToString(" ")
        if (normalized.isBlank()) return emptySet()
        return buildSet {
            LATIN_MARKERS.forEach { (kind, pattern) ->
                if (pattern.containsMatchIn(normalized)) add(kind)
            }
            CJK_MARKERS.forEach { (kind, marker) ->
                if (normalized.contains(marker)) add(kind)
            }
        }
    }

    /** 合并多段文本（标题、专辑、平台标签）里的版本标记。 */
    fun kindsOf(vararg texts: String?): Set<SongVersionKind> = kindsOf(texts.asIterable())

    /**
     * 请求版本与候选版本是否冲突。
     *
     * 这里是**双向**校验：候选多出一个版本标记要被拒绝（普通版不能拿 Live 顶替），
     * 候选少一个请求中的版本标记同样要被拒绝（请求 Live 不能拿录音室版顶替）。
     */
    fun conflicts(
        requested: Set<SongVersionKind>,
        candidate: Set<SongVersionKind>,
    ): Boolean = requested != candidate

    /**
     * 排序用的版本一致性等级：3 完全一致，1 一侧没有标记，0 明确冲突。
     *
     * 与 [conflicts] 的区别：排序只在多个候选之间挑「更可能正确」的那个，
     * 允许一侧没有版本信息时降级保留，最终取舍由调用方决定。
     */
    fun rank(
        requested: Set<SongVersionKind>,
        candidate: Set<SongVersionKind>,
    ): Int = when {
        requested == candidate -> 3
        requested.isEmpty() && candidate.isNotEmpty() -> 0
        requested.isNotEmpty() && candidate.isEmpty() -> 1
        requested.intersect(candidate).isNotEmpty() -> 1
        else -> 0
    }

    /**
     * 去掉标题**末尾**的版本标记，用于判断「是不是同一首歌」。
     *
     * 只处理两种情况：
     * - 括号里只有版本信息的片段，例如 `晴天 (Live)`、`晴天【现场版】`；
     * - 结尾的版本词，例如 `晴天 Live`、`晴天现场版`。
     *
     * 刻意不动开头与中间：`Live Forever` 这类歌名里的 `Live` 属于标题本身。
     * 如果去掉后什么都不剩（歌名本身就叫 `Live`），保留原值。
     */
    fun stripTrailingVersionMarkers(value: String): String {
        if (value.isBlank()) return value
        var text = value
        BRACKET_SEGMENT_PATTERN.findAll(value).toList().asReversed().forEach { match ->
            if (kindsOf(match.groupValues[1]).isNotEmpty()) {
                text = text.removeRange(match.range)
            }
        }
        var previous: String
        do {
            previous = text
            text = text.trim()
            TRAILING_MARKER_PATTERNS.forEach { pattern ->
                val stripped = trimTrailingSeparators(pattern.replace(text, ""))
                if (stripped.isNotBlank()) text = stripped
            }
        } while (text != previous && text.isNotBlank())
        return trimTrailingSeparators(text).ifBlank { value }
    }

    /** `学不会 - LIVE` 去掉 `LIVE` 后残留的分隔符也要一起清掉。 */
    private fun trimTrailingSeparators(value: String): String =
        value.trim().trimEnd(' ', '-', '–', '—', '(', '（', '[', '【', ':', '：', '·', '~')

    private fun normalize(value: String): String {
        val builder = StringBuilder(value.length)
        value.forEach { character ->
            val code = character.code
            when {
                // 全角 ASCII → 半角，保证「Ｌｉｖｅ」「（Ｌｉｖｅ）」也能识别。
                code in FULLWIDTH_ASCII_START..FULLWIDTH_ASCII_END ->
                    builder.append((code - FULLWIDTH_OFFSET).toChar())
                code == IDEOGRAPHIC_SPACE -> builder.append(' ')
                else -> builder.append(character)
            }
        }
        return builder.toString().lowercase()
    }

    private fun latinWord(value: String): Regex =
        Regex("(^|[^a-z0-9])${Regex.escape(value)}($|[^a-z0-9])")

    private fun trailingLatinMarker(value: String): Regex =
        Regex("(?i)(?:\\s+|^)${Regex.escape(value)}(?:\\s*版|\\s*version)?\\s*$")

    private fun trailingCjkMarker(value: String): Regex =
        Regex("${Regex.escape(value)}版?\\s*$")

    private const val FULLWIDTH_ASCII_START = 0xFF01
    private const val FULLWIDTH_ASCII_END = 0xFF5E
    private const val FULLWIDTH_OFFSET = 0xFEE0
    private const val IDEOGRAPHIC_SPACE = 0x3000

    private val BRACKET_SEGMENT_PATTERN = Regex("[（(\\[【]([^）)\\]】]*)[）)\\]】]")

    private val TRAILING_MARKER_PATTERNS: List<Regex> = listOf(
        "live", "cover", "remix", "instrumental", "off vocal", "off-vocal", "karaoke",
        "acoustic", "unplugged", "demo", "solo", "sped up", "speed up", "slowed",
        "remaster", "remastered",
    ).map(::trailingLatinMarker) + listOf(
        "现场", "現場", "演唱会", "演唱會", "ライブ",
        "翻唱", "翻自", "歌ってみた", "弾いてみた",
        "混音", "重混", "dj版", "dj 版",
        "伴奏", "纯音乐", "純音樂", "カラオケ",
        "不插电", "不插電", "小样", "小樣", "独唱", "獨唱",
        "加速版", "慢速版", "降速", "倍速", "重制", "重製",
    ).map(::trailingCjkMarker)

    private val LATIN_MARKERS: List<Pair<SongVersionKind, Regex>> = listOf(
        SongVersionKind.LIVE to latinWord("live"),
        SongVersionKind.COVER to latinWord("cover"),
        SongVersionKind.REMIX to latinWord("remix"),
        SongVersionKind.INSTRUMENTAL to latinWord("instrumental"),
        SongVersionKind.INSTRUMENTAL to latinWord("off vocal"),
        SongVersionKind.INSTRUMENTAL to latinWord("off-vocal"),
        SongVersionKind.KARAOKE to latinWord("karaoke"),
        SongVersionKind.ACOUSTIC to latinWord("acoustic"),
        SongVersionKind.ACOUSTIC to latinWord("unplugged"),
        SongVersionKind.DEMO to latinWord("demo"),
        SongVersionKind.SOLO to latinWord("solo"),
        SongVersionKind.SPEED_CHANGED to latinWord("sped up"),
        SongVersionKind.SPEED_CHANGED to latinWord("speed up"),
        SongVersionKind.SPEED_CHANGED to latinWord("slowed"),
        SongVersionKind.REMASTER to latinWord("remaster"),
        SongVersionKind.REMASTER to latinWord("remastered"),
    )

    private val CJK_MARKERS: List<Pair<SongVersionKind, String>> = listOf(
        SongVersionKind.LIVE to "现场",
        SongVersionKind.LIVE to "現場",
        SongVersionKind.LIVE to "演唱会",
        SongVersionKind.LIVE to "演唱會",
        SongVersionKind.LIVE to "ライブ",
        SongVersionKind.COVER to "翻唱",
        SongVersionKind.COVER to "翻自",
        SongVersionKind.COVER to "歌ってみた",
        SongVersionKind.COVER to "弾いてみた",
        SongVersionKind.REMIX to "混音",
        SongVersionKind.REMIX to "重混",
        SongVersionKind.REMIX to "dj版",
        SongVersionKind.REMIX to "dj 版",
        SongVersionKind.INSTRUMENTAL to "伴奏",
        SongVersionKind.INSTRUMENTAL to "纯音乐",
        SongVersionKind.INSTRUMENTAL to "純音樂",
        SongVersionKind.INSTRUMENTAL to "カラオケ",
        SongVersionKind.ACOUSTIC to "不插电",
        SongVersionKind.ACOUSTIC to "不插電",
        SongVersionKind.DEMO to "小样",
        SongVersionKind.DEMO to "小樣",
        SongVersionKind.SOLO to "独唱",
        SongVersionKind.SOLO to "獨唱",
        SongVersionKind.SPEED_CHANGED to "加速版",
        SongVersionKind.SPEED_CHANGED to "慢速版",
        SongVersionKind.SPEED_CHANGED to "降速",
        SongVersionKind.SPEED_CHANGED to "倍速",
        SongVersionKind.REMASTER to "重制",
        SongVersionKind.REMASTER to "重製",
    )
}
