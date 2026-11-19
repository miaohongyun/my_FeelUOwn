package org.feeluown.mobile.sourceupdate

/**
 * 安装前的静态安全扫描。
 *
 * 这不是沙箱，只是第一道筛子：把"明显不该自动安装"的脚本挡在目录之外。
 * 真正的隔离由运行时负责（QuickJS 独立 context + 宿主代理网络 + 域名白名单）。
 *
 * 判据分级：
 * - BLOCK：出现即拒绝安装（动态执行、Node 宿主能力、卡密、隧道、未声明的域名、混淆）；
 * - WARN：可以安装但需要人工确认（浏览器环境 API、可疑体积、长循环）。
 *
 * 明确不做的事：不执行脚本、不做正则之外的分析、不声称"扫描通过 = 安全"。
 */
data class StaticFinding(
    val ruleId: String,
    val severity: StaticSeverity,
    val message: String,
    val evidence: String,
)

enum class StaticSeverity { BLOCK, WARN }

enum class StaticVerdict { CLEAN, NEEDS_REVIEW, BLOCKED }

data class StaticScanReport(
    val verdict: StaticVerdict,
    val findings: List<StaticFinding>,
    val declaredHosts: Set<String>,
    val undeclaredHosts: Set<String>,
) {
    val blocked: Boolean get() = verdict == StaticVerdict.BLOCKED

    fun blockReasons(): List<String> =
        findings.filter { it.severity == StaticSeverity.BLOCK }.map { "${it.ruleId}: ${it.message}" }
}

object SourceStaticPolicy {

    const val DEFAULT_MAX_SCRIPT_BYTES = 1024 * 1024
    private const val MAX_OBFUSCATION_MARKERS = 20
    private const val MAX_EVIDENCE_LENGTH = 80

    private data class Rule(val id: String, val pattern: Regex, val severity: StaticSeverity, val message: String)

    private val RULES = listOf(
        Rule("dynamic-eval", Regex("""\beval\s*\("""), StaticSeverity.BLOCK, "使用 eval 动态执行代码，无法静态审计"),
        Rule("dynamic-function", Regex("""\bnew\s+Function\s*\("""), StaticSeverity.BLOCK, "使用 new Function 动态构造代码"),
        Rule("dynamic-import", Regex("""\bimport\s*\("""), StaticSeverity.BLOCK, "动态 import 可加载第二段远程脚本"),
        Rule("remote-script-text", Regex("""data:text/javascript""", RegexOption.IGNORE_CASE), StaticSeverity.BLOCK, "内联 data: JS 脚本，等价于第二段代码"),
        Rule("node-require", Regex("""\brequire\s*\(\s*['"]"""), StaticSeverity.BLOCK, "使用 Node require，宿主未提供该能力"),
        Rule("node-fs", Regex("""\bfs\s*\.\s*(readFile|writeFile|readdir|unlink)"""), StaticSeverity.BLOCK, "尝试访问文件系统"),
        Rule("node-process", Regex("""\bprocess\s*\.\s*(env|binding|exit|mainModule)"""), StaticSeverity.BLOCK, "尝试访问进程环境"),
        Rule("node-child-process", Regex("""child_process"""), StaticSeverity.BLOCK, "尝试启动子进程"),
        Rule("websocket", Regex("""\bWebSocket\b"""), StaticSeverity.BLOCK, "使用 WebSocket 隧道，绕过宿主网络策略"),
        Rule("card-key", RegexCompact("""(CARD_KEY|cardKey|卡密|卡密共享)"""), StaticSeverity.BLOCK, "内嵌卡密/共享密钥"),
        Rule("membership-bypass", RegexCompact("""(SVIP|破解|解锁会员|会员解锁)"""), StaticSeverity.BLOCK, "以会员权益解锁为功能定位"),
        Rule("browser-cookie", Regex("""document\s*\.\s*cookie"""), StaticSeverity.WARN, "读取浏览器 Cookie，宿主不存在该对象"),
        Rule("browser-storage", Regex("""(localStorage|sessionStorage)"""), StaticSeverity.WARN, "使用浏览器存储，宿主不存在该对象"),
        Rule("xhr", Regex("""\bXMLHttpRequest\b"""), StaticSeverity.WARN, "使用 XMLHttpRequest，应改走 lx.request"),
        Rule("busy-wait", Regex("""while\s*\(\s*(true|1)\s*\)"""), StaticSeverity.WARN, "存在无限循环，依赖运行时超时中断"),
    )

    /** `卡密` 之类的全角/中文字面量不需要忽略大小写，用精简构造避免重复的选项参数。 */
    private fun RegexCompact(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    fun scan(
        scriptText: String,
        allowedHosts: Collection<String>,
        maxBytes: Int = DEFAULT_MAX_SCRIPT_BYTES,
    ): StaticScanReport {
        val findings = mutableListOf<StaticFinding>()
        val byteSize = scriptText.encodeToByteArray().size
        if (byteSize > maxBytes) {
            findings += StaticFinding(
                ruleId = "oversized",
                severity = StaticSeverity.BLOCK,
                message = "脚本 $byteSize 字节，超过上限 $maxBytes",
                evidence = "",
            )
            return StaticScanReport(StaticVerdict.BLOCKED, findings, allowedHosts.toSet(), emptySet())
        }

        for (rule in RULES) {
            val match = rule.pattern.find(scriptText) ?: continue
            findings += StaticFinding(
                ruleId = rule.id,
                severity = rule.severity,
                message = rule.message,
                evidence = excerpt(scriptText, match.range.first),
            )
        }

        val obfuscationMarkers = Regex("""_0x[0-9a-f]{2,}""").findAll(scriptText).count()
        if (obfuscationMarkers >= MAX_OBFUSCATION_MARKERS) {
            findings += StaticFinding(
                ruleId = "obfuscated",
                severity = StaticSeverity.BLOCK,
                message = "命中 $obfuscationMarkers 处 _0x 混淆标记，无法审计其网络与凭据行为",
                evidence = "",
            )
        }

        val hosts = extractHosts(scriptText)
        val declared = normalizeHosts(allowedHosts)
        val undeclared = hosts.filter { host -> declared.none { it.matches(host) } }.toSet()
        if (undeclared.isNotEmpty()) {
            findings += StaticFinding(
                ruleId = "undeclared-host",
                severity = StaticSeverity.BLOCK,
                message = "脚本请求未在目录声明的域名：${undeclared.joinToString(", ")}",
                evidence = undeclared.joinToString(", ").take(MAX_EVIDENCE_LENGTH),
            )
        }

        val verdict = when {
            findings.any { it.severity == StaticSeverity.BLOCK } -> StaticVerdict.BLOCKED
            findings.isNotEmpty() -> StaticVerdict.NEEDS_REVIEW
            else -> StaticVerdict.CLEAN
        }
        return StaticScanReport(verdict, findings, declared.map { it.raw }.toSet(), undeclared)
    }

    /** 从脚本文本里抽出所有绝对 URL 的主机名（含 http://，因为国内接口大量是明文）。 */
    fun extractHosts(scriptText: String): Set<String> =
        Regex("""(?:https?|wss?)://([A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)""", RegexOption.IGNORE_CASE)
            .findAll(scriptText)
            .mapNotNull { it.groupValues.getOrNull(1)?.lowercase()?.trim('.') }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun normalizeHosts(allowedHosts: Collection<String>): List<HostPattern> = allowedHosts
        .mapNotNull { raw ->
            val value = raw.trim().lowercase()
            when {
                value.isEmpty() -> null
                value.startsWith("*.") -> HostPattern(value, wildcard = true)
                else -> HostPattern(value, wildcard = false)
            }
        }

    private data class HostPattern(val raw: String, val wildcard: Boolean) {
        fun matches(host: String): Boolean {
            if (!wildcard) return host == raw
            // `*.example.com` 同时匹配裸域 example.com 与任意子域
            val suffix = raw.removePrefix("*")
            return host == suffix.removePrefix(".") || host.endsWith(suffix)
        }
    }

    private fun excerpt(text: String, index: Int): String {
        val start = (index - 20).coerceAtLeast(0)
        val end = (index + MAX_EVIDENCE_LENGTH).coerceAtMost(text.length)
        return text.substring(start, end).replace('\n', ' ').replace('\r', ' ').trim()
    }
}
