package com.feeluown.modeb

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

interface ModeBStorage {
    suspend fun readText(key: String): String?
    suspend fun writeText(key: String, content: String)
}

@Serializable
data class SourceHealthEntry(
    val failures: Int = 0,
    val cooldownUntilMillis: Long = 0L,
    val lastError: String? = null,
    val lastUpdatedMillis: Long = 0L,
)

@Serializable
data class HealthStateSnapshot(
    val entries: Map<String, SourceHealthEntry> = emptyMap(),
)

enum class GatePermission {
    CLOSED,
    HALF_OPEN,
    OPEN,
}

/**
 * 懒检查 + 失败驱动持久化熔断器：
 * - 协程 Mutex 保护并发安全
 * - 冷却到期仅放行 1 个 HALF_OPEN 探针，防并发雪崩
 * - 每次状态变更持久化到 ModeBStorage，重启后不撞死源
 */
class CircuitBreaker(
    private val storage: ModeBStorage,
    private val storageKey: String = "modeb_health_state.json",
    private var failureThreshold: Int = 3,
    private var cooldownMillis: Long = 600_000L,
    private val nowMillis: () -> Long,
) {
    private val mutex = Mutex()
    private val halfOpenInflight = mutableSetOf<String>()
    private var loaded = false
    private val entries = mutableMapOf<String, SourceHealthEntry>()

    private suspend fun ensureLoaded() {
        if (loaded) return
        val raw = storage.readText(storageKey)
        if (!raw.isNullOrBlank()) {
            runCatching {
                ModeBJson.decodeFromString(HealthStateSnapshot.serializer(), raw)
            }.onSuccess { snap ->
                entries.clear()
                entries.putAll(snap.entries)
            }
        }
        loaded = true
    }

    private suspend fun persist() {
        val raw = ModeBJson.encodeToString(HealthStateSnapshot.serializer(), HealthStateSnapshot(entries.toMap()))
        storage.writeText(storageKey, raw)
    }

    suspend fun updatePolicy(threshold: Int, cooldownSeconds: Long) {
        mutex.withLock {
            failureThreshold = threshold
            cooldownMillis = cooldownSeconds * 1000L
        }
    }

    suspend fun acquirePermission(sourceId: String): GatePermission = mutex.withLock {
        ensureLoaded()
        val entry = entries[sourceId] ?: return@withLock GatePermission.CLOSED
        if (entry.cooldownUntilMillis <= 0L) return@withLock GatePermission.CLOSED
        val now = nowMillis()
        if (now < entry.cooldownUntilMillis) return@withLock GatePermission.OPEN
        if (!halfOpenInflight.add(sourceId)) return@withLock GatePermission.OPEN
        GatePermission.HALF_OPEN
    }

    suspend fun recordSuccess(sourceId: String) {
        mutex.withLock {
            ensureLoaded()
            halfOpenInflight.remove(sourceId)
            if (entries.containsKey(sourceId)) {
                entries[sourceId] = SourceHealthEntry(
                    failures = 0,
                    cooldownUntilMillis = 0L,
                    lastError = null,
                    lastUpdatedMillis = nowMillis(),
                )
                persist()
            }
        }
    }

    /** 返回 true 表示本次失败刚好触发进入 OPEN 冷却态 */
    suspend fun recordFailure(sourceId: String, reason: String): Boolean {
        val now = nowMillis()
        return mutex.withLock {
            ensureLoaded()
            val wasHalfOpen = halfOpenInflight.remove(sourceId)
            val prev = entries[sourceId] ?: SourceHealthEntry()
            val newFailures = prev.failures + 1
            val shouldOpen = wasHalfOpen || newFailures >= failureThreshold
            val cooldownUntil = if (shouldOpen) now + cooldownMillis else 0L
            val trippedNow = shouldOpen && (prev.cooldownUntilMillis <= 0L || wasHalfOpen)

            entries[sourceId] = SourceHealthEntry(
                failures = newFailures,
                cooldownUntilMillis = cooldownUntil,
                lastError = reason,
                lastUpdatedMillis = now,
            )
            persist()
            trippedNow
        }
    }

    suspend fun resetAll() {
        mutex.withLock {
            halfOpenInflight.clear()
            entries.clear()
            loaded = true
            persist()
        }
    }

    suspend fun snapshot(): Map<String, SourceHealthEntry> = mutex.withLock {
        ensureLoaded()
        entries.toMap()
    }
}
