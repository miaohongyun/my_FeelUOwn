package org.feeluown.mobile.provider.lx

/**
 * `lx.request` 的出站校验（SSRF 防护）。
 *
 * 为什么需要这一层：LX 脚本是第三方代码，`lx.request(url, ...)` 里的 url 完全由脚本决定。
 * 如果宿主照单全收，脚本就能拿 App 的身份去探测内网（路由器管理页、NAS、局域网曲库）。
 * 官方移动端把脚本放在独立环境里，我们做不到进程级隔离，就必须在这里挡住。
 *
 * 允许明文 http：国内音乐接口大量仍是 http，且本 App 的 network_security_config 已允许
 * cleartext。真正的边界是"不能打内网"，而不是"必须 https"。
 *
 * 空白名单语义（安全边界，不可放宽）：**allowedHosts 为空 = 拒绝一切网络请求**。
 * 原因：静态扫描只能发现源码里写死的字符串，脚本用字符串拼接/远端响应动态生成 URL 时，
 * 静态提取不完整。若空名单放行，等于把 SSRF 防护退化成"挡住写死的内网 IP"。
 * 目录解析器同时保证"可自动安装的脚本必须声明非空 allowedHosts"（两道防线）。
 */
internal object LxNetworkPolicy {

    /**
     * @param allowedHosts 目录为这个脚本声明的域名白名单。**必须非空**，且 host 必须命中其一。
    *   空集合在正式运行时一律拒绝；仅返回错误文案，由调用方决定如何展示。
     */
    fun rejectionReason(rawUrl: String, allowedHosts: Set<String> = emptySet()): String? {
        if (allowedHosts.isEmpty()) {
            return "脚本未声明 allowedHosts，拒绝全部网络请求"
        }
        val dangerous = dangerousTargetReason(rawUrl, httpsOnly = false)
        if (dangerous != null) return dangerous
        val lower = rawUrl.trim().lowercase()
        val host = extractHost(lower)!!
        val normalized = normalizeIpv4(host)
        if (!hostAllowed(host, normalized, allowedHosts)) {
            return "域名未在目录声明：$host"
        }
        return null
    }

    /**
     * 纯 SSRF 危险目标检测（不涉及白名单），供受控下载器等场景复用。
     * [httpsOnly] 为 true 时连明文 http 一起拒绝——社区脚本下载默认只走 HTTPS。
     * 返回 null 表示目标地址安全；非 null 为拒绝原因。
     */
    fun dangerousTargetReason(rawUrl: String, httpsOnly: Boolean): String? {
        val lower = rawUrl.trim().lowercase()
        if (httpsOnly) {
            if (!lower.startsWith("https://")) return "仅允许 HTTPS 协议"
        } else if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "仅允许 http/https 协议"
        }
        val host = extractHost(lower) ?: return "URL 缺少主机名"
        if (host.isEmpty()) return "URL 缺少主机名"

        if (host == "localhost" || host.endsWith(".localhost")) return "禁止访问 localhost"
        if (host.startsWith("[")) return "禁止访问 IPv6 字面量地址"
        // 非标准 IPv4 表示（0x7f000001 / 2130706433 / 127.1 / 0177.0.0.1 等）
        // 必须先归一化再判私网，否则浏览器/内核会解析成回环地址而绕过这里的字符串比较。
        val normalized = normalizeIpv4(host)
        if (normalized != null && isUnsafeIpv4(normalized)) return "禁止访问私网/回环/保留地址"
        return null
    }

    private fun hostAllowed(host: String, normalizedIpv4: String?, allowedHosts: Set<String>): Boolean =
        allowedHosts.any { raw ->
            val pattern = raw.trim().lowercase()
            when {
                pattern.isEmpty() -> false
                // 白名单里声明的是 IP 时，用归一化后的地址比较，避免 127.1 之类的变体绕过。
                normalizedIpv4 != null && isIpLiteral(pattern) -> normalizeIpv4(pattern) == normalizedIpv4
                pattern.startsWith("*.") -> {
                    val suffix = pattern.removePrefix("*.")
                    host == suffix || host.endsWith(".$suffix")
                }
                else -> host == pattern
            }
        }

    private fun extractHost(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            return if (end > 0) hostPort.substring(0, end + 1) else hostPort
        }
        return hostPort.substringBefore(':')
    }

    /** 只有全部由数字、点、0x 组成的 host 才可能是 IP 字面量；域名一律返回 null。 */
    private fun isIpLiteral(host: String): Boolean =
        host.isNotBlank() && host.all { it.isDigit() || it == '.' || it == 'x' || it in 'a'..'f' }

    /**
     * 把常见 IPv4 写法归一成 `a.b.c.d`：
     * - 点分十进制 `192.168.1.1`
     * - 短形式 `127.1`（补 0 段）
     * - 八进制 `0177.0.0.1`（0 前缀）
     * - 十六进制 `0x7f000001` / `0x7f.0x0.0x0.0x1`
     * - 纯整数 `2130706433`
     * 无法解析（域名、非法值）返回 null。
     */
    private fun normalizeIpv4(host: String): String? {
        val value = host.trim().lowercase()
        if (!isIpLiteral(value)) return null

        return try {
            if (!value.contains('.')) {
                val number = parseRadix(value) ?: return null
                if (number < 0 || number > 0xffff_ffffL) return null
                return longToIpv4(number)
            }
            val parts = value.split('.')
            if (parts.size > 4 || parts.isEmpty()) return null
            val numbers = parts.map { parseRadix(it) ?: return null }
            if (numbers.any { it < 0 || it > 255 }) return null
            // 短形式：最后一段承载剩余字节（127.1 -> 127.0.0.1）
            val filled = when (numbers.size) {
                4 -> numbers
                3 -> listOf(numbers[0], numbers[1], numbers[2] shr 8, numbers[2] and 0xff)
                2 -> listOf(numbers[0], numbers[1] shr 16, (numbers[1] shr 8) and 0xff, numbers[1] and 0xff)
                1 -> longToIpv4(numbers[0]).split('.').map { it.toLong() }
                else -> return null
            }
            if (filled.any { it < 0 || it > 255 }) return null
            filled.joinToString(".") { it.toString() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRadix(part: String): Long? = when {
        part.isEmpty() -> null
        part.startsWith("0x") -> part.removePrefix("0x").toLongOrNull(16)
        part.length > 1 && part.startsWith('0') && part.all { it in '0'..'7' } ->
            part.removePrefix("0").ifEmpty { "0" }.toLongOrNull(8)
        part.all { it.isDigit() } -> part.toLongOrNull()
        else -> null
    }

    private fun longToIpv4(value: Long): String = listOf(
        (value shr 24) and 0xff,
        (value shr 16) and 0xff,
        (value shr 8) and 0xff,
        value and 0xff,
    ).joinToString(".")

    /**
     * 归一化后的 IPv4 是否属于不可访问地址段。
     * 覆盖 RFC1918、回环、链路本地、CGNAT、运营商保留、组播、广播与元数据服务。
     */
    private fun isUnsafeIpv4(host: String): Boolean {
        val segments = host.split('.')
        if (segments.size != 4) return true
        val o = IntArray(4)
        for (index in 0..3) {
            o[index] = segments[index].toIntOrNull() ?: return true
            if (o[index] !in 0..255) return true
        }
        return when {
            o[0] == 0 -> true                                       // 0.0.0.0/8 "this network"
            o[0] == 10 -> true                                      // RFC1918
            o[0] == 127 -> true                                     // loopback
            o[0] == 169 && o[1] == 254 -> true                      // link-local + 169.254.169.254 metadata
            o[0] == 172 && o[1] in 16..31 -> true                   // RFC1918
            o[0] == 192 && o[1] == 168 -> true                      // RFC1918
            o[0] == 100 && o[1] in 64..127 -> true                  // CGNAT (RFC6598)
            o[0] == 192 && o[1] == 0 && o[2] == 0 -> true          // IETF protocol assignments
            o[0] == 192 && o[1] == 0 && o[2] == 2 -> true          // TEST-NET-1
            o[0] == 198 && (o[1] == 18 || o[1] == 19) -> true      // benchmarking
            o[0] == 198 && o[1] == 51 && o[2] == 100 -> true       // TEST-NET-2
            o[0] == 203 && o[1] == 0 && o[2] == 113 -> true        // TEST-NET-3
            o[0] in 224..239 -> true                               // multicast
            o[0] in 240..255 -> true                               // reserved + broadcast
            else -> false
        }
    }
}

/**
 * [LxNetworkPolicy] 危险目标检测的公开门面，供受控下载器等同仓模块复用。
 * 只暴露纯 SSRF 判定，不改变 LX 脚本运行时的白名单语义。
 */
object LxUrlSafety {
    fun dangerousTargetReason(rawUrl: String, httpsOnly: Boolean): String? =
        LxNetworkPolicy.dangerousTargetReason(rawUrl, httpsOnly = httpsOnly)
}
