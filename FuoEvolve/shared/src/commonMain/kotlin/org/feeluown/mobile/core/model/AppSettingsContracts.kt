package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class LyricFontSize(
    val label: String,
) {
    Small("小"),
    Medium("中"),
    Large("大"),
}

@Serializable
enum class ThemeMode(
    val label: String,
) {
    System("跟随系统"),
    Light("亮色"),
    Dark("暗色"),
}

@Serializable
enum class ThemeColorScheme(
    val label: String,
) {
    Dynamic("系统动态"),
    ExpressiveDefault("Expressive 默认"),
    FuoGreen("青绿"),
    OceanBlue("海蓝"),
    Violet("紫罗兰"),
    Rose("蔷薇"),
    Amber("琥珀"),
}

@Serializable
enum class ThemePaletteStyle(
    val label: String,
) {
    TonalSpot("Tonal Spot"),
    Neutral("Neutral"),
    Vibrant("Vibrant"),
    Expressive("Expressive"),
    Rainbow("Rainbow"),
    FruitSalad("FruitSalad"),
    Monochrome("Monochrome"),
    Fidelity("Fidelity"),
    Content("Content"),
}

@Serializable
enum class ThemeColorSpec(
    val label: String,
) {
    Material3_2021("Material 3 (2021)"),
    Expressive_2025("Expressive (2025)"),
}

@Serializable
enum class DesktopVideoDecodeMode(
    val label: String,
    val description: String,
) {
    HardwareCompatible("兼容模式", "默认模式，优先保证画面正常"),
    HardwareDirect("性能模式", "减少画面复制，部分设备可能不兼容"),
    Software("软件解码", "使用 CPU 解码，用于硬件解码绿屏或兼容性问题"),
}

@Serializable
data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val homeSection: HomeSection = HomeSection.Recommend,
    val mineSection: MineSection = MineSection.Playlists,
    val playlistFilter: PlaylistFilter = PlaylistFilter.All,
    val localMusicViewMode: LocalMusicViewMode = LocalMusicViewMode.All,
    val excludedLocalMusicDirectoryIds: Set<String> = emptySet(),
    val includedLocalMusicDirectoryIds: Set<String> = emptySet(),
    val localMusicMinDurationSeconds: Int = DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS,
    val searchScope: SearchScope = SearchScope.All,
    val selectedSearchProviderId: String? = null,
    val selectedSettingsProviderId: String? = null,
    val providerLoginMode: ProviderLoginMode = ProviderLoginMode.WebView,
    val providerCookieInputs: Map<String, String> = emptyMap(),
    val providerHeaderInputs: Map<String, ProviderHeaderInput> = emptyMap(),
    val enabledProviderIds: Set<String> = DEFAULT_ENABLED_PROVIDER_IDS,
    val providerOrderIds: List<String> = DEFAULT_PROVIDER_ORDER_IDS,
    val searchProviderIds: Set<String> = emptySet(),
    val recommendProviderIds: Set<String> = emptySet(),
    val exploreProviderIds: Set<String> = emptySet(),
    val mineProviderIds: Set<String> = emptySet(),
    val playbackReportingProviderIds: Set<String> = emptySet(),
    val audioCacheLimitMb: Int = DEFAULT_AUDIO_CACHE_LIMIT_MB,
    val imageCacheLimitMb: Int = DEFAULT_IMAGE_CACHE_LIMIT_MB,
    val downloadParallelism: Int = DEFAULT_DOWNLOAD_PARALLELISM,
    /** 播放/下载时自动到开放曲库寻找 FLAC 候选并执行质量筛查。 */
    val losslessEnhanceEnabled: Boolean = true,
    /** 播放当前歌曲时提前准备下一首的无损音源，避免每首都等待。 */
    val losslessPrefetchEnabled: Boolean = true,
    /** 仅在 Wi-Fi 下预取，避免移动网络消耗大量流量。 */
    val losslessPrefetchWifiOnly: Boolean = true,
    /** 首次播放时等待无损音源的最长秒数，超时先用原音源并后台继续。 */
    val losslessPlaybackWaitSeconds: Int = 10,
    /** Jamendo 开放曲库的 client_id；内置默认值，留空保存则停用该来源。 */
    val jamendoClientId: String = DEFAULT_JAMENDO_CLIENT_ID,
    /** 是否启用 Navidrome / Subsonic 自建音乐源。 */
    val subsonicEnabled: Boolean = false,
    /** Subsonic 服务地址，例如 https://demo.navidrome.org 。 */
    val subsonicServerUrl: String = "",
    val subsonicUsername: String = "",
    val subsonicPassword: String = "",
    val wifiAudioQualityPolicy: AudioQualityPolicy = DEFAULT_WIFI_AUDIO_QUALITY_POLICY,
    val cellularAudioQualityPolicy: AudioQualityPolicy = DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY,
    val unavailablePlaybackPolicy: UnavailablePlaybackPolicy = DEFAULT_UNAVAILABLE_PLAYBACK_POLICY,
    val smartReplacementProviderIds: Set<String> = emptySet(),
    val smartReplacementMinScore: Double = DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
    val smartReplacementSelections: Map<String, SmartReplacementSelection> = emptyMap(),
    val lyricsAssociations: Map<String, String> = emptyMap(),
    val lyricsAlignmentOffsetsMs: Map<String, Long> = emptyMap(),
    val pauseOnOtherAppPlayback: Boolean = DEFAULT_PAUSE_ON_OTHER_APP_PLAYBACK,
    val desktopVideoDecodeMode: DesktopVideoDecodeMode = DesktopVideoDecodeMode.HardwareCompatible,
    val lyricFontSize: LyricFontSize = LyricFontSize.Small,
    val statusBarLyricsEnabled: Boolean = false,
    val bydInstrumentLyricsEnabled: Boolean = false,
    val bluetoothLyricsEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val themeColorScheme: ThemeColorScheme = ThemeColorScheme.Dynamic,
    val themePaletteStyle: ThemePaletteStyle = ThemePaletteStyle.Expressive,
    val themeColorSpec: ThemeColorSpec = ThemeColorSpec.Expressive_2025,
    val dynamicCoverColorEnabled: Boolean = false,
    val waveformAnimationDisabled: Boolean = false,
    val appUpdateChannel: AppUpdateChannel = AppUpdateChannel.Stable,
    val autoCheckAppUpdates: Boolean = true,
    /** 是否允许自动检查受控音源目录并安装通过门禁的脚本更新。 */
    val sourceAutoUpdateEnabled: Boolean = DEFAULT_SOURCE_AUTO_UPDATE_ENABLED,
    /** 正常检查间隔（小时）；失败时另有 1/4/12/24 小时的退避。 */
    val sourceUpdateIntervalHours: Int = DEFAULT_SOURCE_UPDATE_INTERVAL_HOURS,
    /** 目录拉取策略。首版只有 hybrid：主地址 → 镜像 → 上次成功缓存。 */
    val sourceMirrorPolicy: String = DEFAULT_SOURCE_MIRROR_POLICY,
    /**
     * 最近一次成功加载的目录版本，仅用于设置页展示。
     * 真正的安装状态以 `sources/index.json` 为准，这里不回读做决策。
     */
    val sourceCatalogVersion: Long? = null,
    /** 已安装脚本版本快照，同样只作展示镜像，不参与安装判断。 */
    val installedScriptVersions: Map<String, String> = emptyMap(),
    /** 用户手动隔离的脚本 ID：即使目录重新批准也不会自动启用。 */
    val quarantinedScriptIds: Set<String> = emptySet(),
    val playlistPlaybackStatsVersion: Int = 0,
    val playlistPlaybackStats: Map<String, PlaylistPlaybackStat> = emptyMap(),
    /**
     * 音源默认集迁移版本：老用户升级时把本版本新增的默认音源补入 enabledProviderIds，
     * 只补一次；之后用户可自由关闭。见 migrateProviderDefaults。
     */
    val providerDefaultsMigrationVersion: Int = 0,
)

@Serializable
data class PlaylistPlaybackStat(
    val playCount: Long = 0,
    val lastPlayedAtMillis: Long = 0,
)

data class YtMusicOAuthFlowUiState(
    val userCode: String,
    val verificationUrl: String,
    val verificationUrlWithCode: String,
    val statusMessage: String = "请在浏览器中完成授权",
    val browserOpened: Boolean = false,
)

const val DEFAULT_AUDIO_CACHE_LIMIT_MB = 512
const val DEFAULT_IMAGE_CACHE_LIMIT_MB = 128
const val DEFAULT_DOWNLOAD_PARALLELISM = 2

/** 内置 Jamendo client_id；个人使用，留空保存可停用该来源。 */
const val DEFAULT_JAMENDO_CLIENT_ID = "5ace5988"

const val DEFAULT_SOURCE_AUTO_UPDATE_ENABLED = true
const val DEFAULT_SOURCE_UPDATE_INTERVAL_HOURS = 24
const val DEFAULT_SOURCE_MIRROR_POLICY = "hybrid"
/** 允许的检查间隔范围：小于 1 小时会触发上游限流，超过一周等于放弃更新。 */
const val MIN_SOURCE_UPDATE_INTERVAL_HOURS = 1
const val MAX_SOURCE_UPDATE_INTERVAL_HOURS = 168

/**
 * Default sources for a fresh installation. QQ Music and Bilibili complement NetEase
 * for Chinese-language discovery; YouTube Music remains opt-in because availability
 * and network reachability vary substantially by region.
 *
 * "modeb" is the personal multi-source router (Radio Paradise FLAC / Radio Browser live radios).
 * It is enabled by default so the local build can search "flac"/"电台" and play real streams
 * out of the box; on ordinary keyword searches its search() returns no tracks and makes no
 * network call, so it does not pollute normal discovery results.
 *
 * "lxscript" is deliberately absent from the default set: with an empty catalog it can only
 * return nothing, and showing an always-empty source in search would be noise. It is switched on
 * automatically the first time the controlled catalog actually installs an approved script
 * (see SourceUpdateHost).
 *
 * "jamendo" is now enabled by default (product decision 2026-11-19): the app must be useful
 * out of the box for family members without visiting Settings. Its results are clearly labelled
 * "开放曲库" in search/detail/player (see sourceLabel / TrackLicenseInfo), so open-catalog tracks
 * are never presented as the commercial original. Users can still disable it in Settings.
 *
 * "ytmusic" stays opt-in because availability and network reachability vary by region.
 */
val DEFAULT_ENABLED_PROVIDER_IDS = setOf("netease", "qqmusic", "bilibili", "modeb", "jamendo")
val DEFAULT_PROVIDER_ORDER_IDS = listOf("netease", "qqmusic", "bilibili", "lxscript", "ytmusic", "modeb", "jamendo")

/**
 * One-time provider-defaults migration. When an upgrading user's persisted
 * providerDefaultsMigrationVersion is below [CURRENT_PROVIDER_DEFAULTS_MIGRATION_VERSION],
 * the newly-defaulted [PROVIDER_DEFAULTS_ADDED_IN_V1] ids are added to their enabled set once,
 * then the version is bumped and written back. New installs already get the full default set,
 * so they skip this. The user remains free to disable any provider afterwards.
 */
const val CURRENT_PROVIDER_DEFAULTS_MIGRATION_VERSION = 1
val PROVIDER_DEFAULTS_ADDED_IN_V1 = setOf("jamendo")

/** Adds newly-defaulted providers to an existing set, exactly once (idempotent). */
fun migrateProviderDefaults(
    enabledProviderIds: Set<String>,
    fromVersion: Int,
): MigratedProviderDefaults {
    if (fromVersion >= CURRENT_PROVIDER_DEFAULTS_MIGRATION_VERSION) {
        return MigratedProviderDefaults(enabledProviderIds, fromVersion)
    }
    return MigratedProviderDefaults(
        enabledProviderIds = enabledProviderIds + PROVIDER_DEFAULTS_ADDED_IN_V1,
        migrationVersion = CURRENT_PROVIDER_DEFAULTS_MIGRATION_VERSION,
    )
}

data class MigratedProviderDefaults(
    val enabledProviderIds: Set<String>,
    val migrationVersion: Int,
)

data class SettingsState(
    val isLoaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val errorMessage: String? = null,
)

interface AppSettingsRepository {
    val state: StateFlow<SettingsState>
    suspend fun awaitSettings(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
    suspend fun updateThemePaletteStyle(value: ThemePaletteStyle) {
        update { settings -> settings.copy(themePaletteStyle = value) }
    }
    suspend fun updateThemeColorSpec(value: ThemeColorSpec) {
        update { settings -> settings.copy(themeColorSpec = value) }
    }
}

class InMemoryAppSettingsRepository(
    initialSettings: AppSettings = AppSettings(),
) : AppSettingsRepository {
    private val mutableState = MutableStateFlow(SettingsState(isLoaded = true, settings = initialSettings))
    override val state: StateFlow<SettingsState> = mutableState

    override suspend fun awaitSettings(): AppSettings = mutableState.value.settings

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        val current = mutableState.value.settings
        val transformed = transform(current)
        mutableState.value = SettingsState(
            isLoaded = true,
            settings = transformed.copy(
                themePaletteStyle = current.themePaletteStyle,
                themeColorSpec = current.themeColorSpec,
            ),
        )
    }

    override suspend fun updateThemePaletteStyle(value: ThemePaletteStyle) {
        val current = mutableState.value.settings
        mutableState.value = SettingsState(
            isLoaded = true,
            settings = current.copy(themePaletteStyle = value),
        )
    }

    override suspend fun updateThemeColorSpec(value: ThemeColorSpec) {
        val current = mutableState.value.settings
        mutableState.value = SettingsState(
            isLoaded = true,
            settings = current.copy(themeColorSpec = value),
        )
    }
}
