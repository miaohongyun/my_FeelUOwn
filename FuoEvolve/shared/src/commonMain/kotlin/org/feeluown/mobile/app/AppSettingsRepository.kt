package org.feeluown.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface LegacyAppSettingsLoader {
    suspend fun load(): AppSettings
}

class PersistentAppSettingsRepository(
    private val store: SettingsSnapshotStore,
    private val legacyLoader: LegacyAppSettingsLoader?,
    private val scope: CoroutineScope,
) : AppSettingsRepository {
    private val updateMutex = Mutex()
    private val ready = CompletableDeferred<AppSettings>()
    private val mutableState = MutableStateFlow(SettingsState())

    override val state: StateFlow<SettingsState> = mutableState.asStateFlow()

    init {
        AppLogger.i("AppSettings", "Settings initialization started")
        scope.launchSettingsInitialization()
    }

    override suspend fun awaitSettings(): AppSettings = ready.await()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        updateInternal(preserveThemeTuning = true, transform = transform)
    }

    override suspend fun updateThemePaletteStyle(value: ThemePaletteStyle) {
        updateInternal(preserveThemeTuning = false) { current ->
            current.copy(themePaletteStyle = value)
        }
    }

    override suspend fun updateThemeColorSpec(value: ThemeColorSpec) {
        updateInternal(preserveThemeTuning = false) { current ->
            current.copy(themeColorSpec = value)
        }
    }

    private suspend fun updateInternal(
        preserveThemeTuning: Boolean,
        transform: (AppSettings) -> AppSettings,
    ) {
        ready.await()
        updateMutex.withLock {
            val current = mutableState.value.settings
            val transformed = transform(current)
            val updated = if (preserveThemeTuning) {
                transformed.copy(
                    themePaletteStyle = current.themePaletteStyle,
                    themeColorSpec = current.themeColorSpec,
                )
            } else {
                transformed
            }.withoutProviderCredentials()
            store.write(updated.toPersistedSettings())
            mutableState.value = SettingsState(isLoaded = true, settings = updated)
        }
    }

    private fun CoroutineScope.launchSettingsInitialization() = launch {
        runCatching { loadOrMigrate() }
            .onSuccess { settings ->
                AppLogger.i(
                    "AppSettings",
                    "Settings initialization completed: " +
                        "onboardingCompleted=${settings.onboardingCompleted}",
                )
                mutableState.value = SettingsState(isLoaded = true, settings = settings)
                ready.complete(settings)
            }
            .onFailure { throwable ->
                AppLogger.e(
                    "AppSettings",
                    "Settings initialization failed; falling back to default settings",
                    throwable,
                )
                val fallback = AppSettings()
                mutableState.value = SettingsState(
                    isLoaded = true,
                    settings = fallback,
                    errorMessage = throwable.message ?: throwable::class.simpleName,
                )
                ready.complete(fallback)
            }
    }

    private suspend fun loadOrMigrate(): AppSettings {
        AppLogger.d("AppSettings", "Reading persisted settings snapshot")
        val loaded = when (val result = store.read()) {
            is SettingsSnapshotReadResult.Loaded -> {
                AppLogger.i("AppSettings", "Persisted settings snapshot loaded")
                result.snapshot.toAppSettings().withoutProviderCredentials()
            }
            SettingsSnapshotReadResult.Corrupted -> AppSettings()
                .copy(providerDefaultsMigrationVersion = CURRENT_PROVIDER_DEFAULTS_MIGRATION_VERSION)
                .also { fallback ->
                    AppLogger.w("AppSettings", "Persisted settings snapshot is corrupted; writing defaults")
                    store.write(fallback.toPersistedSettings())
                }
            SettingsSnapshotReadResult.Missing -> {
                AppLogger.i("AppSettings", "Persisted settings snapshot is missing; writing defaults")
                val legacy = legacyLoader?.load()?.withoutProviderCredentials()
                // 全新安装用最新默认（含 jamendo、版本最新），无需迁移；
                // 从旧存储迁移来的用户保留其版本，交给 applyProviderDefaultsMigration 补新增源。
                val migrated = legacy ?: AppSettings().copy(
                    providerDefaultsMigrationVersion = CURRENT_PROVIDER_DEFAULTS_MIGRATION_VERSION,
                )
                store.write(migrated.toPersistedSettings())
                migrated
            }
        }
        return applyProviderDefaultsMigration(loaded)
    }

    /**
     * 一次性把本版本新增的默认音源补入老用户的 enabledProviderIds。
     *
     * 只补一次并回写版本号，因此升级后开箱即用、而用户之后仍可自由关闭该音源。
     * 新装用户的快照版本已是最新，这里是无操作。
     */
    private suspend fun applyProviderDefaultsMigration(settings: AppSettings): AppSettings {
        val migrated = migrateProviderDefaults(
            enabledProviderIds = settings.enabledProviderIds,
            fromVersion = settings.providerDefaultsMigrationVersion,
        )
        if (migrated.enabledProviderIds == settings.enabledProviderIds &&
            migrated.migrationVersion == settings.providerDefaultsMigrationVersion
        ) {
            return settings
        }
        AppLogger.i(
            "AppSettings",
            "Migrating provider defaults to v${migrated.migrationVersion}: " +
                "added=${PROVIDER_DEFAULTS_ADDED_IN_V1 - settings.enabledProviderIds}",
        )
        val updated = settings.copy(
            enabledProviderIds = migrated.enabledProviderIds,
            providerDefaultsMigrationVersion = migrated.migrationVersion,
        ).withoutProviderCredentials()
        store.write(updated.toPersistedSettings())
        return updated
    }
}

private fun AppSettings.withoutProviderCredentials(): AppSettings = copy(
    providerCookieInputs = emptyMap(),
    providerHeaderInputs = emptyMap(),
)

internal fun AppSettings.toPersistedSettings(): PersistedSettingsV1 = PersistedSettingsV1(
    onboardingCompleted = onboardingCompleted,
    homeSection = homeSection.name,
    mineSection = mineSection.name,
    playlistFilter = playlistFilter.name,
    localMusicViewMode = localMusicViewMode.name,
    excludedLocalMusicDirectoryIds = excludedLocalMusicDirectoryIds,
    includedLocalMusicDirectoryIds = includedLocalMusicDirectoryIds,
    localMusicMinDurationSeconds = localMusicMinDurationSeconds,
    searchScope = searchScope.name,
    selectedSearchProviderId = selectedSearchProviderId,
    selectedSettingsProviderId = selectedSettingsProviderId,
    providerLoginMode = providerLoginMode.name,
    enabledProviderIds = enabledProviderIds,
    providerOrderIds = providerOrderIds,
    searchProviderIds = searchProviderIds,
    recommendProviderIds = recommendProviderIds,
    exploreProviderIds = exploreProviderIds,
    mineProviderIds = mineProviderIds,
    playbackReportingProviderIds = playbackReportingProviderIds,
    audioCacheLimitMb = audioCacheLimitMb,
    imageCacheLimitMb = imageCacheLimitMb,
    downloadParallelism = downloadParallelism,
    losslessEnhanceEnabled = losslessEnhanceEnabled,
    losslessPrefetchEnabled = losslessPrefetchEnabled,
    losslessPrefetchWifiOnly = losslessPrefetchWifiOnly,
    losslessPlaybackWaitSeconds = losslessPlaybackWaitSeconds,
    jamendoClientId = jamendoClientId,
    subsonicEnabled = subsonicEnabled,
    subsonicServerUrl = subsonicServerUrl,
    subsonicUsername = subsonicUsername,
    subsonicPassword = subsonicPassword,
    wifiAudioQualityPolicy = wifiAudioQualityPolicy.name,
    cellularAudioQualityPolicy = cellularAudioQualityPolicy.name,
    unavailablePlaybackPolicy = unavailablePlaybackPolicy.name,
    smartReplacementProviderIds = smartReplacementProviderIds,
    smartReplacementMinScore = smartReplacementMinScore,
    smartReplacementSelections = smartReplacementSelections.mapValues { (_, selection) -> selection.toPersisted() },
    lyricsAssociations = lyricsAssociations,
    lyricsAlignmentOffsetsMs = lyricsAlignmentOffsetsMs,
    pauseOnOtherAppPlayback = pauseOnOtherAppPlayback,
    desktopVideoDecodeMode = desktopVideoDecodeMode.name,
    lyricFontSize = lyricFontSize.name,
    statusBarLyricsEnabled = statusBarLyricsEnabled,
    bydInstrumentLyricsEnabled = bydInstrumentLyricsEnabled,
    bluetoothLyricsEnabled = bluetoothLyricsEnabled,
    themeMode = themeMode.name,
    themeColorScheme = themeColorScheme.name,
    themePaletteStyle = themePaletteStyle.name,
    themeColorSpec = themeColorSpec.name,
    dynamicCoverColorEnabled = dynamicCoverColorEnabled,
    waveformAnimationDisabled = waveformAnimationDisabled,
    appUpdateChannel = appUpdateChannel.name,
    autoCheckAppUpdates = autoCheckAppUpdates,
    sourceAutoUpdateEnabled = sourceAutoUpdateEnabled,
    sourceUpdateIntervalHours = sourceUpdateIntervalHours,
    sourceMirrorPolicy = sourceMirrorPolicy,
    sourceCatalogVersion = sourceCatalogVersion,
    installedScriptVersions = installedScriptVersions.takeIf { it.isNotEmpty() },
    quarantinedScriptIds = quarantinedScriptIds.takeIf { it.isNotEmpty() },
    playlistPlaybackStatsVersion = playlistPlaybackStatsVersion,
    playlistPlaybackStats = playlistPlaybackStats.mapValues { (_, stat) ->
        PersistedPlaylistPlaybackStat(stat.playCount, stat.lastPlayedAtMillis)
    },
    providerDefaultsMigrationVersion = providerDefaultsMigrationVersion,
)

internal fun PersistedSettingsV1.toAppSettings(): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        onboardingCompleted = onboardingCompleted ?: defaults.onboardingCompleted,
        homeSection = homeSection.enumOr(defaults.homeSection),
        mineSection = mineSection.enumOr(defaults.mineSection),
        playlistFilter = playlistFilter.enumOr(defaults.playlistFilter),
        localMusicViewMode = localMusicViewMode.enumOr(defaults.localMusicViewMode),
        excludedLocalMusicDirectoryIds = excludedLocalMusicDirectoryIds ?: defaults.excludedLocalMusicDirectoryIds,
        includedLocalMusicDirectoryIds = includedLocalMusicDirectoryIds ?: defaults.includedLocalMusicDirectoryIds,
        localMusicMinDurationSeconds = localMusicMinDurationSeconds ?: defaults.localMusicMinDurationSeconds,
        searchScope = searchScope.enumOr(defaults.searchScope),
        selectedSearchProviderId = selectedSearchProviderId,
        selectedSettingsProviderId = selectedSettingsProviderId,
        providerLoginMode = providerLoginMode.enumOr(defaults.providerLoginMode),
        providerCookieInputs = emptyMap(),
        providerHeaderInputs = emptyMap(),
        enabledProviderIds = enabledProviderIds ?: defaults.enabledProviderIds,
        providerOrderIds = providerOrderIds ?: defaults.providerOrderIds,
        searchProviderIds = searchProviderIds ?: defaults.searchProviderIds,
        recommendProviderIds = recommendProviderIds ?: defaults.recommendProviderIds,
        exploreProviderIds = exploreProviderIds ?: defaults.exploreProviderIds,
        mineProviderIds = mineProviderIds ?: defaults.mineProviderIds,
        playbackReportingProviderIds = playbackReportingProviderIds ?: defaults.playbackReportingProviderIds,
        audioCacheLimitMb = audioCacheLimitMb ?: defaults.audioCacheLimitMb,
        imageCacheLimitMb = imageCacheLimitMb ?: defaults.imageCacheLimitMb,
        downloadParallelism = downloadParallelism ?: defaults.downloadParallelism,
        losslessEnhanceEnabled = losslessEnhanceEnabled ?: defaults.losslessEnhanceEnabled,
        losslessPrefetchEnabled = losslessPrefetchEnabled ?: defaults.losslessPrefetchEnabled,
        losslessPrefetchWifiOnly = losslessPrefetchWifiOnly ?: defaults.losslessPrefetchWifiOnly,
        losslessPlaybackWaitSeconds = losslessPlaybackWaitSeconds
            ?: defaults.losslessPlaybackWaitSeconds,
        jamendoClientId = jamendoClientId?.takeIf { it.isNotBlank() } ?: defaults.jamendoClientId,
        subsonicEnabled = subsonicEnabled ?: defaults.subsonicEnabled,
        subsonicServerUrl = subsonicServerUrl ?: defaults.subsonicServerUrl,
        subsonicUsername = subsonicUsername ?: defaults.subsonicUsername,
        subsonicPassword = subsonicPassword ?: defaults.subsonicPassword,
        wifiAudioQualityPolicy = wifiAudioQualityPolicy.enumOr(defaults.wifiAudioQualityPolicy),
        cellularAudioQualityPolicy = cellularAudioQualityPolicy.enumOr(defaults.cellularAudioQualityPolicy),
        unavailablePlaybackPolicy = unavailablePlaybackPolicy.enumOr(defaults.unavailablePlaybackPolicy),
        smartReplacementProviderIds = smartReplacementProviderIds ?: defaults.smartReplacementProviderIds,
        smartReplacementMinScore = smartReplacementMinScore ?: defaults.smartReplacementMinScore,
        smartReplacementSelections = smartReplacementSelections
            ?.mapValues { (_, selection) -> selection.toDomain() }
            ?: defaults.smartReplacementSelections,
        lyricsAssociations = lyricsAssociations ?: defaults.lyricsAssociations,
        lyricsAlignmentOffsetsMs = lyricsAlignmentOffsetsMs ?: defaults.lyricsAlignmentOffsetsMs,
        pauseOnOtherAppPlayback = pauseOnOtherAppPlayback ?: defaults.pauseOnOtherAppPlayback,
        desktopVideoDecodeMode = desktopVideoDecodeMode.enumOr(defaults.desktopVideoDecodeMode),
        lyricFontSize = lyricFontSize.enumOr(defaults.lyricFontSize),
        statusBarLyricsEnabled = statusBarLyricsEnabled ?: defaults.statusBarLyricsEnabled,
        bydInstrumentLyricsEnabled = bydInstrumentLyricsEnabled ?: defaults.bydInstrumentLyricsEnabled,
        bluetoothLyricsEnabled = bluetoothLyricsEnabled ?: defaults.bluetoothLyricsEnabled,
        themeMode = themeMode.enumOr(defaults.themeMode),
        themeColorScheme = themeColorScheme.enumOr(defaults.themeColorScheme),
        themePaletteStyle = themePaletteStyle.enumOr(defaults.themePaletteStyle),
        themeColorSpec = themeColorSpec.enumOr(defaults.themeColorSpec),
        dynamicCoverColorEnabled = dynamicCoverColorEnabled ?: defaults.dynamicCoverColorEnabled,
        waveformAnimationDisabled = waveformAnimationDisabled ?: defaults.waveformAnimationDisabled,
        appUpdateChannel = appUpdateChannel.enumOr(defaults.appUpdateChannel),
        autoCheckAppUpdates = autoCheckAppUpdates ?: defaults.autoCheckAppUpdates,
        sourceAutoUpdateEnabled = sourceAutoUpdateEnabled ?: defaults.sourceAutoUpdateEnabled,
        sourceUpdateIntervalHours = (sourceUpdateIntervalHours ?: defaults.sourceUpdateIntervalHours)
            .coerceIn(MIN_SOURCE_UPDATE_INTERVAL_HOURS, MAX_SOURCE_UPDATE_INTERVAL_HOURS),
        sourceMirrorPolicy = sourceMirrorPolicy?.takeIf { it.isNotBlank() } ?: defaults.sourceMirrorPolicy,
        sourceCatalogVersion = sourceCatalogVersion,
        installedScriptVersions = installedScriptVersions ?: defaults.installedScriptVersions,
        quarantinedScriptIds = quarantinedScriptIds ?: defaults.quarantinedScriptIds,
        playlistPlaybackStatsVersion = playlistPlaybackStatsVersion ?: defaults.playlistPlaybackStatsVersion,
        playlistPlaybackStats = playlistPlaybackStats
            ?.mapValues { (_, stat) -> PlaylistPlaybackStat(stat.playCount, stat.lastPlayedAtMillis) }
            ?: defaults.playlistPlaybackStats,
        providerDefaultsMigrationVersion = providerDefaultsMigrationVersion
            ?: defaults.providerDefaultsMigrationVersion,
    )
}

private fun SmartReplacementSelection.toPersisted() = PersistedSmartReplacementSelection(
    replacementId = replacementId,
    replacementTitle = replacementTitle,
    replacementArtists = replacementArtists,
    replacementAlbum = replacementAlbum,
    replacementSource = replacementSource,
    replacementProviderName = replacementProviderName,
    replacementCoverUrl = replacementCoverUrl,
    replacementDurationMs = replacementDurationMs,
    replacementScore = replacementScore,
)

private fun PersistedSmartReplacementSelection.toDomain() = SmartReplacementSelection(
    replacementId = replacementId,
    replacementTitle = replacementTitle,
    replacementArtists = replacementArtists,
    replacementAlbum = replacementAlbum,
    replacementSource = replacementSource,
    replacementProviderName = replacementProviderName,
    replacementCoverUrl = replacementCoverUrl,
    replacementDurationMs = replacementDurationMs,
    replacementScore = replacementScore,
)

private inline fun <reified T : Enum<T>> String?.enumOr(default: T): T =
    this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default
