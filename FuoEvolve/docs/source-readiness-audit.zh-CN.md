# 来源就绪度审计（阶段 K0）

> 目标不变：**中国大陆普通家庭成员安装 App 后，不配置音源、不写 JS、不配服务器，打开就能搜索、播放。**
> K0 的成功不是"又接入一个音源"，而是找到至少一个"授权明确 + 大陆目标网络直连可用 + 真实搜索 + App 真实播放 + 身份核验"的来源，
> 或用充分证据确认当前候选均不满足并给出下一步方向。**不为完成阶段而降低标准。**

审计时间：2026-11-21。仓库 `my_FeelUOwn` 工作分支 `work/android-community-discovery`。
执行设备：内网 Windows（DNS `192.168.6.1` / `opnsense.dev.yuntaiyi.com`），JDK25，`--rerun-tasks`。

---

## K0.1 执行前仓库审计

### Git 状态
- 当前分支 `work/android-community-discovery`，HEAD=`8e8c428aebb1f191b90ec4a457777bb50be3f272`（J2.5），
  父=`e68681ac67cba6887d24983e1f6841a6515114a0`（J2.4）。工作区**干净**（`git status --porcelain` 0 行）。
- 远端（只读 `ls-remote`，openssl+7897 代理）：work=`8e8c428`（与本地一致）、master=`fc05530c09966dedacb413168a1dc6fda0d3afe1`（未动）。
- 相对 master 的 ahead/behind：**本地无法计算**——工作分支与 master 是两条独立历史线，且本 checkout 为 shallow clone
  （`.git/shallow` 边界 `6d92d0b`，master 对象不在本地）。工作分支自根提交起共 30 提交。如实记录，不臆造数字。
- 本阶段不改 master、不改写历史、不推送（除非用户另行授权）。

### 三类来源的"已有实现 / 已验证 / 仅接口"严格区分

| 来源 | 模块 | 已有实现 | 已验证能力（真实网络） | 默认启用 | 走统一 Gateway |
| --- | --- | --- | --- | --- | --- |
| **Jamendo** | `provider/jamendo`（`settings.gradle.kts:62`） | 搜索/URL 重取/解析/播放载荷齐备 | 有（opt-in E2E + 生产路径，默认跳过） | **是**（`DEFAULT_ENABLED_PROVIDER_IDS`） | 否，走 `ProviderHttpClient` 共享客户端 |
| **Internet Archive** | 无 Provider；仅 `lossless/InternetArchiveClient` | advancedsearch/metadata/download 真实代码 | **无真实网络测试** | lossless 增强默认开（`losslessEnhanceEnabled=true`），但无 Provider | 否，直连 `HttpURLConnection` |
| **Subsonic / WebDAV** | 无独立 Provider；`lossless/SubsonicClient` + MusicFree 插件元数据 | Subsonic 真实直连；插件仅登记 | **无真实网络测试** | `subsonicEnabled=false`；插件 deny-all | lossless 绕过 Gateway；沙箱路径 deny-all |

**关键澄清（不可把模块存在写成来源可用）**：
- Jamendo 的"URL 解析"是**播放地址时效重取**（`trackById`），不是"分享链接→曲目"解析；后者全局只支持 netease/bilibili/qqmusic/ytmusic
  （`shared/.../core/ui/ShareResources.kt:243-248`），Jamendo 未实现。
- Internet Archive 是**播放/下载时的 FLAC 替换增强器**（`LosslessEnhancer.kt:35,52`，最低优先级），**搜索页不会按关键词返回 archive.org 曲目**。
- WebDAV/Navidrome 在 MusicFree 路径 `declaredHosts=emptySet()` → `PolicyDecision.Denied`（正当 deny-all，不放宽 SSRF）。
- 项目有**两套网络层**：MusicFree 沙箱专用 `ProviderNetworkGateway`（`provider/musicfree/.../ProviderNetworkGateway.kt:23`，
  HTTPS-only/禁通配符/禁裸 IP/逐跳重定向/1MB/8s/15s/DNS rebinding 未解）；原生 Provider（含 Jamendo）走共享
  `ProviderNetwork.kt:196 ProviderHttpClient`（connect 10s/request 30s/有界读取）。不能统称"统一 Gateway 出口"。

### 测试覆盖分类（不合并为总通过数）
- **真实网络**：Jamendo `JamendoEndToEndTest`(2)+`JamendoProductionPathTest`(2)（opt-in，默认 SKIP）；
  musicfree `PhaseJ2RealSearchTest`(3×2 端) + `PhaseJ1RealNetworkBaseTest`(5×2，本地 socket)；lxruntime 真实接口(6，opt-in)。
  Internet Archive / Subsonic / WebDAV：**零真实网络测试**。
- **Mock/纯逻辑**：Jamendo `JamendoApiClientTest`(10)、`JamendoSearchToPlaybackFlowTest`(2，明示 Mock)、
  `TrackLicensePresentationTest`(5)；Gateway/策略类全为受控执行器；无损增强均本地 fixture。
- **UI 测试**：全仓 278 个 `*Test.kt` 中 **零** Compose `testTag`/`createComposeRule`/截图测试；许可证弹窗无 UI 测试。
  仅有 UI 策略纯逻辑测试（不代表真实渲染）。

---

## K0.2 来源合规与内容审查

### Jamendo（证据：官方 About 页 + developer.jamendo.com/v3.0）
- **授权依据**：Jamendo 官方自述"first platform to legally share music for free under Creative Commons licenses"；
  "You can stream all the music for free, download it and support the artist"；商业用途另走 Jamendo Licensing 收费
  （影片/电视/广告/商业空间背景乐）。开发者页列"Non-Commercial Apps：Access our public API…up to 35,000 requests/month"。
- **四权分离**：
  - API 可调用：是（公开 API + client_id，非商业额度 35k/月；内置公开 key `5ace5988`）。
  - 内容可播放/流媒体：**是**（官方明确 free streaming）。
  - 内容可下载：**官方允许下载支持艺术家**，但**本 App 不默认开放下载**——见下。
  - 内容可重新分发：受每首作品 CC 条款约束，**不可一概而论**。
- **单曲许可**：API 每首返回 `license_ccurl`；实测首条为 `http://creativecommons.org/licenses/by-nc-nd/3.0/`（CC BY-NC-ND 3.0）。
  CC BY-NC-ND 3.0：可 Share（复制/原样再分发）、须署名+链接许可、**禁商业**、**禁演绎**。对"家人自己听"合规；不得改作铃声/混音再分发。
- **App 现有映射**：`JamendoModels.kt:23-57` 解析 `license_ccurl` → CC 类型（BY/NC/ND/SA/CC0/PD）+ 中文摘要；
  `JamendoMusicProvider.kt:118-127` 写入 `providerTags`（`jamendo`/`cc:CODE`/`license-url:URL`）；
  UI 在正在播放页/曲目详情/弹窗展示（`PlayerFormatting.kt:40-79`，`PlayerDialogs.kt:96-128`），并声明"开放曲库·非原曲"。
- **缺口**：署名仅以摘要文本体现，**无独立 attribution 字段**；`downloadUrl` 解析后**未传播到 MusicTrack**，播放只用 `audioUrl`；
  ND/NC 仅 UI 文本提示，**无技术性下载/播放阻断**。
- **下载结论**：下载能力**单独审批，本阶段不开放**。依据：① 非商业 API 额度与 NC/ND 作品不宜默认长期离线保存；
  ② 无独立下载许可字段与阻断逻辑；③ CC "可 Share" 不等价于"App 可默认分发/缓存其音频"。

### Internet Archive（证据：archive.org advancedsearch API 真实元数据）
- **授权依据**：平台**逐 item** 标注 `licenseurl`，实测 3 个音频 item 分别为
  `CC0 1.0` / `CC BY 3.0` / `CC BY-NC-ND 2.5 HU`——**许可各不相同**。
- **四权分离**：平台公开提供文件 ≠ 全部可自由使用；**必须逐 item 核对**。CC0 最宽松，BY 须署名，BY-NC-ND 禁商业/演绎。
- **现有实现缺陷**：`lossless/InternetArchiveClient.kt` 只接受 FLAC、跳过小文件，**不采集 licenseurl/署名**
  （`LosslessModels.kt:79-89` 的 `LosslessCandidate` 无 license/attribution 字段）。授权**无法可靠映射到统一模型**。
- **结论**：授权不明确的内容**不进入默认播放/下载路径**；当前 IA 仅作为后台 FLAC 增强，不具备成为"按关键词搜索的默认来源"的资格。

### Subsonic / WebDAV
用户自有曲库，属**可选接入、不计入开箱即用公共来源数量**。`subsonicEnabled` 默认 false；MusicFree 插件路径 deny-all。
Subsonic lossless 直连用户端点且 `instanceFollowRedirects=true`、无 SSRF 校验——这是用户自填可信端点的设计取舍，
本阶段不重构、不放宽公共 Gateway。

---

## K0.3 中国大陆网络真实可用性

**环境如实声明**：本机内网 Windows，DNS `192.168.6.1`。**无法确认为"中国大陆家庭宽带/移动网络"**——不做推断。
"直连"=强制 `--noproxy '*'` 从本机出口直连；"代理"=经 `127.0.0.1:7897`。家庭宽带/移动网/Android 真机/Desktop 真机 App：**均 NOT_RUN**。

| 来源 | 域名 | DNS | TCP | TLS | HTTP/API | 音频 | 判定 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **Jamendo API** | `api.jamendo.com` | OK → Cloudflare `104.26.12.43`/`104.26.13.43`/`172.67.73.205`（0.35s） | 直连 0.23s | 直连 0.46s | **直连 200 / 8118B / 1.13s**，2 条真实结果 | — | **直连可达** |
| **Jamendo 音频** | `prod-1.storage.jamendo.com` | OK → `141.94.253.3` 等 Jamendo 自有 IP（0.08s） | 直连 0.23s | 直连 0.66s | **直连 206**，Content-Range `bytes 0-15/14125568`，Content-Type `audio/mpeg`，0.86s | Range 16B 合法音频 | **直连可达** |
| **Internet Archive** | `archive.org` | **污染** → `31.13.96.195`（Facebook 段，0.03s） | 直连 12s 超时 connect=0 | 未进行 | 直连 000/0B；代理 200/352B | 未测（API 不通） | **直连失败**（DNS 污染+连接超时） |
| Audiomack（历史 J2.5） | `api.audiomack.com` | 污染 → `174.36.228.136`；真实 IP TLS 被 reset | 假 IP 超时 | SNI reset | 直连 000/0B | — | 直连失败（保留历史证据） |

- 代理对照：Jamendo API 经代理返回 200 但 0 条（偶发空结果，105B）；Internet Archive / Audiomack 经代理 200。
  **代理成功不推断直连成功**；本次 Jamendo 是**直连**拿到 200/206，优于代理结果。
- 网络类型：本次直连证据来自内网 Windows 出口，**不是**已确认的家庭宽带；只能说"直连不依赖本机代理、无 DNS 污染/SNI 阻断"，
  不承诺大陆家庭长期稳定。单次成功不代表长期稳定。
- 未为测试关闭 Gateway、跳过 TLS 校验或放宽 SSRF。

---

## K0.4 单一候选七级真实能力验证（候选 = Jamendo）

选择依据：Jamendo 是唯一"合规明确 + 目标网络直连可达 + 已有 Provider + 默认启用"的候选；IA/Audiomack 直连失败，按任务书停止为其接入。

| 级别 | 结论 | 证据 |
| --- | --- | --- |
| REAL_INIT | PASS | 生产 `JamendoApiClient` 用内置 client_id 初始化、真实请求成功（K0.3 直连 200） |
| REAL_SEARCH | PASS | 直连真实搜索 2 条（id=1348698 / Music vast vast sky / Alexander Morozov），生产解析器映射成功 |
| REAL_RESULT_MAPPING | PASS | 标题/歌手/时长/CC 许可 URL 映射正确；`licenseCcUrl` 非空且含 creativecommons.org |
| REAL_URL_RESOLVE | PASS | `trackById` 真实重取同 id/曲名，仍给 https audioUrl（E2E 第 2 用例） |
| REAL_AUDIO_RESPONSE | PASS | 直连 Range 16B → 206 + Content-Range 0-15/total + audio/mpeg；E2E 断言魔数 ID3/MP3、读 17B 恰得 16B |
| REAL_PLAYBACK | **NOT_RUN** | E2E 止步于音频字节校验，**未驱动 App 正式播放器**（ExoPlayer/AVPlayer/libmpv）；本环境无真机/GUI |
| REAL_TRACK_IDENTITY_VERIFIED | PASS（有限） | E2E 断言"搜索 id == 音频 URL 的 trackid 参数"+元数据一致；**非播放后音频指纹核验** |

**真实性与反证**：opt-in 测试 `FUOE_REAL_VERIFY=1` 默认 SKIP；为证明 PASS 不是 SKIP 假象，用错误 client_id
（`JAMENDO_CLIENT_ID=TOTALLY_INVALID_KEY_123`）反证——两个用例均 FAILED，证明环境变量确已传入、测试真的打了网络；
随后用正确 key 重跑恢复，2 项 0 失败。音频仅 Range 读 16B，**未下载完整音乐**。

---

## K0.5 统一模型与许可模型审计

`MusicTrack`（`core/model/.../MediaModels.kt:98-135`）字段：
`id,title,artists,album,source,sourceType,coverUrl,durationMs,localUri,localDirectoryId,lyrics,providerId,providerName,
isSmartReplacement,originalId,originalTitle,originalArtists,originalAlbum,originalSource,originalProviderName,originalCoverUrl,
replacementId/…,isUnavailable,artistItemId,albumItemId,artistItems,providerUrl,providerTags`。

- 已具备：Provider ID、原始歌曲 ID、标题、艺术家、专辑、封面、时长、来源链接（`providerUrl`）。
- **缺失（结构化字段）**：`licenseType`、`attribution`、`playLicense`、`downloadLicense`、`sourceUrl` 独立字段均**无**；
  许可只能经 `providerTags` 的 `cc:`/`license-url:` 字符串约定承载，UI 层 `TrackLicenseInfo` 从 tags 反解。
  **播放许可与下载许可未分离**，无技术性阻断。这是"多来源家庭使用"的模型缺口，本阶段不重构（任务书禁止）。
- 身份区分：id 用 `providerId:原始id`，同名/翻唱/现场版靠不同原始 id 区分（非仅标题+歌手），符合"不得仅据标题歌手认定同一录音"。

---

## K0.6 产品结论与路线

### 是否建议进入默认音源候选

| 候选 | 建议 | 理由 |
| --- | --- | --- |
| **Jamendo** | **维持默认启用（已是默认）；不新增下载能力** | 授权依据明确（官方 CC 平台+公开 API）、目标网络直连可达、真实搜索/映射/URL/音频/身份五级 PASS；已是开箱即用的默认来源，契合家庭目标 |
| Internet Archive | 不进入默认来源 | 直连 DNS 污染+超时、无 Provider、逐 item 许可未采集、无测试 |
| Subsonic/WebDAV | 不计入公共默认来源 | 用户自有曲库可选接入，需用户配服务器，非开箱即用 |
| Audiomack | 不进入默认（维持 K0 前决定） | 直连失败、仅代理可达、播放域名 DENIED；保留 Provider 与历史证据，不删除、不继续投入 |

**Jamendo 的诚实边界**：REAL_PLAYBACK=NOT_RUN（未驱动正式播放器）；目标网络为内网 Windows 非确认家庭宽带；
许可多为 BY-NC-ND（禁商业/演绎，适合个人听）；无独立许可字段/下载阻断。这些不阻碍"默认启用听歌"，但**不得宣称已完成播放验证，也不得默认开放下载**。

### 下一步明确方向（不降低标准）

1. **补 REAL_PLAYBACK**：在可运行环境（桌面 libmpv 或真机 ExoPlayer）驱动 Jamendo 音频 URL 完成实际播放，
   并核对播放曲目身份（级别 6/7），把 K0.4 的两个 NOT_RUN 关闭——这是"家人打开就能听"最关键的一环。
2. **补目标网络样本**：在中国大陆家庭宽带/移动网/Android 真机分别复测 Jamendo 直连（K0.3 的 NOT_RUN），确认长期稳定。
3. **补统一许可字段**：为 `MusicTrack` 增加结构化 `licenseType/attribution/playLicense/downloadLicense`，将 providerTags 约定迁移为字段，
   为"下载单独审批"提供技术基础（独立任务，不混入本阶段）。
4. **继续寻找**：jamendo 单一来源不足以覆盖家庭全部需求（曲库偏 CC 独立音乐、无主流商业曲目）。按既定标准继续寻找
   "授权明确 + 大陆直连 + 可播放"的来源；找不到就如实说没有，不接入不合规候选。

### 遗留风险
- K0.3 直连证据来自内网 Windows，非家庭宽带/移动网；不承诺大陆长期稳定。
- 代理偶发空结果说明 Jamendo 公开 API 有稳定性波动（E2E 代码注释亦记录"同一关键词有时 count=0"），需连续样本。
- 无 Compose UI 测试、无播放自动化；UI/播放正确性未自动化覆盖。
- 许可证字段非结构化、下载无技术阻断（见 K0.5）。
- shallow clone 导致 ahead/behind 本地不可算（已如实记录）。

---

提交：K0 本阶段为审计与实测产出（未改代码），单独提交报告与文档索引，不改写历史、不推送（除非用户授权）。
