package org.feeluown.mobile.provider.musicfree

/**
 * 阶段 H4：真实播放闭环验证台账（状态机，非播放器）。
 *
 * 严格区分五级：REAL_SEARCH / REAL_TRACK_IDENTITY_VERIFIED / REAL_URL_RESOLVE /
 * REAL_AUDIO_RESPONSE / REAL_PLAYBACK。HTTP 200/206、成功解析 URL、Mock 解码都不冒充真实播放。
 *
 * 闭环 8 步：搜索→身份匹配→URL解析→网络权限→音频响应→播放器读取→解码输出→暂停/继续/Seek/切歌/取消。
 * 任一步未通过即停在该步，后续标 NOT_RUN 并记录原因。
 *
 * 本阶段事实（G3 真实网络 + H3 域名审查）：
 * - 真实搜索/身份匹配/URL解析有 G3 实测数据；
 * - **网络权限步被 H3 拒绝**（music.audiomack.com 未授权，证据不足）；
 * - 因此音频响应之后的全部步骤在本闭环中 NOT_RUN（G3 曾读 1 字节响应头，但那是独立只读探测，
 *   不构成"经授权闭环的音频响应/播放"），真实播放 NOT_RUN。
 */
object MusicFreePlaybackVerification {

    enum class Level {
        REAL_SEARCH, REAL_TRACK_IDENTITY_VERIFIED, REAL_URL_RESOLVE, REAL_AUDIO_RESPONSE, REAL_PLAYBACK,
    }

    enum class StepStatus { PASSED, FAILED, NOT_RUN }

    data class StepResult(
        val index: Int,
        val name: String,
        val level: Level?,
        val status: StepStatus,
        val reason: String,
    )

    data class PlaybackVerificationReport(
        val candidateId: String,
        val steps: List<StepResult>,
        val achievedLevels: Set<Level>,
    ) {
        val realPlaybackAchieved: Boolean get() = Level.REAL_PLAYBACK in achievedLevels
        val haltedAtIndex: Int? get() = steps.firstOrNull { it.status != StepStatus.PASSED }?.index
    }

    /**
     * 构造闭环验证报告。
     *
     * @param searchOk 真实搜索是否有实测成功样本（G3: true）
     * @param identityMatch 搜索首条与解析 id 是否匹配（G3: true）
     * @param urlResolved URL 是否真实解析（G3: true）
     * @param networkAllowed 网络权限/域名是否获授权（H3: false）
     * @param audioResponseOk 是否经授权闭环读到音频响应（本闭环: false，因第4步已停）
     * @param realPlaybackDone 是否真实解码输出（本闭环: false）
     */
    fun buildReport(
        candidateId: String = "mf-audiomack",
        searchOk: Boolean,
        identityMatch: Boolean,
        urlResolved: Boolean,
        networkAllowed: Boolean,
        audioResponseOk: Boolean = false,
        realPlaybackDone: Boolean = false,
        controlStepsDone: Boolean = false,
    ): PlaybackVerificationReport {
        val steps = mutableListOf<StepResult>()
        var halt = false
        fun step(index: Int, name: String, level: Level?, ok: Boolean, passReason: String, failReason: String) {
            if (halt) {
                steps += StepResult(index, name, level, StepStatus.NOT_RUN, "前置步骤未通过，本步 NOT_RUN")
                return
            }
            if (ok) {
                steps += StepResult(index, name, level, StepStatus.PASSED, passReason)
            } else {
                halt = true
                steps += StepResult(index, name, level, StepStatus.FAILED, failReason)
            }
        }
        step(1, "真实搜索", Level.REAL_SEARCH, searchOk, "G3 真实 OAuth 搜索 n=8 成功", "真实搜索无成功样本")
        step(2, "歌曲身份匹配", Level.REAL_TRACK_IDENTITY_VERIFIED, identityMatch,
            "G3 搜索首条 id=10921920 == 解析 id，MATCH", "身份不匹配，禁止继续")
        step(3, "URL 解析", Level.REAL_URL_RESOLVE, urlResolved,
            "G3 music/play/10921920 → signedUrl(https)", "URL 解析失败")
        step(4, "网络权限/域名授权", null, networkAllowed,
            "播放域名已获精确授权",
            "H3 拒绝：music.audiomack.com 未获授权（证据不足/条款未明确），不放宽白名单")
        step(5, "音频响应（经授权闭环）", Level.REAL_AUDIO_RESPONSE, audioResponseOk,
            "经授权读取音频响应", "第4步未通过，音频响应在闭环内 NOT_RUN")
        step(6, "播放器读取音频数据", null, realPlaybackDone,
            "播放器已读取", "未到播放阶段（NOT_RUN）")
        step(7, "实际解码与输出声音", Level.REAL_PLAYBACK, realPlaybackDone,
            "真实解码输出", "真实播放 NOT_RUN（需用户明确操作 + 有权内容 + 域名授权）")
        step(8, "暂停/继续/Seek/切歌/取消", null, controlStepsDone,
            "播放控制已验证", "播放未启动，控制步骤 NOT_RUN")

        val achieved = steps.filter { it.status == StepStatus.PASSED && it.level != null }
            .mapNotNull { it.level }.toSet()
        return PlaybackVerificationReport(candidateId, steps, achieved)
    }

    /** 当前 Audiomack 闭环报告：第4步即停，真实播放 NOT_RUN。 */
    fun currentAudiomackReport(): PlaybackVerificationReport = buildReport(
        searchOk = true,
        identityMatch = true,
        urlResolved = true,
        networkAllowed = MusicFreePlaybackDomainAudit.mayAllowPlaybackHost(
            MusicFreePlaybackDomainAudit.resolvedPlaybackHost
        ), // H3: false
    )
}
