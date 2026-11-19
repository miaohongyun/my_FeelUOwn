package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.feeluown.mobile.source.SourceCatalogRuntime
import org.feeluown.mobile.source.SourceUpdateHost
import platform.UIKit.UIViewController

internal fun handleIosLocalPlaylistImportResult(
    fileName: String?,
    content: String?,
    onImport: (String, String) -> Unit,
    onReadFailure: () -> Unit,
) {
    if (fileName == null && content == null) return
    val validFileName = fileName?.takeIf { it.isNotBlank() }
    val validContent = content?.takeIf { it.isNotBlank() }
    if (validFileName != null && validContent != null) onImport(validFileName, validContent) else onReadFailure()
}

internal fun handleIosJsonImportResult(
    content: String?,
    onImport: (String) -> Unit,
    onReadFailure: () -> Unit,
) {
    val validContent = content?.takeIf { it.isNotBlank() }
    if (validContent != null) onImport(validContent) else onReadFailure()
}

fun MainViewController(
    audioOutput: IosAudioOutput,
    videoOutput: IosVideoOutput,
    mediaLibraryOutput: IosMediaLibraryOutput,
    downloadOutput: IosDownloadOutput,
    webLoginOutput: IosWebLoginOutput,
    shareOutput: IosShareOutput,
    localPlaylistFileOutput: IosLocalPlaylistFileOutput,
    networkStatusOutput: IosNetworkStatusOutput,
    audioRecognitionOutput: IosAudioRecognitionOutput,
    oauthDeviceCodeOutput: IosOAuthDeviceCodeOutput,
): UIViewController = ComposeUIViewController {
    IosApp(
        audioOutput, videoOutput, mediaLibraryOutput, downloadOutput, webLoginOutput,
        shareOutput, localPlaylistFileOutput, networkStatusOutput, audioRecognitionOutput, oauthDeviceCodeOutput,
    )
}

@Composable
private fun IosApp(
    audioOutput: IosAudioOutput,
    videoOutput: IosVideoOutput,
    mediaLibraryOutput: IosMediaLibraryOutput,
    downloadOutput: IosDownloadOutput,
    webLoginOutput: IosWebLoginOutput,
    shareOutput: IosShareOutput,
    localPlaylistFileOutput: IosLocalPlaylistFileOutput,
    networkStatusOutput: IosNetworkStatusOutput,
    audioRecognitionOutput: IosAudioRecognitionOutput,
    oauthDeviceCodeOutput: IosOAuthDeviceCodeOutput,
) {
    IosVideoOutputHolder.output = videoOutput
    val container = remember {
        IosAppContainer(
            audioOutput, mediaLibraryOutput, downloadOutput, webLoginOutput,
            networkStatusOutput, audioRecognitionOutput, oauthDeviceCodeOutput,
        )
    }
    // iOS 不承诺系统保证的每日后台执行，因此每次进入前台都补查一次（策略内部会判断是否到期）。
    LaunchedEffect(container) {
        container.checkSourceUpdatesOnLaunch()
    }
    AppRoot(
        appViewModel = container.appViewModel,
        uiGraph = container.appUiGraph,
        platform = AppPlatformBindings(
            hasAudioPermission = container.hasAudioPermission,
            onRequestAudioPermission = container::requestAudioPermission,
            audioRecognitionAccess = AudioRecognitionAccess(
                source = AudioRecognitionSource.Microphone,
                isAvailable = container.hasMicrophonePermission,
                requestAccess = container::requestMicrophonePermission,
            ),
            onOpenProviderWebLogin = container::openProviderWebLogin,
            onLogoutProvider = container::logoutProvider,
            onImportYtmusicHeaderFile = {
                localPlaylistFileOutput.importFile { _, content ->
                    handleIosJsonImportResult(
                        content = content,
                        onImport = container.appUiGraph.providerAuth::loginYtmusicWithHeaderFile,
                        onReadFailure = { container.appViewModel.showFeedback("无法读取 ytmusic_header.json") },
                    )
                }
            },
            onImportYtmusicOAuthFile = {
                localPlaylistFileOutput.importFile { _, content ->
                    handleIosJsonImportResult(
                        content = content,
                        onImport = container.appUiGraph.providerAuth::importYtmusicOAuthRelatedJson,
                        onReadFailure = { container.appViewModel.showFeedback("无法读取 OAuth JSON") },
                    )
                }
            },
            onStartYtmusicOAuth = container.appUiGraph.providerAuth::startYtmusicTvOAuthLogin,
            onImportLocalPlaylistFile = {
                localPlaylistFileOutput.importFile { fileName, content ->
                    handleIosLocalPlaylistImportResult(
                        fileName = fileName,
                        content = content,
                        onImport = container.appUiGraph.localPlaylist::prepareImport,
                        onReadFailure = { container.appViewModel.showFeedback("无法读取本地歌单文件") },
                    )
                }
            },
            onExportLocalPlaylistFile = localPlaylistFileOutput::exportFile,
            onShareLocalPlaylistFile = localPlaylistFileOutput::shareFile,
            onShareText = shareOutput::share,
        ),
    )
}

private class IosAppContainer(
    audioOutput: IosAudioOutput,
    mediaLibraryOutput: IosMediaLibraryOutput,
    downloadOutput: IosDownloadOutput,
    private val webLoginOutput: IosWebLoginOutput,
    networkStatusOutput: IosNetworkStatusOutput,
    private val audioRecognitionOutput: IosAudioRecognitionOutput,
    oauthDeviceCodeOutput: IosOAuthDeviceCodeOutput,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 设置必须先于 providerGraph 构造：动态音源的 client_id 与隔离列表都从设置投影而来。
    private val settingsRepository = createIosAppSettingsRepository(scope)
    private val sourceCatalogRuntime = SourceCatalogRuntime(
        quarantine = { settingsRepository.state.value.settings.quarantinedScriptIds },
    )
    // 用 lazy：构造顺序上 providerGraph 还在后面，直接初始化会读到尚未赋值的字段。
    private val sourceUpdateHost by lazy {
        SourceUpdateHost(
            runtime = sourceCatalogRuntime,
            settings = settingsRepository,
            providerRegistry = providerGraph.registry,
        )
    }

    private val providerGraph = createFuoProviderGraph(
        credentials = IosProviderCredentialStore(),
        persistentCache = IosProviderCacheStore(),
        isCellularConnection = networkStatusOutput::isCellularConnection,
        dynamicProviders = DynamicProviderDependencies(
            scriptSource = sourceCatalogRuntime.scripts,
            jamendoClientId = {
                settingsRepository.state.value.settings.jamendoClientId.takeIf(String::isNotBlank)
            },
        ),
    )
    private val playbackProvider = createAppPlaybackProviderPort(
        providerRegistry = providerGraph.registry,
        providerSearch = providerGraph.search,
        providerCatalog = providerGraph.content,
        providerPlaybackSource = providerGraph.playbackSource,
    )
    private val localRepository = IosLocalMusicRepository(mediaLibraryOutput)
    private val localPlaylistRepository = IosLocalPlaylistRepository()
    private val downloadRepository = IosDownloadRepository(playbackProvider, downloadOutput)
    /** 前台补查受控音源目录；失败只写日志，不影响进入应用。 */
    suspend fun checkSourceUpdatesOnLaunch() {
        runCatching { sourceUpdateHost.checkIfDue() }
    }

    private val playbackEngine = IosNativeAudioEngine(scope, audioOutput, settingsRepository)
    private val providerSessionRepository = DefaultProviderSessionRepository(providerGraph.auth)
    private val navigator = AppNavigator()
    private val trackNavigationPort: TrackNavigationPort = createTrackNavigationPort(navigator)
    private val homeRefreshPort: HomeRefreshPort by lazy { createHomeRefreshPort { homeFeatureController } }
    private val oauthDeviceCodeAssistant: OAuthDeviceCodeAssistant = IosOAuthDeviceCodeAssistant(oauthDeviceCodeOutput)
    private val playbackQueueStore = IosPlaybackQueueStore()
    private val listeningHistorySink: ListeningHistorySink = NoOpListeningHistorySink
    private val resourceCacheRepository = IosResourceCacheRepository()
    private val debugLogFeatureController by lazy { createDebugLogFeatureController(NoOpDebugLogRepository, scope) }
    private val audioRecognitionRepository = IosAudioRecognitionRepository(audioRecognitionOutput)

    private val searchController: SearchFeatureController by lazy {
        val initialSettings = settingsRepository.state.value.settings
        createSearchFeatureController(
            providerRepository = providerGraph.search,
            localRepository = localRepository,
            scope = scope,
            providerIdsForSearch = {
                val active = providerSessionRepository.state.value.authStates.keys
                settingsRepository.state.value.settings.searchProviderIdsForFeature().filter(active::contains)
            },
            providerExists = { it in providerSessionRepository.state.value.authStates },
            openSearch = { navigator.navigate(AppRoute.Search) },
            onPreferencesChanged = { searchScope, selectedProviderId ->
                scope.launch {
                    settingsRepository.update {
                        it.copy(searchScope = searchScope, selectedSearchProviderId = selectedProviderId)
                    }
                }
            },
            initialState = SearchUiState(
                searchScope = initialSettings.searchScope,
                selectedSearchProviderId = initialSettings.selectedSearchProviderId,
            ),
        )
    }

    private val recognitionController by lazy {
        createRecognitionFeatureController(
            repository = audioRecognitionRepository,
            scope = scope,
            isPlaybackActive = { playbackEngine.state.value.status == PlayerStatus.Playing },
            pausePlayback = playbackEngine::pause,
            resumePlayback = playbackEngine::resume,
        )
    }

    private val providerCatalogFeatureController by lazy {
        createProviderCatalogFeatureController(
            providerRegistry = providerGraph.registry,
            providerCatalog = providerGraph.content,
            sessionRepository = providerSessionRepository,
            settingsRepository = settingsRepository,
            scope = scope,
            onHomeRefreshNeeded = { section ->
                when (section) {
                    null -> homeRefreshPort.markAllStale()
                    ProviderDisplaySection.Recommend -> homeRefreshPort.markHomeSectionStale(HomeSection.Recommend)
                    ProviderDisplaySection.Explore -> homeRefreshPort.markHomeSectionStale(HomeSection.Music)
                    ProviderDisplaySection.Mine -> homeRefreshPort.markHomeSectionStale(HomeSection.Mine)
                    ProviderDisplaySection.Search,
                    ProviderDisplaySection.Replace -> Unit
                }
            },
        )
    }

    private val playlistMigrationFeatureController: PlaylistMigrationFeatureController by lazy {
        createPlaylistMigrationFeatureController(
            storage = createIosMigrationDocumentStorage(),
            registry = providerGraph.registry,
            catalog = providerGraph.content,
            library = providerGraph.content,
            search = providerGraph.search,
            candidateProvider = createPlaylistMigrationCandidateProvider(playbackProvider),
            scope = scope,
        )
    }

    private val localPlaylistFeatureController: LocalPlaylistFeatureOwner by lazy {
        createLocalPlaylistFeatureController(
            repository = localPlaylistRepository,
            navigator = navigator,
            scope = scope,
            providers = { providerCatalogFeatureController.uiState.value.providers },
        )
    }

    private val localMusicFeatureController: LocalMusicFeatureController by lazy {
        createLocalMusicFeatureController(
            repository = localRepository,
            providerSearch = providerGraph.search,
            providerPlaybackSource = providerGraph.playbackSource,
            navigator = navigator,
            settingsRepository = settingsRepository,
            providers = { providerCatalogFeatureController.uiState.value.providers },
            isLocalMusicSectionActive = {
                val settings = settingsRepository.state.value.settings
                settings.homeSection == HomeSection.Mine && settings.mineSection == MineSection.LocalMusic
            },
            scope = scope,
            onTrackUpdated = { trackId, track -> playbackFeatureOwner.updateTrackCopies(trackId, track) },
        )
    }

    private val downloadActionPort: DownloadFeaturePorts by lazy {
        createDownloadActionPort(
            playbackProvider = playbackProvider,
            downloadRepository = downloadRepository,
            localRepository = localRepository,
            localMusicController = localMusicFeatureController,
            settingsRepository = settingsRepository,
            scope = scope,
            isLocalMusicSectionActive = {
                val settings = settingsRepository.state.value.settings
                settings.homeSection == HomeSection.Mine && settings.mineSection == MineSection.LocalMusic
            },
            qualityResolver = providerGraph.qualityResolver,
        )
    }

    private val playbackFeatureOwner: PlaybackFeatureOwner by lazy {
        createPlaybackFeatureOwner(
            playbackProvider = playbackProvider,
            playbackEngine = playbackEngine,
            playbackQueueStore = playbackQueueStore,
            settingsRepository = settingsRepository,
            downloadActions = downloadActionPort,
            scope = scope,
            openTrackDetail = trackNavigationPort::open,
            listeningHistorySink = listeningHistorySink,
        )
    }

    private val providerDetailOwners: ProviderDetailOwners by lazy {
        createProviderDetailOwners(
            providerRepository = providerGraph.content,
            playbackQueue = playbackFeatureOwner.transport,
            settingsRepository = settingsRepository,
            providerCatalog = providerCatalogFeatureController,
            navigator = navigator,
            scope = scope,
            onProviderMutation = { homeRefreshPort.refreshMine() },
        )
    }

    private val homeFeatureController: HomeFeatureController by lazy {
        createHomeFeatureController(
            providerRepository = providerGraph.content,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            playbackQueue = playbackFeatureOwner.transport,
            localPlaylist = localPlaylistFeatureController,
            localMusic = localMusicFeatureController,
            settingsRepository = settingsRepository,
            navigator = navigator,
            scope = scope,
        )
    }

    private val playlistActionPort: PlaylistActionPort by lazy {
        createPlaylistActionPort(
            providerLibrary = providerGraph.content,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            localPlaylist = localPlaylistFeatureController,
            scope = scope,
            onProviderMutation = { homeRefreshPort.refreshMine() },
        )
    }

    private val providerTrackActionPort: ProviderTrackActionPort by lazy {
        createProviderTrackActionPort(
            providerCatalogRepository = providerGraph.content,
            providerLibrary = providerGraph.content,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            searchController = searchController,
            playbackNavigation = playbackFeatureOwner.navigation,
            playbackQueue = playbackFeatureOwner.transport,
            scope = scope,
            refreshMineContent = homeRefreshPort::refreshMine,
        )
    }

    private val providerAuthFeatureController by lazy {
        createProviderAuthFeatureController(
            providerAuth = providerGraph.auth,
            sessionRepository = providerSessionRepository,
            oauthDeviceCodeAssistant = oauthDeviceCodeAssistant,
            scope = scope,
            providerName = { providerId ->
                providerCatalogFeatureController.uiState.value.availableProviders.firstOrNull { it.providerId == providerId }?.providerName
                    ?: providerId
            },
            onSessionChanged = homeRefreshPort::markAllStale,
        )
    }

    private val settingsFeatureController by lazy {
        createSettingsFeatureController(
            settingsRepository = settingsRepository,
            providerAudioQuality = providerGraph.audioQuality,
            downloadRepository = downloadRepository,
            resourceCacheRepository = resourceCacheRepository,
            localMusicController = localMusicFeatureController,
            debugLogViewerAvailable = debugLogFeatureController.isAvailable,
            navigator = navigator,
            scope = scope,
            sourceUpdateHost = sourceUpdateHost,
        )
    }

    private val onboardingFeatureController: OnboardingFeatureController by lazy {
        createOnboardingFeatureController(
            providerRegistry = providerGraph.registry,
            settingsRepository = settingsRepository,
            providerCatalog = providerCatalogFeatureController,
            scope = scope,
        )
    }

    private val sharedResourceActionPort: SharedResourceActionPort by lazy {
        createSharedResourceActionPort(
            providerRegistry = providerGraph.registry,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            searchController = searchController,
            settingsRepository = settingsRepository,
            scope = scope,
            onProviderConfigurationChanged = homeRefreshPort::markAllStale,
        )
    }

    private val playbackSession by lazy {
        createIosPlaybackRuntimeSession(
            playbackState = playbackFeatureOwner.playbackState,
            playbackEngine = playbackEngine,
            transportCoordinator = playbackFeatureOwner.transport,
            startFailureSource = playbackFeatureOwner.startFailureSource,
            scope = scope,
        )
    }

    private val searchAppPort by lazy {
        DefaultSearchAppPort(
            searchController = searchController,
            providerSessions = { providerSessionRepository.state.value },
            playbackQueue = playbackFeatureOwner.transport,
            downloads = downloadActionPort,
            playlists = playlistActionPort,
            providerTrackActions = providerTrackActionPort,
            navigator = navigator,
        )
    }

    private val recognitionAppPort by lazy {
        DefaultRecognitionAppPort(
            isProviderEnabled = { id -> providerSessionRepository.state.value.providers.any { it.providerId == id } },
            loadTrackDetail = providerGraph.content::trackDetail,
            navigator = navigator,
        )
    }

    private val playbackPresentationPort by lazy {
        DefaultPlaybackPresentationPort(playbackEngine, playbackFeatureOwner.transport, settingsRepository, scope)
    }

    val appUiGraph: AppUiGraph by lazy {
        createAppUiGraph(
            playbackSession = playbackSession,
            playbackNavigationPort = playbackFeatureOwner.navigation,
            playbackPresentationPort = playbackPresentationPort,
            playbackQueueUiPort = playbackFeatureOwner.transport,
            playbackSleepTimerPort = playbackFeatureOwner.sleepTimer,
            downloadActionPort = downloadActionPort,
            downloadQuality = downloadActionPort,
            playlistActionPort = playlistActionPort,
            providerTrackActionPort = providerTrackActionPort,
            localMusicActionPort = localMusicFeatureController,
            playbackLyricsPort = playbackFeatureOwner.lyrics,
            replacementActionPort = playbackFeatureOwner.replacement,
            debugLogFeatureController = debugLogFeatureController,
            providerCatalogFeatureController = providerCatalogFeatureController,
            providerAuthFeatureController = providerAuthFeatureController,
            settingsFeatureController = settingsFeatureController,
            onboardingFeatureController = onboardingFeatureController,
            providerDetailOwners = providerDetailOwners,
            localMusicFeatureController = localMusicFeatureController,
            localPlaylistFeatureController = localPlaylistFeatureController,
            homeFeatureController = homeFeatureController,
            sharedResourceActionPort = sharedResourceActionPort,
            searchController = searchController,
            searchAppPort = searchAppPort,
            recognitionController = recognitionController,
            recognitionAppPort = recognitionAppPort,
            playlistMigrationFeatureController = playlistMigrationFeatureController,
        )
    }

    private val appBackCoordinator: AppBackCoordinator by lazy {
        createAppBackCoordinator(
            navigator = navigator,
            playbackNavigationPort = playbackFeatureOwner.navigation,
            playlistActionPort = playlistActionPort,
            providerTrackActionPort = providerTrackActionPort,
            localMusicFeatureController = localMusicFeatureController,
            providerDetailOwners = providerDetailOwners,
            searchAppPort = searchAppPort,
            recognitionController = recognitionController,
            settingsFeatureController = settingsFeatureController,
            localPlaylistFeatureController = localPlaylistFeatureController,
        )
    }

    val appViewModel = FuoAppViewModel(
        settingsRepository = settingsRepository,
        navigator = navigator,
        recognitionController = recognitionController,
        backCoordinator = appBackCoordinator,
    )

    var hasMicrophonePermission by mutableStateOf(audioRecognitionOutput.hasPermission())
        private set

    val hasAudioPermission: Boolean get() = localRepository.hasPermission

    fun requestAudioPermission() {
        localRepository.requestPermission {
            localMusicFeatureController.onPermissionChange(true)
            localMusicFeatureController.refresh()
        }
    }

    fun requestMicrophonePermission() {
        audioRecognitionOutput.requestPermission { granted ->
            hasMicrophonePermission = granted
            appViewModel.onMicrophonePermissionChange(granted)
        }
    }

    fun openProviderWebLogin(provider: ProviderInfo) {
        val loginConfig = provider.loginConfig ?: return
        val groupsJson = loginConfig.cookieKeyGroups.joinToString(prefix = "[", postfix = "]") { group ->
            group.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
        }
        webLoginOutput.open(provider.providerId, provider.providerName, loginConfig.loginUrl, groupsJson) { cookiesJson ->
            if (!cookiesJson.isNullOrBlank()) providerAuthFeatureController.loginWithCookies(provider.providerId, cookiesJson)
        }
    }

    fun logoutProvider(provider: ProviderInfo) {
        webLoginOutput.clear()
        providerAuthFeatureController.logout(provider.providerId)
    }
}
