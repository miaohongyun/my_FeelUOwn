package org.feeluown.mobile.provider.musicfree

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.EngineCapabilities
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.ExecResult
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.FailureLevel
import org.feeluown.mobile.provider.musicfree.MusicFreeSandbox.Limits
import kotlin.coroutines.cancellation.CancellationException as StdCancellation

/**
 * 阶段 H1：真实 QuickJS 沙箱会话（每个 Provider 独占一个实例，互不共享 Context / 模块缓存）。
 *
 * 隔离实现（非"重写 require 字符串"）：
 * - 每个 session `QuickJs.create(Dispatchers.Default)` 独立 native context；
 * - 设置 evaluationTimeoutMillis（native 层可中断死循环）、setMemoryLimit、setMaxStackSize；
 * - require 走宿主 binding，只接受 [MusicFreeSandbox.ModuleRegistry] 登记的模块；
 *   模块由宿主在 [initialize] 中以 ESM `addModule` 预载并求出 exports JSON 缓存于本 session；
 * - 模块缓存是本 session 私有的 [moduleCache]；不同 session 之间不可见（A 的缓存污染不到 B）；
 * - 唯一宿主能力是纯计算 md5（无网络/文件/进程）；
 * - close() 取消标记 + quickJs.close() 释放 native 资源。
 *
 * 诚实声明（H0 §16.5）：
 * - quickjs-kt 1.0.15 无公开 job-queue 驱动 API，**不提供跨求值的后台 timer**；
 * - CommonJS 桥只承载 **JSON 可序列化的 exports**；依赖 Node 原生模块（axios/crypto-js/webdav）
 *   的官方插件**当前不可执行**——沙箱基础设施先行，插件执行适配属后续阶段；
 * - memory limit / stack 为 native Context 层限制，是否生效由 [capabilities] 如实回报，不虚报；
 * - DNS rebinding 与本沙箱无关，仍按 H2 网关层如实保留。
 */
class MusicFreeSandboxSession(
    val providerId: String,
    val limits: Limits = Limits(),
) {
    private val quickJs: QuickJs = QuickJs.create(Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    // sha256 -> exports JSON（本 session 私有）。快照在 initialize 内一次发布，binding 只读。
    private var moduleCache: Map<String, String> = emptyMap()
    // name -> exports JSON 的不可变快照；binding 只读它，避免跨线程读全局 registry 造成可见性/sha 竞态。
    private var modulesByName: Map<String, String> = emptyMap()
    private val requireBindingName = "__mfRequire_${providerId.replace(Regex("[^A-Za-z0-9_]"), "_")}_${hashCode().toString(16)}"
    private val REQUIRE_BINDING get() = requireBindingName
    private val delayBindingName = "__mfDelay_${providerId.replace(Regex("[^A-Za-z0-9_]"), "_")}_${hashCode().toString(16)}"
    private val cancelTimerBindingName = "__mfCancelTimer_${providerId.replace(Regex("[^A-Za-z0-9_]"), "_")}_${hashCode().toString(16)}"
    private var closed = false
    private var initialized = false

    // 阶段 I3：受控 timer。回调留在 JS 侧执行，宿主只提供"延时到了"信号。
    // 复用 LxScriptRuntime 已验证模式：mutex 保护表 + SupervisorJob scope + close 时 cancel+join。
    private val timerScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> Unit },
    )
    private val timerSignals = mutableMapOf<Long, CompletableDeferred<Unit>>()
    private val timerMutex = Mutex()
    private val timerJobs = mutableSetOf<Job>()
    private val maxTimers = 64

    /** 当前活跃 timer 数（测试据此证明 close 后清理干净）。 */
    suspend fun activeTimerCount(): Int = timerMutex.withLock { timerSignals.size }

    /** 各平台实际能强制的限制，执行结果里回报，供 UI/报告诚实展示。 */
    val capabilities: EngineCapabilities = runCatching {
        quickJs.memoryLimit = limits.maxMemoryBytes
        // 注：quickjs-kt 1.0.15 的 maxStackSize 在 commonMain 未暴露为可写属性
        // （JVM 字节码有 setMaxStackSize，但 KMP 公共 API 不稳定），因此 stack 上限如实记为未强制。
        EngineCapabilities(
            canSetMemoryLimit = quickJs.memoryLimit == limits.maxMemoryBytes,
            canSetStackSize = false,
            canInterruptEvaluation = true, // evaluationTimeoutMillis + interruptEvaluation 原生可用
        )
    }.getOrDefault(
        EngineCapabilities(
            canSetMemoryLimit = false, canSetStackSize = false, canInterruptEvaluation = true,
        )
    )

    init {
        quickJs.evaluationTimeoutMillis = limits.evaluationTimeoutMs
    }

    /** 必须在 [execute] 前调用一次；注册宿主 binding + shim + 预载 allowlist 模块。幂等。 */
    suspend fun initialize() {
        withContext(Dispatchers.Default) {
            lifecycleMutex.withLock {
                if (initialized || closed) return@withLock
                registerBindings()
                quickJs.evaluate<Any?>(
                    SHIM.replace("__MF_NATIVE_REQUIRE__", REQUIRE_BINDING)
                        .replace("__MF_DELAY__", delayBindingName)
                        .replace("__MF_CANCEL_TIMER__", cancelTimerBindingName),
                    "mf_sandbox_shim.js",
                )
                // 预载期把 require 临时换成"返回空对象"的桩：模块 A 求 exports 时若调用
                // require(B)，此时 modulesByName 尚未发布，真实 binding 会误报"未预载"。
                // 代价：模块间 require 依赖在预载期拿到 {}，属当前已知限制（见能力矩阵）。
                quickJs.evaluate<Any?>(
                    "globalThis.__mfRealRequire = globalThis.require; " +
                        "globalThis.require = function(){ return {}; };",
                    "mf_preload_stub.js",
                )
                // 阶段 I1 根因修复：H1 用 evaluate<String>(..., asModule = true) 读取模块
                // exports，但 quickjs-kt 1.0.15 的 asModule 求值不回传结果（与 LxScriptRuntime
                // 的 asModule 模板同样处理），导致 modulesByName 值全为 null，JS require 必然
                // 报"未预载"。改为非 asModule 的 IIFE：每个模块拥有独立 module/exports 对象，
                // 结果写回 globalThis 后再以普通 evaluate 读取。
                // __mfModules 保存"函数导出模块"的真实 JS 对象（不经 JSON）；其余模块走 JSON 缓存。
                quickJs.evaluate<Any?>("globalThis.__mfModules = {};", "mf_modules_table.js")
                val cache = LinkedHashMap<String, String>()
                val byName = LinkedHashMap<String, String>()
                MusicFreeSandbox.ModuleRegistry.all().forEach { module ->
                    if (module.source.encodeToByteArray().size > limits.maxModuleBytes) return@forEach
                    if (module.allowFunctionExports) {
                        // 真实对象通道：module.exports 含函数，直接挂到 JS 侧模块表。
                        // 仍在同一受控 Context 内执行审查源码，无宿主网络/文件/进程能力。
                        quickJs.evaluate<Any?>(
                            "(function(){ var module = { exports: {} }; var exports = module.exports; " +
                                module.source + "\n; " +
                                "globalThis.__mfModules[" + kotlinJsQuote(module.name) + "] = module.exports; })();",
                            "mfmod_fn_${module.sha256}",
                        )
                        cache[module.sha256] = "<functions>"
                        byName[module.name] = "<functions>"
                    } else {
                        quickJs.evaluate<Any?>(
                            "globalThis.__mfModOut = (function(){ " +
                                "var module = { exports: {} }; var exports = module.exports; " +
                                module.source + "\n; " +
                                "return JSON.stringify(globalThis.__mfCollect(module.exports)); })();",
                            "mfmod_${module.sha256}",
                        )
                        val exportsJson = quickJs.evaluate<String>("globalThis.__mfModOut || '{}'")
                        cache[module.sha256] = exportsJson
                        byName[module.name] = exportsJson
                    }
                }
                // 恢复真实 require 并清空预载期可能写入的伪缓存。
                quickJs.evaluate<Any?>(
                    "globalThis.require = globalThis.__mfRealRequire; globalThis.__mfRequireCache = {};",
                    "mf_preload_restore.js",
                )
                moduleCache = cache
                modulesByName = byName
                initialized = true
            }
        }
    }

    /** 已加载模块 sha 集合（本 session 私有，测试据此证明跨 Provider 缓存隔离）。 */
    fun cachedModules(): Set<String> = moduleCache.keys.toSet()

    /** 读某模块在本 session 内的导出 JSON（测试隔离用）。 */
    fun moduleExports(sha256: String): String? = moduleCache[sha256]

    private fun registerBindings() {
        // require 唯一同步入口：allowlist 校验 + 本 session 私有缓存读取，无文件/进程/远程加载。
        quickJs.function<String>(REQUIRE_BINDING) { args ->
            val nameText = args.firstOrNull()?.toString().orEmpty()
            MusicFreeSandbox.requireRejection(nameText, limits)?.let { throw IllegalStateException(it) }
            // 只读本 session 预载时发布的不可变快照，不读全局 registry（跨线程安全）。
            // 注：JS→Kotlin 字符串实参统一经 toString() 归一（H1 报告如实记录封送差异）。
            modulesByName[nameText]
                ?: throw IllegalStateException("模块 $nameText 未在本 Provider 沙箱预载")
        }
        // 纯计算宿主能力：确定性哈希占位（真实 MD5 由脚本侧纯 JS 实现，不经宿主指纹）。
        quickJs.function<String>("__mfMd5") { args ->
            val text = args.firstOrNull()?.toString().orEmpty()
            text.encodeToByteArray().fold(5381) { acc, b -> (acc * 31 + b.toInt()) and 0x7fffffff }.toString(16)
        }
        // 阶段 I3：延时信号。回调在 JS 侧执行，宿主只负责"到点了"。关闭/超限时取消。
        quickJs.asyncFunction<Any?>(delayBindingName) { args ->
            val id = (args.getOrNull(0)?.toString() ?: "0").toLongOrNull() ?: 0L
            val waitMs = (args.getOrNull(1)?.toString() ?: "0").toLongOrNull() ?: 0L
            val signal = CompletableDeferred<Unit>()
            val registered = timerMutex.withLock {
                if (closed || timerSignals.size >= maxTimers || timerSignals.containsKey(id)) false
                else {
                    timerSignals[id] = signal
                    val job = timerScope.async {
                        delay(waitMs.coerceAtLeast(0))
                        signal.complete(Unit)
                        timerMutex.withLock {
                            if (timerSignals[id] === signal) timerSignals.remove(id)
                            val j = currentCoroutineContext()[Job]
                            if (j != null) timerJobs.remove(j)
                        }
                    }
                    timerJobs += job
                    true
                }
            }
            if (registered) {
                try {
                    signal.await()
                    "fired"
                } catch (_: CancellationException) {
                    "cancelled"
                }
            } else {
                "rejected"
            }
        }
        quickJs.asyncFunction<String>(cancelTimerBindingName) { args ->
            val id = (args.firstOrNull()?.toString() ?: "0").toLongOrNull() ?: 0L
            val signal = timerMutex.withLock { timerSignals.remove(id) }
            signal?.cancel()
            if (signal != null) "cancelled" else "none"
        }
    }

    /**
     * 在受控 context 内执行一段已审计脚本。返回 [ExecResult]，失败分级。
     * 无限循环由 native evaluationTimeoutMillis 中断；取消由协程 CancellationException 传播。
     */
    suspend fun execute(source: String): ExecResult {
        if (closed) return ExecResult.Failed("会话已关闭", FailureLevel.REJECTED)
        if (source.encodeToByteArray().size > limits.maxScriptBytes) {
            return ExecResult.Failed("脚本超过大小上限 ${limits.maxScriptBytes}", FailureLevel.REJECTED)
        }
        val preflight = MusicFreeSandbox.preflightRequires(source, limits)
        if (preflight.isNotEmpty()) {
            return ExecResult.Failed("require 预检未通过：${preflight.joinToString("; ")}", FailureLevel.REJECTED)
        }
        initialize()
        return withContext(Dispatchers.Default) {
            lifecycleMutex.withLock {
                if (closed) return@withLock ExecResult.Failed("会话已关闭", FailureLevel.REJECTED)
                try {
                    val result = quickJs.evaluate<String>(source, "mf_provider_${providerId}.js")
                    ExecResult.Ok(result, capabilities)
                } catch (e: CancellationException) {
                    ExecResult.Failed("执行被取消", FailureLevel.CANCELLED)
                } catch (e: StdCancellation) {
                    ExecResult.Failed("执行被取消", FailureLevel.CANCELLED)
                } catch (e: QuickJsException) {
                    val msg = e.message.orEmpty()
                    if (msg.contains("interrupt", true) || msg.contains("timeout", true)) {
                        ExecResult.Failed("求值超时被中断：$msg", FailureLevel.TIMEOUT)
                    } else {
                        ExecResult.Failed("脚本执行错误：$msg", FailureLevel.ERROR)
                    }
                } catch (e: Exception) {
                    ExecResult.Failed("脚本执行异常：${e.message}", FailureLevel.ERROR)
                }
            }
        }
    }

    /**
     * 阶段 I3：执行含顶层 await 的异步脚本（timer/Promise 驱动）。
     * quickjs-kt 1.0.15 的 async binding 只在 evaluate 期间被 pump，且 asModule 模式不回传
     * 求值结果——因此用 ESM 顶层 await 保持求值活跃，把结果写回 globalThis 再用普通
     * evaluate 读回（与 LxScriptRuntime 的 DISPATCH_MODULE_TEMPLATE 同一模式）。
     * [body] 是一个 async IIFE 的函数体，其返回值被写入 globalThis.__mfAwaitResult。
     */
    suspend fun executeAwait(body: String): ExecResult {
        if (closed) return ExecResult.Failed("会话已关闭", FailureLevel.REJECTED)
        initialize()
        return withContext(Dispatchers.Default) {
            lifecycleMutex.withLock {
                if (closed) return@withLock ExecResult.Failed("会话已关闭", FailureLevel.REJECTED)
                try {
                    quickJs.evaluate<Any?>(
                        "globalThis.__mfAwaitResult = await (async function(){ $body })(); true;",
                        "mf_async_${providerId}.mjs", asModule = true,
                    )
                    val value = quickJs.evaluate<String>(
                        "typeof globalThis.__mfAwaitResult === 'string' ? globalThis.__mfAwaitResult : JSON.stringify(globalThis.__mfAwaitResult)",
                        "mf_async_read_${providerId}.js",
                    )
                    ExecResult.Ok(value, capabilities)
                } catch (e: CancellationException) {
                    ExecResult.Failed("执行被取消", FailureLevel.CANCELLED)
                } catch (e: StdCancellation) {
                    ExecResult.Failed("执行被取消", FailureLevel.CANCELLED)
                } catch (e: QuickJsException) {
                    val msg = e.message.orEmpty()
                    if (msg.contains("interrupt", true) || msg.contains("timeout", true)) {
                        ExecResult.Failed("求值超时被中断：$msg", FailureLevel.TIMEOUT)
                    } else {
                        ExecResult.Failed("脚本执行错误：$msg", FailureLevel.ERROR)
                    }
                } catch (e: Exception) {
                    ExecResult.Failed("脚本执行异常：${e.message}", FailureLevel.ERROR)
                }
            }
        }
    }

    /** 供宿主在超时场景下主动中断 native 求值。 */
    fun interrupt() {
        runCatching { quickJs.interruptEvaluation() }
    }

    /** 释放 native Context、模块缓存与全部 timer；幂等。 */
    suspend fun close() {
        lifecycleMutex.withLock {
            if (closed) return@withLock
            closed = true
            moduleCache = emptyMap()
            modulesByName = emptyMap()
            // I3：先取消所有延时信号与 timer scope，并 join 等待宿主 Job 真正退出，再销毁 Context。
            val jobs = timerMutex.withLock {
                timerSignals.values.forEach { runCatching { it.cancel() } }
                timerSignals.clear()
                timerJobs.toList()
            }
            timerScope.cancel()
            jobs.joinAll()
            runCatching { quickJs.close() }
        }
    }

    val isClosed: Boolean get() = closed

    companion object {
        /** Kotlin 字符串 -> JS 字符串字面量（供受控模块注入用，不依赖 JVM API）。 */
        private fun kotlinJsQuote(value: String): String {
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

        /**
         * CommonJS 形状 shim：module/exports/require 最小面；
         * __mfCollect 过滤不可序列化值（函数/符号），只保留 JSON 数据 —— 这是有意的能力收缩。
         * 显式不提供 fetch/XMLHttpRequest/WebSocket/process/fs/eval 包装。
         */
        private val SHIM = """
            globalThis.__mfCollect = function(o) {
                var out = {};
                if (!o) return out;
                for (var k in o) {
                    try {
                        var v = o[k];
                        var t = typeof v;
                        if (t === 'function' || t === 'symbol' || t === 'undefined') continue;
                        JSON.stringify(v);
                        out[k] = v;
                    } catch (e) {}
                }
                return out;
            };
            globalThis.__mfExports = {};
            globalThis.module = { exports: __mfExports };
            globalThis.exports = __mfExports;
            globalThis.__mfRequireCache = {};
            globalThis.require = function(name) {
                var key = String(name);
                // 函数导出模块（I2 Node 兼容子集）：直接返回 JS 侧真实对象，不经 JSON。
                if (globalThis.__mfModules && Object.prototype.hasOwnProperty.call(globalThis.__mfModules, key)) {
                    return globalThis.__mfModules[key];
                }
                if (Object.prototype.hasOwnProperty.call(globalThis.__mfRequireCache, key)) {
                    return globalThis.__mfRequireCache[key];
                }
                var json = __MF_NATIVE_REQUIRE__(key);
                var obj;
                try { obj = JSON.parse(json); } catch (e) { obj = {}; }
                globalThis.__mfRequireCache[key] = obj;
                return obj;
            };
            // 阶段 I3：受控 timer。宿主只提供延时信号，回调在 JS context 内执行。
            // clearTimeout/clearInterval 用 token 失效化，即便延时已到也不再触发。
            var __mfTimers = Object.create(null);
            var __mfTimerSeq = 0;
            var __MF_MAX_TIMERS = 64;
            globalThis.setTimeout = function(fn, delay){
                if (typeof fn !== 'function') return 0;
                if (Object.keys(__mfTimers).length >= __MF_MAX_TIMERS) return 0;
                var id = ++__mfTimerSeq;
                var ms = Number(delay); if (!isFinite(ms) || ms < 0) ms = 0;
                if (ms > 2147483647) ms = 2147483647;
                __mfTimers[id] = { interval: false };
                __MF_DELAY__(id, ms).then(function(){
                    var t = __mfTimers[id];
                    if (!t) return;
                    try { fn(); } catch(e) {}
                    if (!t.interval) delete __mfTimers[id];
                }).catch(function(){});
                return id;
            };
            globalThis.clearTimeout = function(id){
                if (id && __mfTimers[id]) { delete __mfTimers[id]; __MF_CANCEL_TIMER__(id); }
            };
            globalThis.setInterval = function(fn, delay){
                if (typeof fn !== 'function') return 0;
                if (Object.keys(__mfTimers).length >= __MF_MAX_TIMERS) return 0;
                var id = ++__mfTimerSeq;
                var ms = Number(delay); if (!isFinite(ms) || ms < 0) ms = 0;
                __mfTimers[id] = { interval: true };
                function tick(){
                    __MF_DELAY__(id, ms).then(function(){
                        var t = __mfTimers[id];
                        if (!t || !t.interval) return;
                        try { fn(); } catch(e) {}
                        tick();
                    }).catch(function(){});
                }
                tick();
                return id;
            };
            globalThis.clearInterval = function(id){ globalThis.clearTimeout(id); };
            true;
        """.trimIndent()
    }
}
