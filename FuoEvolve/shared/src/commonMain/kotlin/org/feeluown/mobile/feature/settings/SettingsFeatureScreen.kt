package org.feeluown.mobile

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import org.feeluown.mobile.LocalPlaybackQueueUiPort
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.TrackSourceType
import org.feeluown.mobile.source.InstalledScriptStatus
import org.feeluown.mobile.sourceupdate.sourceUpdateNowMillis
import kotlin.math.abs

@Serializable
private enum class FeatureSettingsCategory(
    val title: String,
    val supportingText: String,
) {
    Sources("音源与账号", "音源启用、排序、登录与显示范围"),
    Playback("播放与音质", "网络音质、播放策略与智能替换"),
    Appearance("外观与显示", "主题、歌词字号与歌词同步"),
    LocalMusic("本地音乐", "媒体目录扫描与短音频过滤"),
    Storage("下载与存储", "下载行为、缓存上限与清理"),
    Updates("应用更新", "Stable / Canary、自动检查与安装"),
    About("关于", "版本、项目链接与诊断信息"),
}

@Serializable
private sealed interface FeatureSettingsRoute : NavKey {
    @Serializable data object Main : FeatureSettingsRoute
    @Serializable data class Category(val category: FeatureSettingsCategory) : FeatureSettingsRoute
    @Serializable data object Theme : FeatureSettingsRoute
    @Serializable data object CredentialBackup : FeatureSettingsRoute
    @Serializable data class Provider(val providerId: String) : FeatureSettingsRoute
}

private fun settingsPageTransition(
    initialOffsetX: (Int) -> Int,
    targetOffsetX: (Int) -> Int,
): ContentTransform = (
    slideInHorizontally(
        initialOffsetX = initialOffsetX,
        animationSpec = tween(FuoMotion.pageTransitionMillis),
    ) + fadeIn(animationSpec = tween(FuoMotion.pageFadeMillis))
    ) togetherWith (
    slideOutHorizontally(
        targetOffsetX = targetOffsetX,
        animationSpec = tween(FuoMotion.pageTransitionMillis),
    ) + fadeOut(animationSpec = tween(FuoMotion.pageFadeMillis))
    )

private fun settingsForwardPageTransition(): ContentTransform =
    settingsPageTransition(initialOffsetX = { it }, targetOffsetX = { -it })

private fun settingsPopPageTransition(): ContentTransform =
    settingsPageTransition(initialOffsetX = { -it }, targetOffsetX = { it })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsFeatureScreen(
    settingsController: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
    providerAuth: ProviderAuthFeatureController,
    appVersionInfo: String?,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)? = null,
    onImportYtmusicOAuthFile: (() -> Unit)? = null,
    onStartYtmusicOAuth: (() -> Unit)? = null,
    desktopVideoSettingsAvailable: Boolean = false,
) {
    val settingsState by settingsController.uiState.collectAsStateWithLifecycle()
    val catalogState by providerCatalog.uiState.collectAsStateWithLifecycle()
    val authState by providerAuth.uiState.collectAsStateWithLifecycle()
    val credentialBackupActions = LocalProviderCredentialBackupActions.current
    val layoutInfo = LocalAppLayoutInfo.current
    var backStack by remember { mutableStateOf<List<FeatureSettingsRoute>>(listOf(FeatureSettingsRoute.Main)) }
    var wideSelection by remember { mutableStateOf(FeatureSettingsCategory.Sources) }

    fun push(route: FeatureSettingsRoute) {
        if (backStack.lastOrNull() != route) backStack = backStack + route
    }

    fun pop() {
        if (backStack.size > 1) backStack = backStack.dropLast(1) else settingsController.close()
    }

    fun openCategory(category: FeatureSettingsCategory) {
        if (layoutInfo.useWideLayout && backStack.lastOrNull() == FeatureSettingsRoute.Main) {
            wideSelection = category
        } else {
            push(FeatureSettingsRoute.Category(category))
        }
    }

    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = ::pop,
        transitionSpec = { settingsForwardPageTransition() },
        popTransitionSpec = { settingsPopPageTransition() },
        entryProvider = { route ->
            NavEntry(key = route) {
                when (route) {
                    FeatureSettingsRoute.Main -> SettingsMainPage(
                        settings = settingsState,
                        catalog = catalogState,
                        appVersionInfo = appVersionInfo,
                        useWideLayout = layoutInfo.useWideLayout,
                        wideSelection = wideSelection,
                        onSelectCategory = { category ->
                            wideSelection = category
                            openCategory(category)
                        },
                        onOpenTheme = { push(FeatureSettingsRoute.Theme) },
                        onOpenProvider = { push(FeatureSettingsRoute.Provider(it.providerId)) },
                        onOpenCredentialBackup = { push(FeatureSettingsRoute.CredentialBackup) },
                        onBack = settingsController::close,
                        settingsController = settingsController,
                        providerCatalog = providerCatalog,
                        desktopVideoSettingsAvailable = desktopVideoSettingsAvailable,
                    )
                    is FeatureSettingsRoute.Category -> SettingsCategoryPage(
                        category = route.category,
                        settings = settingsState,
                        catalog = catalogState,
                        appVersionInfo = appVersionInfo,
                        settingsController = settingsController,
                        providerCatalog = providerCatalog,
                        onOpenTheme = { push(FeatureSettingsRoute.Theme) },
                        onOpenProvider = { push(FeatureSettingsRoute.Provider(it.providerId)) },
                        onOpenCredentialBackup = { push(FeatureSettingsRoute.CredentialBackup) },
                        onBack = ::pop,
                        desktopVideoSettingsAvailable = desktopVideoSettingsAvailable,
                    )
                    FeatureSettingsRoute.Theme -> SettingsScaffold(
                        title = "主题设置",
                        onBack = ::pop,
                        isLoading = settingsState.isBusy || catalogState.isLoading,
                    ) { bodyModifier ->
                        SettingsDetailColumn(modifier = bodyModifier) {
                            ThemeSettingsContent(
                                state = settingsState,
                                controller = settingsController,
                            )
                        }
                    }
                    FeatureSettingsRoute.CredentialBackup -> SettingsScaffold(
                        title = "登录凭证",
                        onBack = ::pop,
                        isLoading = catalogState.isLoading,
                    ) { bodyModifier ->
                        SettingsDetailColumn(modifier = bodyModifier) {
                            ProviderCredentialBackupSettings(
                                state = catalogState,
                                actions = credentialBackupActions,
                            )
                        }
                    }
                    is FeatureSettingsRoute.Provider -> {
                        val provider = catalogState.availableProviders.firstOrNull { it.providerId == route.providerId }
                            ?: catalogState.providers.firstOrNull { it.providerId == route.providerId }
                        SettingsScaffold(
                            title = provider?.providerName ?: "音源账号",
                            onBack = ::pop,
                            isLoading = settingsState.isBusy || catalogState.isLoading,
                        ) { bodyModifier ->
                            if (provider == null) {
                                Box(bodyModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("该音源当前不可用", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                SettingsDetailColumn(modifier = bodyModifier) {
                                    ProviderDetailSettingsExpressive(
                                        provider = provider,
                                        catalog = catalogState,
                                        catalogController = providerCatalog,
                                        settings = settingsState,
                                        settingsController = settingsController,
                                        authController = providerAuth,
                                        authUiState = authState,
                                        onOpenProviderWebLogin = onOpenProviderWebLogin,
                                        onLogoutProvider = onLogoutProvider,
                                        onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
                                        onImportYtmusicOAuthFile = onImportYtmusicOAuthFile,
                                        onStartYtmusicOAuth = onStartYtmusicOAuth,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
    PlatformBackHandler(
        enabled = backStack.size > 1,
        onBack = ::pop,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsMainPage(
    settings: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    appVersionInfo: String?,
    useWideLayout: Boolean,
    wideSelection: FeatureSettingsCategory,
    onSelectCategory: (FeatureSettingsCategory) -> Unit,
    onOpenTheme: () -> Unit,
    onOpenProvider: (ProviderInfo) -> Unit,
    onOpenCredentialBackup: () -> Unit,
    onBack: () -> Unit,
    settingsController: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
    desktopVideoSettingsAvailable: Boolean,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (useWideLayout) {
                TopAppBar(
                    title = { Text("设置") },
                    navigationIcon = { SettingsBackButton(onBack) },
                    colors = settingsTopAppBarColors(),
                )
            } else {
                LargeTopAppBar(
                    title = { Text("设置") },
                    navigationIcon = { SettingsBackButton(onBack) },
                    colors = settingsTopAppBarColors(),
                )
            }
        },
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            LoadingIndicator(settings.isBusy || catalog.isLoading)
            if (useWideLayout) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = FuoSpacing.lg, vertical = FuoSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
                ) {
                    SettingsCategoryPane(
                        modifier = Modifier.width(320.dp),
                        selected = wideSelection,
                        settings = settings,
                        catalog = catalog,
                        appVersionInfo = appVersionInfo,
                        onSelect = onSelectCategory,
                    )
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        SettingsCategoryDetail(
                            category = wideSelection,
                            settings = settings,
                            catalog = catalog,
                            appVersionInfo = appVersionInfo,
                            showHeading = true,
                            settingsController = settingsController,
                            providerCatalog = providerCatalog,
                            onOpenTheme = onOpenTheme,
                            onOpenProvider = onOpenProvider,
                            onOpenCredentialBackup = onOpenCredentialBackup,
                            desktopVideoSettingsAvailable = desktopVideoSettingsAvailable,
                        )
                    }
                }
            } else {
                SettingsOverview(
                    modifier = Modifier.fillMaxSize(),
                    settings = settings,
                    catalog = catalog,
                    appVersionInfo = appVersionInfo,
                    onSelect = onSelectCategory,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCategoryPage(
    category: FeatureSettingsCategory,
    settings: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    appVersionInfo: String?,
    settingsController: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
    onOpenTheme: () -> Unit,
    onOpenProvider: (ProviderInfo) -> Unit,
    onOpenCredentialBackup: () -> Unit,
    onBack: () -> Unit,
    desktopVideoSettingsAvailable: Boolean,
) {
    SettingsScaffold(
        title = category.title,
        onBack = onBack,
        isLoading = settings.isBusy || catalog.isLoading,
    ) { bodyModifier ->
        SettingsCategoryDetail(
            category = category,
            settings = settings,
            catalog = catalog,
            appVersionInfo = appVersionInfo,
            showHeading = false,
            settingsController = settingsController,
            providerCatalog = providerCatalog,
            onOpenTheme = onOpenTheme,
            onOpenProvider = onOpenProvider,
            onOpenCredentialBackup = onOpenCredentialBackup,
            desktopVideoSettingsAvailable = desktopVideoSettingsAvailable,
            modifier = bodyModifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    isLoading: Boolean,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { SettingsBackButton(onBack) },
                colors = settingsTopAppBarColors(),
            )
        },
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            LoadingIndicator(isLoading)
            content(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun SettingsBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun settingsTopAppBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surface,
)

@Composable
private fun SettingsOverview(
    settings: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    appVersionInfo: String?,
    onSelect: (FeatureSettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsDetailColumn(modifier = modifier) {
        SettingsGroup {
            FeatureSettingsCategory.entries.forEachIndexed { index, category ->
                SettingsRow(
                    title = category.title,
                    supportingText = categorySummary(category, settings, catalog, appVersionInfo),
                    leadingContent = {
                        Icon(
                            imageVector = categoryIcon(category),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { onSelect(category) },
                )
                if (index < FeatureSettingsCategory.entries.lastIndex) SettingsDivider()
            }
        }
    }
}

@Composable
private fun SettingsCategoryPane(
    selected: FeatureSettingsCategory,
    settings: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    appVersionInfo: String?,
    onSelect: (FeatureSettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = FuoSpacing.sm),
        ) {
            FeatureSettingsCategory.entries.forEach { category ->
                SettingsRow(
                    title = category.title,
                    supportingText = categorySummary(category, settings, catalog, appVersionInfo),
                    selected = selected == category,
                    leadingContent = { Icon(categoryIcon(category), contentDescription = null) },
                    onClick = { onSelect(category) },
                )
            }
        }
    }
}

@Composable
private fun SettingsCategoryDetail(
    category: FeatureSettingsCategory,
    settings: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    appVersionInfo: String?,
    showHeading: Boolean,
    settingsController: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
    onOpenTheme: () -> Unit,
    onOpenProvider: (ProviderInfo) -> Unit,
    onOpenCredentialBackup: () -> Unit,
    desktopVideoSettingsAvailable: Boolean,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    SettingsDetailColumn(modifier = modifier) {
        if (showHeading) {
            Column(verticalArrangement = Arrangement.spacedBy(FuoSpacing.xs)) {
                Text(category.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    category.supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (category) {
            FeatureSettingsCategory.Sources -> ProviderCatalogSettingsExpressive(
                state = catalog,
                controller = providerCatalog,
                onOpenProvider = onOpenProvider,
                onOpenCredentialBackup = onOpenCredentialBackup,
            )
            FeatureSettingsCategory.Playback -> PlaybackFeatureSettings(
                state = settings,
                catalog = catalog,
                settingsController = settingsController,
                providerCatalog = providerCatalog,
                desktopVideoSettingsAvailable = desktopVideoSettingsAvailable,
            )
            FeatureSettingsCategory.Appearance -> AppearanceFeatureSettings(
                state = settings,
                controller = settingsController,
                onOpenTheme = onOpenTheme,
            )
            FeatureSettingsCategory.LocalMusic -> LocalMusicFeatureSettings(settings, settingsController)
            FeatureSettingsCategory.Storage -> StorageFeatureSettings(settings, settingsController)
            FeatureSettingsCategory.Updates -> AppUpdateFeatureSettings(settings, settingsController)
            FeatureSettingsCategory.About -> AboutFeatureSettings(settings, settingsController, appVersionInfo)
        }
    }
}

@Composable
private fun SettingsDetailColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(FuoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
            content = content,
        )
    }
}

@Composable
private fun ProviderCredentialBackupSettings(
    state: ProviderCatalogUiState,
    actions: ProviderCredentialBackupActions,
) {
    val orderedLoggedInProviders = remember(state.availableProviders, state.providerOrderIds, state.sessions.authStates) {
        val order = state.providerOrderIds.withIndex().associate { it.value to it.index }
        state.availableProviders
            .filter { provider -> state.sessions.authStates[provider.providerId]?.isLoggedIn == true }
            .sortedBy { provider -> order[provider.providerId] ?: Int.MAX_VALUE }
    }
    val exportAll = actions.exportAll
    val exportProvider = actions.exportProvider
    val importBackup = actions.importBackup

    SettingsGroup(title = "备份") {
        SettingsRow(
            title = "导出全部",
            supportingText = orderedLoggedInProviders.takeIf { it.isNotEmpty() }?.let { "${it.size} 个音源" },
            enabled = exportAll != null && orderedLoggedInProviders.isNotEmpty(),
            leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = exportAll,
        )
    }

    SettingsGroup(title = "单独导出") {
        if (orderedLoggedInProviders.isEmpty()) {
            SettingsRow(title = "暂无已登录音源")
        } else {
            orderedLoggedInProviders.forEachIndexed { index, provider ->
                val auth = state.sessions.authStates[provider.providerId]
                SettingsRow(
                    title = provider.providerName,
                    supportingText = auth?.userName?.takeIf { it.isNotBlank() },
                    enabled = exportProvider != null,
                    leadingContent = { Icon(Icons.Filled.ManageAccounts, contentDescription = null) },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = exportProvider?.let { action -> { action(provider) } },
                )
                if (index < orderedLoggedInProviders.lastIndex) SettingsDivider()
            }
        }
    }

    SettingsGroup(title = "恢复") {
        SettingsRow(
            title = "从文件恢复",
            enabled = importBackup != null,
            leadingContent = { Icon(Icons.Filled.Settings, contentDescription = null) },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = importBackup,
        )
    }
}

@Composable
private fun PlaybackFeatureSettings(
    state: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    settingsController: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
    desktopVideoSettingsAvailable: Boolean,
) {
    val settings = state.settings
    val busy = state.isBusy || catalog.isLoading
    SettingsGroup(title = "音质") {
        SettingsChoiceRow(
            title = "Wi‑Fi",
            supportingText = "连接 Wi‑Fi 时优先使用的音质",
            value = settings.wifiAudioQualityPolicy.label,
            options = AudioQualityPolicy.entries,
            selected = settings.wifiAudioQualityPolicy,
            optionLabel = AudioQualityPolicy::label,
            enabled = !busy,
            onSelect = settingsController::setWifiAudioQualityPolicy,
        )
        SettingsDivider()
        SettingsChoiceRow(
            title = "蜂窝网络",
            supportingText = "使用移动数据时优先使用的音质",
            value = settings.cellularAudioQualityPolicy.label,
            options = AudioQualityPolicy.entries,
            selected = settings.cellularAudioQualityPolicy,
            optionLabel = AudioQualityPolicy::label,
            enabled = !busy,
            onSelect = settingsController::setCellularAudioQualityPolicy,
        )
    }

    SettingsGroup(title = "无损增强") {
        SettingsToggleRow(
            title = "自动寻找 FLAC 高音质源",
            supportingText = "音源不是已识别的无损格式时，依次到自建 Navidrome/Subsonic、Jamendo、Internet Archive 查找 FLAC；容器与频谱检查仅作质量筛查，需要联网",
            checked = settings.losslessEnhanceEnabled,
            enabled = !busy,
        ) { enabled -> settingsController.setLosslessEnhanceEnabled(enabled) }
        SettingsDivider(startPadding = FuoSpacing.lg)
        SettingsToggleRow(
            title = "播放时预取下一首",
            supportingText = "听当前歌曲时提前准备下一首 FLAC 候选，连播时尽量减少等待",
            checked = settings.losslessPrefetchEnabled,
            enabled = !busy && settings.losslessEnhanceEnabled,
        ) { enabled -> settingsController.setLosslessPrefetchEnabled(enabled) }
        SettingsDivider(startPadding = FuoSpacing.lg)
        SettingsToggleRow(
            title = "仅 Wi-Fi 下预取",
            supportingText = "避免移动网络下自动下载整首无损；关闭后移动网络也会预取",
            checked = settings.losslessPrefetchWifiOnly,
            enabled = !busy && settings.losslessEnhanceEnabled && settings.losslessPrefetchEnabled,
        ) { enabled -> settingsController.setLosslessPrefetchWifiOnly(enabled) }
        SettingsDivider(startPadding = FuoSpacing.lg)
        SettingsChoiceRow(
            title = "首次等待上限",
            supportingText = "超过该时间先用原音源播放，后台继续下载，下次播放直接秒开",
            value = "${settings.losslessPlaybackWaitSeconds} 秒",
            options = listOf(5, 10, 15, 20),
            selected = settings.losslessPlaybackWaitSeconds,
            optionLabel = { "${it} 秒" },
            enabled = !busy && settings.losslessEnhanceEnabled,
            onSelect = { seconds ->
                settingsController.setLosslessPlaybackWaitSeconds(seconds)
            },
        )
        SettingsDivider(startPadding = FuoSpacing.lg)
        JamendoSourceRow(
            clientId = settings.jamendoClientId,
            enabled = !busy && settings.losslessEnhanceEnabled,
            onSave = { settingsController.setJamendoClientId(it) },
        )
        SettingsDivider(startPadding = FuoSpacing.lg)
        SubsonicSourceRow(
            serverUrl = settings.subsonicServerUrl,
            username = settings.subsonicUsername,
            password = settings.subsonicPassword,
            enabled = !busy && settings.losslessEnhanceEnabled,
            onSave = { url, user, password ->
                settingsController.setSubsonicSource(url, user, password)
            },
        )
    }

    SourceCatalogSettings(
        state = state.sourceUpdate,
        controller = settingsController,
    )

    SettingsGroup(title = "播放策略") {
        SettingsToggleRow(
            title = "其他应用播放时自动暂停",
            supportingText = "检测到其他应用开始播放时暂停当前播放",
            checked = settings.pauseOnOtherAppPlayback,
            enabled = !busy,
        ) { enabled -> settingsController.update { it.copy(pauseOnOtherAppPlayback = enabled) } }
        SettingsDivider(startPadding = FuoSpacing.lg)
        Column(
            modifier = Modifier.padding(FuoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
        ) {
            Text("资源不可用时", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                UnavailablePlaybackPolicy.entries.forEachIndexed { index, policy ->
                    SegmentedButton(
                        selected = settings.unavailablePlaybackPolicy == policy,
                        enabled = !busy,
                        onClick = { settingsController.update { it.copy(unavailablePlaybackPolicy = policy) } },
                        shape = SegmentedButtonDefaults.itemShape(index, UnavailablePlaybackPolicy.entries.size),
                        colors = settingsSegmentedButtonColors(),
                    ) { Text(policy.label) }
                }
            }
        }
    }

    CustomPlaybackUrlSettings(busy = busy)

    if (desktopVideoSettingsAvailable) {
        SettingsGroup(title = "视频播放") {
            SettingsChoiceRow(
                title = "播放模式",
                supportingText = "${settings.desktopVideoDecodeMode.description}；重新打开视频后生效",
                value = settings.desktopVideoDecodeMode.label,
                options = DesktopVideoDecodeMode.entries,
                selected = settings.desktopVideoDecodeMode,
                optionLabel = DesktopVideoDecodeMode::label,
                enabled = !busy,
                onSelect = settingsController::setDesktopVideoDecodeMode,
            )
        }
    }

    SmartReplacementFeatureSettings(
        settings = settings,
        catalog = catalog,
        busy = busy,
        settingsController = settingsController,
        providerCatalog = providerCatalog,
    )
}

@Composable
private fun SmartReplacementFeatureSettings(
    settings: AppSettings,
    catalog: ProviderCatalogUiState,
    busy: Boolean,
    settingsController: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
) {
    val enabled = settings.unavailablePlaybackPolicy == UnavailablePlaybackPolicy.SmartReplace
    val providers = catalog.providers
    val presets = listOf(
        "宽松" to 0.45,
        "平衡" to DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
        "严格" to 0.70,
    )
    fun isPreset(score: Double): Boolean = presets.any { (_, value) -> abs(score - value) < 0.001 }
    var customExpanded by remember { mutableStateOf(!isPreset(settings.smartReplacementMinScore)) }
    LaunchedEffect(settings.smartReplacementMinScore) {
        if (!isPreset(settings.smartReplacementMinScore)) customExpanded = true
    }

    SettingsGroup(title = "智能替换") {
        Column(
            modifier = Modifier.padding(FuoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.md),
        ) {
            Text(
                if (enabled) {
                    "原音源无法播放时，从所选音源中搜索并匹配可播放版本"
                } else {
                    "当前资源不可用策略不是“智能替换”，切换后以下设置生效"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("替换音源", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (providers.isEmpty()) {
                Text("没有已启用的音源", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
                ) {
                    providers.forEach { provider ->
                        val selected = provider.providerId in settings.smartReplacementProviderIds
                        FilterChip(
                            selected = selected,
                            enabled = enabled && !busy,
                            onClick = {
                                providerCatalog.setDisplayProviderEnabled(
                                    ProviderDisplaySection.Replace,
                                    provider.providerId,
                                    !selected,
                                )
                            },
                            label = { Text(provider.providerName) },
                            colors = settingsFilterChipColors(),
                        )
                    }
                }
                Text(
                    if (providers.size < 2) {
                        "仅启用一个音源时无法跨源替换；播放时会自动排除歌曲自身来源"
                    } else {
                        "播放时会自动排除歌曲自身来源；候选音源顺序沿用上方音源排序"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("匹配严格度", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
            ) {
                presets.forEach { (label, score) ->
                    FilterChip(
                        selected = !customExpanded && abs(settings.smartReplacementMinScore - score) < 0.001,
                        enabled = enabled && !busy,
                        onClick = {
                            customExpanded = false
                            settingsController.update { it.copy(smartReplacementMinScore = score) }
                        },
                        label = { Text(label) },
                        colors = settingsFilterChipColors(),
                    )
                }
                FilterChip(
                    selected = customExpanded,
                    enabled = enabled && !busy,
                    onClick = { customExpanded = true },
                    label = { Text("自定义") },
                    colors = settingsFilterChipColors(),
                )
            }
            if (customExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("最低匹配分")
                    Text(formatReplacementScore(settings.smartReplacementMinScore), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = settings.smartReplacementMinScore.toFloat(),
                    onValueChange = { value ->
                        settingsController.update {
                            it.copy(smartReplacementMinScore = roundReplacementScore(value.toDouble()))
                        }
                    },
                    valueRange = 0f..1f,
                    steps = 19,
                    enabled = enabled && !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("分数越高匹配越严格", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AppearanceFeatureSettings(
    state: SettingsFeatureUiState,
    controller: SettingsFeatureController,
    onOpenTheme: () -> Unit,
) {
    val settings = state.settings
    val enabled = !state.isBusy
    SettingsGroup(title = "主题") {
        SettingsChoiceRow(
            title = "主题模式",
            supportingText = "选择浅色、深色或跟随系统",
            value = settings.themeMode.label,
            leadingContent = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
            options = ThemeMode.entries,
            selected = settings.themeMode,
            optionLabel = ThemeMode::label,
            enabled = enabled,
        ) { value -> controller.update { it.copy(themeMode = value) } }
        SettingsDivider()
        SettingsRow(
            title = "主题设置",
            supportingText = "${settings.themeColorScheme.label} · 调色板与色彩规范",
            enabled = enabled,
            leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = onOpenTheme,
        )
    }

    SettingsGroup(title = "播放显示") {
        Column(
            modifier = Modifier.padding(horizontal = FuoSpacing.lg, vertical = FuoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
        ) {
            Text("歌词字号", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                LyricFontSize.entries.forEachIndexed { index, size ->
                    SegmentedButton(
                        selected = settings.lyricFontSize == size,
                        enabled = enabled,
                        onClick = { controller.update { it.copy(lyricFontSize = size) } },
                        shape = SegmentedButtonDefaults.itemShape(index, LyricFontSize.entries.size),
                        colors = settingsSegmentedButtonColors(),
                    ) { Text(size.label) }
                }
            }
        }
        if (state.statusBarLyricsAvailable) {
            SettingsDivider(startPadding = FuoSpacing.lg)
            SettingsToggleRow(
                title = "状态栏歌词",
                supportingText = "通过词幕在系统状态栏显示当前歌词",
                checked = settings.statusBarLyricsEnabled,
                enabled = enabled,
                onCheckedChange = controller::setStatusBarLyricsEnabled,
            )
        }
        if (state.bluetoothLyricsAvailable) {
            SettingsDivider(startPadding = FuoSpacing.lg)
            SettingsToggleRow(
                title = "蓝牙车载歌词",
                supportingText = "蓝牙输出时通过媒体标题显示当前歌词；兼容模式可能影响其他系统媒体控制器",
                checked = settings.bluetoothLyricsEnabled,
                enabled = enabled,
                onCheckedChange = controller::setBluetoothLyricsEnabled,
            )
        }
        if (state.bydInstrumentLyricsAvailable) {
            SettingsDivider(startPadding = FuoSpacing.lg)
            SettingsToggleRow(
                title = "比亚迪仪表歌词",
                supportingText = "将当前歌词同步到驾驶仪表的三行歌词区域",
                checked = settings.bydInstrumentLyricsEnabled,
                enabled = enabled,
                onCheckedChange = controller::setBydInstrumentLyricsEnabled,
            )
        }
        SettingsDivider(startPadding = FuoSpacing.lg)
        SettingsToggleRow(
            title = "关闭波形动画",
            supportingText = "应用内所有进度条使用普通直线样式",
            checked = settings.waveformAnimationDisabled,
            enabled = enabled,
            onCheckedChange = controller::setWaveformAnimationDisabled,
        )
    }
}

@Composable
private fun ThemeSettingsContent(
    state: SettingsFeatureUiState,
    controller: SettingsFeatureController,
) {
    val settings = state.settings
    val enabled = !state.isBusy
    SettingsGroup(title = "颜色") {
        SettingsChoiceRow(
            title = "强调色",
            supportingText = "选择应用主要颜色方案",
            value = settings.themeColorScheme.label,
            options = ThemeColorScheme.entries,
            selected = settings.themeColorScheme,
            optionLabel = ThemeColorScheme::label,
            enabled = enabled,
        ) { value -> controller.update { it.copy(themeColorScheme = value) } }
        SettingsDivider()
        SettingsChoiceRow(
            title = "调色板风格",
            supportingText = "Material 3 动态配色算法风格",
            value = settings.themePaletteStyle.label,
            options = ThemePaletteStyle.entries,
            selected = settings.themePaletteStyle,
            optionLabel = ThemePaletteStyle::label,
            enabled = enabled,
            onSelect = controller::setThemePaletteStyle,
        )
        SettingsDivider()
        SettingsChoiceRow(
            title = "色彩规范",
            supportingText = "选择 Material 3 色彩规范版本",
            value = settings.themeColorSpec.label,
            options = ThemeColorSpec.entries,
            selected = settings.themeColorSpec,
            optionLabel = ThemeColorSpec::label,
            enabled = enabled,
            onSelect = controller::setThemeColorSpec,
        )
    }
    SettingsGroup(title = "封面") {
        SettingsToggleRow(
            title = "封面动态取色",
            supportingText = "播放时根据当前封面调整界面颜色",
            checked = settings.dynamicCoverColorEnabled,
            enabled = enabled,
        ) { value -> controller.update { it.copy(dynamicCoverColorEnabled = value) } }
    }
}

@Composable
private fun LocalMusicFeatureSettings(state: SettingsFeatureUiState, controller: SettingsFeatureController) {
    LaunchedEffect(Unit) { controller.refreshLocalMusicDirectories() }
    val enabled = !state.isBusy
    SettingsGroup(title = "扫描设置") {
        SettingsChoiceRow(
            title = "忽略短音频",
            supportingText = "扫描时过滤低于指定时长的音频",
            value = if (state.localMusic.minDurationSeconds == 0) "不过滤" else "${state.localMusic.minDurationSeconds} 秒",
            options = listOf(0, 15, 30, 60, 120),
            selected = state.localMusic.minDurationSeconds,
            optionLabel = { if (it == 0) "不过滤" else "$it 秒" },
            enabled = enabled,
            onSelect = controller::setLocalMusicMinDurationSeconds,
        )
    }
    SettingsGroup(title = "媒体目录") {
        if (state.localMusic.directories.isEmpty()) {
            SettingsRow(title = "暂无可用目录", supportingText = "刷新本地音乐后将在这里显示媒体目录")
        } else {
            state.localMusic.directories.forEachIndexed { index, directory ->
                val directoryEnabled = !isLocalMusicDirectoryExcluded(directory.id, state.localMusic.excludedDirectoryIds)
                SettingsToggleRow(
                    title = directory.name,
                    supportingText = "${directory.trackCount} 首",
                    checked = directoryEnabled,
                    enabled = enabled,
                ) { controller.setLocalMusicDirectoryEnabled(directory.id, it) }
                if (index < state.localMusic.directories.lastIndex) SettingsDivider(startPadding = FuoSpacing.lg)
            }
        }
    }
}

@Composable
private fun StorageFeatureSettings(state: SettingsFeatureUiState, controller: SettingsFeatureController) {
    val settings = state.settings
    val enabled = !state.isBusy
    SettingsGroup(title = "下载") {
        SettingsRow(
            title = "下载管理",
            supportingText = "${state.downloadTasks.count { it.status == DownloadTaskStatus.Downloading }} 个下载中",
            enabled = enabled,
            leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = controller::openDownloadManager,
        )
        SettingsDivider()
        SettingsChoiceRow(
            title = "并行下载数量",
            supportingText = "同时进行的下载任务数量",
            value = settings.downloadParallelism.toString(),
            options = (1..5).toList(),
            selected = settings.downloadParallelism,
            optionLabel = { it.toString() },
            enabled = enabled,
            onSelect = controller::setDownloadParallelism,
        )
    }
    SettingsGroup(title = "缓存") {
        SettingsChoiceRow(
            title = "音频缓存上限",
            supportingText = "本地音频缓存最大占用空间",
            value = "${settings.audioCacheLimitMb} MB",
            options = listOf(128, 256, 512, 1024, 2048),
            selected = settings.audioCacheLimitMb,
            optionLabel = { "$it MB" },
            enabled = enabled,
            onSelect = controller::setAudioCacheLimitMb,
        )
        SettingsDivider()
        SettingsChoiceRow(
            title = "图片缓存上限",
            supportingText = "封面等图片缓存最大占用空间",
            value = "${settings.imageCacheLimitMb} MB",
            options = listOf(64, 128, 256, 512),
            selected = settings.imageCacheLimitMb,
            optionLabel = { "$it MB" },
            enabled = enabled,
            onSelect = controller::setImageCacheLimitMb,
        )
        SettingsDivider()
        SettingsRow(
            title = "当前缓存",
            supportingText = formatCacheBytes(state.cacheUsage.totalBytes),
            trailingContent = {
                OutlinedButton(onClick = controller::clearCache, enabled = enabled) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.size(FuoSpacing.xs))
                    Text("清理")
                }
            },
        )
    }
}

@Composable
private fun AboutFeatureSettings(
    state: SettingsFeatureUiState,
    controller: SettingsFeatureController,
    appVersionInfo: String?,
) {
    val uriHandler = LocalUriHandler.current
    SettingsGroup(title = "应用信息") {
        appVersionInfo?.takeIf { it.isNotBlank() }?.let { version ->
            SettingsRow(
                title = "版本",
                trailingContent = {
                    Text(
                        version.removePrefix("版本 "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
            SettingsDivider()
        }
        SettingsRow(
            title = "FuoEvolve 源代码",
            supportingText = "GitHub 项目主页",
            leadingContent = { Icon(Icons.Filled.Code, contentDescription = null) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
            onClick = { uriHandler.openUri(FUO_EVOLVE_SOURCE_URL) },
        )
        SettingsDivider()
        SettingsRow(
            title = "FeelUOwn 主项目",
            supportingText = "上游项目主页",
            leadingContent = { Icon(Icons.Filled.Code, contentDescription = null) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
            onClick = { uriHandler.openUri(FEELUOWN_SOURCE_URL) },
        )
    }
    if (state.debugLogViewerAvailable) {
        SettingsGroup(title = "诊断") {
            SettingsRow(
                title = "应用日志",
                supportingText = "查看调试日志与错误信息",
                enabled = !state.isBusy,
                leadingContent = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = controller::openDebugLogs,
            )
        }
    }
}

@Composable
private fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm)) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = FuoSpacing.sm),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
        ) { Column(content = content) }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    supportingText: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else Modifier
    val contentAlpha = if (enabled) 1f else 0.55f
    ListItem(
        headlineContent = {
            Text(title, color = titleColor.copy(alpha = contentAlpha), maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = supportingText?.let { value ->
            {
                Text(
                    value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        modifier = Modifier.fillMaxWidth().then(clickableModifier),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    supportingText: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(
        title = title,
        supportingText = supportingText,
        enabled = enabled,
        trailingContent = { Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange) },
        onClick = { onCheckedChange(!checked) },
    )
}

@Composable
private fun <T> SettingsChoiceRow(
    title: String,
    supportingText: String? = null,
    value: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    leadingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsRow(
        title = title,
        supportingText = supportingText,
        leadingContent = leadingContent,
        enabled = enabled,
        trailingContent = {
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
                DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(optionLabel(option)) },
                            trailingIcon = if (option == selected) {
                                { Text("✓", color = MaterialTheme.colorScheme.primary) }
                            } else null,
                            onClick = {
                                expanded = false
                                onSelect(option)
                            },
                        )
                    }
                }
            }
        },
        onClick = { if (enabled) expanded = true },
    )
}

@Composable
private fun SettingsDivider(startPadding: Dp = 0.dp) {
    HorizontalDivider(modifier = Modifier.padding(start = startPadding), color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 自定义播放地址：贴一条 http/https 音频直链直接播放。
 *
 * 通过 [MusicTrack.localUri] 交给播放层，服务端不再走音源解析，因此可以播放
 * 网盘直链、自建服务或公开试听地址；本地文件仍建议走“本地音乐”导入。
 */
@Composable
private fun CustomPlaybackUrlSettings(busy: Boolean) {
    val queue = LocalPlaybackQueueUiPort.current
    var showDialog by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }
    var titleText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    SettingsGroup(title = "自定义播放地址") {
        SettingsRow(
            title = "播放音频直链",
            supportingText = "支持 http/https 直链（网盘直链、自建服务、公开试听地址）；直接播放，不经过音源解析",
            enabled = !busy,
            trailingContent = { Text("去添加", color = MaterialTheme.colorScheme.primary) },
            onClick = {
                error = null
                showDialog = true
            },
        )
    }

    if (!showDialog) return

    AlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text("播放自定义地址") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "常用示例（点击填入）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CUSTOM_URL_EXAMPLES.forEach { example ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                urlText = example.url
                                titleText = example.title
                                error = null
                            }
                            .padding(vertical = FuoSpacing.xs),
                    ) {
                        Text(example.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            example.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        error = null
                    },
                    label = { Text("音频直链") },
                    placeholder = { Text("https://example.com/song.flac") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("标题（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = urlText.trim()
                if (!value.startsWith("http://", ignoreCase = true) &&
                    !value.startsWith("https://", ignoreCase = true)
                ) {
                    error = "只支持 http/https 直链；本地文件请用“本地音乐”导入"
                    return@TextButton
                }
                val track = MusicTrack(
                    id = "custom-${value.hashCode()}",
                    title = titleText.trim().ifBlank { customUrlTitle(value) },
                    artists = "自定义地址",
                    album = "",
                    source = "custom",
                    sourceType = TrackSourceType.LocalMediaStore,
                    localUri = value,
                    providerName = "自定义地址",
                )
                queue.playTracks(listOf(track), 0)
                showDialog = false
                urlText = ""
                titleText = ""
            }) { Text("播放") }
        },
        dismissButton = { TextButton(onClick = { showDialog = false }) { Text("取消") } },
    )
}

/** 从直链里取一个可读标题，取不到就用兜底文案。 */
private fun customUrlTitle(url: String): String {
    val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    return name.substringBeforeLast('.').ifBlank { "自定义音频" }
}

/**
 * 受控音源目录（设计文档 4.2 / 8.3）。
 *
 * 这里刻意把所有"危险"信息摊开：目录版本、上次成功时间、每个脚本的审核状态与错误，
 * 而不是只给一个"已是最新"。用户要能自己判断"为什么这首歌搜不到"。
 */
@Composable
private fun SourceCatalogSettings(
    state: SourceUpdateUiState,
    controller: SettingsFeatureController,
) {
    SettingsGroup(title = "音源脚本目录") {
        SettingsToggleRow(
            title = "自动检查脚本更新",
            supportingText = if (state.available) {
                "每天检查一次受控目录（自带签名校验与静态扫描）；失败按 1/4/12/24 小时退避，不影响已装版本"
            } else {
                "当前平台未启用脚本运行时，仅展示原生音源"
            },
            checked = state.enabled,
            enabled = state.available && !state.busy,
        ) { enabled -> controller.setSourceAutoUpdateEnabled(enabled) }
        SettingsDivider(startPadding = FuoSpacing.lg)
        SettingsChoiceRow(
            title = "检查间隔",
            supportingText = "仅影响正常检查；失败重试按 1/4/12/24 小时自动退避",
            value = if (state.intervalHours >= 168) "每周" else "${state.intervalHours} 小时",
            options = listOf(12, 24, 48, 168),
            selected = state.intervalHours,
            optionLabel = { hours -> if (hours >= 168) "每周" else "$hours 小时" },
            enabled = state.available && state.enabled && !state.busy,
            onSelect = { hours -> controller.setSourceUpdateIntervalHours(hours) },
        )
        SettingsDivider(startPadding = FuoSpacing.lg)
        SettingsRow(
            title = if (state.busy) "正在检查…" else "立即检查",
            supportingText = state.message ?: sourceCatalogStatusText(state),
            enabled = state.available && !state.busy,
            trailingContent = {
                Text(
                    if (state.busy) "请稍候" else "检查",
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            onClick = { if (state.available && !state.busy) controller.checkSourceUpdatesNow() },
        )
        if (state.installed.isEmpty()) {
            SettingsDivider(startPadding = FuoSpacing.lg)
            SettingsRow(
                title = "尚未安装任何脚本",
                supportingText = "受控目录当前为空：社区脚本需通过签名、静态扫描与人工审核后才会上架，客户端不内置任何脚本",
                enabled = false,
            )
        } else {
            state.installed.forEach { script ->
                SettingsDivider(startPadding = FuoSpacing.lg)
                SourceScriptRow(
                    script = script,
                    busy = state.busy,
                    onToggle = { enabled ->
                        // 隔离状态下的"打开"= 解除隔离并启用，避免用户找不到恢复入口。
                        if (enabled && script.quarantined) {
                            controller.setSourceScriptQuarantined(script.id, false)
                        }
                        controller.setSourceScriptEnabled(script.id, enabled)
                    },
                    onQuarantine = { quarantined ->
                        controller.setSourceScriptQuarantined(script.id, quarantined)
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceScriptRow(
    script: InstalledScriptStatus,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onQuarantine: (Boolean) -> Unit,
) {
    val active = script.enabled && !script.quarantined
    SettingsRow(
        title = "${script.name} ${script.version}",
        supportingText = when {
            script.quarantined -> "已隔离：自动更新不会重新启用它${script.lastError?.let { err -> "；上次错误：$err" }.orEmpty()}"
            script.lastError != null -> "已停用（${script.lastError}）"
            script.enabled -> "已启用 · 已通过目录审核"
            else -> "已停用 · 已通过目录审核"
        },
        enabled = !busy,
        trailingContent = {
            Switch(
                checked = active,
                enabled = !busy,
                onCheckedChange = onToggle,
            )
        },
        onClick = { if (!busy) onToggle(!active) },
    )
    if (script.quarantined) {
        SettingsRow(
            title = "解除隔离",
            supportingText = "解除后该脚本可再次被自动更新启用",
            enabled = !busy,
            trailingContent = { Text("解除", color = MaterialTheme.colorScheme.primary) },
            onClick = { if (!busy) onQuarantine(false) },
        )
    }
}

/** 状态行文案：没有可信信息就直说"还没检查过"，不伪造时间。 */
private fun sourceCatalogStatusText(state: SourceUpdateUiState): String {
    if (!state.available) return "未启用脚本运行时"
    if (!state.enabled) return "自动更新已关闭，可手动检查"
    val status = state.status
    if (status.lastSuccessMillis <= 0L) return "尚未成功检查过目录"
    val scripts = if (state.installed.isEmpty()) "未安装脚本" else "已安装 ${state.installed.size} 个脚本"
    val version = if (status.catalogVersion > 0) "目录版本 ${status.catalogVersion}" else "目录版本未知"
    val failures = if (status.consecutiveFailures > 0) " · 连续失败 ${status.consecutiveFailures} 次" else ""
    return "$version · $scripts · 上次成功 ${relativeTimeText(status.lastSuccessMillis)}$failures"
}

/** 相对时间避免引入跨平台日期格式化；设置页只需要"多久以前"这个粒度。 */
private fun relativeTimeText(millis: Long): String {
    if (millis <= 0L) return "从未"
    val deltaMillis = (sourceUpdateNowMillis() - millis).coerceAtLeast(0L)
    val minutes = deltaMillis / 60_000L
    return when {
        minutes < 1L -> "刚刚"
        minutes < 60L -> "$minutes 分钟前"
        minutes < 60L * 24L -> "${minutes / 60L} 小时前"
        else -> "${minutes / (60L * 24L)} 天前"
    }
}

/**
 * Jamendo 来源配置行。
 *
 * Jamendo 在国内可直连，代码里已内置可用的 client_id，开箱即用；
 * 需要换成自己的 client_id 时可在此修改，留空保存会恢复内置默认值。
 */
@Composable
private fun JamendoSourceRow(
    clientId: String,
    enabled: Boolean,
    onSave: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    var text by remember(clientId) { mutableStateOf(clientId) }
    val usingBuiltIn = clientId == DEFAULT_JAMENDO_CLIENT_ID

    SettingsRow(
        title = "Jamendo 无损源",
        supportingText = when {
            clientId.isBlank() -> "未配置。国内可直连；到 developer.jamendo.com 免费注册应用拿到 client_id 后填入即可启用"
            usingBuiltIn -> "已内置默认 client_id（${clientId}），可直接使用；如需换成自己的点此修改"
            else -> "已配置：${clientId}（留空保存会恢复内置默认值）"
        },
        enabled = enabled,
        trailingContent = {
            Text(
                if (clientId.isBlank()) "去配置" else "修改",
                color = MaterialTheme.colorScheme.primary,
            )
        },
        onClick = {
            text = clientId
            showDialog = true
        },
    )

    if (!showDialog) return

    AlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text("Jamendo client_id") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("client_id") },
                    placeholder = { Text("例如：1a2b3c4d") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "已内置可用的默认 client_id，留空保存会恢复默认值；想用自己的可在 developer.jamendo.com 免费注册应用获取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(text)
                showDialog = false
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { showDialog = false }) { Text("取消") } },
    )
}

/**
 * Navidrome / Subsonic 自建曲库配置行。
 *
 * 支持一键填入官方演示服务器，方便没有自建服务时验证链路；
 * 地址或用户名留空保存即停用该来源。
 */
@Composable
private fun SubsonicSourceRow(
    serverUrl: String,
    username: String,
    password: String,
    enabled: Boolean,
    onSave: (String, String, String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    var urlText by remember(serverUrl) { mutableStateOf(serverUrl) }
    var userText by remember(username) { mutableStateOf(username) }
    var passwordText by remember(password) { mutableStateOf(password) }
    val configured = serverUrl.isNotBlank() && username.isNotBlank()

    SettingsRow(
        title = "Navidrome / Subsonic 曲库",
        supportingText = if (configured) {
            "已连接：$username@${serverUrl.removePrefix("https://").removePrefix("http://")}"
        } else {
            "自建曲库（Navidrome 等）；没有也可以用官方演示地址（demo/demo）测试。留空保存可停用"
        },
        enabled = enabled,
        trailingContent = {
            Text(
                if (configured) "修改" else "去配置",
                color = MaterialTheme.colorScheme.primary,
            )
        },
        onClick = {
            urlText = serverUrl
            userText = username
            passwordText = password
            showDialog = true
        },
    )

    if (!showDialog) return

    AlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text("Navidrome / Subsonic") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm)) {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text("http://192.168.1.10:4533") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = userText,
                    onValueChange = { userText = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passwordText,
                    onValueChange = { passwordText = it },
                    label = { Text("密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        urlText = "https://demo.navidrome.org"
                        userText = "demo"
                        passwordText = "demo"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("填入官方演示（仅测试用）") }
                Text(
                    "只检索 FLAC；演示服务器曲库有限且可能随时关停，长期使用请在 NAS 上自建 Navidrome 或使用支持 Subsonic 协议的服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(urlText, userText, passwordText)
                showDialog = false
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { showDialog = false }) { Text("取消") } },
    )
}

/** 自定义播放地址的内置测试样例。 */
private data class CustomUrlExample(
    val title: String,
    val url: String,
)

/**
 * 国内可直连的测试地址。
 *
 * 网易云外链只有 128kbps，仅用于验证播放链路；
 * FLAC 样例用于验证 FLAC 文件的播放与格式识别；不能证明源文件来自无损母带。
 */
private val CUSTOM_URL_EXAMPLES = listOf(
    CustomUrlExample(
        title = "网易云外链示例 1（128k，国内直连）",
        url = "https://music.163.com/song/media/outer/url?id=33894312.mp3",
    ),
    CustomUrlExample(
        title = "网易云外链示例 2（128k，国内直连）",
        url = "https://music.163.com/song/media/outer/url?id=1824020871.mp3",
    ),
    CustomUrlExample(
        title = "公开 MP3 测试音频",
        url = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
    ),
    CustomUrlExample(
        title = "公开 FLAC 测试音频（下载较慢）",
        url = "https://filesamples.com/samples/audio/flac/sample1.flac",
    ),
)

private fun categorySummary(
    category: FeatureSettingsCategory,
    settings: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    appVersionInfo: String?,
): String = when (category) {
    FeatureSettingsCategory.Sources -> {
        val loggedIn = catalog.sessions.authStates.values.count { it.isLoggedIn }
        if (loggedIn > 0) "${catalog.enabledProviderIds.size} 个音源已启用 · $loggedIn 个已登录"
        else "${catalog.enabledProviderIds.size} 个音源已启用"
    }
    FeatureSettingsCategory.Playback ->
        "Wi‑Fi ${settings.settings.wifiAudioQualityPolicy.label} · ${settings.settings.unavailablePlaybackPolicy.label}"
    FeatureSettingsCategory.Appearance -> "${settings.settings.themeMode.label} · ${settings.settings.themeColorScheme.label}"
    FeatureSettingsCategory.LocalMusic -> "${settings.localMusic.directories.size} 个媒体目录"
    FeatureSettingsCategory.Storage -> "并行下载 ${settings.settings.downloadParallelism} · 缓存与清理"
    FeatureSettingsCategory.Updates -> when {
        !settings.appUpdate.supported -> "当前平台暂不支持"
        settings.appUpdate.phase == AppUpdatePhase.UpdateAvailable ->
            "发现 ${settings.appUpdate.remoteVersionName ?: "新版本"}"
        settings.appUpdate.phase == AppUpdatePhase.WaitingForStable -> "Stable · 等待正式版追上"
        else -> "${settings.appUpdate.channel.label} · ${if (settings.appUpdate.autoCheckEnabled) "自动检查" else "手动检查"}"
    }
    FeatureSettingsCategory.About -> appVersionInfo ?: "FuoEvolve"
}

private fun categoryIcon(category: FeatureSettingsCategory): ImageVector = when (category) {
    FeatureSettingsCategory.Sources -> Icons.Filled.ManageAccounts
    FeatureSettingsCategory.Playback -> Icons.Filled.Tune
    FeatureSettingsCategory.Appearance -> Icons.Filled.Palette
    FeatureSettingsCategory.LocalMusic -> Icons.Filled.Settings
    FeatureSettingsCategory.Storage -> Icons.Filled.Download
    FeatureSettingsCategory.Updates -> Icons.Filled.Download
    FeatureSettingsCategory.About -> Icons.Filled.Code
}

private fun roundReplacementScore(value: Double): Double =
    ((value.coerceIn(0.0, 1.0) * 20.0 + 0.5).toInt() / 20.0).coerceIn(0.0, 1.0)

private fun formatReplacementScore(value: Double): String {
    val hundredths = (value.coerceIn(0.0, 1.0) * 100.0 + 0.5).toInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

private fun formatCacheBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GB"
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
