package org.feeluown.mobile.sourceupdate

import kotlinx.serialization.json.Json

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
        val parseError: String?,
        val fetchErrors: List<String>,
    ) {
        val ok: Boolean get() = parseError == null
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
     * 从主地址开始按序尝试镜像读取清单。全部失败时用上次成功缓存的原文继续解析；
     * 连缓存都没有则本轮 no-op（不清空候选）。
     */
    suspend fun discover(listUrls: List<String>): DiscoveryReport {
        val now = clock()
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

        var usedCached = false
        if (raw == null) {
            val cached = loadStore()
            if (!cached.lastGoodListRaw.isNullOrBlank()) {
                raw = cached.lastGoodListRaw
                usedUrl = cached.lastGoodListUrl
                usedCached = true
            }
        }
        if (raw == null) {
            return DiscoveryReport(null, false, 0, 0, 0, 0, 0,
                parseError = "清单读取失败且无缓存，保留现有候选", fetchErrors = errors)
        }

        val parsed = CommunityListParser.parse(raw)
        if (parsed !is CommunityListParse.Valid) {
            // 结构非法：保留现有候选与上次成功缓存，只记录错误。
            return DiscoveryReport(usedUrl, true, 0, 0, 0, 0, 0,
                parseError = (parsed as CommunityListParse.Invalid).reason, fetchErrors = errors)
        }

        val previous = loadStore()
        val candidates = previous.candidates.toMutableList()
        var added = 0
        var sameIdUpdated = 0
        var merged = 0
        val seenIds = mutableSetOf<String>()

        for (entry in parsed.list.sources) {
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
                    candidates[decision.existingIndex] = old.copy(
                        name = entry.name,
                        rawUrl = entry.url,
                        normalizedUrl = norm,
                        repoPath = path,
                        lastCheckedAtMillis = now,
                        // 只更新线索元数据；审核状态、验证等级、风险一律沿用人工结论。
                        reviewState = old.reviewState,
                        verificationLevel = old.verificationLevel,
                        risk = old.risk,
                    )
                    sameIdUpdated++
                }
                is DedupDecision.DuplicateUrl -> {
                    val old = candidates[decision.existingIndex]
                    val mirrors = (old.mirrorUrls + entry.url).distinct()
                    candidates[decision.existingIndex] = old.copy(
                        mirrorUrls = mirrors,
                        lastCheckedAtMillis = now,
                    )
                    merged++
                }
            }
        }

        // 清单中消失的条目标记 STALE（不删除本地记录）——远端删除不等于本地失效。
        var markedStale = 0
        for (i in candidates.indices) {
            val c = candidates[i]
            if (c.id.lowercase() !in seenIds && c.review != CandidateReviewState.STALE) {
                candidates[i] = c.copy(reviewState = CandidateReviewState.STALE.wireName)
                markedStale++
            }
        }

        saveStore(CandidateStore(
            updatedAtMillis = now,
            lastGoodListRaw = raw,
            lastGoodListUrl = usedUrl,
            candidates = candidates,
        ))
        return DiscoveryReport(
            listUrl = usedUrl,
            usedCachedList = usedCached,
            entriesInList = parsed.list.sources.size,
            added = added,
            sameIdUpdated = sameIdUpdated,
            duplicateUrlsMerged = merged,
            markedStale = markedStale,
            parseError = null,
            fetchErrors = errors,
        )
    }

    /**
     * 把**已下载到隔离区**的脚本文本做静态扫描，只更新候选元数据与风险，
     * 不执行、不安装、不扩白名单、不改审核状态为 APPROVED。
     *
     * @param scriptText 隔离区脚本原文（由调用方通过受控下载通道取得，本框架不下载）。
     */
    suspend fun scanQuarantined(candidateId: String, scriptText: String, sha256: String): ScanOutcome {
        val snapshot = loadStore()
        val index = snapshot.candidates.indexOfFirst { it.id.equals(candidateId, ignoreCase = true) }
        if (index < 0) return ScanOutcome.NotFound
        val candidate = snapshot.candidates[index]
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
            sha256 = sha256,
            sizeBytes = scriptText.encodeToByteArray().size.toLong(),
            discoveredDomains = (candidate.discoveredDomains + SourceStaticPolicy.extractHosts(scriptText))
                .distinct().sorted(),
            staticVerdict = verdict,
            staticBlockReasons = blockReasons,
            risk = risk.name,
            reviewState = nextState,
            verificationLevel = VerificationLevel.STATIC_REVIEW.name,
        )
        val updatedList = snapshot.candidates.toMutableList().also { it[index] = updated }
        saveStore(snapshot.copy(updatedAtMillis = clock(), candidates = updatedList))
        return ScanOutcome.Scanned(updated, report)
    }

    sealed interface ScanOutcome {
        data object NotFound : ScanOutcome
        data class Scanned(val candidate: DiscoveredCandidate, val report: StaticScanReport) : ScanOutcome
    }
}
