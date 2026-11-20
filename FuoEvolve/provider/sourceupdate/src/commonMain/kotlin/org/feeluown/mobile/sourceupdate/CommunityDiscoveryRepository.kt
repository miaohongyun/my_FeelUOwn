package org.feeluown.mobile.sourceupdate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import org.feeluown.mobile.sourceupdate.crypto.Sha256

/**
 * 社区音源「发现 + 审核 + 隔离区管理」的应用层编排（阶段 D / 修复版）。
 *
 * 这是 [CommunityCatalogDiscovery] 框架与 UI/后台 Worker 之间的唯一入口：
 * - 发现刷新：调用 discovery.discover()，URL 只用 [communityListUrls] 里硬编码的可信地址；
 * - 人工审核（隔离/拒绝/重新审核，写 rejectReason）；
 * - 隔离区：脚本正文由受控下载器在**锁外**取得，哈希核验后写隔离区并静态扫描；
 * - 删除候选只删 community/ 子树，绝不碰正式脚本目录。
 *
 * 并发一致性（修复 P2-1）：所有候选库的「读-改-写」都经过同一个 [mutex]，避免后台发现、
 * UI 手动刷新、人工审核、扫描、删除互相用旧快照覆盖。人工决定（REJECTED/QUARANTINED）
 * 优先级高于普通发现结果——discover 的 SameId 分支本身保留 reviewState，且串行化后不会
 * 再出现「人工审核写入后被并发的旧发现快照覆盖」。
 *
 * 安全边界（不可突破）：
 * - 绝不执行脚本、绝不安装、绝不自动批准（[updateStateLocked] 有 check 断言）、
 *   绝不扩大 SSRF 白名单、绝不覆盖受控目录；
 * - 隔离区脚本只写文本到 [CommunityCatalogDiscovery.QUARANTINE_PREFIX]，不交给 LX Runtime；
 * - 协程取消（[CancellationException]）不在任何锁内被吞掉。
 */
class CommunityDiscoveryRepository(
    private val discovery: CommunityCatalogDiscovery,
    private val store: SourceFileStore,
    /** 社区清单地址：必须硬编码为可信来源，不接受 UI/远端注入。 */
    private val communityListUrls: List<String>,
) {
    private val mutex = Mutex()

    /** 一次刷新的结果摘要，供 UI 与 Worker 共用。 */
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

    /** 判断刷新失败是否可重试（Worker 据此决定 retry / failure）。清单自身解析失败属配置/数据错误。 */
    fun isRetryableRefreshFailure(result: RefreshResult): Boolean =
        !result.ok && result.parseError == null

    /** Worker 结果分类（纯函数，便于 commonTest 验证等待机制之外的结果判定）。 */
    enum class RefreshDisposition { SUCCESS, RETRY, FAILURE }

    fun workerDisposition(result: RefreshResult): RefreshDisposition = when {
        result.ok -> RefreshDisposition.SUCCESS
        isRetryableRefreshFailure(result) -> RefreshDisposition.RETRY
        else -> RefreshDisposition.FAILURE
    }

    suspend fun candidates(): List<DiscoveredCandidate> = mutex.withLock {
        discovery.loadStore().candidates
    }

    suspend fun candidate(id: String): DiscoveredCandidate? = mutex.withLock {
        discovery.loadStore().candidates.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    /**
     * 真正的挂起刷新：Worker 与 UI 共用。
     *
     * 两阶段（阶段 D.1 P0）：
     * - 阶段 A [discovery.fetchRemoteCandidates]：**锁外**做网络请求与解析，绝不读候选库；
     * - 阶段 B [discovery.mergeFetchedCandidates]：**锁内**重读最新候选库再合并写回。
     *
     * 因此网络等待期间 Mutex 是空闲的，人工审核/删除/扫描提交不会被慢网络阻塞；
     * 而合并永远基于锁内读到的最新状态，不会被请求发出前的旧快照覆盖。
     */
    suspend fun refresh(): RefreshResult {
        // 阶段 A：锁外网络，CancellationException 由框架/ktor 自然上抛。
        val fetched = discovery.fetchRemoteCandidates(communityListUrls)
        // 网络返回后、拿锁前检查取消：取消后绝不写入这份"迟到的发现结果"。
        coroutineContext.ensureActive()
        // 阶段 B：锁内合并（含缓存回退），读改写全程互斥。
        return mutex.withLock {
            coroutineContext.ensureActive()
            val report = discovery.mergeFetchedCandidates(fetched)
            RefreshResult(
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
    }

    suspend fun quarantine(id: String, reason: String): Boolean = updateStateLocked(id) { old ->
        old.copy(
            reviewState = CandidateReviewState.QUARANTINED.wireName,
            rejectReason = reason.ifBlank { old.rejectReason },
        )
    }

    suspend fun reject(id: String, reason: String): Boolean = updateStateLocked(id) { old ->
        old.copy(
            reviewState = CandidateReviewState.REJECTED.wireName,
            rejectReason = reason.ifBlank { "人工标记拒绝" },
        )
    }

    /** UI 显式的人工重审；允许 STALE/QUARANTINED/REJECTED → DISCOVERED，绝不写 APPROVED。 */
    suspend fun reopenForReview(id: String): Boolean = updateStateLocked(id) { old ->
        old.copy(reviewState = CandidateReviewState.DISCOVERED.wireName)
    }

    private suspend fun updateStateLocked(
        id: String,
        transform: (DiscoveredCandidate) -> DiscoveredCandidate,
    ): Boolean = mutex.withLock {
        val snapshot = discovery.loadStore()
        val index = snapshot.candidates.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (index < 0) return@withLock false
        val updated = transform(snapshot.candidates[index])
        check(updated.review != CandidateReviewState.APPROVED) { "社区发现流程禁止写入 APPROVED" }
        val next = snapshot.candidates.toMutableList().also { it[index] = updated }
        saveStoreLocked(snapshot, next)
        true
    }

    /**
     * 删除候选及其隔离脚本；只触 community/ 子树，不碰正式目录。
     *
     * 删除=永久忽略：把主 id 与所有别名写进 [CandidateStore.ignoredIds] 墓碑。
     * 这样即使删除时有一条刷新正在锁外下载清单，它完成后的合并也会跳过这些 id，
     * 不会把刚删除的候选"复活"。用户若想重新纳入，需显式调用 [unignore]。
     */
    suspend fun deleteCandidate(id: String): Boolean = mutex.withLock {
        val snapshot = discovery.loadStore()
        val target = snapshot.candidates.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: return@withLock false
        val next = snapshot.candidates.filterNot { it.id.equals(id, ignoreCase = true) }
        val tombstones = (snapshot.ignoredIds + listOf(target.id) + target.aliasIds)
            .map { it.lowercase() }.distinct()
        saveStoreLocked(snapshot.copy(ignoredIds = tombstones), next)
        val scriptPath = quarantinePath(target.id)
        if (store.exists(scriptPath)) store.delete(scriptPath)
        true
    }

    /** 显式解除忽略：允许该 id 在下一次刷新中被重新发现（人工决定，非自动）。 */
    suspend fun unignore(id: String): Boolean = mutex.withLock {
        val snapshot = discovery.loadStore()
        val next = snapshot.ignoredIds.filterNot { it.equals(id, ignoreCase = true) }
        if (next.size == snapshot.ignoredIds.size) return@withLock false
        saveStoreLocked(snapshot.copy(ignoredIds = next), snapshot.candidates)
        true
    }

    suspend fun quarantinedScriptText(id: String): String? = mutex.withLock {
        store.readText(quarantinePath(id))
    }

    fun quarantinePath(id: String): String {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(120)
        return "${CommunityCatalogDiscovery.QUARANTINE_PREFIX}$safe.js"
    }

    /** 下载 + 隔离 + 扫描的结果。 */
    sealed interface QuarantineDownloadResult {
        data object NotFound : QuarantineDownloadResult
        data class Sha256Mismatch(val expected: String, val actual: String) : QuarantineDownloadResult
        data class Scanned(val candidate: DiscoveredCandidate) : QuarantineDownloadResult
        data class Failed(val reason: String) : QuarantineDownloadResult
    }

    /**
     * 完整下载流程（P1-2）：网络下载在锁外，拿到文本后调 [commitDownloadLocked]。
     *
     * @param downloader 受控下载器（HTTPS/SSRF/大小/超时/禁重定向由它保证）。
     * @param url 必须是候选记录里已登记的 URL（rawUrl 或 mirrorUrls），由调用方约束；
     *   本方法不信任任意 URL，会校验它确实属于该候选。
     */
    suspend fun downloadAndScan(
        id: String,
        url: String,
        downloader: suspend (String) -> CommunityScriptDownloadOutcome,
    ): QuarantineDownloadResult {
        // 锁外先读取候选的登记 URL 与预期哈希（快照仅用于下载决策，提交时会重读）。
        val snapshotCandidate = candidate(id) ?: return QuarantineDownloadResult.NotFound
        val allowedUrls = (listOf(snapshotCandidate.rawUrl) + snapshotCandidate.mirrorUrls)
            .map { it.trim().trimEnd('/') }.toSet()
        if (url.trim().trimEnd('/') !in allowedUrls) {
            return QuarantineDownloadResult.Failed("URL 不在候选登记的地址中，拒绝下载")
        }
        val outcome = try {
            downloader(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return QuarantineDownloadResult.Failed("下载失败: ${e.message.orEmpty()}")
        }
        return when (outcome) {
            is CommunityScriptDownloadOutcome.Failed -> QuarantineDownloadResult.Failed(outcome.reason)
            is CommunityScriptDownloadOutcome.Downloaded ->
                commitDownloadLocked(id, outcome.text, snapshotCandidate.sha256, outcome.version)
        }
    }

    /**
     * 已有脚本文本直接核验 + 扫描（供手动导入/测试）。锁内完成核验、写隔离区、扫描、回写，
     * 全程使用最新候选快照，避免旧快照覆盖并发的人工审核结果。
     */
    suspend fun scanDownloadedScript(
        id: String,
        scriptText: String,
        expectedSha256: String,
        version: String? = null,
    ): QuarantineDownloadResult = commitDownloadLocked(id, scriptText, expectedSha256.takeIf { it.isNotBlank() }, version)

    /** 锁内提交：重读最新状态 → 哈希核验 → 写隔离脚本 → 扫描 → 落回候选。 */
    private suspend fun commitDownloadLocked(
        id: String,
        scriptText: String,
        expectedSha256: String?,
        version: String?,
    ): QuarantineDownloadResult = mutex.withLock {
        val snapshot = discovery.loadStore()
        val index = snapshot.candidates.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (index < 0) return@withLock QuarantineDownloadResult.NotFound
        val current = snapshot.candidates[index]
        val actual = Sha256.hex(scriptText.encodeToByteArray())
        // 预期哈希若声明了，必须是合法 64 位 hex；非法格式直接拒绝提交（不写隔离区）。
        if (expectedSha256 != null && !isSha256Hex(expectedSha256)) {
            return@withLock QuarantineDownloadResult.Failed("预期 SHA256 格式非法（应为 64 位 hex）")
        }
        val hashOk = expectedSha256?.let { actual.equals(it.trim(), ignoreCase = true) }

        if (hashOk == false) {
            // 有预期值且不符：整条拒绝，不写隔离脚本、不改候选、不留半成品。
            return@withLock QuarantineDownloadResult.Sha256Mismatch(expectedSha256!!, actual)
        }
        // 无预期值：允许写入但标记"哈希未经来源确认"。
        val path = quarantinePath(id)
        store.writeText(path, scriptText)
        val outcome = discovery.scanQuarantined(
            id, scriptText, expectedSha256 ?: actual, version ?: current.version,
        )
        when (outcome) {
            is CommunityCatalogDiscovery.ScanOutcome.NotFound -> QuarantineDownloadResult.NotFound
            is CommunityCatalogDiscovery.ScanOutcome.Sha256Mismatch -> QuarantineDownloadResult.Sha256Mismatch(
                outcome.expected, outcome.actual,
            )
            is CommunityCatalogDiscovery.ScanOutcome.Scanned -> {
                // scanQuarantined 内部又读了一次 store 并回写；这里在其结果上补 hashUnverified 标记，
                // 基于最新 store 再写一次，避免丢标记。
                val after = discovery.loadStore()
                val idx2 = after.candidates.indexOfFirst { it.id.equals(id, ignoreCase = true) }
                if (idx2 >= 0) {
                    val marked = after.candidates[idx2].copy(
                        sha256 = actual,
                        hashUnverified = hashOk == null,
                        sizeBytes = scriptText.encodeToByteArray().size.toLong(),
                    )
                    saveStoreLocked(after, after.candidates.toMutableList().also { it[idx2] = marked })
                    QuarantineDownloadResult.Scanned(marked)
                } else {
                    QuarantineDownloadResult.Scanned(outcome.candidate)
                }
            }
        }
    }

    private suspend fun saveStoreLocked(snapshot: CandidateStore, next: List<DiscoveredCandidate>) {
        store.writeText(
            CommunityCatalogDiscovery.CANDIDATE_STORE_PATH,
            CommunityDiscoverySerialization.encode(
                snapshot.copy(updatedAtMillis = sourceUpdateNowMillis(), candidates = next),
            ),
        )
    }
}

/** 下载器与 Repository 之间的中性结果类型（解耦 UI 层下载器实现）。 */
sealed interface CommunityScriptDownloadOutcome {
    data class Downloaded(val text: String, val version: String? = null) : CommunityScriptDownloadOutcome
    data class Failed(val reason: String) : CommunityScriptDownloadOutcome
}

/** 是否是合法的 SHA256 hex（64 位十六进制）。 */
internal fun isSha256Hex(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/** 候选 store 的 JSON 编解码（集中一处，避免 Repository 和框架各写一份配置不一致）。 */
internal object CommunityDiscoverySerialization {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun encode(store: CandidateStore): String =
        json.encodeToString(CandidateStore.serializer(), store)

    fun decode(raw: String): CandidateStore =
        json.decodeFromString(CandidateStore.serializer(), raw)
}
