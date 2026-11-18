package org.feeluown.mobile.provider.modeb

import com.feeluown.modeb.ModeBStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-only fallback storage. Used by tests and as a safe default when a platform
 * cannot provide durable file I/O. Production Android/iOS/Desktop inject a durable
 * [ModeBStorage] implementation created by [createPlatformModeBStorage].
 */
internal class InMemoryModeBStorage : ModeBStorage {
    private val mutex = Mutex()
    private val values = linkedMapOf<String, String>()

    override suspend fun readText(key: String): String? = mutex.withLock { values[key] }

    override suspend fun writeText(key: String, content: String) {
        mutex.withLock { values[key] = content }
    }
}

/**
 * Creates the durable, platform-specific Mode B storage.
 *
 * - Android: `context.filesDir/mode_b`, write-then-atomic-rename.
 * - iOS: `NSApplicationSupportDirectory/FuoEvolve/mode_b`, atomic write.
 * - Desktop: `~/.FeelUOwn/mode_b` (or the OS config dir), atomic write.
 *
 * All file I/O is performed on `Dispatchers.IO` inside each platform implementation.
 */
expect fun createPlatformModeBStorage(): ModeBStorage
