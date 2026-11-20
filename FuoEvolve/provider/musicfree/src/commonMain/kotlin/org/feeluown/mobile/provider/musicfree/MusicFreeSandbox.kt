package org.feeluown.mobile.provider.musicfree

/**
 * 阶段 H1：受控 CommonJS 执行环境（宿主侧实现，不修改 LX Runtime 核心协议）。
 *
 * 设计原则（与阶段 H 任务书逐条对应）：
 * - **独立实例**：每个 Provider / 每个脚本创建独立 [SandboxSession]，QuickJS Context 互不共享；
 * - **require allowlist**：模块必须先经静态审查登记在 [ModuleRegistry]，未知模块一律拒绝（fail-closed）；
 * - **无文件/进程/远程加载**：模块源由宿主以内存字符串注入，没有 fs/process/动态 import 宿主 API；
 * - **模块缓存按 Provider 隔离**：缓存在 [SandboxSession] 内，跨 session 不共享；
 * - **资源限制**：entry 脚本字节数、单个模块字节数、模块总数、求值超时（由内核 native 中断兜底）；
 * - **取消/异常回收/资源释放**：close() 取消任务并释放 QuickJS Context。
 *
 * 重要诚实声明（见 H0 审计 §16.5）：
 * - quickjs-kt 1.0.15 的 memory limit / max stack / interrupt 是 native Context 层能力，
 *   但本模块运行在 commonMain，具体 QuickJs 实例由注入的 [QuickJsFactory] 提供；
 *   若某个平台的 factory 无法设置内存上限，会话创建时以 [Limits] 记录并要求工厂返回 [SandboxEngineCapabilities]，
 *   不支持的限制**如实标记 unsupported，不伪称已隔离**。
 * - 跨 request 的后台 timer / job-queue 手动泵送在该库版本无公开 API，本沙箱不提供跨求值任务。
 */
object MusicFreeSandbox {

    /** 资源限制。所有值都是上限，超限 fail-closed。 */
    data class Limits(
        val maxScriptBytes: Int = 512 * 1024,
        val maxModuleBytes: Int = 256 * 1024,
        val maxModules: Int = 32,
        val evaluationTimeoutMs: Long = 5_000L,
        val maxMemoryBytes: Long = 8L * 1024 * 1024,
        val maxStackBytes: Long = 256L * 1024,
    )

    /** 某平台引擎实际能强制的限制（如实声明，不伪称）。 */
    data class EngineCapabilities(
        val canSetMemoryLimit: Boolean,
        val canSetStackSize: Boolean,
        val canInterruptEvaluation: Boolean,
    )

    /** 一次沙箱执行结果。 */
    sealed class ExecResult {
        data class Ok(val value: String?, val caps: EngineCapabilities) : ExecResult()
        data class Failed(val reason: String, val level: FailureLevel) : ExecResult()
    }

    enum class FailureLevel { REJECTED, TIMEOUT, CANCELLED, ERROR }

    /** 宿主 ability：JS 侧只能看到这些名字，且全部无网络/文件/进程能力。 */
    enum class HostAbility(val jsName: String) {
        MD5("__mfMd5"),
        B64("__mfBase64"),
        NOW("__mfNow"),
    }

    /**
     * 模块登记：只有经过审查并显式登记的模块才能被 require。
     * [sha256] 绑定模块正文；内容变化即拒绝（等价于 H2 的脚本 SHA 失效语义）。
     */
    data class RegisteredModule(
        val name: String,
        val source: String,
        val sha256: String,
        val auditedAt: String,
        val note: String,
    )

    object ModuleRegistry {
        private val modules = linkedMapOf<String, RegisteredModule>()

        fun register(module: RegisteredModule) {
            // 阶段 I1：允许受控相对模块名（如 'lib/helper'），但仍拒绝路径穿越/绝对路径/反斜杠。
            require(isSafeModuleName(module.name)) { "非法模块名" }
            modules[module.name] = module
        }

        /** 安全的模块名：允许多段相对路径，禁止 '..'、绝对路径与反斜杠；段名仅字母数字下划线连字符。 */
        fun isSafeModuleName(name: String): Boolean {
            if (name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains("..")) return false
            return name.split("/").all { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
        }

        fun lookup(name: String): RegisteredModule? = modules[name]

        fun clear() = modules.clear()

        fun all(): List<RegisteredModule> = modules.values.toList()
    }

    /** 拒绝原因：require 不在 allowlist / 内容超限 / 非法命名。 */
    fun requireRejection(name: String, limits: Limits): String? {
        if (name.isBlank()) return "require 模块名为空"
        if (!ModuleRegistry.isSafeModuleName(name)) return "require 禁止非法路径穿越：$name"
        val m = ModuleRegistry.lookup(name) ?: return "require 模块未登记在 allowlist：$name（fail-closed）"
        if (m.source.encodeToByteArray().size > limits.maxModuleBytes) return "模块 $name 超过大小上限"
        return null
    }

    /**
     * 纯静态校验：统计一段 entry 脚本的 require 目标是否全部在 allowlist。
     * 这不宣称"沙箱"，只是**安装前预检**；真正的隔离由 [SandboxSession] 的运行时 allowlist 保证。
     */
    fun preflightRequires(source: String, limits: Limits): List<String> {
        val rejected = mutableListOf<String>()
        val regex = Regex("""require\(\s*['"]([^'"]+)['"]\s*\)""")
        var count = 0
        regex.findAll(source).forEach { m ->
            count++
            val name = m.groupValues[1]
            requireRejection(name, limits)?.let { rejected += "$name: $it" }
        }
        if (count > limits.maxModules) rejected += "require 次数 $count 超过模块数上限 ${limits.maxModules}"
        return rejected
    }
}
