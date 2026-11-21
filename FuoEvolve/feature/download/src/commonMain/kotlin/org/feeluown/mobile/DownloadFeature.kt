package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DownloadFeatureState<Task, DownloadState>(
    val states: Map<String, DownloadState> = emptyMap(),
    val tasks: List<Task> = emptyList(),
    val queueFeedback: String? = null,
    val parallelism: Int = 2,
)

interface DownloadFeatureRepository<Track, Payload, Task, DownloadState> {
    val states: StateFlow<Map<String, DownloadState>>
    val tasks: StateFlow<List<Task>>
    suspend fun load()
    suspend fun download(track: Track, payload: Payload)
    suspend fun updateParallelism(value: Int)
    suspend fun pause(taskId: String)
    suspend fun resume(taskId: String)
    suspend fun retry(taskId: String)
    suspend fun deleteTask(taskId: String, deleteFile: Boolean)
    suspend fun deleteDownloaded(track: Track)
}

interface DownloadMediaResolver<Track, Payload> {
    suspend fun resolve(track: Track): Payload

    /**
     * Resolves the media of [track] with an explicit provider quality policy.
     *
     * [qualityPolicy] is the provider-facing policy string (the same dialect the desktop player
     * uses, e.g. ">>>" / "hq<>" / "sq<>" / "lq<>"). A null policy keeps the default policy.
     * The default implementation ignores the policy so existing resolvers keep working.
     */
    suspend fun resolve(track: Track, qualityPolicy: String?): Payload = resolve(track)
}

interface DownloadLocalLibraryPort {
    val mediaChangeEvents: Flow<Unit>
    val hasPermission: Boolean
    suspend fun isDatabaseReady(): Boolean
    fun refresh(forceRefresh: Boolean, showLoading: Boolean)
}

interface DownloadFeatureOperations<Track, Task> {
    fun isProviderTrack(track: Track): Boolean
    fun trackTitle(track: Track): String
    fun taskId(task: Task): String
    fun isCompleted(task: Task): Boolean

    /**
     * K2 下载许可门控：来源显式允许下载的曲目才返回 true。
     * K5-D.3：默认实现改为保守拒绝（fail-closed），禁止默认放行的许可实现；
     * 生产绑定覆写为逐曲许可判定，测试可显式覆写为 true 表达"已授权场景"。
     */
    fun canDownload(track: Track): Boolean = false

    /** 下载被许可拒绝时展示的可读原因。 */
    fun downloadDenialMessage(track: Track): String = "该曲目许可不允许下载，仅可在线播放"
}

interface DownloadFeatureOwner<Track, Task, DownloadState> {
    val state: StateFlow<DownloadFeatureState<Task, DownloadState>>

    fun start()
    fun restoreParallelism(value: Int)
    fun onParallelismChange(value: Int)
    fun download(track: Track)

    /**
     * Downloads [track] with an explicit provider quality policy.
     *
     * A null policy keeps the default policy. The default implementation ignores the policy so
     * existing owners keep working.
     */
    fun download(track: Track, qualityPolicy: String?) = download(track)
    fun deleteDownload(track: Track)
    fun pause(taskId: String)
    fun resume(taskId: String)
    fun retry(taskId: String)
    fun deleteTask(taskId: String, deleteFile: Boolean)
    fun dismissQueueFeedback(feedback: String)
}

fun <Track, Payload, Task, DownloadState> createDownloadFeatureOwner(
    repository: DownloadFeatureRepository<Track, Payload, Task, DownloadState>,
    resolver: DownloadMediaResolver<Track, Payload>,
    localLibrary: DownloadLocalLibraryPort,
    operations: DownloadFeatureOperations<Track, Task>,
    scope: CoroutineScope,
    shouldShowLocalMusicLoading: () -> Boolean,
    persistParallelism: (Int) -> Unit,
    onFeedback: (String) -> Unit = {},
    failureMessage: (Throwable) -> String = { throwable ->
        throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "下载操作失败" }
    },
    onError: (Throwable) -> Unit = {},
): DownloadFeatureOwner<Track, Task, DownloadState> = DefaultDownloadFeatureOwner(
    repository = repository,
    resolver = resolver,
    localLibrary = localLibrary,
    operations = operations,
    scope = scope,
    shouldShowLocalMusicLoading = shouldShowLocalMusicLoading,
    persistParallelism = persistParallelism,
    onFeedback = onFeedback,
    failureMessage = failureMessage,
    onError = onError,
)

private class DefaultDownloadFeatureOwner<Track, Payload, Task, DownloadState>(
    private val repository: DownloadFeatureRepository<Track, Payload, Task, DownloadState>,
    private val resolver: DownloadMediaResolver<Track, Payload>,
    private val localLibrary: DownloadLocalLibraryPort,
    private val operations: DownloadFeatureOperations<Track, Task>,
    private val scope: CoroutineScope,
    private val shouldShowLocalMusicLoading: () -> Boolean,
    private val persistParallelism: (Int) -> Unit,
    private val onFeedback: (String) -> Unit,
    private val failureMessage: (Throwable) -> String,
    private val onError: (Throwable) -> Unit,
) : DownloadFeatureOwner<Track, Task, DownloadState> {
    private val mutableState = MutableStateFlow(DownloadFeatureState<Task, DownloadState>())
    override val state: StateFlow<DownloadFeatureState<Task, DownloadState>> = mutableState.asStateFlow()

    private var started = false
    private var observedCompletedTaskIds = emptySet<String>()
    private var pendingMediaRefresh: Job? = null

    override fun start() {
        if (started) return
        started = true
        scope.launch {
            repository.states.collect { states ->
                mutableState.value = state.value.copy(states = states)
            }
        }
        scope.launch {
            repository.tasks.collect { tasks ->
                mutableState.value = state.value.copy(tasks = tasks)
                val completedIds = tasks.asSequence()
                    .filter(operations::isCompleted)
                    .map(operations::taskId)
                    .toSet()
                val newlyCompleted = completedIds - observedCompletedTaskIds
                observedCompletedTaskIds = completedIds
                if (newlyCompleted.isNotEmpty() && localLibrary.hasPermission) {
                    localLibrary.refresh(true, shouldShowLocalMusicLoading())
                }
            }
        }
        scope.launch {
            localLibrary.mediaChangeEvents.collect {
                pendingMediaRefresh?.cancel()
                pendingMediaRefresh = launch {
                    delay(MEDIA_CHANGE_DEBOUNCE_MS)
                    if (localLibrary.hasPermission && localLibrary.isDatabaseReady()) {
                        localLibrary.refresh(true, shouldShowLocalMusicLoading())
                    }
                }
            }
        }
    }

    override fun restoreParallelism(value: Int) {
        mutableState.value = state.value.copy(parallelism = value.coerceIn(1, 5))
    }

    override fun onParallelismChange(value: Int) {
        val normalized = value.coerceIn(1, 5)
        mutableState.value = state.value.copy(parallelism = normalized)
        persistParallelism(normalized)
        scope.launch { repository.updateParallelism(normalized) }
    }

    override fun download(track: Track) = download(track, null)

    override fun download(track: Track, qualityPolicy: String?) {
        if (!operations.isProviderTrack(track)) return
        // K2 许可门控：未知许可 / 许可不允许下载时，拒绝入队并给出可读原因，不发起 resolve。
        if (!operations.canDownload(track)) {
            publishFeedback(operations.downloadDenialMessage(track))
            return
        }
        publishFeedback("已加入下载队列：${operations.trackTitle(track)}")
        scope.launch {
            runCatching {
                val payload = resolver.resolve(track, qualityPolicy)
                repository.download(track, payload)
            }.onFailure(::publishError)
        }
    }

    override fun deleteDownload(track: Track) {
        scope.launch {
            runCatching { repository.deleteDownloaded(track) }
                .onSuccess {
                    if (localLibrary.hasPermission) {
                        localLibrary.refresh(true, shouldShowLocalMusicLoading())
                    }
                    publishFeedback("已删除下载：${operations.trackTitle(track)}")
                }
                .onFailure(::publishError)
        }
    }

    override fun pause(taskId: String) = runAction { repository.pause(taskId) }
    override fun resume(taskId: String) = runAction { repository.resume(taskId) }
    override fun retry(taskId: String) = runAction { repository.retry(taskId) }

    override fun deleteTask(taskId: String, deleteFile: Boolean) = runAction {
        repository.deleteTask(taskId, deleteFile)
    }

    override fun dismissQueueFeedback(feedback: String) {
        if (state.value.queueFeedback == feedback) {
            mutableState.value = state.value.copy(queueFeedback = null)
        }
    }

    private fun publishFeedback(message: String) {
        mutableState.value = state.value.copy(queueFeedback = message)
        onFeedback(message)
    }

    private fun publishError(throwable: Throwable) {
        val message = failureMessage(throwable)
        mutableState.value = state.value.copy(queueFeedback = message)
        onError(throwable)
        onFeedback(message)
    }

    private fun runAction(action: suspend () -> Unit) {
        scope.launch {
            runCatching { action() }.onFailure(::publishError)
        }
    }

    private companion object {
        const val MEDIA_CHANGE_DEBOUNCE_MS = 750L
    }
}
