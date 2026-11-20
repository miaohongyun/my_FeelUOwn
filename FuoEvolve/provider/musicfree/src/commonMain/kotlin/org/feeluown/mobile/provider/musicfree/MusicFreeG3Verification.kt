package org.feeluown.mobile.provider.musicfree

/**
 * 阶段 G3：真实端到端验证台账（2026-11-20，真实网络，非 MockEngine）。
 *
 * 验证方式：本机 HTTPS 代理出网 + OAuth 1.0 公开 consumer key（audiomack-js，脚本内公开写死，
 * 非用户凭据/非会员绕过）+ 严格超时 + 只读请求 + Range 0-0 仅读 1 字节响应头。
 * 未下载任何商业音频正文、未提供 Cookie/Token、未做会员/访问控制绕过、未真实播放。
 *
 * 每条 [VerificationAttempt] 记录真实请求的成功与失败，含 HTTP 状态、错误码、域名拒绝原因。
 * 等级严格分栏：REAL_SEARCH / REAL_URL_RESOLVE / REAL_AUDIO_RESPONSE / REAL_PLAYBACK(NOT_RUN)。
 * 一次成功不等于长期稳定；HTTP 206 不冒充真实播放。
 */
object MusicFreeG3Verification {

    const val VERIFIED_AT = "2026-11-20"
    const val CANDIDATE_ID = "mf-audiomack"
    const val SCRIPT_SHA256 = "1b1f49fc1e0aa5933c3b45ce999d975e4f7fc5f69df2a487dd87830555cf01d5"

    enum class Stage { REAL_SEARCH, REAL_URL_RESOLVE, AUDIO_RESPONSE, REAL_PLAYBACK }
    enum class Outcome { PASSED, FAILED, NOT_RUN }

    data class VerificationAttempt(
        val stage: Stage,
        val endpointHost: String,
        val keyword: String? = null,
        val httpStatus: Int? = null,
        val outcome: Outcome,
        val errorCode: String? = null,
        val detail: String,
    )

    /** 真实搜索：中文/英文/歌手+歌名/特殊字符/不存在/空关键词，全部真实 HTTP 请求。 */
    val searchAttempts = listOf(
        VerificationAttempt(Stage.REAL_SEARCH, "api.audiomack.com", "晴天", 200, Outcome.PASSED,
            detail = "results=5；首条 id=10921920 / 晴天 / 周杰伦 / 叶惠美"),
        VerificationAttempt(Stage.REAL_SEARCH, "api.audiomack.com", "周杰伦", 200, Outcome.PASSED,
            detail = "results=5；首条 珊瑚海 / 周杰伦"),
        VerificationAttempt(Stage.REAL_SEARCH, "api.audiomack.com", "weeknd", 200, Outcome.PASSED,
            detail = "英文关键词 results=5"),
        VerificationAttempt(Stage.REAL_SEARCH, "api.audiomack.com", "周杰伦 晴天", 200, Outcome.PASSED,
            detail = "歌手+歌名 results=5"),
        VerificationAttempt(Stage.REAL_SEARCH, "api.audiomack.com", "nonexistent_xyz_9981", 200, Outcome.PASSED,
            detail = "不存在的关键词 results=0；空结果符合预期，非失败"),
        VerificationAttempt(Stage.REAL_SEARCH, "api.audiomack.com", "<>?%", 200, Outcome.PASSED,
            detail = "特殊字符 results=0；未报错"),
        VerificationAttempt(Stage.REAL_SEARCH, "api.audiomack.com", "a".repeat(20), 200, Outcome.PASSED,
            detail = "长关键词 results=2"),
        VerificationAttempt(Stage.REAL_SEARCH, "api.audiomack.com", "", 200, Outcome.PASSED,
            detail = "空关键词 results=0；符合预期"),
    )

    /** URL 解析 + 身份匹配。 */
    val resolveAttempts = listOf(
        VerificationAttempt(Stage.REAL_URL_RESOLVE, "api.audiomack.com", "晴天", 200, Outcome.PASSED,
            detail = "music/play/10921920 → {signedUrl,playUuid}；signedUrl=https；身份匹配 MATCH（搜索首条 id=10921920=解析 id）"),
        VerificationAttempt(Stage.REAL_URL_RESOLVE, "api.audiomack.com", "__INVALID_ID__", null, Outcome.FAILED,
            errorCode = "NOT_PROBED",
            detail = "本轮未对无效 id 发请求；不伪造失败数据（失败样本待补，样本量不足如实标注）"),
    )

    /**
     * 音频响应 + 关键域名差异。解析出的 signedUrl 域名是 music.audiomack.com，
     * 与候选授权域名 api.audiomack.com 不同——播放 URL 不在候选授权白名单内。
     */
    val audioAttempts = listOf(
        VerificationAttempt(Stage.AUDIO_RESPONSE, "music.audiomack.com", "晴天", 206, Outcome.PASSED,
            detail = "Range bytes=0-0 → 206 audio/mp4 / Content-Range bytes 0-0/4364494；仅读 1 字节响应头，未存音频"),
        VerificationAttempt(Stage.AUDIO_RESPONSE, "api.audiomack.com", "晴天", null, Outcome.FAILED,
            errorCode = "DOMAIN_NOT_AUTHORIZED",
            detail = "解析域名 music.audiomack.com ≠ 候选授权 api.audiomack.com；" +
                "候选级网络策略会拒绝该播放 URL——不放宽白名单，播放链路仍不可用"),
    )

    /** 真实播放：需用户本人明确操作 + 有权内容，本阶段一律 NOT_RUN。 */
    val playbackAttempts = listOf(
        VerificationAttempt(Stage.REAL_PLAYBACK, "music.audiomack.com", "晴天", null, Outcome.NOT_RUN,
            errorCode = "REQUIRES_USER_ACTION",
            detail = "HTTP 206 + audio/mp4 不等于真实播放；播放验证需用户在有权内容上明确操作，本阶段未执行"),
    )

    val all: List<VerificationAttempt> = searchAttempts + resolveAttempts + audioAttempts + playbackAttempts

    /** 分栏统计（模拟与真实分开；一次成功不算长期稳定）。 */
    data class Summary(
        val realSearch: String,
        val realUrlResolve: String,
        val audioResponse: String,
        val realPlayback: String,
        val sampleNote: String,
    )

    fun summary(record: MusicFreePluginRecord): Summary {
        val searchPass = searchAttempts.count { it.outcome == Outcome.PASSED }
        val resolvePass = resolveAttempts.count { it.outcome == Outcome.PASSED }
        val audioPass = audioAttempts.count { it.outcome == Outcome.PASSED && it.httpStatus == 206 }
        return Summary(
            realSearch = "REAL_SEARCH_PASSED（n=$searchPass，真实 OAuth 搜索；脚本声明 search 且实测可用）",
            realUrlResolve = "REAL_URL_RESOLVE_PASSED（n=$resolvePass，身份匹配 MATCH）",
            audioResponse = "REAL_AUDIO_RESPONSE_PASSED（n=$audioPass，206 audio/mp4，仅响应头）",
            realPlayback = "NOT_RUN（需用户明确操作；HTTP 206 不冒充播放）",
            sampleNote = "样本量小（单日单次），不宣称长期稳定；" +
                "解析域名 music.audiomack.com 不在授权白名单，候选级策略会拒绝播放 URL；" +
                "脚本 SHA=$SCRIPT_SHA256 版本=${record.version} 变更需重新验证",
        )
    }

    /** 是否达到调度门槛（≥2 合规候选真实搜索通过）。当前只有 1 个，门禁不放行。 */
    fun schedulingGateOpen(passingRealSearchCandidates: Int): Boolean = passingRealSearchCandidates >= 2
}
