package org.feeluown.mobile.sourceupdate

import kotlin.random.Random

/**
 * 自动更新节奏（设计文档 4.2 / 4.3）。
 *
 * - 默认每 24 小时检查一次，启动时补查；
 * - 失败指数退避 1h → 4h → 12h → 24h，退避期间不再重试，避免弱网下反复撞墙；
 * - 每日任务加随机抖动，避免所有设备在同一秒请求同一个镜像。
 */
class SourceUpdatePolicy(
    val intervalHours: Int = 24,
    private val backoffHours: List<Int> = listOf(1, 4, 12, 24),
) {
    init {
        require(intervalHours in 1..168) { "intervalHours 必须在 1..168 之间" }
    }

    val intervalMillis: Long get() = intervalHours * 60L * 60L * 1000L

    fun shouldCheck(index: SourceIndex, nowMillis: Long, force: Boolean = false): Boolean {
        if (force) return true
        if (index.consecutiveFailures > 0) {
            return nowMillis - index.lastCheckMillis >= backoffMillis(index.consecutiveFailures)
        }
        if (index.lastSuccessMillis <= 0L) return true
        return nowMillis - index.lastSuccessMillis >= intervalMillis
    }

    fun backoffMillis(failures: Int): Long {
        val hours = backoffHours[(failures - 1).coerceIn(0, backoffHours.lastIndex)]
        return hours * 60L * 60L * 1000L
    }

    /**
     * 当日计划触发的抖动窗口：最多把检查推后 interval 的 1/12（约 2 小时）。
     * 用固定种子保证同一设备同一天的窗口稳定，便于日志排查。
     */
    fun jitterMillis(seed: Long): Long {
        val window = intervalMillis / 12
        if (window <= 1L) return 0L
        return Random(seed).nextLong(0L, window)
    }
}

data class MirrorHealth(
    val failures: Int = 0,
    val circuitOpenUntilMillis: Long = 0L,
    val lastError: String? = null,
    val lastSuccessMillis: Long = 0L,
)

/**
 * 下载通道选择（hybrid）。
 *
 * 信任边界：镜像只影响"能不能下载到"，不影响"下载到的东西是否可信"——脚本仍要过
 * SHA-256 与签名。因此这里允许公共代理作为通道，但要求：
 * 1. 只走 https；
 * 2. 域名必须在内置 allowlist 里（不接受目录远程下发新代理）；
 * 3. 单镜像连续失败后熔断 30 分钟。
 */
class SourceMirrorPolicy(
    private val mirrors: List<String>,
    private val allowlist: Set<String> = emptySet(),
    private val circuitMillis: Long = 30L * 60L * 1000L,
    private val failureThreshold: Int = 2,
) {
    private val health = mutableMapOf<String, MirrorHealth>()

    fun orderedMirrors(nowMillis: Long): List<String> =
        mirrors.filter { isAllowed(it) && isAvailable(it, nowMillis) }

    fun isAllowed(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        if (allowlist.isEmpty()) return true
        val host = hostOf(url) ?: return false
        return allowlist.any { it.equals(host, ignoreCase = true) }
    }

    fun isAvailable(url: String, nowMillis: Long): Boolean {
        val entry = health[url] ?: return true
        return nowMillis >= entry.circuitOpenUntilMillis
    }

    fun recordFailure(url: String, nowMillis: Long, reason: String? = null) {
        val entry = health[url] ?: MirrorHealth()
        val failures = entry.failures + 1
        health[url] = entry.copy(
            failures = failures,
            lastError = reason,
            circuitOpenUntilMillis = if (failures >= failureThreshold) nowMillis + circuitMillis else entry.circuitOpenUntilMillis,
        )
    }

    fun recordSuccess(url: String, nowMillis: Long) {
        health[url] = MirrorHealth(lastSuccessMillis = nowMillis)
    }

    fun snapshot(): Map<String, MirrorHealth> = health.toMap()

    private fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        return afterScheme.substringBefore('/').substringBefore('?').substringAfterLast('@').substringBefore(':')
            .lowercase()
            .takeIf { it.isNotEmpty() }
    }
}
