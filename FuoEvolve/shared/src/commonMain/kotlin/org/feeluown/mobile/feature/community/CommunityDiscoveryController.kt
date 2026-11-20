package org.feeluown.mobile.feature.community

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.feeluown.mobile.sourceupdate.CandidateReviewState
import org.feeluown.mobile.sourceupdate.CommunityDiscoveryRepository
import org.feeluown.mobile.sourceupdate.CommunityScriptDownloadOutcome
import org.feeluown.mobile.sourceupdate.DiscoveredCandidate
import org.feeluown.mobile.sourceupdate.VerificationLevel

/**
 * 社区音源发现页的状态容器（阶段 D）。
 *
 * 只做 UI 状态编排：加载候选、主动刷新、人工审核动作、隔离区扫描。
 * 所有业务规则都在 [CommunityDiscoveryRepository]，本类不绕过任何门禁。
 */
interface CommunityDiscoveryController {
    val uiState: StateFlow<CommunityDiscoveryUiState>
    fun refresh()
    /** 把候选登记 URL 的脚本下载到隔离区：下载 → SHA256 核验 → 写隔离文件 → 静态扫描。 */
    fun downloadToQuarantine(id: String, url: String)
    fun quarantine(id: String, reason: String)
    fun reject(id: String, reason: String)
    fun reopen(id: String)
    fun delete(id: String)
    /** 受控下载脚本文本后调用：写隔离区 + 哈希核验 + 静态扫描。 */
    fun scanScript(id: String, scriptText: String, expectedSha256: String, version: String?)
    fun select(id: String?)
    fun requestDelete(id: String?) // 二次确认：先标记，再真正删除
    fun clearFeedback()
    /** E5：设置名称/平台关键词筛选。 */
    fun setFilterText(text: String)
    /** E5：设置审核状态筛选（null = 全部）。 */
    fun setStatusFilter(status: CandidateReviewState?)
}

/** 操作错误的分类，供 UI 精确提示（网络/哈希/BLOCK/存储/安全拒绝）。 */
enum class CommunityOperationError {
    NETWORK, HASH_MISMATCH, STATIC_BLOCK, STORAGE, SECURITY_REJECTED, NOT_FOUND, OTHER
}

data class CommunityDiscoveryUiState(
    val candidates: List<DiscoveredCandidate> = emptyList(),
    val isRefreshing: Boolean = false,
    val isDownloading: Boolean = false,
    val isBusy: Boolean = false, // 审核/删除/重新扫描期间防重复提交
    val feedback: String? = null,
    val errorKind: CommunityOperationError? = null,
    val selectedId: String? = null,
    val deleteConfirmId: String? = null,
    /** E5：名称/平台关键词筛选（大小写不敏感）。 */
    val filterText: String = "",
    /** E5：审核状态筛选；null = 全部。 */
    val statusFilter: CandidateReviewState? = null,
) {
    /** 按筛选条件过滤后的候选列表（纯函数，供 UI 与测试共用）。 */
    val visibleCandidates: List<DiscoveredCandidate>
        get() = CommunityDiscoveryPresentation.filterCandidates(candidates, filterText, statusFilter)

    /** E5 顶部统计（全部候选，不受筛选影响）。 */
    val stats: CommunityDiscoveryPresentation.CandidateStats
        get() = CommunityDiscoveryPresentation.CandidateStats.from(candidates)
}

/** 展示辅助：把候选映射成 UI 文案，集中在此以便测试与多端一致。 */
object CommunityDiscoveryPresentation {
    /** DISCOVERED/CLEAN 绝不能显示为"已批准"。 */
    fun reviewLabel(candidate: DiscoveredCandidate): String = when (candidate.review) {
        CandidateReviewState.DISCOVERED -> "已发现（待审核）"
        CandidateReviewState.QUARANTINED -> "已隔离"
        CandidateReviewState.NEEDS_REVIEW -> "待人工审核"
        CandidateReviewState.APPROVED -> "已批准（受控目录）"
        CandidateReviewState.REJECTED -> "已拒绝"
        CandidateReviewState.STALE -> "已失效（清单中消失）"
    }

    /** E5 状态筛选按钮的短标签。 */
    fun statusShortLabel(status: CandidateReviewState): String = when (status) {
        CandidateReviewState.DISCOVERED -> "已发现"
        CandidateReviewState.QUARANTINED -> "已隔离"
        CandidateReviewState.NEEDS_REVIEW -> "待审"
        CandidateReviewState.APPROVED -> "已批准"
        CandidateReviewState.REJECTED -> "已拒绝"
        CandidateReviewState.STALE -> "失效"
    }

    fun verdictLabel(candidate: DiscoveredCandidate): String = when (candidate.staticVerdict) {
        "CLEAN" -> "静态扫描通过"
        "NEEDS_REVIEW" -> "需人工审查"
        "BLOCKED" -> "命中 BLOCK 规则"
        else -> "未扫描"
    }

    fun verificationLabel(candidate: DiscoveredCandidate): String =
        runCatching { VerificationLevel.valueOf(candidate.verificationLevel) }
            .getOrDefault(VerificationLevel.STATIC_REVIEW)
            .let { if (it.isReal) it.name else "$it（非真实音频验证）" }

    /** E5 顶部统计：候选总数/已发现/已下载/静态通过/BLOCK/隔离/LX验证/真实验证。 */
    data class CandidateStats(
        val total: Int,
        val discovered: Int,
        val downloaded: Int,
        val staticPassed: Int,
        val blocked: Int,
        val quarantined: Int,
        val lxVerified: Int,
        val realVerified: Int,
    ) {
        companion object {
            fun from(list: List<DiscoveredCandidate>): CandidateStats = CandidateStats(
                total = list.size,
                discovered = list.count { it.review == CandidateReviewState.DISCOVERED },
                downloaded = list.count { it.sha256 != null },
                staticPassed = list.count { it.staticVerdict == "CLEAN" },
                blocked = list.count { it.staticVerdict == "BLOCKED" || it.review == CandidateReviewState.QUARANTINED },
                quarantined = list.count { it.review == CandidateReviewState.QUARANTINED },
                lxVerified = list.count {
                    runCatching { VerificationLevel.valueOf(it.verificationLevel) }
                        .getOrDefault(VerificationLevel.STATIC_REVIEW).rank >= VerificationLevel.LX_RUNTIME_LOAD.rank
                },
                realVerified = list.count {
                    runCatching { VerificationLevel.valueOf(it.verificationLevel) }
                        .getOrDefault(VerificationLevel.STATIC_REVIEW).isReal
                },
            )
        }
    }

    /** E5 筛选：名称含关键词（大小写不敏感）或 discoveredDomains 命中，再按审核状态过滤。 */
    fun filterCandidates(
        list: List<DiscoveredCandidate>,
        text: String,
        status: CandidateReviewState?,
    ): List<DiscoveredCandidate> {
        val q = text.trim()
        return list.filter { c ->
            val matchesText = q.isEmpty() ||
                c.name.contains(q, ignoreCase = true) ||
                c.id.contains(q, ignoreCase = true) ||
                c.discoveredDomains.any { it.contains(q, ignoreCase = true) }
            val matchesStatus = status == null || c.review == status
            matchesText && matchesStatus
        }
    }

    /** E5 安装限制说明：本框架不提供安装/启用入口，任何正式启用需单独人工审核 + 受控目录签名发布。 */
    const val INSTALL_POLICY_NOTE =
        "安装/启用需单独人工审核与受控目录签名发布；本页不提供安装按钮，候选不会自动成为正式音源。"

    /** 把下载/扫描的失败原因归到明确类别，避免笼统"操作失败"。 */
    fun classifyError(reason: String): CommunityOperationError = when {
        reason.contains("SHA256", ignoreCase = true) || reason.contains("哈希", ignoreCase = true) ->
            CommunityOperationError.HASH_MISMATCH
        reason.contains("BLOCK", ignoreCase = true) || reason.contains("隔离", ignoreCase = true) ->
            CommunityOperationError.STATIC_BLOCK
        reason.contains("HTTPS", ignoreCase = true) || reason.contains("SSRF", ignoreCase = true) ||
            reason.contains("私网", ignoreCase = true) || reason.contains("重定向", ignoreCase = true) ||
            reason.contains("localhost", ignoreCase = true) ->
            CommunityOperationError.SECURITY_REJECTED
        reason.contains("超时", ignoreCase = true) || reason.contains("网络", ignoreCase = true) ||
            reason.contains("DNS", ignoreCase = true) || reason.contains("HTTP 5", ignoreCase = true) ->
            CommunityOperationError.NETWORK
        reason.contains("写入", ignoreCase = true) || reason.contains("存储", ignoreCase = true) ->
            CommunityOperationError.STORAGE
        reason.contains("不存在", ignoreCase = true) -> CommunityOperationError.NOT_FOUND
        else -> CommunityOperationError.OTHER
    }

    fun errorLabel(kind: CommunityOperationError): String = when (kind) {
        CommunityOperationError.NETWORK -> "网络失败（可稍后重试）"
        CommunityOperationError.HASH_MISMATCH -> "SHA256 不匹配，已拒绝写入隔离区"
        CommunityOperationError.STATIC_BLOCK -> "静态扫描命中 BLOCK 规则，候选已隔离"
        CommunityOperationError.STORAGE -> "本地存储写入失败"
        CommunityOperationError.SECURITY_REJECTED -> "安全策略拒绝（非 HTTPS / SSRF / 重定向）"
        CommunityOperationError.NOT_FOUND -> "候选不存在"
        CommunityOperationError.OTHER -> "操作失败"
    }
}

class RealCommunityDiscoveryController(
    private val repository: CommunityDiscoveryRepository,
    private val scope: CoroutineScope,
    /** 受控下载器：由 Factory 构造（HTTPS/SSRF/大小/超时/禁重定向）；可注入假实现测试。 */
    private val downloader: CommunityScriptDownloader? = null,
) : CommunityDiscoveryController {
    private val mutable = MutableStateFlow(CommunityDiscoveryUiState())
    override val uiState: StateFlow<CommunityDiscoveryUiState> = mutable.asStateFlow()

    override fun refresh() {
        if (mutable.value.isRefreshing) return // 防重复提交
        scope.launch {
            mutable.value = mutable.value.copy(isRefreshing = true, feedback = null, errorKind = null)
            val result = try {
                repository.refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(
                    isRefreshing = false, feedback = e.message,
                    errorKind = CommunityOperationError.NETWORK,
                )
                return@launch
            }
            mutable.value = mutable.value.copy(
                candidates = repository.candidates(),
                isRefreshing = false,
                feedback = if (result.ok) {
                    "刷新成功：新增 ${result.added} · 更新 ${result.sameIdUpdated} · 失效 ${result.markedStale}"
                } else {
                    "使用缓存清单：${result.parseError ?: "网络不可用"}（本地候选已保留）"
                },
            )
        }
    }

    override fun downloadToQuarantine(id: String, url: String) {
        val dl = downloader ?: run {
            mutable.value = mutable.value.copy(
                feedback = "下载器未注入", errorKind = CommunityOperationError.OTHER,
            )
            return
        }
        if (mutable.value.isDownloading) return // 防重复提交
        scope.launch {
            mutable.value = mutable.value.copy(
                isDownloading = true, feedback = null, errorKind = null,
            )
            // 下载器结果 → Repository 中性结果；下载在 Repository 锁外执行。
            val bridge: suspend (String) -> CommunityScriptDownloadOutcome = { u ->
                when (val r = dl.download(u)) {
                    is CommunityScriptDownloader.DownloadResult.Success ->
                        CommunityScriptDownloadOutcome.Downloaded(r.text, null)
                    is CommunityScriptDownloader.DownloadResult.Rejected ->
                        CommunityScriptDownloadOutcome.Failed("安全策略拒绝：${r.reason}")
                    is CommunityScriptDownloader.DownloadResult.Timeout ->
                        CommunityScriptDownloadOutcome.Failed("网络超时")
                    is CommunityScriptDownloader.DownloadResult.TooLarge ->
                        CommunityScriptDownloadOutcome.Failed("响应超过 ${r.limit} 字节上限（实际 ${r.observed}）")
                    is CommunityScriptDownloader.DownloadResult.Failed ->
                        CommunityScriptDownloadOutcome.Failed("网络失败：${r.reason}")
                }
            }
            val result = try {
                repository.downloadAndScan(id, url, bridge)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CommunityDiscoveryRepository.QuarantineDownloadResult.Failed(e.message.orEmpty())
            }
            val (message, errorKind) = when (result) {
                is CommunityDiscoveryRepository.QuarantineDownloadResult.Scanned -> {
                    val c = result.candidate
                    val hashNote = if (c.hashUnverified) "（哈希未经来源确认，不可信）" else ""
                    "已隔离并扫描：${CommunityDiscoveryPresentation.verdictLabel(c)}$hashNote" to
                        if (c.review == CandidateReviewState.QUARANTINED) CommunityOperationError.STATIC_BLOCK else null
                }
                is CommunityDiscoveryRepository.QuarantineDownloadResult.Sha256Mismatch ->
                    "SHA256 不匹配（实际 ${result.actual.take(12)}…），未写入隔离区" to
                        CommunityOperationError.HASH_MISMATCH
                is CommunityDiscoveryRepository.QuarantineDownloadResult.NotFound -> "候选不存在" to
                    CommunityOperationError.NOT_FOUND
                is CommunityDiscoveryRepository.QuarantineDownloadResult.Failed ->
                    result.reason to CommunityDiscoveryPresentation.classifyError(result.reason)
            }
            mutable.value = mutable.value.copy(
                candidates = repository.candidates(),
                isDownloading = false,
                feedback = message,
                errorKind = errorKind,
            )
        }
    }

    override fun quarantine(id: String, reason: String) = mutateWithFeedback(id) {
        repository.quarantine(id, reason)
    }

    override fun reject(id: String, reason: String) = mutateWithFeedback(id) {
        repository.reject(id, reason)
    }

    override fun reopen(id: String) = mutateWithFeedback(id) { repository.reopenForReview(id) }

    /** 二次确认：第一次标记，第二次（id 匹配）才真正删除。 */
    override fun requestDelete(id: String?) {
        val current = mutable.value.deleteConfirmId
        if (id == null) {
            mutable.value = mutable.value.copy(deleteConfirmId = null)
            return
        }
        if (current == id) {
            mutable.value = mutable.value.copy(deleteConfirmId = null)
            delete(id)
        } else {
            mutable.value = mutable.value.copy(deleteConfirmId = id)
        }
    }

    override fun delete(id: String) = mutateWithFeedback(id) {
        if (mutable.value.selectedId == id) mutable.value = mutable.value.copy(selectedId = null)
        repository.deleteCandidate(id)
    }

    override fun scanScript(id: String, scriptText: String, expectedSha256: String, version: String?) {
        if (mutable.value.isBusy) return
        scope.launch {
            mutable.value = mutable.value.copy(isBusy = true, feedback = null, errorKind = null)
            val result = try {
                repository.scanDownloadedScript(id, scriptText, expectedSha256, version)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CommunityDiscoveryRepository.QuarantineDownloadResult.Failed(e.message.orEmpty())
            }
            val (message, errorKind) = when (result) {
                is CommunityDiscoveryRepository.QuarantineDownloadResult.Scanned ->
                    "扫描完成：${CommunityDiscoveryPresentation.verdictLabel(result.candidate)}" to null
                is CommunityDiscoveryRepository.QuarantineDownloadResult.Sha256Mismatch ->
                    "SHA256 不匹配，已拒绝（实际 ${result.actual.take(12)}…）" to CommunityOperationError.HASH_MISMATCH
                is CommunityDiscoveryRepository.QuarantineDownloadResult.NotFound -> "候选不存在" to CommunityOperationError.NOT_FOUND
                is CommunityDiscoveryRepository.QuarantineDownloadResult.Failed ->
                    result.reason to CommunityDiscoveryPresentation.classifyError(result.reason)
            }
            mutable.value = mutable.value.copy(
                candidates = repository.candidates(), isBusy = false, feedback = message, errorKind = errorKind,
            )
        }
    }

    override fun select(id: String?) {
        mutable.value = mutable.value.copy(selectedId = id, deleteConfirmId = null)
    }

    override fun clearFeedback() {
        mutable.value = mutable.value.copy(feedback = null, errorKind = null)
    }

    override fun setFilterText(text: String) {
        mutable.value = mutable.value.copy(filterText = text)
    }

    override fun setStatusFilter(status: CandidateReviewState?) {
        mutable.value = mutable.value.copy(statusFilter = status)
    }

    private fun mutateWithFeedback(id: String, action: suspend () -> Boolean) {
        if (mutable.value.isBusy) return
        scope.launch {
            mutable.value = mutable.value.copy(isBusy = true, errorKind = null)
            val ok = try {
                action()
            } catch (e: kotlinx.coroutines.CancellationException) {
                mutable.value = mutable.value.copy(isBusy = false)
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(
                    isBusy = false, feedback = e.message,
                    errorKind = CommunityOperationError.STORAGE,
                )
                return@launch
            }
            mutable.value = mutable.value.copy(
                candidates = repository.candidates(), isBusy = false,
                feedback = if (ok) "操作已保存" else "候选不存在",
                errorKind = if (ok) null else CommunityOperationError.NOT_FOUND,
            )
        }
    }
}

/** 测试/无平台注入时的空实现。 */
class NoOpCommunityDiscoveryController : CommunityDiscoveryController {
    private val mutable = MutableStateFlow(CommunityDiscoveryUiState())
    override val uiState: StateFlow<CommunityDiscoveryUiState> = mutable.asStateFlow()
    override fun refresh() = Unit
    override fun downloadToQuarantine(id: String, url: String) = Unit
    override fun quarantine(id: String, reason: String) = Unit
    override fun reject(id: String, reason: String) = Unit
    override fun reopen(id: String) = Unit
    override fun delete(id: String) = Unit
    override fun scanScript(id: String, scriptText: String, expectedSha256: String, version: String?) = Unit
    override fun select(id: String?) = Unit
    override fun requestDelete(id: String?) = Unit
    override fun clearFeedback() = Unit
    override fun setFilterText(text: String) = Unit
    override fun setStatusFilter(status: CandidateReviewState?) = Unit
}
