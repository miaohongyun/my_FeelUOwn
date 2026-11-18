package org.feeluown.mobile

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadFeatureQualityPolicyTest {
    @Test
    fun downloadForwardsSelectedQualityPolicyToResolver() = runTest {
        val resolver = RecordingResolver()
        val repository = RecordingRepository()
        val owner = createDownloadFeatureOwner(
            repository = repository,
            resolver = resolver,
            localLibrary = NoOpLocalLibrary(),
            operations = ProviderTrackOperations(),
            scope = this,
            shouldShowLocalMusicLoading = { false },
            persistParallelism = {},
        )

        owner.download("track-1", ">>>")
        testScheduler.advanceUntilIdle()

        assertEquals(1, resolver.calls.size)
        assertEquals("track-1", resolver.calls[0].first)
        assertEquals(">>>", resolver.calls[0].second)
        assertEquals(listOf("track-1"), repository.downloaded)
    }

    @Test
    fun downloadWithoutPolicyKeepsDefaultPolicy() = runTest {
        val resolver = RecordingResolver()
        val repository = RecordingRepository()
        val owner = createDownloadFeatureOwner(
            repository = repository,
            resolver = resolver,
            localLibrary = NoOpLocalLibrary(),
            operations = ProviderTrackOperations(),
            scope = this,
            shouldShowLocalMusicLoading = { false },
            persistParallelism = {},
        )

        owner.download("track-1")
        testScheduler.advanceUntilIdle()

        assertEquals(1, resolver.calls.size)
        assertEquals("track-1", resolver.calls[0].first)
        assertEquals(null, resolver.calls[0].second)
    }

    @Test
    fun nonProviderTrackIsIgnored() = runTest {
        val resolver = RecordingResolver()
        val repository = RecordingRepository()
        val owner = createDownloadFeatureOwner(
            repository = repository,
            resolver = resolver,
            localLibrary = NoOpLocalLibrary(),
            operations = ProviderTrackOperations(providerTrack = false),
            scope = this,
            shouldShowLocalMusicLoading = { false },
            persistParallelism = {},
        )

        owner.download("track-1", "hq<>")
        testScheduler.advanceUntilIdle()

        assertTrue(resolver.calls.isEmpty())
        assertTrue(repository.downloaded.isEmpty())
    }

    private class RecordingResolver : DownloadMediaResolver<String, String> {
        val calls = mutableListOf<Pair<String, String?>>()

        override suspend fun resolve(track: String): String = resolve(track, null)

        override suspend fun resolve(track: String, qualityPolicy: String?): String {
            calls += track to qualityPolicy
            return "payload:$track"
        }
    }

    private class RecordingRepository : DownloadFeatureRepository<String, String, String, String> {
        val downloaded = mutableListOf<String>()

        override val states = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
        override val tasks = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
        override suspend fun load() = Unit
        override suspend fun download(track: String, payload: String) {
            downloaded += track
        }

        override suspend fun updateParallelism(value: Int) = Unit
        override suspend fun pause(taskId: String) = Unit
        override suspend fun resume(taskId: String) = Unit
        override suspend fun retry(taskId: String) = Unit
        override suspend fun deleteTask(taskId: String, deleteFile: Boolean) = Unit
        override suspend fun deleteDownloaded(track: String) = Unit
    }

    private class NoOpLocalLibrary : DownloadLocalLibraryPort {
        override val mediaChangeEvents = kotlinx.coroutines.flow.flowOf(Unit)
        override val hasPermission = false
        override suspend fun isDatabaseReady() = false
        override fun refresh(forceRefresh: Boolean, showLoading: Boolean) = Unit
    }

    private class ProviderTrackOperations(
        private val providerTrack: Boolean = true,
    ) : DownloadFeatureOperations<String, String> {
        override fun isProviderTrack(track: String): Boolean = providerTrack
        override fun trackTitle(track: String): String = track
        override fun taskId(task: String): String = task
        override fun isCompleted(task: String): Boolean = false
    }
}
