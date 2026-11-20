package org.feeluown.mobile.sourceupdate

import org.feeluown.mobile.sourceupdate.crypto.Sha256

/**
 * 社区音源「发现 + 审核 + 隔离区管理」的应用层编排（阶段 D）。
 *
 * 这是 [CommunityCatalogDiscovery] 框架与 UI/后台调度之间的唯一入口：
 * - D2 发现刷新：调用 discovery.discover()，URL 只用 [communityListUrls] 里硬编码的可信地址；
 * - D3 候选查询与人工审核动作（隔离/拒绝/恢复），写回候选 store；
 * - D4 隔离区管理：受控下载脚本文本 → SHA256 核验 → scanQuarantined；哈希不符整条拒绝；
 * - 删除候选只删 community/ 子树，绝不碰正式脚本目录。
 *
 * 安全边界（与框架一致，不可突破）：
 * - 绝不执行脚本、绝不安装、绝不自动批准、绝不扩大 SSRF 白名单、绝不覆盖受控目录；
 * - 审核动作只能由 UI 显式调用（人工），本类不会自行把任何候选改成 APPROVED；
 * - 隔离区脚本只写文本到 [CommunityCatalogDiscovery.QUARANTINE_PREFIX]，不交给 LX Runtime。
 */
class CommunityDiscoveryRepository(
    private val discovery: CommunityCatalogDiscovery,
    private val store: SourceFileStore,
    /** 社区清单地址：必须硬编码为可信来源（raw.githubusercontent / 已知代理），不接受 UI/远端注入。 */
    private val communityListUrls: List<String>,
) {
    /** 一次刷新的结果摘要，供 UI 展示。 */
    data class RefreshResult(
        val ok: Boolean,
        val candidateCount: Int,
        val added: Int,
        val sameIdUpdated: Int,
        val merged: Int,
        val markedStale: Int,
        val revivedFromStale: Int,
        val usedCachedList: Boolean,
        val parseError: String?,
    )

    suspend fun candidates(): List<DiscoveredCandidate> = discovery.loadStore().candidates

    suspend fun candidate(id: String): DiscoveredCandidate? =
        discovery.loadStore().candidates.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** 主动刷新 / 后台刷新都走这里；清单失败不清空候选（框架已保证）。 */
    suspend fun refresh(): RefreshResult {
        val report = discovery.discover(communityListUrls)
        return RefreshResult(
            ok = report.ok,
            candidateCount = discovery.loadStore().candidates.size,
            added = report.added,
            sameIdUpdated = report.sameIdUpdated,
            merged = report.duplicateUrlsMerged,
            markedStale = report.markedStale,
            revivedFromStale = report.revivedFromStale,
            usedCachedList = report.usedCachedList,
            parseError = report.parseError,
        )
    }

    /** 人工审核动作：隔离。绝不写 APPROVED。 */
    suspend fun quarantine(id: String, reason: String): Boolean = updateState(id) { old ->
        old.copy(
            reviewState = CandidateReviewState.QUARANTINED.wireName,
            rejectReason = reason.ifBlank { old.rejectReason },
        )
    }

    /** 人工审核动作：标记拒绝。 */
    suspend fun reject(id: String, reason: String): Boolean = updateState(id) { old ->
        old.copy(
            reviewState = CandidateReviewState.REJECTED.wireName,
            rejectReason = reason.ifBlank { "人工标记拒绝" },
        )
    }

    /**
     * 人工审核动作：恢复到待审。
     * 这是 UI 显式的人工操作，允许把 QUARANTINED/REJECTED/STALE 改回 DISCOVERED；
     * 但本类任何路径都不会把候选改成 APPROVED——批准永远是受控目录签发流程的事。
     */
    suspend fun reopenForReview(id: String): Boolean = updateState(id) { old ->
        old.copy(reviewState = CandidateReviewState.DISCOVERED.wireName)
    }

    private suspend fun updateState(
        id: String,
        transform: (DiscoveredCandidate) -> DiscoveredCandidate,
    ): Boolean {
        val snapshot = discovery.loadStore()
        val index = snapshot.candidates.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (index < 0) return false
        val updated = transform(snapshot.candidates[index])
        check(updated.review != CandidateReviewState.APPROVED) { "社区发现流程禁止写入 APPROVED" }
        val next = snapshot.candidates.toMutableList().also { it[index] = updated }
        store.writeText(
            CommunityCatalogDiscovery.CANDIDATE_STORE_PATH,
            serializeStore(snapshot.copy(updatedAtMillis = sourceUpdateNowMillis(), candidates = next)),
        )
        return true
    }

    /**
     * 删除候选及其隔离脚本。存储键强制在 community/ 子树内——
     * 正式脚本位于 sources/ 等其它前缀，绝不会被这里的删除碰到。
     */
    suspend fun deleteCandidate(id: String): Boolean {
        val snapshot = discovery.loadStore()
        val target = snapshot.candidates.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: return false
        val next = snapshot.candidates.filterNot { it.id.equals(id, ignoreCase = true) }
        store.writeText(
            CommunityCatalogDiscovery.CANDIDATE_STORE_PATH,
            serializeStore(snapshot.copy(updatedAtMillis = sourceUpdateNowMillis(), candidates = next)),
        )
        // 只删该候选的隔离脚本，路径前缀固定为 community/quarantine/。
        val scriptPath = quarantinePath(target.id)
        if (store.exists(scriptPath)) store.delete(scriptPath)
        return true
    }

    suspend fun quarantinedScriptText(id: String): String? =
        store.readText(quarantinePath(id))

    /** 隔离脚本路径：固定 community/quarantine/<id>.js，不与正式脚本目录混用。 */
    fun quarantinePath(id: String): String {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(120)
        return "${CommunityCatalogDiscovery.QUARANTINE_PREFIX}$safe.js"
    }

    /** 受控下载结果。 */
    sealed interface QuarantineDownloadResult {
        data object NotFound : QuarantineDownloadResult
        data class Sha256Mismatch(val expected: String, val actual: String) : QuarantineDownloadResult
        data class Scanned(val candidate: DiscoveredCandidate) : QuarantineDownloadResult
        data class Failed(val reason: String) : QuarantineDownloadResult
    }

    /**
     * D4 核心：把已下载的脚本文本落进隔离区并核验哈希后扫描。
     * 脚本正文由调用方（受控下载通道）提供，本类不发起任意 URL 下载、不执行脚本。
     * 哈希不符时：不写隔离脚本、不更新候选（整条拒绝）。
     */
    suspend fun scanDownloadedScript(
        id: String,
        scriptText: String,
        expectedSha256: String,
        version: String? = null,
    ): QuarantineDownloadResult {
        val candidate = candidate(id) ?: return QuarantineDownloadResult.NotFound
        val actual = Sha256.hex(scriptText.encodeToByteArray())
        if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
            return QuarantineDownloadResult.Sha256Mismatch(expectedSha256, actual)
        }
        // 先写隔离区（独立前缀），再扫描；扫描失败也不影响正式目录。
        store.writeText(quarantinePath(id), scriptText)
        return when (val outcome = discovery.scanQuarantined(id, scriptText, expectedSha256, version)) {
            is CommunityCatalogDiscovery.ScanOutcome.NotFound -> QuarantineDownloadResult.NotFound
            is CommunityCatalogDiscovery.ScanOutcome.Sha256Mismatch ->
                QuarantineDownloadResult.Sha256Mismatch(outcome.expected, outcome.actual)
            is CommunityCatalogDiscovery.ScanOutcome.Scanned -> QuarantineDownloadResult.Scanned(outcome.candidate)
        }
    }

    private fun serializeStore(snapshot: CandidateStore): String =
        CommunityDiscoverySerialization.encode(snapshot)
}

/** 候选 store 的 JSON 编解码（集中一处，避免 Repository 和框架各写一份配置不一致）。 */
internal object CommunityDiscoverySerialization {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun encode(store: CandidateStore): String =
        json.encodeToString(CandidateStore.serializer(), store)

    fun decode(raw: String): CandidateStore =
        json.decodeFromString(CandidateStore.serializer(), raw)
}
