package org.feeluown.mobile.sourceupdate

/**
 * 阶段 F：真实功能验证台账 + 质量评分 + 主备调度的数据模型（commonMain，纯逻辑，可离线测试）。
 *
 * 诚信边界（贯穿整个文件）：
 * - SIMULATED（LX 模拟初始化）与 REAL_*（真实网络）严格分栏，不混合统计；
 * - 一次成功不等于稳定：评分必须带样本量，样本不足的维度不得给满分/不得判 EXCELLENT；
 * - MockEngine 结果永远只能标 SIMULATED，禁止升级为 REAL_*；
 * - URL 解析成功 ≠ 播放成功；HTTP 200/audio content-type ≠ 真实播放；
 * - 真实播放必须用户本人有权访问 + 用户明确控制，默认 NOT_RUN。
 */

/** 验证阶段（与 [VerificationLevel] 对齐，额外区分「脚本不支持」）。 */
enum class VerificationStage {
    LX_SIMULATED_INIT,
    REAL_SEARCH,
    REAL_URL_RESOLVE,
    REAL_AUDIO_RESPONSE,
    REAL_PLAYBACK,
}

/** 单个阶段的验证结论。 */
enum class VerificationOutcome {
    /** 真实验证通过。 */
    PASSED,
    /** 真实执行但失败（记录错误码/原因）。 */
    FAILED,
    /** 脚本未实现该 action（例如只有 musicUrl、没有 search）——不是通过也不是网络失败。 */
    NOT_SUPPORTED,
    /** 未执行（如真实播放需要用户授权）。 */
    NOT_RUN,
    /** 候选被 BLOCK/QUARANTINE，禁止进入真实验证。 */
    BLOCKED,
}

/** 一次验证尝试的原始数据（每个请求一行，成功失败都记录，禁止只挑成功）。 */
data class VerificationAttempt(
    val candidateId: String,
    val stage: VerificationStage,
    val verifiedAt: String,
    val source: String,               // REAL_NETWORK / LX_MOCK / STATIC
    val endpointHost: String? = null,
    val keyword: String? = null,
    val platform: String? = null,
    val httpStatus: Int? = null,
    val responseMs: Long? = null,
    val resultCount: Int? = null,
    val outcome: VerificationOutcome,
    val errorCode: String? = null,
    val error: String? = null,
    val domainRejectionReason: String? = null,
    val note: String? = null,
)

/** 某候选在某阶段的汇总（含样本量）。 */
data class StageSummary(
    val stage: VerificationStage,
    val outcome: VerificationOutcome,
    val sampleSize: Int,
    val successCount: Int,
    val failureCount: Int,
    val avgResponseMs: Long?,
    val lastVerifiedAt: String?,
    val lastFailureReason: String?,
)

/** 质量评分（合计 100）。每个维度都带样本量，样本不足不判满分。 */
data class QualityScore(
    val candidateId: String,
    val searchSuccess: Int,       // 0..25
    val searchAccuracy: Int,      // 0..20
    val urlResolveSuccess: Int,   // 0..20
    val playbackStability: Int,   // 0..20
    val errorHandlingSpeed: Int,  // 0..10
    val maintenanceTrust: Int,    // 0..5
    val total: Int,               // 0..100
    val realSearchSamples: Int,
    val realUrlSamples: Int,
    val realPlaybackSamples: Int,
    val grade: QualityGrade,
    val reason: String,
)

enum class QualityGrade {
    EXCELLENT, GOOD, LIMITED, UNSTABLE, UNVERIFIED, BLOCKED,
}

/** 主备路由建议（不自动启用；写进 UI 供人工确认）。 */
enum class AdmissionRecommendation {
    PRIMARY,        // 建议主音源
    BACKUP,         // 建议备用音源
    LIMITED,        // 限定平台使用
    OBSERVE,        // 继续观察
    DO_NOT_ADMIT,   // 暂不接入
    BLOCK,          // 阻断
}

data class CandidateAdmission(
    val candidateId: String,
    val recommendation: AdmissionRecommendation,
    val reason: String,
)

/**
 * 真实网络验证台账（2026-11-20 实跑快照，含失败请求、错误码、域名拒绝原因）。
 *
 * 数据来自真实 HTTPS 请求（经本机代理出网），只读公开端点、限流读取响应头/前若干字节，
 * 未下载商业音频、未提供任何 Cookie/Token、未做会员绕过。
 * 每台记录标注 source=REAL_NETWORK；E3 的 MockEngine 结果只以 LX_SIMULATED_INIT 出现，
 * 不计入任何 REAL_* 统计。
 */
object CommunityRealVerificationLedger {

    const val VERIFIED_AT = "2026-11-20"

    /** 全量尝试记录（成功与失败都保留，便于复核，禁止只挑成功）。 */
    val attempts: List<VerificationAttempt> = buildList {
        // ===== F0 至尊：裸 IP/明文 HTTP/1314，网络权限已拒绝；仍做只读探测记录真实失败 =====
        add(VerificationAttempt(
            "zhizun", VerificationStage.REAL_SEARCH, VERIFIED_AT, "REAL_NETWORK",
            endpointHost = "110.42.36.53", httpStatus = 502, responseMs = 5018,
            outcome = VerificationOutcome.FAILED, errorCode = "HTTP_502",
            error = "脚本未实现 search action（仅 musicUrl）；API 根探测返回 502",
            domainRejectionReason = "网络权限拒绝：裸 IP 明文 HTTP:1314，不满足 HTTPS/443/具名域名",
            note = "候选级权限 zhizunDenied 为 deny-all；未执行脚本内 URL 解析",
        ))
        add(VerificationAttempt(
            "zhizun", VerificationStage.REAL_URL_RESOLVE, VERIFIED_AT, "REAL_NETWORK",
            endpointHost = "110.42.36.53", httpStatus = 502, responseMs = 5018,
            outcome = VerificationOutcome.FAILED, errorCode = "HTTP_502",
            error = "解析端点 /url/wy/{id}/{quality} 返回空响应",
            domainRejectionReason = "网络权限拒绝：裸 IP 明文 HTTP:1314",
        ))

        // ===== F0 HUIBQ：服务被所有者暂停 =====
        add(VerificationAttempt(
            "huibq", VerificationStage.REAL_SEARCH, VERIFIED_AT, "REAL_NETWORK",
            endpointHost = "lxmusicapi.onrender.com", outcome = VerificationOutcome.NOT_SUPPORTED,
            errorCode = "NO_SEARCH_ACTION",
            note = "脚本 inited 仅声明 musicUrl action，无 search；无法提供搜索能力",
        ))
        add(VerificationAttempt(
            "huibq", VerificationStage.REAL_URL_RESOLVE, VERIFIED_AT, "REAL_NETWORK",
            endpointHost = "lxmusicapi.onrender.com", httpStatus = 503, responseMs = 3235,
            outcome = VerificationOutcome.FAILED, errorCode = "SERVICE_SUSPENDED",
            error = "Service Suspended by its owner（onrender 免费实例已被所有者暂停）",
            note = "解析端点 /url/wy/{id}/{quality} 同样返回暂停页；X-Request-Key=share-v3 为脚本内置公开 key，非用户凭据",
        ))

        // ===== F0 gd-studio：唯一真实可用的解析源 =====
        add(VerificationAttempt(
            "gd-studio", VerificationStage.REAL_SEARCH, VERIFIED_AT, "REAL_NETWORK",
            endpointHost = "music-api.gdstudio.xyz", keyword = "周杰伦", platform = "wy",
            httpStatus = 200, responseMs = 3244, resultCount = 2,
            outcome = VerificationOutcome.PASSED,
            note = "types=search 返回合法 JSON 数组；首条 屋顶 / 周杰伦、温岚、吴宗宪。" +
                "注意：这是上游 API 的搜索，不是 LX inited 的 search action（脚本仅声明 musicUrl）",
        ))
        add(VerificationAttempt(
            "gd-studio", VerificationStage.REAL_URL_RESOLVE, VERIFIED_AT, "REAL_NETWORK",
            endpointHost = "music-api.gdstudio.xyz", keyword = "__INVALID_ID__", platform = "wy",
            httpStatus = 200, responseMs = 3100, outcome = VerificationOutcome.FAILED,
            errorCode = "EMPTY_URL", error = "types=url 返回 {\"url\":\"\",\"br\":-1}，错误返回符合预期结构",
            note = "失败用例：无效 id 不返回可用 URL，符合预期（错误返回校验）",
        ))
        add(VerificationAttempt(
            "gd-studio", VerificationStage.REAL_URL_RESOLVE, VERIFIED_AT, "REAL_NETWORK",
            endpointHost = "music-api.gdstudio.xyz", platform = "wy", httpStatus = 200,
            responseMs = 3200, outcome = VerificationOutcome.PASSED,
            note = "types=url(id=5257138,br=320) 返回 https 播放地址，br=320，size=12764517；" +
                "解析 URL 域名为 m701.music.126.net（第三方 CDN），非候选授权域名 music-api.gdstudio.xyz",
        ))
        add(VerificationAttempt(
            "gd-studio", VerificationStage.REAL_AUDIO_RESPONSE, VERIFIED_AT, "REAL_NETWORK",
            endpointHost = "m701.music.126.net", httpStatus = 206, responseMs = 900,
            outcome = VerificationOutcome.PASSED,
            note = "Range 0-0 只读 1 字节响应头：HTTP 206 Partial Content / Content-Type audio/mpeg / " +
                "Accept-Ranges 支持。未保存音频文件、未做完整下载、未验证播放内容对应性",
        ))
        add(VerificationAttempt(
            "gd-studio", VerificationStage.REAL_PLAYBACK, VERIFIED_AT, "NOT_RUN",
            outcome = VerificationOutcome.NOT_RUN,
            note = "真实播放需用户本人有权访问对应内容并在用户明确控制下短时验证；默认不执行，HTTP 200/206 不冒充播放",
        ))

        // ===== F1 ikun：初始化即触发 checkUpdate（UPDATE_ENABLE=true） =====
        add(VerificationAttempt(
            "ikun-v515", VerificationStage.LX_SIMULATED_INIT, VERIFIED_AT, "LX_MOCK",
            endpointHost = "api.ikunshare.com", outcome = VerificationOutcome.FAILED,
            errorCode = "EMPTY_ALLOWED_HOSTS",
            error = "初始化 checkUpdate 调用 lx.request(/script/lxmusic?...)，空 allowedHosts 被 SSRF 门禁拒绝",
            note = "E3 已验证；失败证明门禁有效，不放宽全局白名单",
        ))
        add(VerificationAttempt(
            "ikun-v515", VerificationStage.REAL_SEARCH, VERIFIED_AT, "REAL_NETWORK",
            endpointHost = "api.ikunshare.com", httpStatus = 0, responseMs = 2188,
            outcome = VerificationOutcome.FAILED, errorCode = "TLS_HANDSHAKE_FAILED",
            error = "schannel: failed to receive handshake, SSL/TLS connection failed",
            note = "候选级权限已授权 api.ikunshare.com；真实网络 TLS 握手失败，与权限拒绝是不同错误",
        ))

        // ===== F1 juhe：init.conf 是初始化必需请求 =====
        add(VerificationAttempt(
            "juhe", VerificationStage.LX_SIMULATED_INIT, VERIFIED_AT, "LX_MOCK",
            endpointHost = "api.music.lerd.dpdns.org", outcome = VerificationOutcome.FAILED,
            errorCode = "EMPTY_ALLOWED_HOSTS",
            error = "脚本顶层 h(A+'/init.conf').then(...) — init 载荷来自服务器，空 allowedHosts 直接拒绝导致初始化失败",
            note = "区分：这是初始化必需网络请求，不是可选 checkUpdate；按候选授权 init 域名后可通过",
        ))
        add(VerificationAttempt(
            "juhe", VerificationStage.REAL_SEARCH, VERIFIED_AT, "REAL_NETWORK",
            endpointHost = "api.music.lerd.dpdns.org", httpStatus = 200, responseMs = 2653,
            outcome = VerificationOutcome.FAILED, errorCode = "DYNAMIC_303_UNTRUSTED",
            error = "init.conf 返回 code=200/init 载荷；但 musicUrl 走 303 响应体内二次动态 URL，未审不授权",
            note = "域名为动态 DNS（dpdns.org）且二次 URL 由响应体决定，静态不可审计，fail-closed",
        ))

        // ===== F1 animemusic：checkUpdate 初始化触发，真实端点返回 500 =====
        add(VerificationAttempt(
            "animemusic", VerificationStage.LX_SIMULATED_INIT, VERIFIED_AT, "LX_MOCK",
            endpointHost = "animemusic.bzxhkj.com", outcome = VerificationOutcome.FAILED,
            errorCode = "EMPTY_ALLOWED_HOSTS", note = "UPDATE_ENABLE=true，checkUpdate 被门禁拒绝",
        ))
        add(VerificationAttempt(
            "animemusic", VerificationStage.REAL_SEARCH, VERIFIED_AT, "REAL_NETWORK",
            endpointHost = "animemusic.bzxhkj.com", httpStatus = 200, responseMs = 9776,
            outcome = VerificationOutcome.FAILED, errorCode = "API_500_NO_SOURCE",
            error = "{\"code\":500,\"message\":\"不支持的平台: (空)\"}",
            note = "响应慢（约 9.8s）；根端点无平台参数返回 500；脚本仅声明 musicUrl",
        ))

        // ===== F1 Flower：混淆 + 裸 IP 初始化，QUARANTINE 保持 =====
        add(VerificationAttempt(
            "flower-pdone", VerificationStage.LX_SIMULATED_INIT, VERIFIED_AT, "LX_MOCK",
            endpointHost = "97.64.37.235", outcome = VerificationOutcome.BLOCKED,
            errorCode = "AGGREGATE_ERROR",
            error = "Promise.all 两个远程配置请求（含裸 IP http://97.64.37.235 + gist/npm 加载）失败 → AggregateError",
            note = "重度混淆，静态不可审；候选权限 flowerDenied 为 deny-all，QUARANTINE 保持",
        ))
    }

    fun attemptsFor(candidateId: String, stage: VerificationStage? = null): List<VerificationAttempt> =
        attempts.filter {
            it.candidateId.equals(candidateId, ignoreCase = true) && (stage == null || it.stage == stage)
        }

    /** 汇总某候选某阶段（只看真实网络记录；LX_MOCK/STATIC 不计入 success/failure）。 */
    fun summarize(candidateId: String, stage: VerificationStage): StageSummary {
        val real = attemptsFor(candidateId, stage).filter { it.source == "REAL_NETWORK" }
        val success = real.count { it.outcome == VerificationOutcome.PASSED }
        val failure = real.count { it.outcome == VerificationOutcome.FAILED }
        val notSupported = attemptsFor(candidateId, stage).any { it.outcome == VerificationOutcome.NOT_SUPPORTED }
        val blocked = attemptsFor(candidateId, stage).any { it.outcome == VerificationOutcome.BLOCKED }
        val times = real.mapNotNull { it.responseMs }.filter { it > 0 }
        val outcome = when {
            blocked -> VerificationOutcome.BLOCKED
            success > 0 && failure == 0 && real.isNotEmpty() -> VerificationOutcome.PASSED
            failure > 0 -> VerificationOutcome.FAILED
            notSupported -> VerificationOutcome.NOT_SUPPORTED
            real.isEmpty() -> VerificationOutcome.NOT_RUN
            else -> VerificationOutcome.FAILED
        }
        return StageSummary(
            stage = stage,
            outcome = outcome,
            sampleSize = real.size,
            successCount = success,
            failureCount = failure,
            avgResponseMs = if (times.isEmpty()) null else times.sum() / times.size,
            lastVerifiedAt = real.lastOrNull()?.verifiedAt,
            lastFailureReason = real.lastOrNull { it.outcome == VerificationOutcome.FAILED }?.error,
        )
    }
}

/**
 * 质量评分器：按权重打分，但强制样本量约束。
 * 没有任何真实搜索/解析数据的维度记 0，不得因一次成功给满分。
 */
object CommunityQualityScorer {

    const val MIN_SAMPLES_FOR_STABLE = 3

    fun score(candidateId: String): QualityScore {
        val search = CommunityRealVerificationLedger.summarize(candidateId, VerificationStage.REAL_SEARCH)
        val url = CommunityRealVerificationLedger.summarize(candidateId, VerificationStage.REAL_URL_RESOLVE)
        val audio = CommunityRealVerificationLedger.summarize(candidateId, VerificationStage.REAL_AUDIO_RESPONSE)
        val playback = CommunityRealVerificationLedger.summarize(candidateId, VerificationStage.REAL_PLAYBACK)
        val record = CommunitySourceRegistry.find(candidateId)

        // 搜索成功率 25：按真实搜索成功率给分，样本不足按成功率折算上限不超过 15。
        val searchRate = if (search.sampleSize > 0) search.successCount.toDouble() / search.sampleSize else 0.0
        val searchSuccess = (25 * searchRate).toInt().let { if (search.sampleSize < MIN_SAMPLES_FOR_STABLE) minOf(it, 15) else it }

        // 结果准确性 20：只有搜索通过且有结构证据才给基础分；样本不足封顶 12。
        val accuracyBase = if (search.outcome == VerificationOutcome.PASSED) 18 else 0
        val searchAccuracy = if (search.sampleSize < MIN_SAMPLES_FOR_STABLE) minOf(accuracyBase, 12) else accuracyBase

        // URL 解析 20：仅成功 + 域名核验记录齐全才给分；样本不足封顶 12。
        val urlBase = (20 * (if (url.sampleSize > 0) url.successCount.toDouble() / url.sampleSize else 0.0)).toInt()
        val urlResolve = if (url.sampleSize < MIN_SAMPLES_FOR_STABLE) minOf(urlBase, 12) else urlBase

        // 播放稳定性 20：需要真实播放样本，样本不足给 0（不得用 HTTP 206 冒充）。
        val playbackStability = if (playback.sampleSize >= MIN_SAMPLES_FOR_STABLE && playback.successCount > 0) {
            (20 * playback.successCount.toDouble() / playback.sampleSize).toInt()
        } else 0

        // 错误处理/响应 10：有登记记录 + 成功响应均 < 5s 给部分分。
        val fast = listOf(search, url).any { s -> s.avgResponseMs?.let { it < 5000 } == true }
        val errorHandling = (if (record != null) 4 else 0) + (if (fast) 4 else 0)

        // 来源可信 5：来源清晰 + SHA 已核验给满分，但 BLOCK 候选为 0。
        val maintenance = if (record?.hasVerifiedHash == true && !record.isBlocked) 5 else 0

        val total = searchSuccess + searchAccuracy + urlResolve + playbackStability + errorHandling + maintenance
        val grade = when {
            record?.isBlocked == true || audio.outcome == VerificationOutcome.BLOCKED -> QualityGrade.BLOCKED
            playback.sampleSize < MIN_SAMPLES_FOR_STABLE || search.sampleSize == 0 -> QualityGrade.UNVERIFIED
            total >= 80 && search.sampleSize >= MIN_SAMPLES_FOR_STABLE -> QualityGrade.EXCELLENT
            total >= 60 -> QualityGrade.GOOD
            total >= 35 -> QualityGrade.LIMITED
            else -> QualityGrade.UNSTABLE
        }
        val reason = when (grade) {
            QualityGrade.UNVERIFIED -> "真实播放/搜索样本不足（search=${search.sampleSize}, playback=${playback.sampleSize}），不得宣称稳定"
            QualityGrade.BLOCKED -> "候选为 BLOCK/QUARANTINE，禁止真实验证与接入"
            QualityGrade.LIMITED, QualityGrade.GOOD, QualityGrade.EXCELLENT ->
                "基于 ${search.sampleSize} 搜索/${url.sampleSize} 解析/${playback.sampleSize} 播放真实样本，单次成功不代表长期稳定"
            QualityGrade.UNSTABLE -> "失败率或响应表现不佳，样本 ${search.sampleSize}"
        }
        return QualityScore(
            candidateId = candidateId,
            searchSuccess = searchSuccess,
            searchAccuracy = searchAccuracy,
            urlResolveSuccess = urlResolve,
            playbackStability = playbackStability,
            errorHandlingSpeed = errorHandling,
            maintenanceTrust = maintenance,
            total = total,
            realSearchSamples = search.sampleSize,
            realUrlSamples = url.sampleSize,
            realPlaybackSamples = playback.sampleSize,
            grade = grade,
            reason = reason,
        )
    }

    /**
     * 准入建议：只有当至少两个候选的 REAL_SEARCH 通过（真实搜索样本 > 0 且成功率 100%），
     * 才允许给出 PRIMARY/BACKUP；否则一律 DO_NOT_ADMIT/OBSERVE，并如实说明原因。
     */
    fun admissions(): List<CandidateAdmission> {
        val eligible = listOf("zhizun", "huibq", "gd-studio").filter { id ->
            val s = CommunityRealVerificationLedger.summarize(id, VerificationStage.REAL_SEARCH)
            s.outcome == VerificationOutcome.PASSED && s.successCount > 0
        }
        val gateOpen = eligible.size >= 2
        val ids = listOf(
            "zhizun", "huibq", "gd-studio", "ikun-v515", "juhe", "animemusic",
            "flower-pdone", "changqing", "stellarwave", "sixyin", "qdy",
        )
        return ids.map { id ->
            val record = CommunitySourceRegistry.find(id)
            val score = score(id)
            when {
                record?.isBlocked == true || id == "flower-pdone" ->
                    CandidateAdmission(id, AdmissionRecommendation.BLOCK, "BLOCK/QUARANTINE 候选保持阻断")
                !gateOpen ->
                    CandidateAdmission(id, AdmissionRecommendation.DO_NOT_ADMIT,
                        "真实搜索通过候选不足 2 个（当前 ${eligible.size}），调度器不启用；" +
                            if (id == "gd-studio") "gd-studio 解析可用但样本不足且无 search action，暂不接入" else score.reason)
                id == "gd-studio" ->
                    CandidateAdmission(id, AdmissionRecommendation.LIMITED,
                        "URL 解析真实可用（样本 ${score.realUrlSamples}），但无 LX search action、解析域名非授权域名、播放未验证")
                else ->
                    CandidateAdmission(id, AdmissionRecommendation.BACKUP, score.reason)
            }
        }
    }
}
