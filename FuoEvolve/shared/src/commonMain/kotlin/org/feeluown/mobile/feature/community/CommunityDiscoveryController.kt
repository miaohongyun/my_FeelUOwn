package org.feeluown.mobile.feature.community

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.feeluown.mobile.sourceupdate.CandidateReviewState
import org.feeluown.mobile.sourceupdate.CommunityDiscoveryRepository
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
    fun quarantine(id: String, reason: String)
    fun reject(id: String, reason: String)
    fun reopen(id: String)
    fun delete(id: String)
    /** 受控下载脚本文本后调用：写隔离区 + 哈希核验 + 静态扫描。 */
    fun scanScript(id: String, scriptText: String, expectedSha256: String, version: String?)
    fun select(id: String?)
    fun clearFeedback()
}

data class CommunityDiscoveryUiState(
    val candidates: List<DiscoveredCandidate> = emptyList(),
    val isRefreshing: Boolean = false,
    val feedback: String? = null,
    val selectedId: String? = null,
)

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
}

class RealCommunityDiscoveryController(
    private val repository: CommunityDiscoveryRepository,
    private val scope: CoroutineScope,
) : CommunityDiscoveryController {
    private val mutable = MutableStateFlow(CommunityDiscoveryUiState())
    override val uiState: StateFlow<CommunityDiscoveryUiState> = mutable.asStateFlow()

    override fun refresh() {
        scope.launch {
            mutable.value = mutable.value.copy(isRefreshing = true, feedback = null)
            val result = repository.refresh()
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

    override fun quarantine(id: String, reason: String) = mutateWithFeedback(id) {
        repository.quarantine(id, reason)
    }

    override fun reject(id: String, reason: String) = mutateWithFeedback(id) {
        repository.reject(id, reason)
    }

    override fun reopen(id: String) = mutateWithFeedback(id) { repository.reopenForReview(id) }

    override fun delete(id: String) = mutateWithFeedback(id) {
        if (mutable.value.selectedId == id) mutable.value = mutable.value.copy(selectedId = null)
        repository.deleteCandidate(id)
    }

    override fun scanScript(id: String, scriptText: String, expectedSha256: String, version: String?) {
        scope.launch {
            val result = repository.scanDownloadedScript(id, scriptText, expectedSha256, version)
            val message = when (result) {
                is CommunityDiscoveryRepository.QuarantineDownloadResult.Scanned -> "扫描完成：${CommunityDiscoveryPresentation.verdictLabel(result.candidate)}"
                is CommunityDiscoveryRepository.QuarantineDownloadResult.Sha256Mismatch -> "SHA256 不匹配，已拒绝（实际 ${result.actual.take(12)}…）"
                is CommunityDiscoveryRepository.QuarantineDownloadResult.NotFound -> "候选不存在"
                is CommunityDiscoveryRepository.QuarantineDownloadResult.Failed -> "扫描失败：${result.reason}"
            }
            mutable.value = mutable.value.copy(candidates = repository.candidates(), feedback = message)
        }
    }

    override fun select(id: String?) {
        mutable.value = mutable.value.copy(selectedId = id)
    }

    override fun clearFeedback() {
        mutable.value = mutable.value.copy(feedback = null)
    }

    private fun mutateWithFeedback(id: String, action: suspend () -> Boolean) {
        scope.launch {
            val ok = action()
            mutable.value = mutable.value.copy(
                candidates = repository.candidates(),
                feedback = if (ok) "操作已保存" else "候选不存在",
            )
        }
    }
}

/** 测试/无平台注入时的空实现。 */
class NoOpCommunityDiscoveryController : CommunityDiscoveryController {
    private val mutable = MutableStateFlow(CommunityDiscoveryUiState())
    override val uiState: StateFlow<CommunityDiscoveryUiState> = mutable.asStateFlow()
    override fun refresh() = Unit
    override fun quarantine(id: String, reason: String) = Unit
    override fun reject(id: String, reason: String) = Unit
    override fun reopen(id: String) = Unit
    override fun delete(id: String) = Unit
    override fun scanScript(id: String, scriptText: String, expectedSha256: String, version: String?) = Unit
    override fun select(id: String?) = Unit
    override fun clearFeedback() = Unit
}
