package org.feeluown.mobile.provider.lx

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
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
import org.feeluown.mobile.sourceupdate.BoundedBodyRead
import org.feeluown.mobile.sourceupdate.HttpBodyLimits
import org.feeluown.mobile.sourceupdate.crypto.Md5
import org.feeluown.mobile.sourceupdate.readBodyBounded

/**
 * 一个 LX Music 自定义音源脚本的执行实例。
 *
 * 设计取舍：
 * - 每个脚本一个独立 QuickJS context，脚本之间不共享全局对象（官方移动端也是这么隔离的）。
 * - 网络不由脚本直连：脚本调 `lx.request` → JS shim → Kotlin `__lxHttp` → Ktor。这样
 *   宿主能做 scheme/私网校验、超时和体积上限，脚本拿不到 socket。
 * - 超时靠 `evaluationTimeoutMillis`，QuickJS 会强制中断死循环，而不是让播放流程一直挂着。
 * - `crypto` / `zlib` 仅实现 `md5` 与 `buffer`(base64/hex/utf8)：官方移动端对 zlib 标注
 *   「未实现」；AES/RSA/randomBytes 当前没有**已批准且真实可用**的脚本依赖（唯一依赖它们的
 *   無名/小熊猫均为 REJECT/QUARANTINE 且接口已失效），因此不盲目补齐。这些原语在桥接层
 *   调用时明确抛错，不返回伪造值；依赖它们的脚本无法初始化，而不是给出错误 URL。
 *
 * ## 生命周期与并发（[RuntimeSession] + [lifecycleMutex]）
 *
 * 每次 [load] 创建一个不可变的 [RuntimeSession]，它独占自己的 QuickJs 实例、timer scope、
 * timer 表、timer Job 集合和生命周期状态。所有绑定闭包捕获的是**那一次 load 的 session**
 * （局部 val），不再读取外层可变属性。因此旧 context 的 asyncFunction 即使晚于 close 恢复，
 * 也只能操作自己那份（已取消的）session，绝不会把延时挂到新 session 的 scope / 表。
 *
 * [load] 与 [close] 全程在 [lifecycleMutex] 下串行执行，消除两个竞态窗口：
 * - load/load：两个并发 load 不再互相覆盖——第二个在锁内看到第一个已发布的 session，
 *   先可靠关闭它再创建自己的；不会出现某个 session 建好后无人关闭而泄漏。
 * - load/close：close 必须等 load 完成"创建→绑定→evaluate→发布"整段结束后才执行，
 *   不会出现"close 只看到旧 session、而 load 随后又把新 session 发布出去"的漏关。
 *
 * 求值（[requestMusicUrl] 等）**不**与 close 用同一把锁：asModule 顶层 await 挂起等待
 * Promise 时，native evaluationTimeout 无法中断挂起（无 JS 指令在跑），若 close 等求值
 * 返回会一直卡到脚本 timer 到期。改由 quickjs-kt 的 `close()` 负责 cancel 它自己登记的
 * asyncJobs（含求值协程）——这是库保证的安全取消路径；宿主侧则保证自己的 timer Job 在
 * context 销毁前退出，两者叠加避免 use-after-free（见 [RuntimeSession.closeSuspend]）。
 *
 * [close] 的顺序是：标记 session 已关闭（阻止新 timer 登记）→ 取消所有 timer 信号 →
 * 取消 timerScope → **join 等待所有宿主 timer Job 真正退出** → 最后才销毁 QuickJs context。
 * 这样 close 返回后，不存在仍在运行、且可能访问已销毁 context 的宿主协程。
 *
 * quickjs-kt 1.0.15 的实证行为（反编译 QuickJs.class）：
 * - asyncFunction 的协程挂在**库自己的** coroutineScope 上（不是我们的 timer scope），并登记到
 *   库的 asyncJobs；`close()` 用 AtomicBoolean CAS 幂等，遍历 asyncJobs 逐个 cancel；
 * - resolve/reject JS Promise 前检查 `isClosed()`，close 后不再向 JS 写 Promise；
 * - `close()` **不 join** asyncFunction 协程，只 cancel 后立刻销毁 native context。
 * 因此宿主侧必须自己保证 timer 协程与登记表收尾——这正是 [RuntimeSession] 的职责。
 *
 * [close] 是 suspend 函数（本类不再实现 AutoCloseable）：调用方均已在协程上下文中
 * （Provider 的 suspend 方法、测试的 runTest/runBlocking），可以安全地 join 等待。
 */
@OptIn(ExperimentalEncodingApi::class)
class LxScriptRuntime(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /**
     * 关闭自动重定向的客户端视图：重定向由 [sendFollowingAllowedRedirects] 手动处理，
     * 每一跳都重新过域名白名单。此客户端无状态，可跨 session 共享。
     */
    private val noRedirectClient = httpClient.config { followRedirects = false }

    /** load/close 全周期互斥，消除 load/load、load/close 的发布窗口竞态。 */
    private val lifecycleMutex = Mutex()

    /** 当前会话；load 时整体替换。所有可变状态都封在 session 内。 */
    private var session: RuntimeSession? = null

    /** 已创建会话的快照（只读观测，供生命周期测试断言旧 session 不残留、不污染新 session）。 */
    private val sessionHistory = mutableListOf<RuntimeSession>()

    val isLoaded: Boolean get() = session?.let { !it.closed } ?: false

    /** 会话快照：id 递增、是否已关闭、当前仍在表的 timer 数。仅测试使用。 */
    internal data class SessionSnapshot(val id: Int, val closed: Boolean, val activeTimers: Int)

    internal suspend fun sessionSnapshots(): List<SessionSnapshot> {
        val snapshot = sessionHistory.toList()
        return snapshot.mapIndexed { index, s ->
            SessionSnapshot(index + 1, s.closed, s.activeTimerCount())
        }
    }

    /** 加载并执行脚本源码。执行期间的顶层异常按官方语义视为「初始化失败」。 */
    suspend fun load(
        name: String,
        source: String,
        timeoutMs: Long = LxProtocol.DEFAULT_INIT_TIMEOUT_MS,
        allowedHosts: Set<String> = emptySet(),
    ) = lifecycleMutex.withLock {
        require(source.encodeToByteArray().size <= LxProtocol.MAX_SCRIPT_BYTES) {
            "脚本体积超过 ${LxProtocol.MAX_SCRIPT_BYTES} 字节上限"
        }
        // 全程持锁：先可靠关闭旧 session 并 join 其 timer Job，再建新的。
        // closeLocked 不会再去抢 lifecycleMutex（它是内部实现），因此无自锁死锁。
        closeLocked()
        val newSession = RuntimeSession(name, allowedHosts, timeoutMs)
        sessionHistory += newSession
        val instance = newSession.quickJs
        try {
            registerBindings(instance, newSession)
            instance.evaluate<Any?>(LxBridge.source(name), filename = "lx_bridge.js")
            instance.evaluate<Any?>(source, filename = name)
            // 只有全部成功后才发布；失败时旧 session 已在上面 close，保持"无可用 runtime"。
            session = newSession
        } catch (e: QuickJsException) {
            newSession.closeSuspend()
            throw LxScriptException("脚本 [$name] 执行失败: ${e.message}", e)
        } catch (e: CancellationException) {
            newSession.closeSuspend()
            throw e
        } catch (e: Exception) {
            newSession.closeSuspend()
            throw LxScriptException("脚本 [$name] 加载失败: ${e.message}", e)
        }
    }

    /**
     * 读取 `inited` 事件声明的音源能力。
     *
     * 官方约束：初始化事件发送前出现任何错误即视为初始化失败，因此没有 `inited` 是硬错误，
     * 不能当"还没准备好"重试。
     */
    suspend fun initialize(timeoutMs: Long = LxProtocol.DEFAULT_INIT_TIMEOUT_MS): LxScriptMeta {
        val s = requireSession()
        val instance = s.quickJs
        instance.evaluationTimeoutMillis = timeoutMs
        val raw = evaluateOrTimeout(s, timeoutMs) { instance.evaluate<String?>("__lxInited()") }
            ?: throw LxProtocolException("脚本 [${s.name}] 未发送 inited 事件")

        val payload = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw LxProtocolException("inited 载荷不是合法 JSON: ${it.message}") }

        payload["status"]?.jsonPrimitive?.contentOrNull?.let { status ->
            if (status.equals("false", ignoreCase = true)) {
                throw LxProtocolException("脚本 [${s.name}] 在 inited 中报告初始化失败")
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
            throw LxProtocolException("脚本 [${s.name}] 的 inited 未声明任何可用的 music 源")
        }
        return LxScriptMeta(scriptName = s.name, sources = sources)
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
        val s = requireSession()
        val instance = s.quickJs
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

        val result = evaluateOrTimeout(s, timeoutMs) {
            // 脚本的 request 回调按官方要求必须返回 Promise。QuickJS 侧普通 evaluate 不会
            // 等待 Promise，所以这里用 ES module 执行顶层 await，把异步结果落回全局变量。
            instance.evaluate<Any?>(DISPATCH_MODULE_TEMPLATE.replace(DISPATCH_PAYLOAD, quote(payload.toString())), filename = "lx_dispatch.mjs", asModule = true)
            instance.evaluate<String?>(DISPATCH_RESULT_READER)
        }

        val errorText = instance.evaluate<String?>("globalThis.__lxDispatchError")
        if (!errorText.isNullOrBlank()) {
            throw LxScriptException("脚本 [${s.name}] 对 $platform/$quality 失败: $errorText")
        }

        return extractUrl(result)
            ?: throw LxScriptException("脚本 [${s.name}] 对 $platform/$quality 未返回可播放 URL")
    }

    /** 脚本是否注册了 request 事件（用于加载后快速判断脚本是否完整）。 */
    suspend fun hasRequestHandler(): Boolean {
        val instance = requireSession().quickJs
        return runCatching { instance.evaluate<Boolean>("__lxHasHandler('request')") }.getOrDefault(false)
    }

    /**
     * 关闭当前 session，并等待其所有宿主 timer Job 真正退出后才返回。幂等。
     *
     * 与 [load] 共用 [lifecycleMutex]，因此并发 load/close 会串行执行，不会出现
     * "close 只看到旧 session、而 load 随后又发布新 session"的漏关窗口。
     *
     * 不置空 session：保留它让 activeTimerCount / sessionSnapshots 在 close 后仍可观测
     * 该会话已 closed 且 timer 归零，也让"close 后旧 asyncFunction 不会写新表"可被断言。
     */
    suspend fun close() = lifecycleMutex.withLock { closeLocked() }

    /** 在已持有 [lifecycleMutex] 时执行实际关闭；供 [close] 与 [load] 复用。 */
    private suspend fun closeLocked() {
        session?.closeSuspend()
    }

    // ---- 内部实现 ----

    private fun requireSession(): RuntimeSession =
        session?.takeIf { !it.closed }
            ?: throw IllegalStateException("脚本尚未加载")

    /**
     * 一次 load 对应的不可变执行会话。timer scope / timer 表 / 生命周期都属于它，
     * 所有绑定闭包捕获它本身（val），因此不存在"旧异步任务读新 scope"的可能。
     */
    private inner class RuntimeSession(
        val name: String,
        val allowedHosts: Set<String>,
        timeoutMs: Long,
    ) {
        val quickJs: QuickJs = QuickJs.create(Dispatchers.Default).also {
            it.evaluationTimeoutMillis = timeoutMs
        }
        val timerScope: CoroutineScope = newTimerScope()
        val timerSignals = mutableMapOf<Long, CompletableDeferred<Unit>>()
        val timerMutex = Mutex()

        /**
         * 本 session 创建的全部宿主 timer Job。close 会 join 它们，确保返回时没有任何
         * 宿主协程仍在运行（否则它们可能在 QuickJs 销毁后才写表 / 触发 JS 回调）。
         */
        val timerJobs = mutableSetOf<Job>()

        /** 幂等关闭标志；@Volatile 保证跨线程可见（asyncFunction 可能在别的线程读）。 */
        @Volatile
        var closed = false
            private set

        suspend fun activeTimerCount(): Int = timerMutex.withLock { timerSignals.size }

        /**
         * 关闭顺序（不可调换）：
         * 1. 置 closed 门闩——此后 __lxDelay 拒绝登记新 timer；
         * 2. 取消所有 timer 信号——让尚未进入 select 的等待者走异常路径，不触发 JS 回调；
         * 3. 取消 timerScope——正在 select 的 Job 收到 CancellationException；
         * 4. join 所有 timer Job——真正等到它们退出（含 finally 清表）；
         * 5. 最后才销毁 QuickJs context——此时已无宿主协程会再触碰它。
         *
         * 全程用 timerMutex 保护表的读改；不再用 tryLock 跳过（suspend 上下文可直接 withLock）。
         */
        suspend fun closeSuspend() {
            if (closed) return
            closed = true
            val jobs: List<Job>
            timerMutex.withLock {
                timerSignals.values.forEach { it.cancel() }
                timerSignals.clear()
                jobs = timerJobs.toList()
            }
            timerScope.cancel()
            // 等待本 session 的全部宿主 timer Job 退出；即使某个 Job 已经结束，join 也立即返回。
            jobs.joinAll()
            // 库的 close() 幂等且会 cancel 自己的 asyncJobs；这里 try/catch 兜底。
            runCatching { quickJs.close() }
        }
    }

    private fun newTimerScope() = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> Unit },
    )

    /** 供测试断言"当前会话 timer 已全部释放"。没加载过或已 close 时按 0 计。 */
    internal suspend fun activeTimerCount(): Int = session?.activeTimerCount() ?: 0

    /**
     * 求值超时**只**依赖 QuickJS 自己的 `evaluationTimeoutMillis`（它会在 native 侧强制中断
     * 正在跑的脚本）。这里不再套协程 `withTimeout`：JS 求值是阻塞调用而非挂起点，协程超时
     * 既无法打断它，又会在虚拟时间调度器（如测试环境）下与真实时间抢跑造成误判。
     */
    private suspend fun <T> evaluateOrTimeout(
        s: RuntimeSession,
        timeoutMs: Long,
        block: suspend () -> T,
    ): T {
        s.quickJs.evaluationTimeoutMillis = timeoutMs
        return try {
            block()
        } catch (e: QuickJsException) {
            val message = e.message.orEmpty()
            if (message.contains("interrupt", ignoreCase = true) || message.contains("timeout", ignoreCase = true)) {
                throw LxScriptTimeoutException("脚本 [${s.name}] 求值超时 (${timeoutMs}ms)")
            }
            throw LxScriptException("脚本 [${s.name}] 执行异常: $message", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LxScriptTimeoutException) {
            throw e
        } catch (e: Exception) {
            throw LxScriptException("脚本 [${s.name}] 执行异常: ${e.message}", e)
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

    private fun registerBindings(instance: QuickJs, s: RuntimeSession) {
        instance.asyncFunction<String>("__lxHttp") { args ->
            val requestJson = args.firstOrNull()?.toString()
                ?: throw LxScriptException("__lxHttp 缺少请求参数")
            performHttp(requestJson, s)
        }
        instance.function<Any?>("__lxBufferFrom") { args ->
            bufferFrom(args.getOrNull(0)?.toString().orEmpty(), args.getOrNull(1)?.toString() ?: "utf8")
        }
        instance.function<String>("__lxBufToString") { args ->
            bufToString(args.getOrNull(0), args.getOrNull(1)?.toString() ?: "utf8")
        }
        instance.function<String>("__lxMd5") { args ->
            Md5.hex((args.firstOrNull()?.toString() ?: "").encodeToByteArray())
        }
        instance.function<String>("__lxCryptoUnsupported") { args ->
            val name = args.firstOrNull()?.toString() ?: "crypto"
            throw LxScriptException(
                "lx.utils.crypto.$name 尚未在 FuoEvolve 中实现；该脚本依赖此原语，无法安全解析",
            )
        }
        // setTimeout 的延时原语：参数 (id, delayMs)。所有状态都取自捕获的 session s，
        // 旧 context 的 asyncFunction 只敢碰旧 session，不会误入 load() 之后的新 session。
        instance.asyncFunction<Any?>("__lxDelay") { args ->
            val id = args.getOrNull(0)?.toString()?.toLongOrNull() ?: 0L
            val millis = (args.getOrNull(1)?.toString()?.toLongOrNull() ?: 0L)
                .coerceIn(0L, MAX_TIMER_DELAY_MS)
            val signal = CompletableDeferred<Unit>()
            // 登记信号 + 等待 Job 必须在同一把锁内、且检查 closed 门闩：
            // 这样 closeSuspend 置 closed 后，新 timer 无法再登记，也就不会被漏 join。
            val wait = s.timerMutex.withLock {
                if (s.closed) null else {
                    s.timerSignals[id] = signal
                    s.timerScope.async {
                        try {
                            select<Unit> {
                                signal.onAwait { }
                                onTimeout(millis) { }
                            }
                        } finally {
                            // single-arg remove（双参 remove(key,value) 在 Kotlin/Native 不可用）；
                            // identity 判断防止误删"同 id 但属于重试"的新信号。
                            s.timerMutex.withLock {
                                if (s.timerSignals[id] === signal) s.timerSignals.remove(id)
                                s.timerJobs.remove(currentCoroutineContext()[Job])
                            }
                        }
                    }.also { s.timerJobs += it }
                }
            }
            if (wait == null) return@asyncFunction null
            // 等本 timer 的 Job：正常到期/被 clearTimeout 信号唤醒后返回；
            // close 取消 timerScope 时抛 CancellationException，由库的 asyncFunction 框架吞掉
            // （关闭后库不再向 JS 写 Promise），这里显式 catch 让协程干净结束。
            try {
                wait.await()
            } catch (_: CancellationException) {
                return@asyncFunction null
            }
            null
        }
        // clearTimeout：让对应信号"正常完成"，select 走 onAwait 退出（脚本主动清除，
        // JS .then 里 timers[id] 已 delete，回调不会重复执行）。仅作用于捕获的 session。
        instance.asyncFunction<Any?>("__lxCancelTimer") { args ->
            val id = args.getOrNull(0)?.toString()?.toLongOrNull() ?: 0L
            val signal = s.timerMutex.withLock { s.timerSignals.remove(id) }
            signal?.complete(Unit)
            null
        }
    }

    private suspend fun performHttp(requestJson: String, s: RuntimeSession): String {
        val request = json.parseToJsonElement(requestJson).jsonObject
        val url = request["url"]?.jsonPrimitive?.contentOrNull
            ?: throw LxScriptException("lx.request 缺少 url")
        val method = request["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
        val body = request["body"]?.jsonPrimitive?.contentOrNull
        val headerObject = request["headers"] as? JsonObject

        val rejection = LxNetworkPolicy.rejectionReason(url, s.allowedHosts)
        if (rejection != null) throw LxScriptException("lx.request 被拒绝: $rejection")

        val timeoutMs = (request["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L)
            .takeIf { it in 1..MAX_REQUEST_TIMEOUT_MS }
            ?: DEFAULT_REQUEST_TIMEOUT_MS

        return try {
            // 放到 Default 上计时，避免虚拟时间调度器把 timeout 提前触发。
            // 请求 + 流式读取一起纳入超时；超限立即中断，不把超大响应整体读入内存。
            val outcome = withContext(Dispatchers.Default) {
                withTimeoutOrNull(timeoutMs + SUPPORT_MARGIN_MS) {
                    val response = sendFollowingAllowedRedirects(url, method, body, headerObject, s.allowedHosts)
                    val read = readBodyBounded(response, HttpBodyLimits.LX_HTTP_RESPONSE_BYTES)
                    Triple(response.status.value, response.headers.entries().associate { it.key to it.value.joinToString(",") }, read)
                }
            }
            val (status, responseHeaders, read) = outcome
                ?: throw LxScriptException("lx.request 超时 (${timeoutMs}ms): $url")
            if (read !is BoundedBodyRead.Ok) {
                throw LxScriptException(
                    "lx.request 响应超过 ${HttpBodyLimits.LX_HTTP_RESPONSE_BYTES} 字节上限: $url",
                )
            }

            buildJsonObject {
                put("statusCode", status)
                put("status", status)
                put("body", inferBody(read.text))
                put(
                    "headers",
                    buildJsonObject {
                        responseHeaders.forEach { (key, value) -> put(key, value) }
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
     */
    private suspend fun sendFollowingAllowedRedirects(
        url: String,
        method: String,
        body: String?,
        headers: JsonObject?,
        allowedHosts: Set<String>,
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
        // lx.request 响应上限见 HttpBodyLimits.LX_HTTP_RESPONSE_BYTES（四类响应各自独立）。
        /** 单次 setTimeout 延时上限（毫秒），防止脚本设置一个永不结束的超长计时器。 */
        const val MAX_TIMER_DELAY_MS = 2_147_483_647L
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
