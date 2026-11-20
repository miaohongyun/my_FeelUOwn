package org.feeluown.mobile.sourceupdate

import kotlinx.serialization.json.Json
import org.feeluown.mobile.sourceupdate.crypto.Sha256

/**
 * 社区音源「自动发现」框架（阶段 C）。
 *
 * 严格隔离红线（与 [SourceCatalogUpdateHost] 的受控目录更新分开）：
 * - 只读社区清单(sources.json)并把它当作**候选线索**，不是信任根；
 * - 发现结果写进候选区 store，状态只会是 DISCOVERED / QUARANTINED / NEEDS_REVIEW，
 *   **永远不会由本框架写成 APPROVED**；
 * - 不执行脚本、不调用安装事务、不扩大 SSRF 白名单、不覆盖受控目录 source-catalog.json；
 * - 清单读取失败 / 结构非法 / sources 为空时，保留上次成功的候选，绝不清空。
 *
 * 所有行为都可在 commonTest 用假 fetcher + 内存 store 完整验证，不依赖网络与私钥。
 */
class CommunityCatalogDiscovery(
    private val fetcher: CatalogFetcher,
    private val store: SourceFileStore,
    private val clock: () -> Long = { sourceUpdateNowMillis() },
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) {
    companion object {
        const val CANDIDATE_STORE_PATH = "community/candidates.json"
        /** 隔离区脚本存放前缀——只写文本，绝不交给运行时加载。 */
        const val QUARANTINE_PREFIX = "community/quarantine/"
    }

    /** 一次发现运行的报告。 */
    data class DiscoveryReport(
        val listUrl: String?,
        val usedCachedList: Boolean,
        val entriesInList: Int,
        val added: Int,
        val sameIdUpdated: Int,
        val duplicateUrlsMerged: Int,
        val markedStale: Int,
        /** 本轮从 STALE 自动恢复到 DISCOVERED 的条数（仅此一种状态允许自动恢复）。 */
        val revivedFromStale: Int = 0,
        val parseError: String?,
        val fetchErrors: List<String>,
    ) {
        val ok: Boolean get() = parseError == null
    }

    /**
     * 阶段 A（锁外）的产物：远端清单的网络获取 + 解析结果。
     * **不含任何候选库读写**——缓存回退与合并都交给阶段 B 在锁内完成，
     * 保证合并永远基于最新候选库状态。
     */
    data class FetchedCandidates(
        val raw: String?,
        val usedUrl: String?,
        val sources: List<CommunitySourceEntry>,
        val parseError: String?,
        val fetchErrors: List<String>,
    ) {
        /** 成功拿到一份结构合法的清单；全网络失败或结构非法都是 false。 */
        val fetchedOk: Boolean get() = raw != null && parseError == null
    }

    /** 当前候选区快照；损坏/缺失时返回空快照而不是抛异常。 */
    suspend fun loadStore(): CandidateStore {
        val raw = store.readText(CANDIDATE_STORE_PATH) ?: return CandidateStore()
        return runCatching { json.decodeFromString(CandidateStore.serializer(), raw) }
            .getOrDefault(CandidateStore())
    }

    private suspend fun saveStore(storeSnapshot: CandidateStore) {
        store.writeText(CANDIDATE_STORE_PATH, json.encodeToString(CandidateStore.serializer(), storeSnapshot))
    }

    /**
     * 阶段 A：**锁外**从主地址开始按序尝试镜像读取清单并解析。
     * 只做网络请求与解析，绝不读写候选库（缓存回退与合并交给阶段 B）。
     * 调用方必须在 Repository Mutex 之外调用本方法，避免锁内网络请求。
     */
    suspend fun fetchRemoteCandidates(listUrls: List<String>): FetchedCandidates {
        var raw: String? = null
        var usedUrl: String? = null
        val errors = mutableListOf<String>()

        for (url in listUrls) {
            val result = fetcher.fetch(url, maxBytes = HttpBodyLimits.CATALOG_JSON_BYTES)
            if (result.ok && result.body != null) {
                raw = result.body
                usedUrl = url
                break
            }
            errors += "$url: ${result.error ?: "未知错误"} ${result.detail.orEmpty()}".trim()
        }
        if (raw == null) {
            return FetchedCandidates(null, null, emptyList(),
                parseError = "清单读取失败（尚未回退缓存）", fetchErrors = errors)
        }
        val parsed = CommunityListParser.parse(raw)
        return if (parsed is CommunityListParse.Valid) {
            FetchedCandidates(raw, usedUrl, parsed.list.sources, parseError = null, fetchErrors = errors)
        } else {
            FetchedCandidates(raw, usedUrl, emptyList(),
                parseError = (parsed as CommunityListParse.Invalid).reason, fetchErrors = errors)
        }
    }

    /**
     * 阶段 B：**锁内**合并。调用方（Repository）必须持有 Mutex。
     *
     * 保证：
     * - 重新读取最新候选库快照，绝不使用网络请求开始前的旧快照覆盖；
     * - 网络/解析失败时用最新快照的 lastGoodListRaw 回退，且不清空候选；
     * - [CommunityCatalogDiscovery] 的 tombstone（用户删除的 id）命中条目直接跳过，
     *   防止"删除后一条在途的旧刷新把候选复活"；
     * - 人工审核状态（REJECTED/QUARANTINED）沿用最新值，不被发现结果覆盖；
     * - 扫描证据（sha256/扫描时间/静态结论）在远端条目未变化时保留，不被空结果清除。
     */
    suspend fun mergeFetchedCandidates(fetched: FetchedCandidates): DiscoveryReport {
        val now = clock()
        val previous = loadStore() // 锁内重读：始终基于最新快照
        var raw = fetched.raw
        var usedUrl = fetched.usedUrl
        var usedCached = false
        var sources = fetched.sources

        if (!fetched.fetchedOk) {
            // 网络全失败或结构非法：尝试用最新快照的缓存原文继续。
            // 结构非法（raw 存在但 parseError 非空）时不回退到自身，直接保留现有候选。
            if (raw == null && !previous.lastGoodListRaw.isNullOrBlank()) {
                val cachedParsed = CommunityListParser.parse(previous.lastGoodListRaw)
                if (cachedParsed is CommunityListParse.Valid) {
                    raw = previous.lastGoodListRaw
                    usedUrl = previous.lastGoodListUrl
                    sources = cachedParsed.list.sources
                    usedCached = true
                }
            }
            if (raw == null || sources.isEmpty() && fetched.parseError != null) {
                // 无缓存可用，或远端清单结构非法：不清空候选、不覆盖 lastGoodListRaw。
                return DiscoveryReport(
                    usedUrl, usedCached, 0, 0, 0, 0, 0,
                    parseError = fetched.parseError ?: "清单读取失败且无缓存，保留现有候选",
                    fetchErrors = fetched.fetchErrors,
                )
            }
            // 缓存回退成功：清掉远端错误，按 ok 报告。
        }

        val ignored = previous.ignoredIds.map { it.lowercase() }.toSet()
        val candidates = previous.candidates.toMutableList()
        var added = 0
        var sameIdUpdated = 0
        var merged = 0
        var revivedFromStale = 0
        val seenIds = mutableSetOf<String>()

        for (entry in sources) {
            // 墓碑：用户删除过的 id（含别名）不因新的发现而复活。
            if (entry.id.lowercase() in ignored) continue
            seenIds += entry.id.lowercase()
            val norm = CommunityCatalogNormalizer.normalizeUrl(entry.url)
            val path = CommunityCatalogNormalizer.repoPath(entry.url)
            val host = CommunityCatalogNormalizer.hostOf(entry.url)
            when (val decision = CommunityCatalogNormalizer.dedup(candidates, entry)) {
                is DedupDecision.New -> {
                    candidates += DiscoveredCandidate(
                        id = entry.id,
                        name = entry.name,
                        rawUrl = entry.url,
                        normalizedUrl = norm,
                        repoPath = path,
                        discoveredDomains = listOfNotNull(host),
                        discoveredAtMillis = now,
                        lastCheckedAtMillis = now,
                        reviewState = CandidateReviewState.DISCOVERED.wireName,
                        verificationLevel = VerificationLevel.STATIC_REVIEW.name,
                    )
                    added++
                }
                is DedupDecision.SameId -> {
                    val old = candidates[decision.existingIndex]
                    // 只有 STALE 会在重新出现时自动恢复到 DISCOVERED；
                    // QUARANTINED/REJECTED/NEEDS_REVIEW/APPROVED 一律沿用人工结论，绝不自动改。
                    val revived = old.review == CandidateReviewState.STALE
                    if (revived) revivedFromStale++
                    candidates[decision.existingIndex] = old.copy(
                        name = entry.name,
                        rawUrl = entry.url,
                        // URL 变了才清掉旧的哈希/扫描证据；URL 未变时保留，避免每次刷新把证据抹掉。
                        sha256 = if (old.normalizedUrl == norm) old.sha256 else null,
                        hashUnverified = if (old.normalizedUrl == norm) old.hashUnverified else false,
                        sizeBytes = if (old.normalizedUrl == norm) old.sizeBytes else null,
                        version = if (old.normalizedUrl == norm) old.version else null,
                        scannedAtMillis = if (old.normalizedUrl == norm) old.scannedAtMillis else 0,
                        staticVerdict = if (old.normalizedUrl == norm) old.staticVerdict else null,
                        staticBlockReasons = if (old.normalizedUrl == norm) old.staticBlockReasons else emptyList(),
                        lastCheckedAtMillis = now,
                        reviewState = if (revived) CandidateReviewState.DISCOVERED.wireName else old.reviewState,
                        verificationLevel = old.verificationLevel,
                        risk = old.risk,
                    )
                    sameIdUpdated++
                }
                is DedupDecision.DuplicateUrl -> {
                    val old = candidates[decision.existingIndex]
                    val mirrors = (old.mirrorUrls + entry.url).distinct()
                    val aliases = (old.aliasIds + entry.id).distinct()
                    // 别名重新出现同样只自动恢复 STALE → DISCOVERED。
                    val revived = old.review == CandidateReviewState.STALE
                    if (revived) revivedFromStale++
                    candidates[decision.existingIndex] = old.copy(
                        mirrorUrls = mirrors,
                        aliasIds = aliases,
                        lastCheckedAtMillis = now,
                        reviewState = if (revived) CandidateReviewState.DISCOVERED.wireName else old.reviewState,
                    )
                    merged++
                }
            }
        }

        // 清单中消失的条目标记 STALE（不删除本地记录）——远端删除不等于本地失效。
        // 关键：一条候选可能因镜像合并而有多个 id，主 id 或任一别名仍在清单里就不算消失。
        var markedStale = 0
        for (i in candidates.indices) {
            val c = candidates[i]
            val allIds = (listOf(c.id) + c.aliasIds).map { it.lowercase() }.toSet()
            val stillPresent = allIds.any { it in seenIds }
            if (!stillPresent && c.review != CandidateReviewState.STALE) {
                candidates[i] = c.copy(reviewState = CandidateReviewState.STALE.wireName)
                markedStale++
            }
        }

        saveStore(CandidateStore(
            updatedAtMillis = now,
            lastGoodListRaw = raw,
            lastGoodListUrl = usedUrl,
            candidates = candidates,
            ignoredIds = previous.ignoredIds,
        ))
        return DiscoveryReport(
            listUrl = usedUrl,
            usedCachedList = usedCached,
            entriesInList = sources.size,
            added = added,
            sameIdUpdated = sameIdUpdated,
            duplicateUrlsMerged = merged,
            markedStale = markedStale,
            revivedFromStale = revivedFromStale,
            parseError = null,
            fetchErrors = fetched.fetchErrors,
        )
    }

    /**
     * 兼容入口：一次完成「锁外获取 + 合并」两阶段，供离线/单测使用。
     *
     * 注意：本方法**不带锁**，合并阶段的读改写不是原子的。生产 App 必须通过
     * [CommunityDiscoveryRepository.refresh]（它持有 Repository Mutex，锁外 fetch + 锁内 merge）
     * 调用，禁止在生产路径直接调用本方法写候选库。
     */
    suspend fun discover(listUrls: List<String>): DiscoveryReport =
        mergeFetchedCandidates(fetchRemoteCandidates(listUrls))

    /**
     * 把**已下载到隔离区**的脚本文本做静态扫描，只更新候选元数据与风险，
     * 不执行、不安装、不扩白名单、不改审核状态为 APPROVED。
     *
     * SHA256 由框架内部按 [scriptText] 实际字节计算并核验：与 [expectedSha256] 不一致时
     * 拒绝更新扫描结果，避免把错误哈希写进审查证据。更新时一并记录版本、来源 URL、
     * 扫描时间与实际哈希，保证证据可追溯。
     *
     * @param scriptText 隔离区脚本原文（由调用方通过受控下载通道取得，本框架不下载）。
     * @param expectedSha256 下载记录/调用方声明的期望 SHA256（hex）。
     */
    suspend fun scanQuarantined(
        candidateId: String,
        scriptText: String,
        expectedSha256: String,
        version: String? = null,
    ): ScanOutcome {
        val snapshot = loadStore()
        val index = snapshot.candidates.indexOfFirst { it.id.equals(candidateId, ignoreCase = true) }
        if (index < 0) return ScanOutcome.NotFound
        val candidate = snapshot.candidates[index]
        val bytes = scriptText.encodeToByteArray()
        val actualSha256 = Sha256.hex(bytes)
        if (!actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
            // 哈希不符：整条扫描结果拒绝写入，候选保持原样（含原有 sha256/状态/等级）。
            return ScanOutcome.Sha256Mismatch(
                expected = expectedSha256,
                actual = actualSha256,
                candidate = candidate,
            )
        }
        val scannedAt = clock()
        // 发现阶段没有「目录声明的 allowedHosts」，undeclared-host 规则在此不适用。
        // 用脚本自己提取的域名跑扫描，让 BLOCK 只反映真正的 BLOCK 规则（eval/SVIP/混淆/卡密等）。
        // 这不等于扩大白名单：提取到的域名只写进候选元数据供人工审核参考，
        // 真正的运行时白名单只能在人工审核后写进受控目录。
        val selfDeclaredHosts = SourceStaticPolicy.extractHosts(scriptText)
        val report = SourceStaticPolicy.scan(
            scriptText = scriptText,
            allowedHosts = selfDeclaredHosts,
        )
        val verdict = report.verdict.name
        val blockReasons = report.blockReasons()
        val risk = when {
            report.blocked -> CandidateRisk.CRITICAL
            report.verdict == StaticVerdict.NEEDS_REVIEW -> CandidateRisk.HIGH
            else -> CandidateRisk.LOW
        }
        // BLOCK 项自动隔离；其余保持原审核状态（绝不自动 APPROVED）。
        val nextState = if (report.blocked) {
            CandidateReviewState.QUARANTINED.wireName
        } else {
            candidate.reviewState
        }
        val updated = candidate.copy(
            sha256 = actualSha256,
            sizeBytes = bytes.size.toLong(),
            version = version ?: candidate.version,
            scannedAtMillis = scannedAt,
            discoveredDomains = (candidate.discoveredDomains + SourceStaticPolicy.extractHosts(scriptText))
                .distinct().sorted(),
            staticVerdict = verdict,
            staticBlockReasons = blockReasons,
            risk = risk.name,
            reviewState = nextState,
            verificationLevel = VerificationLevel.STATIC_REVIEW.name,
        )
        val updatedList = snapshot.candidates.toMutableList().also { it[index] = updated }
        saveStore(snapshot.copy(updatedAtMillis = scannedAt, candidates = updatedList))
        return ScanOutcome.Scanned(updated, report)
    }

    sealed interface ScanOutcome {
        data object NotFound : ScanOutcome
        /** 传入哈希与实际脚本文节哈希不符，扫描结果被拒绝写入。 */
        data class Sha256Mismatch(
            val expected: String,
            val actual: String,
            val candidate: DiscoveredCandidate,
        ) : ScanOutcome
        data class Scanned(val candidate: DiscoveredCandidate, val report: StaticScanReport) : ScanOutcome
    }
}
