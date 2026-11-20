package org.feeluown.mobile.provider.musicfree

/**
 * 阶段 G2：MusicFree 官方插件登记台账（受控来源，非可安装目录）。
 *
 * 数据来源：官方仓库 `maotoumao/MusicFreePlugins`（GPL-3.0，149 commits，最近维护 2025-11），
 * `plugins.json` 权威清单（本机 2026-11-20 实际下载，sha256 `a9b2d83bba47adca…`）+ 12 个插件正文真实下载计 SHA。
 *
 * 红线：
 * - 这是**取证与适配元数据**，不是信任根；任何条目都不写 APPROVED/自动安装；
 * - allowedHosts 只记录插件源码中静态可见、人工可核的具名域名；运行时网络校验仍需经过
 *   [MusicFreePluginPolicy]（复用 CandidateNetworkPolicy，SHA 绑定 + SSRF fail-closed）；
 * - 版本或 sha256 与登记不一致 → 适配审批自动失效；
 * - 不把入站的动态端点（响应体内 URL）写进授权域名。
 */
enum class MusicFreeCapability { SEARCH, MEDIA_SOURCE, LYRICS, ALBUM_INFO, TOP_LISTS }

/** 插件来源形态：官方自托管示例 vs 第三方订阅站。 */
enum class MusicFreeOrigin {
    /** maotoumao 官方 MusicFreePlugins 仓库（GPL-3.0）。 */
    OFFICIAL,
    /** 第三方订阅站（hebijunge 57 插件，来源为站点自述，未逐条复核）。 */
    THIRD_PARTY_SUBSCRIPTION,
}

data class MusicFreePluginRecord(
    val pluginId: String,
    val name: String,
    val scriptUrl: String,
    val version: String,
    val sha256: String,
    val fileSize: Long,
    val license: String,
    val origin: MusicFreeOrigin,
    val capabilities: Set<MusicFreeCapability>,
    val searchTypes: Set<MusicFreeSearchType> = emptySet(),
    /** 源码中静态可见且人工确认的 API 域名（具名 HTTPS，供候选级授权参考）。 */
    val declaredHosts: Set<String> = emptySet(),
    /** 静态审查命中计数（VIP/会员绕过/混淆等），全为 0 才允许进入适配评估。 */
    val staticFindings: Map<String, Int> = emptyMap(),
    val evidence: String,
) {
    val isStaticallyClean: Boolean get() = staticFindings.values.all { it == 0 }
    val hasSearch: Boolean get() = MusicFreeCapability.SEARCH in capabilities
}

object MusicFreePluginRegistry {
    private const val OFFICIAL_BASE = "https://gitee.com/maotoumao/MusicFreePlugins/raw/v0.1/dist"
    private const val GPL = "GPL-3.0"
    private const val CLEAN = "2026-11-20 静态审查：无 VIP/SVIP/会员绕过/混淆/eval/裸IP 初始化命中"

    val officialPlugins: List<MusicFreePluginRecord> = listOf(
        MusicFreePluginRecord(
            pluginId = "mf-navidrome", name = "Navidrome",
            scriptUrl = "$OFFICIAL_BASE/navidrome/index.js", version = "0.0.0",
            sha256 = "2f1e930e417a72e2a0c870bde45359c442ad139bcf37bfe444df5141fd0cdeb3", fileSize = 4481,
            license = GPL, origin = MusicFreeOrigin.OFFICIAL,
            capabilities = setOf(MusicFreeCapability.SEARCH, MusicFreeCapability.MEDIA_SOURCE, MusicFreeCapability.ALBUM_INFO),
            searchTypes = setOf(MusicFreeSearchType.MUSIC, MusicFreeSearchType.ALBUM),
            declaredHosts = emptySet(), // 端点由用户 userVariables.url 配置（自有服务器），非插件固定域名
            evidence = "$CLEAN；Subsonic 协议；URL/账号/密码来自用户本人配置的自建服务器（用户有权访问的内容）",
        ),
        MusicFreePluginRecord(
            pluginId = "mf-webdav", name = "WebDAV",
            scriptUrl = "$OFFICIAL_BASE/webdav/index.js", version = "0.0.2",
            sha256 = "64e00a417f131dc2fe43da410a13e980e0ca58151852a54a1284bb74ff43b5ca", fileSize = 3886,
            license = GPL, origin = MusicFreeOrigin.OFFICIAL,
            capabilities = setOf(MusicFreeCapability.SEARCH, MusicFreeCapability.MEDIA_SOURCE, MusicFreeCapability.TOP_LISTS),
            searchTypes = setOf(MusicFreeSearchType.MUSIC),
            declaredHosts = emptySet(),
            evidence = "$CLEAN；WebDAV 协议；端点/账号来自用户本人配置的自建存储",
        ),
        MusicFreePluginRecord(
            pluginId = "mf-audiomack", name = "Audiomack",
            scriptUrl = "$OFFICIAL_BASE/audiomack/index.js", version = "1.0",
            sha256 = "1b1f49fc1e0aa5933c3b45ce999d975e4f7fc5f69df2a487dd87830555cf01d5", fileSize = 15789,
            license = GPL, origin = MusicFreeOrigin.OFFICIAL,
            capabilities = setOf(MusicFreeCapability.SEARCH, MusicFreeCapability.MEDIA_SOURCE, MusicFreeCapability.ALBUM_INFO, MusicFreeCapability.TOP_LISTS),
            searchTypes = setOf(MusicFreeSearchType.MUSIC, MusicFreeSearchType.ALBUM),
            declaredHosts = setOf("api.audiomack.com"),
            evidence = "$CLEAN；getMediaSource 仅 standard 档，基于公开 oauth consumer key",
        ),
        MusicFreePluginRecord(
            pluginId = "mf-bilibili", name = "bilibili",
            scriptUrl = "$OFFICIAL_BASE/bilibili/index.js", version = "0.2.3",
            sha256 = "930e32587aef30e6c858ff0f4d308bd98507556362d12b94839da6b89a1cf938", fileSize = 20608,
            license = GPL, origin = MusicFreeOrigin.OFFICIAL,
            capabilities = setOf(MusicFreeCapability.SEARCH, MusicFreeCapability.MEDIA_SOURCE, MusicFreeCapability.ALBUM_INFO, MusicFreeCapability.TOP_LISTS),
            searchTypes = setOf(MusicFreeSearchType.MUSIC),
            declaredHosts = setOf("api.bilibili.com"),
            staticFindings = mapOf("anonymous_visitor_cookie" to 9), // 匿名 buvid3/buvid4，非会员凭据
            evidence = "$CLEAN；cookie 为从 /x/frontend/finger/spi 公开接口取的匿名 buvid3/buvid4 访客标识，非 VIP/用户凭据；仍列为需人工复核项",
        ),
        MusicFreePluginRecord(
            pluginId = "mf-youtube", name = "YouTube",
            scriptUrl = "$OFFICIAL_BASE/youtube/index.js", version = "0.0.1",
            sha256 = "75b8a113b25793446a9a17d1308d29c60a1e1925b44c45ab3e72a804459fef5e", fileSize = 7214,
            license = GPL, origin = MusicFreeOrigin.OFFICIAL,
            capabilities = setOf(MusicFreeCapability.SEARCH, MusicFreeCapability.MEDIA_SOURCE),
            searchTypes = setOf(MusicFreeSearchType.MUSIC),
            declaredHosts = emptySet(), // 解析端点由上游返回，动态，不预先授权
            evidence = "$CLEAN；解析 URL 依赖上游返回（动态端点），不写入静态授权",
        ),
        MusicFreePluginRecord(
            pluginId = "mf-geciwang", name = "歌词网",
            scriptUrl = "$OFFICIAL_BASE/geciwang/index.js", version = "0.0.0",
            sha256 = "b953571441c4d2ef3216d505fdaac2577dad1695e7009382bd4b83f1dd09c4f2", fileSize = 1535,
            license = GPL, origin = MusicFreeOrigin.OFFICIAL,
            capabilities = setOf(MusicFreeCapability.SEARCH, MusicFreeCapability.LYRICS),
            searchTypes = setOf(MusicFreeSearchType.MUSIC),
            evidence = "$CLEAN；歌词类插件，无播放地址解析",
        ),
        MusicFreePluginRecord(
            pluginId = "mf-geciqianxun", name = "歌词千寻",
            scriptUrl = "$OFFICIAL_BASE/geciqianxun/index.js", version = "0.0.0",
            sha256 = "b60977180a3ed5a556fc73dacc1a36a843a31f636a05664ee9e987697b40b27f", fileSize = 1616,
            license = GPL, origin = MusicFreeOrigin.OFFICIAL,
            capabilities = setOf(MusicFreeCapability.SEARCH, MusicFreeCapability.LYRICS),
            searchTypes = setOf(MusicFreeSearchType.MUSIC),
            evidence = "$CLEAN；歌词类插件，无播放地址解析",
        ),
        MusicFreePluginRecord(
            pluginId = "mf-suno", name = "suno",
            scriptUrl = "$OFFICIAL_BASE/suno/index.js", version = "0.0.0",
            sha256 = "6abef66d017eb17ffc042a3d93d82d616aa82a33105b5d39a11ebb681f5922e8", fileSize = 2971,
            license = GPL, origin = MusicFreeOrigin.OFFICIAL,
            capabilities = setOf(MusicFreeCapability.LYRICS, MusicFreeCapability.TOP_LISTS),
            evidence = "$CLEAN；仅歌词/榜单，无 search/musicUrl",
        ),
        MusicFreePluginRecord(
            pluginId = "mf-udio", name = "udio",
            scriptUrl = "$OFFICIAL_BASE/udio/index.js", version = "0.0.0",
            sha256 = "6d1896e591d6d12346740340986843d3e0e03ae96937a2945dc8f8fcefe947c5", fileSize = 5147,
            license = GPL, origin = MusicFreeOrigin.OFFICIAL,
            capabilities = setOf(MusicFreeCapability.SEARCH, MusicFreeCapability.MEDIA_SOURCE, MusicFreeCapability.LYRICS, MusicFreeCapability.TOP_LISTS),
            searchTypes = setOf(MusicFreeSearchType.MUSIC),
            evidence = "$CLEAN；AI 生成音乐平台",
        ),
        MusicFreePluginRecord(
            pluginId = "mf-maoerfm", name = "猫耳FM",
            scriptUrl = "$OFFICIAL_BASE/maoerfm/index.js", version = "0.1.4",
            sha256 = "0eaf6335d85ae9d0d8908fdd159baac22a2881247435c4679e62a43e24121b7f", fileSize = 6610,
            license = GPL, origin = MusicFreeOrigin.OFFICIAL,
            capabilities = setOf(MusicFreeCapability.SEARCH, MusicFreeCapability.MEDIA_SOURCE, MusicFreeCapability.ALBUM_INFO),
            searchTypes = setOf(MusicFreeSearchType.MUSIC, MusicFreeSearchType.ALBUM),
            evidence = "$CLEAN；音频节目平台",
        ),
        MusicFreePluginRecord(
            pluginId = "mf-kuaishou", name = "快手",
            scriptUrl = "$OFFICIAL_BASE/kuaishou/index.js", version = "0.0.2",
            sha256 = "6d5c776a52a5832f5927215605a52174f0c5e631ebff8d6a1edc5a60dcc680de", fileSize = 3847,
            license = GPL, origin = MusicFreeOrigin.OFFICIAL,
            capabilities = setOf(MusicFreeCapability.SEARCH, MusicFreeCapability.MEDIA_SOURCE),
            searchTypes = setOf(MusicFreeSearchType.MUSIC),
            evidence = "$CLEAN；短视频平台音频",
        ),
        MusicFreePluginRecord(
            pluginId = "mf-yinyuetai", name = "音悦台",
            scriptUrl = "$OFFICIAL_BASE/yinyuetai/index.js", version = "0.0.1",
            sha256 = "f87b15752659bd4882e70b71af0fb70af0a6b40078b21dc54caf793ff124d90d", fileSize = 2951,
            license = GPL, origin = MusicFreeOrigin.OFFICIAL,
            capabilities = setOf(MusicFreeCapability.SEARCH, MusicFreeCapability.MEDIA_SOURCE),
            searchTypes = setOf(MusicFreeSearchType.MUSIC),
            evidence = "$CLEAN；MV 平台音频",
        ),
    )

    val all: List<MusicFreePluginRecord> = officialPlugins
    val searchCapable: List<MusicFreePluginRecord> = all.filter { it.hasSearch && it.isStaticallyClean }

    fun find(pluginId: String): MusicFreePluginRecord? =
        all.firstOrNull { it.pluginId.equals(pluginId, ignoreCase = true) }

    /** 官方 plugins.json 的权威 sha256，用于校验来源清单未被篡改。 */
    const val OFFICIAL_MANIFEST_SHA256 =
        "a9b2d83bba47adca8573495bda966472b57768e67af7f60a225b34e15398a6b8"
}
