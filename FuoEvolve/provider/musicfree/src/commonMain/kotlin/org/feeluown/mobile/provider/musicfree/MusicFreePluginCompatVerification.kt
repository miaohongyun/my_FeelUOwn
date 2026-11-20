package org.feeluown.mobile.provider.musicfree

import io.ktor.http.Url
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.feeluown.mobile.provider.core.network.currentTimeMillis
import org.feeluown.mobile.provider.lx.CandidateNetworkPolicy
import org.feeluown.mobile.provider.musicfree.mfJson
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.GatewayResult
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.ProviderNetworkPermission
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.Request
import org.feeluown.mobile.sourceupdate.crypto.Sha256

/**
 * 阶段 I5 / J0：真实插件兼容性验证台账与受控网络桥接。
 *
 * 诚实分级（不把模拟/静态当真实）：
 * - STATIC_REVIEW：插件正文取回 + SHA 核对 + require 清单扫描 + 红线扫描。
 * - COMMONJS_REQUIRE_PASSED：沙箱内真实 require + 初始化 + 读 module.exports。
 * - REAL_SEARCH / REAL_URL_RESOLVE：需真实网络且经 Gateway 授权；未授权一律 DENIED，不伪造。
 *
 * J0 整改（对应任务书 2.3 / 2.4）：
 * - params/headers 改用 kotlinx.serialization 解析（不再手写字符扫描）；
 * - query 使用 application/x-www-form-urlencoded 的 UTF-8 编码（中文/空格/&/+/%/引号/emoji 正确）；
 * - SHA-256 从**实际加载的原始脚本字节**现场计算（[shaOfScriptBytes]），禁止固定字符串冒充；
 * - 异常一律抛出，绝不转换成伪成功响应。
 */
object MusicFreePluginCompatVerification {

    enum class Level {
        STATIC_REVIEW, LX_RUNTIME_LOAD, COMMONJS_REQUIRE_PASSED,
        REAL_SEARCH, REAL_URL_RESOLVE, REAL_AUDIO_RESPONSE, REAL_PLAYBACK,
    }

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

    /** J2：单次 Gateway 调用的审计快照（不含任何凭据头）。 */
    data class GatewayCallAudit(
        val host: String,
        val method: String,
        val port: Int,
        val decision: String,      // ALLOW / DENIED / FAILED
        val httpStatus: Int?,      // 仅 Ok 时非空
        val responseBytes: Int,
        val durationMs: Long,
        val failureReason: String? = null,
    )

    /** 响应序列化 Json：不对 JS 侧 data 做任何隐式改写。 */
    private val responseJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * 从实际加载的原始脚本字节计算 SHA-256（J0 2.4）。
     * 调用方必须把"交给沙箱执行的同一份字节"传进来，禁止传固定常量。
     */
    fun shaOfScriptBytes(scriptBytes: ByteArray): String = Sha256.hex(scriptBytes)

    /**
     * 把插件 axios GET 请求接到 ProviderNetworkGateway 的桥接。
     * [scriptBytes] 是当前实际加载的插件原始字节：每次请求现场计算 SHA 传给 Gateway，
     * 与权限登记值不符即被拒绝——不存在"调用方随手传个固定 SHA 冒充当前脚本"的路径。
     */
    fun gatewayBridge(
        gateway: ProviderNetworkGateway,
        permission: ProviderNetworkPermission,
        scriptBytes: ByteArray,
        calls: MutableList<Request> = mutableListOf(),
        auditSink: ((GatewayCallAudit) -> Unit)? = null,
    ): suspend (url: String, paramsJson: String, headersJson: String) -> String {
        val expectedSha = shaOfScriptBytes(scriptBytes)
        return { url, paramsJson, headersJson ->
            val started = currentTimeMillis()
            val finalUrl = appendQuery(url, encodeQuery(paramsJson))
            val headers = decodeStringMap(headersJson)
            val parsed = runCatching { Url(finalUrl) }.getOrNull()
            val host = parsed?.host ?: "unknown"
            val port = parsed?.port?.takeIf { it > 0 } ?: 443
            val result = gateway.get(permission, Request(finalUrl, "GET", headers), expectedSha)
            val duration = currentTimeMillis() - started
            when (result) {
                is GatewayResult.Ok -> {
                    calls += Request(finalUrl, "GET", headers)
                    auditSink?.invoke(
                        GatewayCallAudit(host, "GET", port, "ALLOW", result.response.statusCode,
                            result.response.body.encodeToByteArray().size, duration),
                    )
                    val body = buildJsonObject {
                        put("status", result.response.statusCode)
                        put("data", result.response.body)
                        put("contentType", result.response.contentType.orEmpty())
                        put("headers", JsonObject(emptyMap()))
                    }
                    responseJson.encodeToString(JsonElement.serializer(), body)
                }
                is GatewayResult.Denied -> {
                    calls += Request(finalUrl, "GET", headers) // 记录尝试（Gateway 未触达 executor）
                    auditSink?.invoke(GatewayCallAudit(host, "GET", port, "DENIED", null, 0, duration, result.reason))
                    throw IllegalStateException("网络被 Gateway 拒绝：${result.reason}")
                }
                is GatewayResult.Failed -> {
                    auditSink?.invoke(GatewayCallAudit(host, "GET", port, "FAILED", null, 0, duration, result.reason))
                    throw IllegalStateException("网络失败：${result.reason}")
                }
            }
        }
    }

    /** 解析扁平 JSON 对象为 Map<String,String>；非字符串值用其 JSON 文本，异常向上传播。 */
    private fun decodeStringMap(json: String): Map<String, String> {
        if (json.isBlank() || json == "{}") return emptyMap()
        val element = mfJson.parseToJsonElement(json)
        require(element is JsonObject) { "params/headers 必须是 JSON 对象：$json" }
        return element.entries.associate { (key, value) ->
            key to when (value) {
                is JsonPrimitive -> value.content
                else -> mfJson.encodeToString(JsonElement.serializer(), value)
            }
        }
    }

    /** 用 UTF-8 form 编码把 params JSON 编成 query；null/空对象返回空串。 */
    private fun encodeQuery(paramsJson: String): String {
        if (paramsJson.isBlank() || paramsJson == "{}" || paramsJson == "null") return ""
        val element = mfJson.parseToJsonElement(paramsJson)
        require(element is JsonObject) { "params 必须是 JSON 对象：$paramsJson" }
        return element.entries
            .filter { (_, v) -> v !is JsonPrimitive || !v.isString || v.content.isNotEmpty() }
            .joinToString("&") { (key, value) ->
                val raw = when (value) {
                    is JsonPrimitive -> value.content
                    else -> mfJson.encodeToString(JsonElement.serializer(), value)
                }
                "${formEncode(key)}=${formEncode(raw)}"
            }
    }

    /**
     * application/x-www-form-urlencoded UTF-8 编码：
     * 未保留字符（A-Za-z0-9-_.~）原样，空格 -> '+'，其余字节 -> %HH（大写十六进制）。
     * 中文/emoji 先 UTF-8 编码再逐字节转义，& = + % " 等危险字符全部转义。
     */
    internal fun formEncode(value: String): String {
        val bytes = value.encodeToByteArray()
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val u = b.toInt() and 0xFF
            when {
                u in 0x41..0x5A || u in 0x61..0x7A || u in 0x30..0x39 ||
                    u == 0x2D || u == 0x5F || u == 0x2E || u == 0x7E -> sb.append(u.toChar())
                u == 0x20 -> sb.append('+')
                else -> sb.append('%').append(
                    u.toString(16).uppercase().padStart(2, '0'),
                )
            }
        }
        return sb.toString()
    }

    /** 把 query 追加到 URL，正确处理已有 ?（含末尾 ? 与 &）。 */
    internal fun appendQuery(url: String, query: String): String {
        if (query.isEmpty()) return url
        return when {
            '?' !in url -> "$url?$query"
            url.endsWith("?") || url.endsWith("&") -> "$url$query"
            else -> "$url&$query"
        }
    }

    /** 为插件构造 deny-all 权限（无授权域名，全部网络请求被 Gateway 拒绝）。 */
    fun denyAllPermission(pluginId: String, sha: String, version: String = "0.0.0") =
        ProviderNetworkPermission(
            providerId = pluginId,
            providerVersion = version,
            scriptSha256 = sha,
            candidatePolicy = CandidateNetworkPolicy(
                candidateId = pluginId,
                boundSha256 = sha,
                boundVersion = version,
                allowedHosts = emptySet(),
                authorizationBasis = "I5/J0：插件正文已静态审查但未授予任何域名，默认拒绝全部网络",
                denialReason = "插件 $pluginId 尚无具名域名人工授权，网络默认拒绝（不放宽白名单）",
            ),
            authorizationBasis = "I5/J0 deny-all 验证",
        )
}
