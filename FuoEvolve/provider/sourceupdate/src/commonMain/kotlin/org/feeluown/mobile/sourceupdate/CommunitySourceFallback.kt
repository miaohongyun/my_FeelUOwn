package org.feeluown.mobile.sourceupdate

/**
 * 阶段 F3：主备音源调度器（纯逻辑，commonMain，可离线测试）。
 *
 * 启用门槛：至少两个合规候选通过 REAL_SEARCH（[CommunityQualityScorer.admissions] 的 gate），
 * 否则调度器保持关闭，绝不自动启用全部候选。
 *
 * 安全边界：
 * - 用户显式指定主/备顺序，最多尝试 [maxAttempts] 个后端，不无限递归、不后台全量请求；
 * - 失败进入冷却（cooldown），冷却内的音源跳过，避免同一后端反复被打；
 * - 记录每次失败原因；不同平台（kw/kg/tx/wy/mg）结果严格隔离，不跨平台错误匹配；
 * - 每个后端的解析结果只归属它自己，绝不冒充成另一个音源的结果。
 */

/** 一次调度中单个后端的执行结果，携带来源身份，禁止冒充。 */
data class SourceAttemptResult(
    val candidateId: String,
    val platform: String,
    val ok: Boolean,
    val value: String? = null,
    val failureReason: String? = null,
)

/** 调度最终结果。 */
data class FallbackOutcome(
    val enabled: Boolean,
    val winner: SourceAttemptResult? = null,
    val attempts: List<SourceAttemptResult> = emptyList(),
    val skippedCooling: List<String> = emptyList(),
    val reason: String? = null,
)

/** 单个后端的冷却状态。 */
data class SourceCooldown(
    val failedAtMs: Long,
    val failureReason: String,
)

/**
 * [nowMs] 由调用方注入（毫秒 epoch），便于离线测试冷却窗口，不依赖 JVM-only 时间 API。
 */
class SourceFallbackScheduler(
    private val nowMs: () -> Long,
    val maxAttempts: Int = 3,
    val cooldownMs: Long = 60_000L,
) {
    private val cooldowns = mutableMapOf<String, SourceCooldown>()

    /** 真实搜索通过的候选集合（启用门槛）。 */
    fun realSearchEligible(gateOpen: Boolean, eligibleIds: Set<String>): Boolean =
        gateOpen && eligibleIds.size >= 2

    fun cooldownState(candidateId: String): SourceCooldown? = cooldowns[candidateId]

    fun markFailure(candidateId: String, reason: String) {
        cooldowns[candidateId] = SourceCooldown(nowMs(), reason)
    }

    fun clearCooldown(candidateId: String) {
        cooldowns.remove(candidateId)
    }

    private fun isCooling(candidateId: String): Boolean {
        val state = cooldowns[candidateId] ?: return false
        return nowMs() - state.failedAtMs < cooldownMs
    }

    /**
     * 按用户主备顺序尝试。execute 由调用方注入（真正的网络/解析），本类只管编排。
     * [platform] 固定，不会在一次调度中跨平台；execute 返回的 candidateId 必须与请求一致，
     * 否则视为冒充并拒绝（fail closed）。
     */
    suspend fun run(
        order: List<String>,
        platform: String,
        gateOpen: Boolean,
        eligibleIds: Set<String>,
        execute: suspend (candidateId: String, platform: String) -> SourceAttemptResult,
    ): FallbackOutcome {
        if (!realSearchEligible(gateOpen, eligibleIds)) {
            return FallbackOutcome(
                enabled = false,
                reason = "真实搜索通过候选不足 2 个，主备调度器未启用（当前 ${eligibleIds.size}）",
            )
        }
        val attempts = mutableListOf<SourceAttemptResult>()
        val skipped = mutableListOf<String>()
        // 去重：同一后端不重复请求；最多 maxAttempts 次。
        val seen = linkedSetOf<String>()
        for (candidateId in order) {
            if (attempts.size >= maxAttempts) break
            if (!seen.add(candidateId)) continue
            if (candidateId !in eligibleIds) continue
            if (isCooling(candidateId)) {
                skipped += candidateId
                continue
            }
            val result = execute(candidateId, platform)
            // 身份校验：禁止后端冒充另一个音源，也禁止跨平台结果混入。
            if (!result.candidateId.equals(candidateId, ignoreCase = true) ||
                !result.platform.equals(platform, ignoreCase = true)
            ) {
                val rejected = SourceAttemptResult(
                    candidateId = candidateId, platform = platform, ok = false,
                    failureReason = "结果身份不匹配（来源=${result.candidateId}, 平台=${result.platform}），拒绝冒充",
                )
                attempts += rejected
                markFailure(candidateId, rejected.failureReason!!)
                continue
            }
            attempts += result
            if (result.ok) {
                return FallbackOutcome(enabled = true, winner = result, attempts = attempts, skippedCooling = skipped)
            }
            markFailure(candidateId, result.failureReason ?: "未知失败")
        }
        return FallbackOutcome(
            enabled = true, winner = null, attempts = attempts, skippedCooling = skipped,
            reason = if (attempts.isEmpty()) "无可用候选（全部冷却或未通过真实搜索）" else "全部后端失败",
        )
    }
}
