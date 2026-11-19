package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadQualitySelectionTest {
    @Test
    fun optionsAreOrderedFromHighestToLowest() {
        val options = defaultDownloadQualityOptions()

        assertEquals(
            listOf(
                AudioQualityPolicy.Highest.policy,
                AudioQualityPolicy.High.policy,
                AudioQualityPolicy.Standard.policy,
                AudioQualityPolicy.Low.policy,
                null,
            ),
            options.map { it.policy },
        )
        assertEquals("无损 / 最高可用", options.first().label)
        assertNull(options.last().policy)
    }

    @Test
    fun sizeEstimateIsDerivedFromDurationAndBitrate() {
        assertEquals("约 4.9 MB", estimateDownloadSizeMb(210_000, 192))
        assertEquals("约 3.3 MB", estimateDownloadSizeMb(210_000, 128))
        assertEquals("约 0.9 MB", estimateDownloadSizeMb(60_000, 128))
    }

    @Test
    fun unboundedAndUnknownDurationsHaveNoEstimate() {
        assertEquals(null, estimateDownloadSizeMb(210_000, null))
        assertEquals(null, estimateDownloadSizeMb(null, 320))
        assertEquals(null, estimateDownloadSizeMb(0, 320))
    }

    @Test
    fun availableQualitiesFilterThePicker() = runTest {
        val owner = RecordingOwner()
        val resolver = RecordingResolver().apply { available = listOf("hq<>", "lq<>") }
        val port = DefaultDownloadQualitySelectionPort(owner, resolver, scope = this)

        port.requestSelection(track())
        advanceUntilIdle()

        val selection = assertNotNull(port.pendingSelection.value)
        assertEquals(listOf("hq<>", "lq<>", null), selection.options.map { it.policy })
        assertTrue(owner.downloads.isEmpty())
    }

    @Test
    fun unknownProviderKeepsTheGenericTiers() = runTest {
        val owner = RecordingOwner()
        val resolver = RecordingResolver().apply { available = null }
        val port = DefaultDownloadQualitySelectionPort(owner, resolver, scope = this)

        port.requestSelection(track())
        advanceUntilIdle()

        val selection = assertNotNull(port.pendingSelection.value)
        assertEquals(defaultDownloadQualityOptions(), selection.options)
        assertNull(selection.notice)
    }

    @Test
    fun providerWithoutAnyPlayableTierExplainsTheEmptyPicker() = runTest {
        val owner = RecordingOwner()
        val resolver = RecordingResolver().apply { available = emptyList() }
        val port = DefaultDownloadQualitySelectionPort(owner, resolver, scope = this)

        port.requestSelection(track())
        advanceUntilIdle()

        val selection = assertNotNull(port.pendingSelection.value)
        assertTrue(selection.options.isEmpty())
        assertNotNull(selection.notice)
        assertTrue(owner.downloads.isEmpty())
    }

    @Test
    fun restrictedAccountHidesTiersAndSaysWhy() = runTest {
        val owner = RecordingOwner()
        val resolver = RecordingResolver().apply { available = listOf(AudioQualityPolicy.Low.policy) }
        val port = DefaultDownloadQualitySelectionPort(owner, resolver, scope = this)

        port.requestSelection(track())
        advanceUntilIdle()

        val selection = assertNotNull(port.pendingSelection.value)
        assertEquals(listOf(AudioQualityPolicy.Low.policy, null), selection.options.map { it.policy })
        assertNotNull(selection.notice)
    }

    @Test
    fun aFailedProbeStillOpensThePicker() = runTest {
        val owner = RecordingOwner()
        val resolver = RecordingResolver().apply { probeFails = true }
        val port = DefaultDownloadQualitySelectionPort(owner, resolver, scope = this)

        port.requestSelection(track())
        advanceUntilIdle()

        val selection = assertNotNull(port.pendingSelection.value)
        assertEquals(defaultDownloadQualityOptions(), selection.options)
    }

    @Test
    fun requestSelectionOpensThePicker() {
        val owner = RecordingOwner()
        val port = DefaultDownloadQualitySelectionPort(owner, RecordingResolver())

        port.requestSelection(track())

        val selection = assertNotNull(port.pendingSelection.value)
        assertEquals("track-1", selection.track.id)
        assertEquals(defaultDownloadQualityOptions(), selection.options)
        assertTrue(owner.downloads.isEmpty())
    }

    @Test
    fun downloadBestStartsImmediatelyAtHighestPolicyWithoutPicker() {
        // 一键下载：不弹窗（pendingSelection 始终 null），直接以 Highest 档入队，
        // Highest 在 Provider 侧会自动从无损回退到可用最高质量。
        val owner = RecordingOwner()
        val port = DefaultDownloadQualitySelectionPort(owner, RecordingResolver())

        port.downloadBest(track())

        assertEquals(1, owner.downloads.size)
        assertEquals("track-1", owner.downloads[0].first)
        assertEquals(AudioQualityPolicy.Highest.policy, owner.downloads[0].second)
        assertNull(port.pendingSelection.value, "一键下载不应弹出音质选择窗")
    }

    @Test
    fun downloadBestClearsAnOpenPicker() {
        // 用户先打开了"更多选项"，随后点普通下载：应关掉选择窗并直接入队。
        val owner = RecordingOwner()
        val resolver = RecordingResolver().apply { available = listOf("hq<>") }
        val port = DefaultDownloadQualitySelectionPort(owner, resolver)
        port.requestSelection(track())

        port.downloadBest(track())

        assertNull(port.pendingSelection.value, "一键下载应清掉已打开的选择窗")
        assertEquals(1, owner.downloads.size)
        assertEquals(AudioQualityPolicy.Highest.policy, owner.downloads[0].second)
    }

    @Test
    fun confirmSelectionDownloadsWithTheChosenPolicy() {
        val owner = RecordingOwner()
        val port = DefaultDownloadQualitySelectionPort(owner, RecordingResolver())

        port.requestSelection(track())
        port.confirmSelection(AudioQualityPolicy.Highest.policy)

        assertEquals(1, owner.downloads.size)
        assertEquals("track-1", owner.downloads[0].first)
        assertEquals(">>>", owner.downloads[0].second)
        assertNull(port.pendingSelection.value)
    }

    @Test
    fun confirmSelectionWithNullPolicyFollowsTheAppSetting() {
        val owner = RecordingOwner()
        val port = DefaultDownloadQualitySelectionPort(owner, RecordingResolver())

        port.requestSelection(track())
        port.confirmSelection(null)

        assertEquals(1, owner.downloads.size)
        assertEquals("track-1", owner.downloads[0].first)
        assertNull(owner.downloads[0].second)
    }

    @Test
    fun dismissSelectionCancelsTheDownload() {
        val owner = RecordingOwner()
        val port = DefaultDownloadQualitySelectionPort(owner, RecordingResolver())

        port.requestSelection(track())
        port.dismissSelection()

        assertNull(port.pendingSelection.value)
        assertTrue(owner.downloads.isEmpty())
    }

    @Test
    fun withoutResolverTheDownloadStartsImmediately() {
        val owner = RecordingOwner()
        val port = DefaultDownloadQualitySelectionPort(owner, resolver = null)

        port.requestSelection(track())

        assertNull(port.pendingSelection.value)
        assertEquals(1, owner.downloads.size)
        assertEquals("track-1", owner.downloads[0].first)
        assertNull(owner.downloads[0].second)
    }

    private fun track(): MusicTrack = MusicTrack(
        id = "track-1",
        title = "测试歌曲",
        artists = "歌手",
        album = "专辑",
        source = "netease",
        sourceType = TrackSourceType.Provider,
    )

    private class RecordingResolver : DownloadQualityResolver {
        var available: List<String>? = null
        var probeFails: Boolean = false

        override suspend fun resolveWithQualityPolicy(track: MusicTrack, qualityPolicy: String): PlaybackPayload? =
            PlaybackPayload(
                url = "http://example.com/audio",
                title = track.title,
                artists = track.artists,
                album = track.album,
                source = track.source,
                audioQuality = qualityPolicy,
            )

        override suspend fun availableQualities(track: MusicTrack): List<String>? {
            if (probeFails) error("probe failed")
            return available
        }
    }

    private class RecordingOwner :
        DownloadFeatureOwner<MusicTrack, DownloadTask, DownloadState> {
        val downloads = mutableListOf<Pair<String, String?>>()

        private val mutableState = MutableStateFlow(DownloadFeatureState<DownloadTask, DownloadState>())
        override val state: StateFlow<DownloadFeatureState<DownloadTask, DownloadState>> = mutableState.asStateFlow()

        override fun start() = Unit
        override fun restoreParallelism(value: Int) = Unit
        override fun onParallelismChange(value: Int) = Unit

        override fun download(track: MusicTrack) = download(track, null)

        override fun download(track: MusicTrack, qualityPolicy: String?) {
            downloads += track.id to qualityPolicy
        }

        override fun deleteDownload(track: MusicTrack) = Unit
        override fun pause(taskId: String) = Unit
        override fun resume(taskId: String) = Unit
        override fun retry(taskId: String) = Unit
        override fun deleteTask(taskId: String, deleteFile: Boolean) = Unit
        override fun dismissQueueFeedback(feedback: String) = Unit
    }
}
