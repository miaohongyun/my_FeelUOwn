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
 */
internal object LxNetworkPolicy {

    /**
     * @param allowedHosts 目录为这个脚本声明的域名白名单。**非空时**只允许白名单内的主机；
     *   为空表示"目录没声明"，此时只做 SSRF 与协议校验（宽松模式，仅用于本地调试脚本）。
     */
    fun rejectionReason(rawUrl: String, allowedHosts: Set<String> = emptySet()): String? {
        val url = rawUrl.trim()
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "仅允许 http/https 协议"
        }
        val host = extractHost(lower) ?: return "URL 缺少主机名"
        if (host.isEmpty()) return "URL 缺少主机名"

        if (host == "localhost" || host.endsWith(".localhost")) return "禁止访问 localhost"
        if (host.startsWith("[")) return "禁止访问 IPv6 字面量地址"
        if (isPrivateIpv4(host)) return "禁止访问私网/回环地址"
        if (allowedHosts.isNotEmpty() && !hostAllowed(host, allowedHosts)) {
            return "域名未在目录声明：$host"
        }

        return null
    }

    private fun hostAllowed(host: String, allowedHosts: Set<String>): Boolean = allowedHosts.any { raw ->
        val pattern = raw.trim().lowercase()
        when {
            pattern.isEmpty() -> false
            pattern.startsWith("*.") -> host == pattern.removePrefix("*.") || host.endsWith(pattern.removePrefix("*"))
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

    private fun isPrivateIpv4(host: String): Boolean {
        val segments = host.split('.')
        if (segments.size != 4) return false
        val octets = IntArray(4)
        for (index in 0..3) {
            octets[index] = segments[index].toIntOrNull() ?: return false
            if (octets[index] !in 0..255) return false
        }
        return when {
            octets[0] == 0 -> true
            octets[0] == 10 -> true
            octets[0] == 127 -> true
            octets[0] == 169 && octets[1] == 254 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            else -> false
        }
    }
}
