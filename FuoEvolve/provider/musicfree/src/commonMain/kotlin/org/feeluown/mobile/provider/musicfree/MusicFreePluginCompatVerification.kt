package org.feeluown.mobile.provider.musicfree

import org.feeluown.mobile.provider.lx.CandidateNetworkPolicy
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.GatewayResult
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.ProviderNetworkPermission
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.Request

/**
 * 阶段 I5：真实插件兼容性验证台账与受控网络桥接。
 *
 * 诚实分级（不把模拟/静态当真实）：
 * - STATIC_REVIEW：插件正文取回 + SHA 核对 + require 清单扫描 + 红线扫描（已完成，见 NodeCompatModules）。
 * - COMMONJS_REQUIRE_PASSED：沙箱内真实 require + 初始化 + 读 module.exports（本阶段验证）。
 * - REAL_SEARCH / REAL_URL_RESOLVE：需真实网络且经 Gateway 授权；未授权域名一律 DENIED，不伪造。
 */
object MusicFreePluginCompatVerification {

    /** 七级验证等级（与任务书一致，严格顺序）。 */
    enum class Level {
        STATIC_REVIEW, LX_RUNTIME_LOAD, COMMONJS_REQUIRE_PASSED,
        REAL_SEARCH, REAL_URL_RESOLVE, REAL_AUDIO_RESPONSE, REAL_PLAYBACK,
    }

    /** 插件兼容测试记录：每个能力给等级与拒绝原因，失败也如实记。 */
    data class PluginCheck(
        val pluginId: String,
        val sha256: String,
        val initialization: Level,
        val requireResult: String,
        val searchLevel: Level?,
        val searchNote: String,
        val urlResolveLevel: Level?,
        val resourceReleased: Boolean,
    )

    /**
     * 构造一个把插件 axios 请求接到 ProviderNetworkGateway 的网络桥接。
     * [permission] 绑定插件 ID/版本/SHA/授权域名；未授权域名经 Gateway 默认拒绝，零网络。
     * 这里只做 GET（与 shim 对齐），params 拼到 URL query；headers 透传但禁止凭据（Gateway 会再过滤）。
     */
    fun gatewayBridge(
        gateway: ProviderNetworkGateway,
        permission: ProviderNetworkPermission,
        currentSha: String,
        calls: MutableList<Request> = mutableListOf(),
    ): suspend (url: String, paramsJson: String, headersJson: String) -> String = { url, paramsJson, headersJson ->
        val query = parseSimpleParams(paramsJson)
        val finalUrl = if (query.isEmpty()) url else url + (if ("?" in url) "&" else "?") + query
        val headers = parseHeaderMap(headersJson)
        val result = gateway.get(permission, Request(finalUrl, "GET", headers), currentSha)
        when (result) {
            is GatewayResult.Ok -> {
                calls += Request(finalUrl, "GET", headers)
                """{"status":${result.response.statusCode},"data":${jsonQuote(result.response.body)},"headers":{}}"""
            }
            is GatewayResult.Denied -> {
                calls += Request(finalUrl, "GET", headers) // 记录尝试，但 Gateway 未触达 executor
                throw IllegalStateException("网络被 Gateway 拒绝：${result.reason}")
            }
            is GatewayResult.Failed -> throw IllegalStateException("网络失败：${result.reason}")
        }
    }

    /** 极简 JSON 扁平对象 -> Map（headers）。 */
    private fun parseHeaderMap(json: String): Map<String, String> {
        if (json.isBlank() || json == "{}") return emptyMap()
        val out = mutableMapOf<String, String>()
        val kv = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
        kv.findAll(json).forEach { out[it.groupValues[1]] = it.groupValues[2] }
        return out
    }

    /** 极简 JSON 字符串 kv 解析（params/headers 来自 JS JSON.stringify 的扁平对象）。 */
    private fun parseSimpleParams(json: String): String {
        if (json.isBlank() || json == "{}") return ""
        val pairs = mutableListOf<String>()
        var i = 0
        while (i < json.length) {
            val k1 = json.indexOf('"', i); if (k1 < 0) break
            val k2 = json.indexOf('"', k1 + 1); if (k2 < 0) break
            val key = json.substring(k1 + 1, k2)
            val colon = json.indexOf(':', k2); if (colon < 0) break
            i = colon + 1
            while (i < json.length && json[i] == ' ') i++
            val value = when {
                i < json.length && json[i] == '"' -> {
                    val v2 = json.indexOf('"', i + 1)
                    if (v2 < 0) break
                    val v = json.substring(i + 1, v2); i = v2 + 1; v
                }
                else -> {
                    val start = i
                    while (i < json.length && json[i] != ',' && json[i] != '}') i++
                    json.substring(start, i).trim()
                }
            }
            pairs += "$key=$value"
            while (i < json.length && json[i] != ',') i++
            i++
        }
        return pairs.joinToString("&")
    }

    private fun jsonQuote(value: String): String {
        val sb = StringBuilder(value.length + 2).append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** 为插件构造一个" deny-all"权限（无授权域名，全部网络请求被 Gateway 拒绝）。 */
    fun denyAllPermission(pluginId: String, sha: String) = ProviderNetworkPermission(
        providerId = pluginId,
        providerVersion = "0.0.0",
        scriptSha256 = sha,
        candidatePolicy = CandidateNetworkPolicy(
            candidateId = pluginId,
            boundSha256 = sha,
            allowedHosts = emptySet(),
            authorizationBasis = "I5：插件正文已静态审查但未授予任何域名，默认拒绝全部网络",
            denialReason = "插件 $pluginId 尚无具名域名人工授权，网络默认拒绝（不放宽白名单）",
        ),
        authorizationBasis = "I5 deny-all 验证",
    )
}
