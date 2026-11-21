package org.feeluown.mobile

import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

internal class DesktopDownloadRepository(
    private val resolvePayload: suspend (MusicTrack) -> PlaybackPayload,
    private val storageRoot: Path = desktopDownloadStorageRoot(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    // K5-D.3 第二层门禁：默认走统一逐曲许可决策（fail-closed）；仅测试可显式注入。
    override val downloadAuthorization: (MusicTrack) -> DownloadPermissionDecision = ::evaluateDownloadPermission,
) : DownloadRepository, AutoCloseable {
    private val mutableStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val mutableTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    override val states: StateFlow<Map<String, DownloadState>> = mutableStates.asStateFlow()
    override val tasks: StateFlow<List<DownloadTask>> = mutableTasks.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val taskRecords = linkedMapOf<String, DownloadTask>()
    private val resumeMetadata = linkedMapOf<String, DesktopDownloadResumeMetadata>()
    private val payloads = mutableMapOf<String, PlaybackPayload>()
    private val taskJobs = mutableMapOf<String, Job>()
    private val taskConnections = ConcurrentHashMap<String, HttpURLConnection>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var parallelism = DEFAULT_PARALLELISM

    override suspend fun load() = withContext(Dispatchers.IO) {
        ensureDirectories()
        mutex.withLock {
            taskRecords.clear()
            resumeMetadata.clear()
            readIndex().forEach { snapshot ->
                val loadedTask = snapshot.toTask()
                val completedValidationFailure = if (loadedTask.status == DownloadTaskStatus.Completed) {
                    completedDownloadValidationFailure(loadedTask.completedUri)
                } else {
                    null
                }
                val normalized = when {
                    loadedTask.status == DownloadTaskStatus.Downloading -> loadedTask.copy(
                        status = DownloadTaskStatus.Paused,
                        updatedAt = nowMillis(),
                    )
                    loadedTask.status == DownloadTaskStatus.Completed && completedValidationFailure != null ->
                        loadedTask.copy(
                            status = DownloadTaskStatus.Failed,
                            completedUri = null,
                            failureMessage = completedValidationFailure,
                            updatedAt = nowMillis(),
                        )
                    else -> loadedTask
                }
                taskRecords[normalized.id] = normalized
                snapshot.resume?.let { resumeMetadata[normalized.id] = it }
            }
            persistLocked()
            publishLocked()
        }
        schedule()
    }

    override suspend fun download(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
        // K5-D.3 第二层门禁：绕过 Feature 直接调用 Repository 也在此拒绝未授权曲目。
        if (isDownloadBlocked(track)) return
        enqueue(track, payload = null)
    }

    override suspend fun download(track: MusicTrack, payload: PlaybackPayload) {
        if (track.sourceType != TrackSourceType.Provider) return
        if (isDownloadBlocked(track)) return
        enqueue(track, payload)
    }

    override suspend fun updateParallelism(parallelism: Int) {
        this.parallelism = parallelism.coerceIn(1, 5)
        schedule()
    }

    override suspend fun pause(taskId: String) {
        mutex.withLock {
            val task = taskRecords[taskId] ?: return
            if (task.status != DownloadTaskStatus.Queued && task.status != DownloadTaskStatus.Downloading) return
            taskJobs.remove(taskId)?.cancel()
            taskConnections.remove(taskId)?.disconnect()
            taskRecords[taskId] = task.copy(
                status = DownloadTaskStatus.Paused,
                updatedAt = nowMillis(),
            )
            persistLocked()
            publishLocked()
        }
    }

    override suspend fun resume(taskId: String) = restart(taskId)

    override suspend fun retry(taskId: String) = restart(taskId)

    override suspend fun deleteTask(taskId: String, deleteFile: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            taskJobs.remove(taskId)?.cancel()
            taskConnections.remove(taskId)?.disconnect()
            val task = taskRecords.remove(taskId) ?: return@withLock
            payloads.remove(taskId)
            resumeMetadata.remove(taskId)
            deletePartFile(taskId)
            if (deleteFile) deleteCompletedFiles(task.completedUri)
            persistLocked()
            publishLocked()
        }
    }

    override suspend fun deleteDownloaded(track: MusicTrack) = withContext(Dispatchers.IO) {
        val taskId = mutex.withLock {
            val candidates = listOfNotNull(
                track.providerId,
                track.id,
                taskRecords.values.firstOrNull { task -> task.completedUri == track.localUri }?.id,
            ).distinct()
            candidates.firstOrNull { candidate -> candidate in taskRecords }
        } ?: return@withContext
        deleteTask(taskId, deleteFile = true)
    }

    override fun close() {
        taskConnections.values.forEach { it.disconnect() }
        taskConnections.clear()
        scope.cancel()
    }

    private suspend fun enqueue(track: MusicTrack, payload: PlaybackPayload?) {
        mutex.withLock {
            val existing = taskRecords[track.id]
            when (existing?.status) {
                DownloadTaskStatus.Queued,
                DownloadTaskStatus.Downloading,
                -> return
                DownloadTaskStatus.Completed -> {
                    if (completedDownloadValidationFailure(existing.completedUri) == null) return
                    taskRecords[track.id] = existing.copy(
                        status = DownloadTaskStatus.Queued,
                        completedUri = null,
                        failureMessage = null,
                        downloadedBytes = 0,
                        totalBytes = null,
                        updatedAt = nowMillis(),
                    )
                }
                DownloadTaskStatus.Paused,
                DownloadTaskStatus.Failed,
                -> taskRecords[track.id] = existing.copy(
                    status = DownloadTaskStatus.Queued,
                    failureMessage = null,
                    updatedAt = nowMillis(),
                )
                null -> taskRecords[track.id] = DownloadTask(
                    id = track.id,
                    track = track,
                    status = DownloadTaskStatus.Queued,
                    createdAt = nowMillis(),
                )
            }
            payload?.let { payloads[track.id] = it }
            persistLocked()
            publishLocked()
        }
        schedule()
    }

    private suspend fun restart(taskId: String) {
        val task = mutex.withLock { taskRecords[taskId] } ?: return
        if (task.status == DownloadTaskStatus.Completed && completedDownloadValidationFailure(task.completedUri) == null) return
        // K5-D.4 恢复/重试重新核验当前许可；恢复的 track 携带许可快照，旧数据无 license → UNKNOWN 拒绝。
        if (isDownloadBlocked(task.track)) {
            mutex.withLock {
                taskRecords[taskId]?.let { current -> taskRecords[taskId] = current.copy(
                    status = DownloadTaskStatus.Failed,
                    failureMessage = downloadAuthorization(task.track).reason ?: "下载权限尚未核实，已停止恢复",
                    updatedAt = nowMillis(),
                ) }
                persistLocked()
                publishLocked()
            }
            return
        }
        mutex.withLock {
            val current = taskRecords[taskId] ?: return
            if (current.status == DownloadTaskStatus.Completed && completedDownloadValidationFailure(current.completedUri) == null) return
            taskRecords[taskId] = current.copy(
                status = DownloadTaskStatus.Queued,
                completedUri = null,
                failureMessage = null,
                updatedAt = nowMillis(),
            )
            persistLocked()
            publishLocked()
        }
        schedule()
    }

    private fun schedule() {
        scope.launch {
            val selected = mutex.withLock {
                val running = taskJobs.values.count { it.isActive }
                val capacity = parallelism - running
                if (capacity <= 0) return@withLock emptyList()
                taskRecords.values
                    .filter { it.status == DownloadTaskStatus.Queued && it.id !in taskJobs }
                    .sortedBy { it.createdAt }
                    .take(capacity)
                    .map { it.id }
            }
            selected.forEach { taskId ->
                mutex.withLock {
                    if (taskId !in taskJobs) {
                        taskJobs[taskId] = scope.launch { runTask(taskId) }
                    }
                }
            }
        }
    }

    private suspend fun runTask(taskId: String) {
        try {
            val task = mutex.withLock {
                val current = taskRecords[taskId] ?: return
                val downloading = current.copy(
                    status = DownloadTaskStatus.Downloading,
                    failureMessage = null,
                    updatedAt = nowMillis(),
                )
                taskRecords[taskId] = downloading
                persistLocked()
                publishLocked()
                downloading
            }
            // K5-D.3 最后一道防线：任何路径进入 runTask（含恢复、重试、换音质）在发起网络前重新核验许可。
            if (isDownloadBlocked(task.track)) {
                throw IllegalStateException(downloadAuthorization(task.track).reason ?: "下载权限尚未核实，已停止")
            }
            val payload = mutex.withLock { payloads.remove(taskId) } ?: resolvePayload(task.track)
            require(payload.url.isNotBlank()) { "播放地址为空" }
            ensureDirectories()
            val extension = mediaExtension(payload)
            val partFile = partFile(taskId)
            val downloadedBytes = writePayload(taskId, payload, partFile)
            coroutineContext.ensureActive()
            desktopDownloadedMediaValidationFailure(partFile, payload)?.let { failure ->
                Files.deleteIfExists(partFile)
                mutex.withLock {
                    resumeMetadata.remove(taskId)
                    persistLocked()
                }
                throw IllegalStateException(failure)
            }
            val finalFile = finalFile(task.track, extension)
            Files.createDirectories(finalFile.parent)
            moveReplacing(partFile, finalFile)
            writeLyrics(payload, finalFile)
            val completedUri = finalFile.toUri().toString()
            mutex.withLock {
                resumeMetadata.remove(taskId)
                val current = taskRecords[taskId] ?: return@withLock
                taskRecords[taskId] = current.copy(
                    status = DownloadTaskStatus.Completed,
                    completedUri = completedUri,
                    downloadedBytes = downloadedBytes,
                    totalBytes = downloadedBytes,
                    failureMessage = null,
                    updatedAt = nowMillis(),
                )
                persistLocked()
                publishLocked()
            }
        } catch (_: CancellationException) {
            // pause/delete own the persisted state; retain the .part file for a later resume.
        } catch (throwable: Throwable) {
            mutex.withLock {
                taskRecords[taskId]?.let { current ->
                    taskRecords[taskId] = current.copy(
                        status = DownloadTaskStatus.Failed,
                        failureMessage = throwable.message ?: "下载失败",
                        updatedAt = nowMillis(),
                    )
                    persistLocked()
                    publishLocked()
                }
            }
        } finally {
            taskConnections.remove(taskId)?.disconnect()
            mutex.withLock { taskJobs.remove(taskId) }
            schedule()
        }
    }

    private suspend fun writePayload(
        taskId: String,
        payload: PlaybackPayload,
        target: Path,
    ): Long = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        Files.createDirectories(target.parent)
        val connection = URL(payload.url).openConnection()
        val http = connection as? HttpURLConnection
        if (http != null) taskConnections[taskId] = http
        try {
            payload.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val existing = if (Files.exists(target)) Files.size(target) else 0L
            val storedResume = mutex.withLock { resumeMetadata[taskId] }
            val resourceKey = payload.url
            // A byte range without a stable entity validator can silently combine two different
            // versions of a remote file. Restart from byte zero unless ETag/Last-Modified exists.
            val resumeValidator = storedResume?.etag ?: storedResume?.lastModified
            val canResume = http != null && existing > 0L &&
                storedResume?.resourceKey == resourceKey && !resumeValidator.isNullOrBlank()
            if (canResume) {
                connection.setRequestProperty("Range", "bytes=$existing-")
                connection.setRequestProperty("If-Range", requireNotNull(resumeValidator))
            }

            val responseCode = http?.responseCode
            if (http != null && responseCode != null && responseCode >= 400) {
                throw IllegalStateException("HTTP $responseCode")
            }
            if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                val contentRange = http?.getHeaderField("Content-Range")
                require(canResume && isExpectedPartialContentRange(contentRange, existing)) {
                    "服务器返回的断点续传范围无效，已拒绝拼接文件"
                }
            }
            val append = canResume && responseCode == HttpURLConnection.HTTP_PARTIAL
            val start = if (append) existing else 0L
            val total = connection.contentLengthLong.takeIf { it > 0L }?.plus(start)
            val metadata = DesktopDownloadResumeMetadata(
                resourceKey = resourceKey,
                etag = http?.getHeaderField("ETag")?.takeIf { it.isNotBlank() }
                    ?: storedResume?.etag.takeIf { append },
                lastModified = http?.getHeaderField("Last-Modified")?.takeIf { it.isNotBlank() }
                    ?: storedResume?.lastModified.takeIf { append },
            )
            mutex.withLock {
                resumeMetadata[taskId] = metadata
                persistLocked()
            }

            var written = start
            var lastPublishedAt = 0L
            var lastPersistedAt = 0L
            connection.getInputStream().use { input ->
                FileOutputStream(target.toFile(), append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = nowMillis()
                        val publish = now - lastPublishedAt >= DownloadCheckpointPolicy.progressPublishIntervalMs
                        val persist = now - lastPersistedAt >= DownloadCheckpointPolicy.persistenceIntervalMs
                        if (publish || persist) {
                            checkpointProgress(taskId, written, total, publish, persist)
                            if (publish) lastPublishedAt = now
                            if (persist) lastPersistedAt = now
                        }
                    }
                }
            }
            checkpointProgress(taskId, written, total ?: written, publish = true, persist = true)
            written
        } finally {
            taskConnections.remove(taskId)
            http?.disconnect()
        }
    }

    private suspend fun checkpointProgress(
        taskId: String,
        downloadedBytes: Long,
        totalBytes: Long?,
        publish: Boolean,
        persist: Boolean,
    ) {
        mutex.withLock {
            val current = taskRecords[taskId] ?: return@withLock
            if (current.status != DownloadTaskStatus.Downloading) return@withLock
            taskRecords[taskId] = current.copy(
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                updatedAt = nowMillis(),
            )
            if (persist) persistLocked()
            if (publish) publishLocked()
        }
    }

    private fun publishLocked() {
        val ordered = taskRecords.values.sortedByDescending { it.createdAt }
        mutableTasks.value = ordered
        mutableStates.value = ordered.associate { task ->
            task.id to when (task.status) {
                DownloadTaskStatus.Queued -> DownloadState.Queued
                DownloadTaskStatus.Downloading -> DownloadState.Downloading(
                    if (task.totalBytes != null && task.totalBytes > 0L) {
                        (task.downloadedBytes.toDouble() / task.totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                )
                DownloadTaskStatus.Paused -> DownloadState.Paused
                DownloadTaskStatus.Failed -> DownloadState.Failed(task.failureMessage ?: "下载失败")
                DownloadTaskStatus.Completed -> task.completedUri
                    ?.let { DownloadState.Downloaded(it) }
                    ?: DownloadState.Failed("下载文件不存在")
            }
        }
    }

    private fun persistLocked() {
        ensureDirectories()
        val snapshots = taskRecords.values.map { task ->
            DesktopDownloadTaskSnapshot.fromTask(task, resumeMetadata[task.id])
        }
        val target = indexFile()
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(snapshots))
        moveReplacing(temporary, target)
    }

    private fun readIndex(): List<DesktopDownloadTaskSnapshot> {
        val target = indexFile()
        if (!Files.exists(target)) return emptyList()
        return runCatching {
            json.decodeFromString<List<DesktopDownloadTaskSnapshot>>(Files.readString(target))
        }.getOrElse { emptyList() }
    }

    private fun ensureDirectories() {
        Files.createDirectories(storageRoot)
        Files.createDirectories(audioDirectory())
        Files.createDirectories(partsDirectory())
    }

    private fun indexFile(): Path = storageRoot.resolve("tasks.json")
    private fun audioDirectory(): Path = storageRoot.resolve("audio")
    private fun partsDirectory(): Path = storageRoot.resolve("parts")
    private fun partFile(taskId: String): Path = partsDirectory().resolve("${stableId(taskId)}.part")

    private fun finalFile(track: MusicTrack, extension: String): Path {
        val display = sanitizeFileName("${track.artists} - ${track.title}")
        return audioDirectory().resolve("${display}_${stableId(track.id)}.$extension")
    }

    private fun deletePartFile(taskId: String) {
        runCatching { Files.deleteIfExists(partFile(taskId)) }
    }

    private fun deleteCompletedFiles(uri: String?) {
        val path = uri?.toFilePathOrNull() ?: return
        runCatching { Files.deleteIfExists(path) }
        runCatching { Files.deleteIfExists(lyricsPath(path)) }
    }

    private fun writeLyrics(payload: PlaybackPayload, audioFile: Path) {
        val lyrics = payload.lyrics?.takeIf { it.isNotBlank() } ?: return
        runCatching { Files.writeString(lyricsPath(audioFile), lyrics) }
    }
}

@Serializable
private data class DesktopDownloadResumeMetadata(
    val resourceKey: String,
    val etag: String? = null,
    val lastModified: String? = null,
)

/** K5-D.4：TrackLicense 的桌面持久化 DTO（领域类保持无序列化注解，此处显式映射）。 */
@Serializable
private data class DesktopLicenseSnapshot(
    val licenseType: String = "UNKNOWN",
    val licenseUrl: String? = null,
    val attribution: String? = null,
    val playLicense: Boolean = false,
    val downloadLicense: Boolean = false,
    val commercialUseAllowed: Boolean = false,
    val derivativesAllowed: Boolean = false,
    val redistributionAllowed: Boolean = false,
    val licenseVerifiedAt: String? = null,
    val licenseSource: String? = null,
    val personalCopyAllowed: Boolean = false,
    val platformDownloadAllowed: Boolean = false,
    val downloadPermissionStatus: String = "UNKNOWN",
    val downloadPermissionEvidence: String? = null,
    val downloadPermissionReason: String? = null,
) {
    fun toLicense(): TrackLicense = TrackLicense(
        licenseType = licenseType,
        licenseUrl = licenseUrl,
        attribution = attribution,
        playLicense = playLicense,
        downloadLicense = downloadLicense,
        commercialUseAllowed = commercialUseAllowed,
        derivativesAllowed = derivativesAllowed,
        redistributionAllowed = redistributionAllowed,
        licenseVerifiedAt = licenseVerifiedAt,
        licenseSource = licenseSource,
        personalCopyAllowed = personalCopyAllowed,
        platformDownloadAllowed = platformDownloadAllowed,
        downloadPermissionStatus = runCatching { DownloadPermissionStatus.valueOf(downloadPermissionStatus) }
            .getOrDefault(DownloadPermissionStatus.UNKNOWN),
        downloadPermissionEvidence = downloadPermissionEvidence,
        downloadPermissionReason = downloadPermissionReason,
    )

    companion object {
        fun from(license: TrackLicense) = DesktopLicenseSnapshot(
            licenseType = license.licenseType,
            licenseUrl = license.licenseUrl,
            attribution = license.attribution,
            playLicense = license.playLicense,
            downloadLicense = license.downloadLicense,
            commercialUseAllowed = license.commercialUseAllowed,
            derivativesAllowed = license.derivativesAllowed,
            redistributionAllowed = license.redistributionAllowed,
            licenseVerifiedAt = license.licenseVerifiedAt,
            licenseSource = license.licenseSource,
            personalCopyAllowed = license.personalCopyAllowed,
            platformDownloadAllowed = license.platformDownloadAllowed,
            downloadPermissionStatus = license.downloadPermissionStatus.name,
            downloadPermissionEvidence = license.downloadPermissionEvidence,
            downloadPermissionReason = license.downloadPermissionReason,
        )
    }
}

@Serializable
private data class DesktopDownloadTaskSnapshot(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val source: String,
    val sourceType: String,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
    val providerId: String? = null,
    val providerName: String? = null,
    val license: DesktopLicenseSnapshot? = null,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val downloadedBytes: Long,
    val totalBytes: Long? = null,
    val failureMessage: String? = null,
    val completedUri: String? = null,
    val resume: DesktopDownloadResumeMetadata? = null,
) {
    fun toTask(): DownloadTask = DownloadTask(
        id = id,
        track = MusicTrack(
            id = id,
            title = title,
            artists = artists,
            album = album,
            source = source,
            sourceType = runCatching { TrackSourceType.valueOf(sourceType) }.getOrDefault(TrackSourceType.Provider),
            coverUrl = coverUrl,
            durationMs = durationMs,
            providerId = providerId,
            providerName = providerName,
            license = license?.toLicense(),
        ),
        status = runCatching { DownloadTaskStatus.valueOf(status) }.getOrDefault(DownloadTaskStatus.Failed),
        createdAt = createdAt,
        updatedAt = updatedAt,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        failureMessage = failureMessage,
        completedUri = completedUri,
    )

    companion object {
        fun fromTask(task: DownloadTask, resume: DesktopDownloadResumeMetadata?): DesktopDownloadTaskSnapshot =
            DesktopDownloadTaskSnapshot(
                id = task.id,
                title = task.track.title,
                artists = task.track.artists,
                album = task.track.album,
                source = task.track.source,
                sourceType = task.track.sourceType.name,
                coverUrl = task.track.coverUrl,
                durationMs = task.track.durationMs,
                providerId = task.track.providerId,
                providerName = task.track.providerName,
                license = task.track.license?.let(DesktopLicenseSnapshot::from),
                status = task.status.name,
                createdAt = task.createdAt,
                updatedAt = task.updatedAt,
                downloadedBytes = task.downloadedBytes,
                totalBytes = task.totalBytes,
                failureMessage = task.failureMessage,
                completedUri = task.completedUri,
                resume = resume,
            )
    }
}

private fun desktopDownloadStorageRoot(): Path {
    val home = System.getProperty("user.home").orEmpty()
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val base = when {
        osName.contains("win") -> System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: "$home/AppData/Local"
        osName.contains("mac") -> "$home/Library/Application Support"
        else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            ?: "$home/.local/share"
    }
    return Path.of(base, "FuoEvolve", "downloads")
}

internal fun mediaExtension(payload: PlaybackPayload): String {
    // Some lossless sources (notably Subsonic) expose a .view endpoint rather than a .flac URL.
    // Prefer the explicit resolved codec label so the downloaded file keeps the right extension.
    if (payload.audioQuality?.contains("flac", ignoreCase = true) == true) return "flac"
    val raw = runCatching { URI(payload.url).path }.getOrNull()
        ?.substringAfterLast('/', "")
        ?.substringAfterLast('.', "")
        ?.lowercase()
        .orEmpty()
    return raw.takeIf { extension ->
        extension.length in 1..8 && extension.all { it.isLetterOrDigit() }
    } ?: "audio"
}

private fun sanitizeFileName(value: String): String {
    val sanitized = buildString {
        value.forEach { char ->
            append(
                when {
                    char.code < 32 || char in "<>:\"/\\|?*" -> '_'
                    else -> char
                },
            )
        }
    }.trim().trim('.', ' ')
    return sanitized.ifBlank { "track" }.take(120)
}

private fun stableId(value: String): String = value.hashCode().toUInt().toString(16)

private fun String.toFilePathOrNull(): Path? = runCatching {
    val uri = URI(this)
    if (uri.scheme.equals("file", ignoreCase = true)) Path.of(uri) else null
}.getOrNull()

/** Re-check completed files on startup and before treating a duplicate download as satisfied. */
private fun completedDownloadValidationFailure(uri: String?): String? {
    val path = uri?.toFilePathOrNull()
        ?: return "下载文件不存在或路径无效，请重新下载"
    if (!Files.isRegularFile(path)) return "下载文件不存在，请重新下载"
    return runCatching { desktopDownloadedMediaValidationFailure(path) }
        .getOrElse { error -> "下载文件校验异常：${error.message ?: "未知错误"}" }
}

private fun lyricsPath(audioFile: Path): Path = audioFile.resolveSibling("${audioFile.fileName}.lrc")

private fun moveReplacing(source: Path, target: Path) {
    runCatching {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.getOrElse {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

private const val DEFAULT_PARALLELISM = 3

