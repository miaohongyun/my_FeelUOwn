package org.feeluown.mobile.provider.lx

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 一个 LX Music 自定义音源脚本的执行实例。
 *
 * 设计取舍：
 * - 每个脚本一个独立 QuickJS context，脚本之间不共享全局对象（官方移动端也是这么隔离的）。
 * - 网络不由脚本直连：脚本调 `lx.request` → JS shim → Kotlin `__lxHttp` → Ktor。这样
 *   宿主能做 scheme/私网校验、超时和体积上限，脚本拿不到 socket。
 * - 超时靠 `evaluationTimeoutMillis`，QuickJS 会强制中断死循环，而不是让播放流程一直挂着。
 * - `crypto` / `zlib` 暂不实现：官方移动端对 zlib 也是「未实现」，crypto 只支持
 *   aes-128-cbc/ecb + md5 + rsaEncrypt。这类脚本在 FuoEvolve 上会明确失败而不是给出错误 URL。
 */
@OptIn(ExperimentalEncodingApi::class)
class LxScriptRuntime(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : AutoCloseable {

    /**
     * 关闭自动重定向的客户端视图：重定向由 [sendFollowingAllowedRedirects] 手动处理，
     * 每一跳都重新过域名白名单。
     */
    private val noRedirectClient = httpClient.config { followRedirects = false }

    private var quickJs: QuickJs? = null
    private var scriptName: String = ""
    private var loaded = false

    /** 目录为该脚本声明的域名白名单；为空表示未声明（只做 SSRF 校验）。 */
    private var allowedHosts: Set<String> = emptySet()

    val isLoaded: Boolean get() = loaded

    /** 加载并执行脚本源码。执行期间的顶层异常按官方语义视为「初始化失败」。 */
    suspend fun load(
        name: String,
        source: String,
        timeoutMs: Long = LxProtocol.DEFAULT_INIT_TIMEOUT_MS,
        allowedHosts: Set<String> = emptySet(),
    ) {
        require(source.encodeToByteArray().size <= LxProtocol.MAX_SCRIPT_BYTES) {
            "脚本体积超过 ${LxProtocol.MAX_SCRIPT_BYTES} 字节上限"
        }
        close()

        scriptName = name
        this.allowedHosts = allowedHosts
        val instance = QuickJs.create(Dispatchers.Default)
        instance.evaluationTimeoutMillis = timeoutMs
        try {
            registerBindings(instance)
            instance.evaluate<Any?>(LxBridge.source(name), filename = "lx_bridge.js")
            instance.evaluate<Any?>(source, filename = name)
            loaded = true
        } catch (e: QuickJsException) {
            runCatching { instance.close() }
            throw LxScriptException("脚本 [$name] 执行失败: ${e.message}", e)
        } catch (e: CancellationException) {
            runCatching { instance.close() }
            throw e
        } catch (e: Exception) {
            runCatching { instance.close() }
            throw LxScriptException("脚本 [$name] 加载失败: ${e.message}", e)
        }
        quickJs = instance
    }

    /**
     * 读取 `inited` 事件声明的音源能力。
     *
     * 官方约束：初始化事件发送前出现任何错误即视为初始化失败，因此没有 `inited` 是硬错误，
     * 不能当"还没准备好"重试。
     */
    suspend fun initialize(timeoutMs: Long = LxProtocol.DEFAULT_INIT_TIMEOUT_MS): LxScriptMeta {
        val instance = requireInstance()
        instance.evaluationTimeoutMillis = timeoutMs
        val raw = evaluateOrTimeout(instance, timeoutMs) { instance.evaluate<String?>("__lxInited()") }
            ?: throw LxProtocolException("脚本 [$scriptName] 未发送 inited 事件")

        val payload = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw LxProtocolException("inited 载荷不是合法 JSON: ${it.message}") }

        payload["status"]?.jsonPrimitive?.contentOrNull?.let { status ->
            if (status.equals("false", ignoreCase = true)) {
                throw LxProtocolException("脚本 [$scriptName] 在 inited 中报告初始化失败")
            }
        }

        val sourcesObject = payload["sources"] as? JsonObject
            ?: throw LxProtocolException("inited 缺少 sources 对象")

        val sources = linkedMapOf<String, LxSourceInfo>()
        for ((platform, element) in sourcesObject) {
            if (platform !in LxProtocol.PLATFORMS) continue
            val obj = element as? JsonObject ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            // local 是本地文件源，FuoEvolve 自己有本地曲库，不做接入。
            if (type != "music" || platform == "local") continue
            val actions = obj["actions"].asStringList()
            if (LxProtocol.ACTION_MUSIC_URL !in actions) continue
            val qualitys = obj["qualitys"].asStringList()
            sources[platform] = LxSourceInfo(
                platform = platform,
                name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { platform },
                actions = actions,
                qualitys = qualitys,
            )
        }

        if (sources.isEmpty()) {
            throw LxProtocolException("脚本 [$scriptName] 的 inited 未声明任何可用的 music 源")
        }
        return LxScriptMeta(scriptName = scriptName, sources = sources)
    }

    /**
     * 请求一首歌的播放地址。返回脚本给出的原始 URL 字符串。
     *
     * 失败语义与 Mode B 对齐：拿不到 URL 就抛异常，由上层决定降级或换源，绝不返回空串。
     */
    suspend fun requestMusicUrl(
        platform: String,
        musicInfo: JsonObject,
        quality: String,
        timeoutMs: Long = LxProtocol.DEFAULT_EVALUATION_TIMEOUT_MS,
    ): String {
        val instance = requireInstance()
        instance.evaluationTimeoutMillis = timeoutMs

        val payload = buildJsonObject {
            put("source", platform)
            put("action", LxProtocol.ACTION_MUSIC_URL)
            put(
                "info",
                buildJsonObject {
                    put("type", quality)
                    put("musicInfo", musicInfo)
                },
            )
        }

        val result = evaluateOrTimeout(instance, timeoutMs) {
            // 脚本的 request 回调按官方要求必须返回 Promise。QuickJS 侧普通 evaluate 不会
            // 等待 Promise，所以这里用 ES module 执行顶层 await，把异步结果落回全局变量。
            instance.evaluate<Any?>(DISPATCH_MODULE_TEMPLATE.replace(DISPATCH_PAYLOAD, quote(payload.toString())), filename = "lx_dispatch.mjs", asModule = true)
            instance.evaluate<String?>(DISPATCH_RESULT_READER)
        }

        val errorText = instance.evaluate<String?>("globalThis.__lxDispatchError")
        if (!errorText.isNullOrBlank()) {
            throw LxScriptException("脚本 [$scriptName] 对 $platform/$quality 失败: $errorText")
        }

        return extractUrl(result)
            ?: throw LxScriptException("脚本 [$scriptName] 对 $platform/$quality 未返回可播放 URL")
    }

    /** 脚本是否注册了 request 事件（用于加载后快速判断脚本是否完整）。 */
    suspend fun hasRequestHandler(): Boolean {
        val instance = requireInstance()
        return runCatching { instance.evaluate<Boolean>("__lxHasHandler('request')") }.getOrDefault(false)
    }

    override fun close() {
        val instance = quickJs ?: return
        quickJs = null
        loaded = false
        runCatching { instance.close() }
    }

    // ---- 内部实现 ----

    private fun requireInstance(): QuickJs =
        quickJs ?: throw IllegalStateException("脚本尚未加载")

    /**
     * 求值超时**只**依赖 QuickJS 自己的 `evaluationTimeoutMillis`（它会在 native 侧强制中断
     * 正在跑的脚本）。这里不再套协程 `withTimeout`：JS 求值是阻塞调用而非挂起点，协程超时
     * 既无法打断它，又会在虚拟时间调度器（如测试环境）下与真实时间抢跑造成误判。
     */
    private suspend fun <T> evaluateOrTimeout(
        instance: QuickJs,
        timeoutMs: Long,
        block: suspend () -> T,
    ): T {
        instance.evaluationTimeoutMillis = timeoutMs
        return try {
            block()
        } catch (e: QuickJsException) {
            val message = e.message.orEmpty()
            if (message.contains("interrupt", ignoreCase = true) || message.contains("timeout", ignoreCase = true)) {
                throw LxScriptTimeoutException("脚本 [$scriptName] 求值超时 (${timeoutMs}ms)")
            }
            throw LxScriptException("脚本 [$scriptName] 执行异常: $message", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LxScriptTimeoutException) {
            throw e
        } catch (e: Exception) {
            throw LxScriptException("脚本 [$scriptName] 执行异常: ${e.message}", e)
        }
    }

    private fun extractUrl(result: Any?): String? = when (result) {
        null -> null
        is String -> result.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
        is JsonElement -> extractUrlFromElement(result)
        else -> {
            // QuickJS 返回的对象在 quickjs-kt 里可能是 JsObject/Map，统一转成字符串再解析。
            val text = result.toString()
            if (text.startsWith("http://") || text.startsWith("https://")) {
                text
            } else {
                runCatching { extractUrlFromElement(json.parseToJsonElement(text)) }.getOrNull()
            }
        }
    }

    private fun extractUrlFromElement(element: JsonElement): String? {
        if (element is JsonPrimitive) {
            val content = element.contentOrNull ?: return null
            return content.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
        val obj = element as? JsonObject ?: return null
        // 少数脚本返回 { url, quality } 而不是裸字符串。
        val direct = obj["url"]?.jsonPrimitive?.contentOrNull?.trim()
        if (!direct.isNullOrEmpty()) return direct
        return null
    }

    private fun registerBindings(instance: QuickJs) {
        instance.asyncFunction<String>("__lxHttp") { args ->
            val requestJson = args.firstOrNull()?.toString()
                ?: throw LxScriptException("__lxHttp 缺少请求参数")
            performHttp(requestJson)
        }
        instance.function<Any?>("__lxBufferFrom") { args ->
            bufferFrom(args.getOrNull(0)?.toString().orEmpty(), args.getOrNull(1)?.toString() ?: "utf8")
        }
        instance.function<String>("__lxBufToString") { args ->
            bufToString(args.getOrNull(0), args.getOrNull(1)?.toString() ?: "utf8")
        }
        instance.function<String>("__lxCryptoUnsupported") { args ->
            val name = args.firstOrNull()?.toString() ?: "crypto"
            throw LxScriptException(
                "lx.utils.crypto.$name 尚未在 FuoEvolve 中实现；该脚本依赖此原语，无法安全解析",
            )
        }
    }

    private suspend fun performHttp(requestJson: String): String {
        val request = json.parseToJsonElement(requestJson).jsonObject
        val url = request["url"]?.jsonPrimitive?.contentOrNull
            ?: throw LxScriptException("lx.request 缺少 url")
        val method = request["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
        val body = request["body"]?.jsonPrimitive?.contentOrNull
        val headerObject = request["headers"] as? JsonObject

        val rejection = LxNetworkPolicy.rejectionReason(url, allowedHosts)
        if (rejection != null) throw LxScriptException("lx.request 被拒绝: $rejection")

        val timeoutMs = (request["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L)
            .takeIf { it in 1..MAX_REQUEST_TIMEOUT_MS }
            ?: DEFAULT_REQUEST_TIMEOUT_MS

        return try {
            // 放到 Default 上计时，避免虚拟时间调度器把 timeout 提前触发。
            val responseOrNull = withContext(Dispatchers.Default) {
                withTimeoutOrNull(timeoutMs + SUPPORT_MARGIN_MS) {
                    sendFollowingAllowedRedirects(url, method, body, headerObject)
                }
            }
            val response = responseOrNull
                ?: throw LxScriptException("lx.request 超时 (${timeoutMs}ms): $url")

            val text = response.bodyAsText()
            val status = response.status.value
            val truncated = if (text.length > MAX_RESPONSE_CHARS) text.substring(0, MAX_RESPONSE_CHARS) else text

            buildJsonObject {
                put("statusCode", status)
                put("status", status)
                put("body", inferBody(truncated))
                put(
                    "headers",
                    buildJsonObject {
                        response.headers.entries().forEach { (key, values) ->
                            put(key, values.joinToString(","))
                        }
                    },
                )
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LxScriptException) {
            throw e
        } catch (e: Exception) {
            throw LxScriptException("lx.request 失败: ${e.message}", e)
        }
    }

    /**
     * 发一次请求，并在**白名单内**手动跟随重定向。
     *
     * 为什么关掉 Ktor 的自动重定向：脚本能控制 URL，而"目录批准的域名"是安全边界。
     * 自动跟随会让 `api.example.com -> evil.com` 这种 302 直接绕过白名单，
     * 等于白名单形同虚设（设计文档 11 明确要求拦截跨域重定向）。
     *
     * 相对 Location 在这里手工解析：Ktor 的 `URLBuilder` 对 `../` 的合并语义
     * 与 RFC 3986 不完全一致，与其赌它的行为，不如把这三种常见形态写清楚。
     */
    private suspend fun sendFollowingAllowedRedirects(
        url: String,
        method: String,
        body: String?,
        headers: JsonObject?,
    ): HttpResponse {
        var currentUrl = url
        var hops = 0
        while (true) {
            val response = noRedirectClient.request(currentUrl) {
                this.method = HttpMethod.parse(method)
                headers?.forEach { (key, value) ->
                    value.jsonPrimitive.contentOrNull?.let { this.headers.append(key, it) }
                }
                if (body != null && method != "GET" && method != "HEAD") setBody(body)
            }
            val status = response.status.value
            if (status !in REDIRECT_STATUSES) return response

            val location = response.headers[HttpHeaders.Location]
                ?: return response
            if (hops >= MAX_REDIRECTS) {
                throw LxScriptException("lx.request 重定向次数超过 $MAX_REDIRECTS 次: $url")
            }
            val next = resolveLocation(currentUrl, location)
            val rejection = LxNetworkPolicy.rejectionReason(next, allowedHosts)
            if (rejection != null) {
                throw LxScriptException("lx.request 重定向被拒绝（$rejection）: $currentUrl -> $next")
            }
            currentUrl = next
            hops++
        }
    }

    /** 解析 Location：绝对地址、协议相对、绝对路径、相对路径四种形态。 */
    private fun resolveLocation(base: String, location: String): String {
        val target = location.trim()
        val lower = target.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return target

        val scheme = base.substringBefore("://", "https")
        if (target.startsWith("//")) return "$scheme:$target"

        val originEnd = base.indexOf("://").let { if (it < 0) -1 else base.indexOf('/', it + 3) }
        val origin = if (originEnd < 0) base else base.substring(0, originEnd)
        if (target.startsWith("/")) return origin + target

        val basePath = if (originEnd < 0) "" else base.substring(originEnd).substringBefore('?').substringBefore('#')
        val directory = basePath.substringBeforeLast('/', "")
        return origin + directory + "/" + target
    }

    private fun inferBody(text: String): JsonElement {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching { json.parseToJsonElement(text) }.getOrNull()?.let { return it }
        }
        return JsonPrimitive(text)
    }

    private fun bufferFrom(value: String, format: String): ByteArray = when (format.lowercase()) {
        "hex" -> hexToBytes(value)
        "base64" -> decodeBase64(value)
        else -> value.encodeToByteArray()
    }

    private fun bufToString(value: Any?, format: String): String {
        val bytes = when (value) {
            is ByteArray -> value
            is String -> value.encodeToByteArray()
            null -> ByteArray(0)
            else -> value.toString().encodeToByteArray()
        }
        return when (format.lowercase()) {
            "hex" -> bytes.joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
            "base64" -> encodeBase64(bytes)
            else -> bytes.decodeToString()
        }
    }

    private fun hexToBytes(value: String): ByteArray {
        val hex = value.trim()
        if (hex.length % 2 != 0) throw LxScriptException("buffer.from(hex) 长度必须是偶数")
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte()
                ?: throw LxScriptException("buffer.from(hex) 含非法字符")
        }
    }

    private fun JsonElement?.asStringList(): List<String> =
        (this as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            ?: emptyList()

    private fun quote(value: String): String =
        json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

    private fun decodeBase64(value: String): ByteArray = runCatching {
        Base64.decode(value.trim())
    }.getOrElse { throw LxScriptException("buffer.from(base64) 非法: ${it.message}") }

    private fun encodeBase64(bytes: ByteArray): String = Base64.encode(bytes)

    private companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L
        const val MAX_REQUEST_TIMEOUT_MS = 30_000L
        const val MAX_RESPONSE_CHARS = 512 * 1024
        /** 给 QuickJS 自身的中断留一点余量，避免和协程超时互相抢跑。 */
        const val SUPPORT_MARGIN_MS = 500L

        const val DISPATCH_PAYLOAD = "__LX_PAYLOAD__"
        const val MAX_REDIRECTS = 3
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        /**
         * 以 ES module 执行，利用顶层 await 等脚本返回的 Promise。结果写回全局变量，
         * 因为 quickjs-kt 在 asModule 模式下不捕获模块返回值。
         */
        val DISPATCH_MODULE_TEMPLATE = """
            globalThis.__lxDispatchResult = null;
            globalThis.__lxDispatchError = null;
            try {
              globalThis.__lxDispatchResult = await __lxDispatch(__LX_PAYLOAD__);
            } catch (e) {
              globalThis.__lxDispatchError = String((e && e.message) || e);
            }
        """.trimIndent()

        val DISPATCH_RESULT_READER = """
            globalThis.__lxDispatchResult === null || globalThis.__lxDispatchResult === undefined
              ? null
              : (typeof globalThis.__lxDispatchResult === 'string'
                  ? globalThis.__lxDispatchResult
                  : JSON.stringify(globalThis.__lxDispatchResult))
        """.trimIndent()
    }
}
