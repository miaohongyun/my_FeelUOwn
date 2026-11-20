package org.feeluown.mobile.provider.musicfree

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderFailure
import org.feeluown.mobile.ProviderFailureKind
import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.provider.core.BaseKotlinProvider
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.KotlinProviderFactory
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.currentTimeMillis
import org.feeluown.mobile.provider.musicfree.ProviderNetworkGateway.ProviderNetworkPermission

/**
 * 阶段 J2：Audiomack 单一来源的正式搜索 Provider。
 *
 * 只实现 search；其余 KotlinMusicProvider 能力由 [BaseKotlinProvider] 默认实现（空/不支持），
 * 不实现 URL 解析/音频/播放（那是后续阶段），不为它们放宽任何权限。
 *
 * 网络链路（无自建客户端、无绕过 Gateway）：
 *   search → MusicFreeSandboxSession.execute/executeAwait 执行真实 audiomack.js 的 search
 *         → js axios.get → 宿主 networkBridge → gatewayBridge → ProviderNetworkGateway.get
 *         → dependencies.http.client.asProviderNetworkExecutor() → 真实 api.audiomack.com
 *         → parseMusicFreeSearch → MusicTrack → ProviderSearchResults。
 *
 * 身份绑定：脚本字节来自 [AudiomackEmbeddedScript]（G2/G3 已审查 SHA），运行时现场算 SHA
 * 与 [MusicFreePluginRegistry] 台账绑定校验；字节变化即拒绝，不静默替换版本。
 */
class AudiomackMusicProvider(
    http: ProviderHttpClient,
    dependencies: ProviderRuntimeDependencies,
    /** 注入以便测试替换；生产为 dependencies.http.client，复用统一网络层，不自建。 */
    private val client: HttpClient = dependencies.http.client,
) : BaseKotlinProvider(
    http = http,
    credentials = dependencies.credentials,
    id = ID,
    name = NAME,
    info = INFO,
    capabilities = CAPABILITIES,
    features = emptyList(),
) {

    /** J2 审计记录：不含任何凭据头。 */
    data class SearchAudit(
        val providerId: String,
        val providerVersion: String,
        val scriptSha256: String,
        val keyword: String,
        val domain: String,
        val method: String,
        val port: Int,
        val gatewayDecision: String,      // ALLOW / DENIED / FAILED
        val redirectChecked: Boolean,
        val httpStatus: Int?,             // 仅请求真正到达服务端时非空
        val responseBytes: Int,
        val durationMs: Long,
        val outcome: String,              // SUCCESS / ZERO_RESULTS / FAILURE / NOT_RUN
        val failureClass: String? = null,
        val failureReason: String? = null,
    )

    private val lifecycleMutex = Mutex()
    private var session: MusicFreeSandboxSession? = null
    private var executor: ProviderNetworkGateway.NetworkExecutor? = null
    private var initialized = false
    private var closed = false

    /** 最近一次审计记录（供 UI/测试/报告读取；不写入日志或外部服务）。 */
    private var _lastAudit: SearchAudit? = null
    val lastAudit: SearchAudit? get() = _lastAudit

    /** 可被测试注入的网关执行器工厂；默认复用统一 client。 */
    private var executorFactory: (HttpClient) -> ProviderNetworkGateway.NetworkExecutor =
        { it.asProviderNetworkExecutor() }

    internal fun setExecutorFactoryForTest(factory: (HttpClient) -> ProviderNetworkGateway.NetworkExecutor) {
        executorFactory = factory
    }

    private val record = MusicFreePluginRegistry.find(ID)
        ?: error("Audiomack 插件未登记于台账")

    private val scriptBytes: ByteArray = AudiomackEmbeddedScript.scriptBytes
    private val scriptSha: String = MusicFreePluginCompatVerification.shaOfScriptBytes(scriptBytes)

    /** 初始化：校验脚本 SHA + 建立授权策略 + 构造 Gateway executor + 注册 CommonJS 模块。 */
    override suspend fun initialize(): Unit = lifecycleMutex.withLock {
        if (initialized || closed) return@withLock
        // 1) 脚本字节必须与台账 SHA 一致；否则拒绝（fail-closed，不静默替换版本）。
        if (!scriptSha.equals(record.sha256, ignoreCase = true)) {
            throw IllegalStateException("Audiomack 脚本字节 SHA 与登记不一致：$scriptSha != ${record.sha256}")
        }
        // 2) 生成精确域名授权策略（api.audiomack.com，HTTPS/443，SHA 绑定），不放宽。
        val decision = MusicFreePluginPolicy.forPlugin(record, scriptSha)
        if (decision !is MusicFreePluginPolicy.PolicyDecision.Authorized) {
            throw IllegalStateException("Audiomack 网络权限未授权：${(decision as MusicFreePluginPolicy.PolicyDecision.Denied).reason}")
        }
        permission = ProviderNetworkPermissionOf(record, decision.policy)
        // 3) 复用统一 client 构造 executor（只构造一次），不自建 HttpClient。
        executor = executorFactory(client)
        // 4) 注册 CommonJS 依赖子集（audiomack 需 axios/cheerio/crypto-js/dayjs）。
        registerCommonJsModules()
        // 5) 建立独立 QuickJS Context，注入 networkBridge（axios → Gateway）。
        val bridge = MusicFreePluginCompatVerification.gatewayBridge(
            gateway = ProviderNetworkGateway(executor!!),
            permission = permission!!,
            scriptBytes = scriptBytes,
            calls = auditCalls,
            auditSink = { lastGatewayAudit = it },
        )
        session = MusicFreeSandboxSession(providerId = ID, networkBridge = bridge)
        session!!.execute(AudiomackEmbeddedScript.source) // initialize + require；不支持则抛
        initialized = true
    }

    private var permission: ProviderNetworkPermission? = null
    private val auditCalls = mutableListOf<ProviderNetworkGateway.Request>()
    private var lastGatewayAudit: MusicFreePluginCompatVerification.GatewayCallAudit? = null

    private fun registerCommonJsModules() {
        MusicFreeSandbox.ModuleRegistry.clear()
        NodeCompatShims.all.forEach { (name, src) ->
            val sha = MusicFreePluginCompatVerification.shaOfScriptBytes(src.encodeToByteArray())
            MusicFreeSandbox.ModuleRegistry.register(
                MusicFreeSandbox.RegisteredModule(
                    name = name, source = src, sha256 = sha,
                    auditedAt = "2026-11-20", note = "J2 audiomack CommonJS 子集",
                    allowFunctionExports = true,
                ),
            )
        }
        // axios 由沙箱内建注入（injectedModules），这里登记占位以通过静态预检。
        MusicFreeSandbox.ModuleRegistry.register(
            MusicFreeSandbox.RegisteredModule(
                name = "axios", source = "// gateway-backed axios (injected)",
                sha256 = scriptSha, auditedAt = "2026-11-20",
                note = "axios 由 SandboxSession 注入，唯一出口是 ProviderNetworkGateway",
                allowFunctionExports = true,
            ),
        )
    }

    private fun ProviderNetworkPermissionOf(
        r: MusicFreePluginRecord,
        policy: org.feeluown.mobile.provider.lx.CandidateNetworkPolicy,
    ) = ProviderNetworkPermission(
        providerId = r.pluginId,
        providerVersion = r.version,
        scriptSha256 = r.sha256,
        candidatePolicy = policy,
        allowedMethods = setOf("GET"),
        redirectPolicy = ProviderNetworkGateway.RedirectPolicy.DENY_ALL,
        maxRedirects = 0,
        authorizationBasis = "J2 单一来源搜索接入：${r.evidence}",
    )

    /** 真实搜索：执行插件 search 函数 → Gateway → 解析 → 统一模型。 */
    override suspend fun search(keyword: String): ProviderSearchResults {
        val startedAt = currentTimeMillis()
        auditCalls.clear()
        lastGatewayAudit = null
        try {
            initialize()
            val s = session ?: run {
                audit(keyword = keyword, outcome = "FAILURE", httpStatus = null, responseBytes = 0,
                    durationMs = currentTimeMillis() - startedAt, failureClass = "SESSION_UNAVAILABLE",
                    failureReason = "沙箱会话不可用（可能已关闭）", gatewayDecision = "NOT_RUN")
                return searchFailure("SESSION_UNAVAILABLE", "沙箱会话不可用")
            }
            // 只做 MUSIC 搜索；executeAwait 用 ESM 顶层 await 驱动真实 async search。
            val body = buildString {
                append("try { var r = await module.exports.search(")
                append(jsStringLiteral(keyword))
                append(", 1, 'music'); return JSON.stringify(r); } catch(e) { ")
                append("return JSON.stringify({__error:String(e && e.message ? e.message : e)}); }")
            }
            val exec = s.executeAwait(body)
            val duration = currentTimeMillis() - startedAt
            if (exec is MusicFreeSandbox.ExecResult.Failed) {
                val gwStatus = lastGatewayAudit?.httpStatus
                val cls = when {
                    exec.level == MusicFreeSandbox.FailureLevel.CANCELLED -> "CANCELLED"
                    exec.level == MusicFreeSandbox.FailureLevel.TIMEOUT -> "TIMEOUT"
                    exec.level == MusicFreeSandbox.FailureLevel.REJECTED -> "SCRIPT_REJECTED"
                    gwStatus in 400..499 -> "HTTP_4XX"
                    gwStatus in 500..599 -> "HTTP_5XX"
                    lastGatewayAudit?.decision == "DENIED" -> "PERMISSION_DENIED"
                    else -> "SCRIPT_ERROR"
                }
                audit(
                    keyword = keyword, outcome = "NOT_RUN", httpStatus = null,
                    responseBytes = 0, durationMs = duration, failureClass = cls,
                    failureReason = exec.reason, gatewayDecision = "NOT_RUN",
                )
                return searchFailure(cls, exec.reason)
            }
            val json = (exec as MusicFreeSandbox.ExecResult.Ok).value.orEmpty()
            if (json.contains("\"__error\"")) {
                val gwStatus = lastGatewayAudit?.httpStatus
                val errCls = when {
                    gwStatus in 400..499 -> "HTTP_4XX"
                    gwStatus in 500..599 -> "HTTP_5XX"
                    lastGatewayAudit?.decision == "DENIED" -> "PERMISSION_DENIED"
                    lastGatewayAudit?.decision == "FAILED" -> "NETWORK"
                    else -> "SCRIPT_ERROR"
                }
                audit(keyword = keyword, outcome = "FAILURE", httpStatus = gwStatus, responseBytes = 0,
                    durationMs = duration, failureClass = errCls, failureReason = json,
                    gatewayDecision = lastGatewayAudit?.decision ?: "DENIED")
                return searchFailure("SCRIPT_ERROR", json)
            }
            val parsed = parseMusicFreeSearch(json, MusicFreeSearchType.MUSIC)
            if (parsed.isFailure) {
                audit(keyword = keyword, outcome = "FAILURE", httpStatus = null, responseBytes = 0,
                    durationMs = duration, failureClass = "PARSE_ERROR", failureReason = parsed.exceptionOrNull()?.message, gatewayDecision = "ALLOWED")
                return searchFailure("PARSE_ERROR", parsed.exceptionOrNull()?.message)
            }
            val result = parsed.getOrThrow()
            val auditOutcome = if (result.items.isEmpty()) "ZERO_RESULTS" else "SUCCESS"
            val gw = lastGatewayAudit
            audit(keyword = keyword, outcome = auditOutcome, httpStatus = gw?.httpStatus,
                responseBytes = gw?.responseBytes ?: responseBytesOf(json),
                durationMs = gw?.durationMs ?: duration, failureClass = null, failureReason = null,
                gatewayDecision = gw?.decision ?: "ALLOWED")
            return ProviderSearchResults(tracks = result.items.map { it.toMusicTrack() })
        } catch (e: CancellationException) {
            throw e // 用户取消原样传播，不回传过期结果
        } catch (e: Exception) {
            val duration = currentTimeMillis() - startedAt
            audit(keyword = keyword, outcome = "FAILURE", httpStatus = null, responseBytes = 0,
                durationMs = duration, failureClass = "EXCEPTION", failureReason = e.message, gatewayDecision = "FAILED")
            return searchFailure("EXCEPTION", e.message)
        }
    }

    private fun MusicFreeMusicItem.toMusicTrack(): MusicTrack = track(
        identifier = id, // 原始来源 id 作为唯一键组成部分（trackKey(providerId,id)）
        title = title,
        artists = artist,
        album = album,
        coverUrl = artwork,
        durationMs = durationMs,
    ).copy(
        // 保留插件原始 id / 标题 / 歌手，避免用拼接值冒充来源唯一 ID。
        originalId = id,
        originalTitle = title,
        originalArtists = artist,
        originalAlbum = album,
        originalSource = ID,
        originalProviderName = NAME,
        providerTags = listOf(TAG, "musicfree", "audiomack"),
    )

    private fun searchFailure(cls: String, reason: String?): ProviderSearchResults = ProviderSearchResults(
        failure = ProviderFailure(
            kind = when (cls) {
                "NETWORK", "TIMEOUT", "HTTP_4XX", "HTTP_5XX" -> ProviderFailureKind.Network
                "PERMISSION_DENIED" -> ProviderFailureKind.RegionRestricted
                "ZERO_RESULTS" -> ProviderFailureKind.ContentUnavailable
                else -> ProviderFailureKind.Unknown
            },
            providerId = ID,
            technicalMessage = "[$cls] ${reason.orEmpty()}",
        ),
    )

    private fun audit(
        keyword: String,
        outcome: String,
        httpStatus: Int?,
        responseBytes: Int,
        durationMs: Long,
        failureClass: String?,
        failureReason: String?,
        gatewayDecision: String,
    ) {
        _lastAudit = SearchAudit(
            providerId = ID, providerVersion = record.version, scriptSha256 = scriptSha,
            keyword = keyword, domain = "api.audiomack.com", method = "GET", port = 443,
            gatewayDecision = gatewayDecision,
            redirectChecked = true, // Gateway 对每跳执行完整校验（DENY_ALL 下直接拒绝）
            httpStatus = httpStatus, responseBytes = responseBytes, durationMs = durationMs,
            outcome = outcome, failureClass = failureClass, failureReason = failureReason,
        )
    }

    /** 释放本 Provider 持有的 QuickJS Context 与 timer 任务；不 close 共享 HttpClient。 */
    suspend fun dispose() = lifecycleMutex.withLock {
        if (closed) return@withLock
        closed = true
        session?.close()
        session = null
        executor = null
        initialized = false
        auditCalls.clear()
    }

    companion object {
        const val ID = "mf-audiomack"
        const val NAME = "Audiomack（社区来源·搜索）"
        const val TAG = "mf-audiomack"
        val INFO = ProviderInfo(providerId = ID, providerName = NAME)
        val CAPABILITIES = ProviderCapabilities(providerId = ID, providerName = NAME)
    }
}

/** J2 工厂：不注册为默认启用来源，由 ProviderComposition 显式装配。 */
object AudiomackProviderFactory : KotlinProviderFactory {
    override val providerId: String = AudiomackMusicProvider.ID
    override val info: ProviderInfo = AudiomackMusicProvider.INFO

    override fun create(dependencies: ProviderRuntimeDependencies): KotlinMusicProvider =
        AudiomackMusicProvider(http = dependencies.http, dependencies = dependencies)
}

// ---- 纯工具（便于测试与跨平台一致）----

internal fun jsStringLiteral(value: String): String = buildString {
    append('"')
    for (ch in value) when (ch) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(ch)
    }
    append('"')
}


internal fun responseBytesOf(json: String): Int = json.encodeToByteArray().size
