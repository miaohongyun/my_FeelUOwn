# 阶段 K2：产品功能完整性与发布候选审计

> 基线：`d9019584352e0aa6ab5cf4e5dad4c4ecca619e0a`（K1.2 最终），父 `2c7572bfa82d516f14ed5eb1bf1c3b4cf736b2b3`。
> 阶段定位：**不依赖 Android 真机/家庭宽带/移动数据/人工听音**，把开发侧能完成的工作做到 PASS，形成发布候选。
> 核心纪律：K2.0 先审计、已实现有证据的能力直接复用不重复开发；追求开发侧完成率，不把真实环境项涂绿；发布候选版本冻结供 K3。
> 审计执行时间：2026-11-21。

---

## 0. Git 基线与纪律核对

| 项目 | 值 |
|---|---|
| 分支 | `work/android-community-discovery` |
| 基线 HEAD | `d9019584352e0aa6ab5cf4e5dad4c4ecca619e0a` |
| 父提交 | `2c7572bfa82d516f14ed5eb1bf1c3b4cf736b2b3` |
| 远端 work / master | 审计时与本地一致 / `fc05530c09966dedacb413168a1dc6fda0d3afe1` |
| K1.2 报告存在 | 是（`jamendo-cn-device-network-acceptance.zh-CN.md`，含逐次数据） |
| 工作区 | 审计起始干净 |

状态定义：PASS（有真实自动化/静态证据）、FAIL（验证失败有明确问题）、NOT_RUN（未执行）、BLOCKED（依赖缺失无法执行）、BUILD_PASS（构建成功≠运行通过）。

---

## 1. K2.0 全项目产品能力盘点（17 模块）

审计方式：三路只读代码审计（Explore），每条结论带 `文件:行号`，区分「已实现有证据 / 已实现无测试 / 仅接口未实现」。完整报告见本文各模块小节。

| # | 模块 | 状态 | 关键证据（文件:行号） | 测试覆盖 |
|---|---|---|---|---|
| 1 | 首次启动与引导 | 成熟可复用（1 缺口） | 3 页中文引导 `OnboardingFeature.kt:64-322`、失败回滚 `:248-263`、默认 5 源幂等迁移 `AppSettingsContracts.kt:219-234`；缺口：设置加载 error 未暴露到 UI（`AppSettingsRepository.kt:82-95`） | onboarding 8 + settings 12 + defaults 7 |
| 2 | 首页与全局搜索 | 成熟可复用 | 并行 `SearchFeature.kt:334-372`、单源隔离 `:338-369`、latest-wins `:153-382`、状态三态 `:397-430`、ID 去重（`SearchFeatureBindings.kt:73-116`） | search 9 + selection 3 |
| 3 | 音源启用禁用排序 | 成熟可复用（1 缺口） | `ProviderCatalogFeature.kt:170-205`、排序持久化 `AppSettingsRepository.kt:175/246`、脚本回滚 `SourceVersionStore.kt:276-281`；缺口：设置页 setProviderEnabled 失败未回滚偏好 | catalog 5 + repository 1 |
| 4 | 搜索匹配与去重规则 | 成熟可复用（3 缺口） | 版本识别 `SongVersionMatch.kt:10-210`（Live/翻唱/伴奏/简繁版本标记）、评分 `PlaybackSmartReplacementPolicy.kt:267-653`；缺口：无四级命名分级、无标题/歌手简繁互转、无歌手别名表 | matcher 11 + policy 18 + tie-break 9 |
| 5 | 音质统一模型 | 成熟可复用（1 缺口） | `AudioQualityProfile.kt:12-160`（tier/codec/容器/采样率/位深/声道/码率/evidence）、5 级证据 `:51-63`、FLAC 校验声明≠认证 `FlacValidator.kt:23-33`；缺口：evidence 未在 lossless 管线回填、无文件大小字段、iOS 无 FLAC 校验 | profile 8 + label 7 + FLAC 多端 |
| 6 | Provider 生命周期 | 成熟可复用 | 独立 Context `MusicFreeSandboxSession.kt:59`、幂等 close `:349-365`、native 超时中断 `:105/344`、资源上限 `MusicFreeSandbox.kt:24-31`、CommonJS 测试 PhaseI1/I2/I5 | H1/I1/I2/I3/I4 多组 + LxTimer |
| 7 | Provider 网络安全 Gateway | 成熟可复用（边界需声明） | SHA 绑定 `Gateway.kt:78-96`、精确域名/no 通配符 `CandidateNetworkPolicy.kt:54-56/111`、HTTPS/443、SSRF `LxNetworkPolicy.kt:103-177`、逐跳重定向 `Gateway.kt:175-201`、流式限额 `:205-268`、DNS rebinding 风险公开记录 `:21` | H2/J0/J11 + policy 测试 |
| 8 | 自动更新 | 成熟可复用（2 缺口） | Ed25519 `SourceArtifactVerifier.kt:52-115`+纯 Kotlin `Ed25519.kt`+JDK 独立验签 `PublishedCatalogTest:37`、审核/SHA/静态/冒烟/激活分离 `SourceVersionStore.kt:300-362`、反重放、回滚；缺口：正式目录 `sources` 为空未签发、无断点续传 | sourceupdate ~140 用例 |
| 9 | 错误报告与诊断日志 | 成熟可复用（2 缺口） | `AppLogger.kt:23-61` 脱敏 `:42-75`（Cookie/Token/密码/URL 签名）、Android/Desktop 最近 3 次启动 ZIP 导出；缺口：无自动崩溃捕获、iOS 诊断 NoOp | AppLogger 5 |
| 10 | 下载管理 | 成熟可复用 | 队列/暂停/续传/取消/重试 Android `AndroidDownloadRepository.kt:96-213`、断点 Range+If-Range `:268-297`、持久化 `:479-581`、重启恢复 `:56-94`；缺口：iOS 弱（无 Range/校验） | download 4 + range 1 + selection 13 |
| 11 | 下载许可模型 | **本阶段实现（K2 核心必做）** | 详见 §2；此前 9 项结构化字段全缺失、无门控 | 本阶段新增 TrackLicense 8 + 门控 1 |
| 12 | 本地音乐库 | 成熟可复用（2 缺口） | 扫描/元数据/指纹/迁移 `AndroidLocalMusicRepository.kt:197-274/741-752`、`LocalMusicDatabase.kt:482-521`；缺口：无内容级重复识别、无文件移动删除 | localmusic 多组 |
| 13 | 播放列表与历史 | 成熟可复用 | `.fuo` 编解码 `LocalPlaylistRepository.kt:107-376`、SQLDelight 历史 `ListeningHistoryStore.kt:34-435` | playlist + history 多组 |
| 14 | NAS/Subsonic/WebDAV | 部分实现（安全缺口） | 配置模型 `AppSettingsContracts.kt:108-112`、搜索/URL `SubsonicClient.kt:53-111`；缺口：**密码明文持久化 `SettingsPersistence.kt:75`、UI 明文显示 `SettingsFeatureScreen.kt:1725-1731`、无连接测试、iOS 无实现、WebDAV 无原生客户端** | 无直接单测 |
| 15 | 平台差异 | 已厘清 | Android Media3/ExoPlayer `FuoPlaybackService.kt:51-343`（generation 并发门 `:72-73`、URL 脱敏 `:876-886`）、Desktop libmpv `DesktopMpvPlaybackEngine.kt:29-519`、iOS 部分 NoOp | playback 40+ |
| 16 | 安装包/版本/发布配置 | 见 K2.7 | applicationId `org.feeluown.mobile`、versionCode/Name 由 git 派生 `androidApp/build.gradle.kts:64-68`、Release 签名走环境变量 `:49-115` | — |
| 17 | 设置与数据迁移 | 成熟可复用 | 设置持久化 `AppSettingsRepository.kt`、版本化迁移、幂等默认值合并 | settings 12 |

**盘点结论**：17 个模块中 14 个核心能力成熟可复用（有代码+测试证据）；缺口集中在：① 下载许可模型（本阶段实现）；② 匹配增强（简繁/别名/四级分级）；③ NAS 凭据安全；④ 崩溃捕获/iOS 诊断；⑤ 正式目录签发。除 ① 外的缺口均为增强/安全加固项，按范围判断列入遗留与后续阶段，不在本阶段借机重构。

---

## 2. K2.1–K2.5 开发侧缺口处理：结构化许可模型 + 下载门控（本阶段核心改动）

### 2.1 问题（审计确认）
- `MusicTrack`（`core/model/.../MediaModels.kt:98-138`）此前有 37 个字段，**9 项许可字段一项都没有**；许可仅以 `cc:`/`license-url:` 字符串存在于 `providerTags`，只供 UI 展示（`PlayerFormatting.kt:40-79`）。
- 下载入口 `DownloadFeature.kt:175-184` 只判断 `isProviderTrack`，**无任何许可门控**；BY-NC-ND 仅"提示不可演绎"不技术拦截；Jamendo `downloadUrl`（`JamendoModels.kt:16`）闲置未消费。
- 这是 K0/K1 文档反复标记、K2 明确的第一必做项，也是当前最高合规风险。

### 2.2 实现（最小、向后兼容）
| 文件 | 改动 |
|---|---|
| `core/model/.../TrackLicense.kt`（新增） | 结构化 `TrackLicense`（licenseType/licenseUrl/attribution/playLicense/downloadLicense/commercialUseAllowed/derivativesAllowed/redistributionAllowed/licenseVerifiedAt/licenseSource）；`trackLicenseFromTags()` 从既有 tag 约定解析（向后兼容）；`canDownloadTrack()` + `downloadDenialReason()` 门控 |
| `core/model/.../MediaModels.kt` | `MusicTrack` 加 `license: TrackLicense? = null`（带默认值，全局构造点兼容） |
| `provider/jamendo/.../JamendoMusicProvider.kt` | 逐曲从 `license_ccurl` 构造结构化许可：playLicense=true、downloadLicense=false、NC→commercialUseAllowed=false、ND→derivativesAllowed=false、redistributionAllowed=false、licenseSource=`jamendo-api:license_ccurl` |
| `feature/download/.../DownloadFeature.kt` | `DownloadFeatureOperations` 加 `canDownload`/`downloadDenialMessage`（默认放行兼容）；`download()` 在 resolve 前门控，拒绝则不入队、不发请求、给可读提示 |
| `shared/.../DownloadFeatureFactory.kt` | 生产绑定覆写为 `canDownloadTrack`/`downloadDenialReason` |

### 2.3 门控策略（严格遵循任务书，透明记录）
`canDownloadTrack` 严格实现"**未知许可默认拒绝下载**"：
- `license==null` → 拒绝；`license.downloadLicense==false`（默认值）→ 拒绝；仅 `downloadLicense==true` 放行。
- **不因"可播放"、存在 CC 标签、或"商业来源应有会员权益"等无证据推断放行**。
- 后果（如实记录，不隐瞒）：商业来源曲目此前无结构化许可，现同样被默认拒绝下载；要恢复须由各商业 Provider 在映射时显式写入 `license=TrackLicense(downloadLicense=true,...)`。这没有证据证明那些来源允许下载，因此不是功能退化，而是"默认拒绝未知许可"的必然结果。

### 2.4 验证
新增测试：`TrackLicenseTest`（8：CC BY-NC-ND 解析、NC/ND 语义、显式 allow、无 tag 返回 null、未知默认拒绝、开放曲库拒绝、结构化默认拒绝、显式放行、可播放≠可下载）、`DownloadFeatureTest` 门控用例（许可拒绝时不 resolve、不入队、给提示）。核心三模块（core:model / feature:download / provider:jamendo）desktopTest 全绿。

### 2.5 其它缺口的范围判断（不借机重构）
- 匹配简繁/别名/四级分级、evidence 回填、iOS FLAC、Subsonic 密码安全存储、崩溃捕获、正式目录签发、断点续传：均为增强/安全加固，已有成熟主体，列入「已知限制与遗留」，本阶段不扩大改动范围（符合"不顺手重构无关模块"）。
- Subsonic 密码明文（`SettingsPersistence.kt:75`）是明确安全缺口，但因涉及设置持久化迁移与 UI 改造、且 NAS 非开箱即用公共来源，列入 K3 前需处理的高优先遗留，本阶段不擅自改持久化格式。

---

## 3. K2.6 全量自动化质量门禁

环境：JDK `Temurin 25.0.5+8`、Gradle `9.5.0`、Kotlin `2.3.20`、Windows 内网。任务名均取自 `./gradlew tasks --all` 真实列表（未猜测）。`--no-daemon`，`-Porg.gradle.java.installations.paths=E:/mypro/.tools/jdk-25.0.5+8`。

| 门禁 | 命令（模块:任务） | 结果 | 用例数 |
|---|---|---|---|
| Desktop 全量测试 | 19 模块 `:desktopTest`（core:model/feature:{download,search,playback,localmusic,localplaylist,settings,providercatalog,onboarding,home}/playback:api/provider:{runtime,lxruntime,sourceupdate,musicfree,jamendo}/persistence:{settings,listening}/shared） | **PASS** BUILD SUCCESSFUL | **1049**（0 失败/0 错误/0 跳过，204 个 XML） |
| Android Host 测试 | 8 模块 `:testAndroidHostTest`（feature:{download,search,playback}/provider:{jamendo,musicfree,sourceupdate,lxruntime}/shared）+ `:androidApp:testDebugUnitTest` | **PASS** BUILD SUCCESSFUL | **925**（androidHost，0 失败）+ **34**（App 单测，0 失败） |
| iOS 主代码编译 | `:core:model:compileKotlinIosSimulatorArm64` + `:feature:download` + `:shared` + `:provider:jamendo` 主代码；`:core:model:compileTestKotlinIosSimulatorArm64` 测试源码，`--rerun-tasks` | **PASS** BUILD SUCCESSFUL | 编译门禁，非运行 |
| iOS Simulator 测试 | 无 macOS/Xcode | **NOT_RUN** | — |
| Android Debug APK | `:androidApp:assembleDebug` | 见 §4 构建状态 | — |
| Windows 桌面包 | `desktopApp:packageDistributionForCurrentOS` / `createReleaseDistributable` | 见 §4 构建状态 | — |

**测试分类统计（不合并为总通过数）**：
- 单元测试（desktop/host/app）：1049 + 925 + 34 = 2008 项，0 失败。
- Mock/受控 executor 测试：包含在上述单元测试内（如 Gateway 失败分类用受控 executor），未单独计数。
- 真实网络测试：K1/K1.1 的真实搜索/URL/音频探测为本阶段之前证据；本阶段未新增真实网络测试（本阶段目标是发布候选，不是真机/网络验收）。
- 真实 UI 测试：**NOT_RUN**（无 GUI 真机自动化）。
- 构建门禁：见 §4，BUILD_PASS 不代表运行通过。

**安全质量门禁**（只读搜索 + 代码审查）：
| 检查项 | 结果 |
|---|---|
| 源码真实凭据/token（`ghp_`/`gho_`/`sk-`/Bearer） | 0 命中 |
| 私钥文件（`.pem`/`.key`/`.jks`/`.keystore`，排除 build） | 0 命中 |
| 生产通配符域名授权 | 0 放行命中；命中的是拒绝通配符的检查代码（`CandidateNetworkPolicy.kt:54`）与验证被拒的测试用例 |
| 关闭 TLS 校验（`trustAll`/`acceptAllCerts`/`allowInsecure`） | 0 命中 |
| 未审核脚本进入正式目录 | 审计确认审核/SHA/静态/冒烟门槛分离（`SourceVersionStore.kt:300-362`），正式目录 `sources` 为空 |
| SSRF/任意重定向回归 | Gateway 测试覆盖，默认 DENY_ALL 重定向 |

安全扫描零命中不等于绝对安全；DNS rebinding、第三方动态端点、诊断 ZIP 依赖 AppLogger 约定等风险已结合代码审查记录（见 §1 模块 7/9）。

---

## 4. K2.7 发布候选构建状态

| 产物 | 状态 | 路径 / 结果 | SHA256 |
|---|---|---|---|
| Android Debug APK | **BUILD_PASS** | `androidApp/build/outputs/apk/debug/androidApp-debug.apk`（36,861,777 B；`:androidApp:assembleDebug` BUILD SUCCESSFUL） | `22c80fb2aac84b64aabf7b559c2a2e4e1a07fe4fa2788496658d4af70bf65b1a` |
| Android Release APK | **BLOCKED** | Release 签名依赖环境变量 `FUO_SIGNING_STORE_FILE`/`fuo.signing.*`（`androidApp/build.gradle.kts:49-115`），本环境无签名密钥文件，按纪律不用 Debug APK 冒充正式签名版 | — |
| Windows 桌面可分发包 | **BLOCKED** | `:desktopApp:createReleaseDistributable` 在 `buildNucleusAudioCaptureLibrary` 失败：`A problem occurred starting process 'command cargo'`——本环境无 Rust/Cargo 工具链（README 亦说明桌面打包需 Rust）。Native mpv bridge 已在 K1 用 clang 编译可用，但音频采集 native helper 缺 cargo 无法构建 | — |
| Desktop Kotlin/JVM 编译 | **BUILD_PASS** | desktopApp main/test Kotlin 编译在 K1 与本次核心改动验证中通过（非完整安装包） | — |
| 冻结代码基线 SHA | `865918c`（本阶段代码提交，文档提交不改源码；完整 SHA 见 release-candidate-checklist 冻结记录） | — | — |

状态说明：BUILD_PASS 只表示构建任务成功，不代表真机运行或真实播放通过（见状态定义）。Android Debug 与 Release、Windows 完整包的区分如实保留，不混为一谈。

---

## 5. K2.8 最终发布候选审计——十二问

| # | 问题 | 回答 | 代码位置 / 测试证据 |
|---|---|---|---|
| 1 | 普通用户能否不配置脚本直接进入应用？ | **能（代码侧）** | 3 页中文引导 `OnboardingFeature.kt:64-322` + 默认 5 源幂等迁移 `AppSettingsContracts.kt:219-234`；onboarding 8 + defaults 7 测试。真机冷启动未测（NOT_RUN） |
| 2 | 搜索/播放/暂停/切歌/停止完整实现？ | **是** | `SearchFeature.kt:334-372`、`DesktopMpvPlaybackEngine.kt:104-141`、`FuoPlaybackService.kt:51-343`；playback 40+ 测试 + Desktop 真实解码 >30s（K1） |
| 3 | 多音源故障是否隔离？ | **是** | `SearchFeature.kt:338-369` + `KotlinProviderRepository.kt:113-133`；`globalSearchKeepsSuccessfulSourcesWhenOneProviderFails` 等测试 |
| 4 | 音质信息是否真实表达？ | **是（声明≠认证）** | `AudioQualityProfile.kt:51-63` 5 级 evidence、`FlacValidator.kt:23-33` 明确不做母带认证；缺口：evidence 未回填 lossless（遗留） |
| 5 | 下载许可是否逐曲控制？ | **是（本阶段实现）** | `TrackLicense.kt` 门控 + `JamendoMusicProvider.kt:128-152` 逐曲 BY-NC-ND 编码 + `DownloadFeature.kt` 拦截；`TrackLicenseTest` 8 + 门控测试 |
| 6 | Provider 更新是否安全？ | **是（机制）** | Ed25519+SHA256+反重放+审核门槛 `SourceVersionStore.kt:300-362`、`PublishedCatalogTest:37` JDK 独立验签；缺口：正式目录空未签发、DNS rebinding 未解决（遗留/已记录） |
| 7 | 本地音乐与播放列表是否可用？ | **是** | `AndroidLocalMusicRepository.kt:197-274`、`LocalPlaylistRepository.kt:107-376`；localmusic/playlist 测试 |
| 8 | NAS/Subsonic 是否具备完整代码链路？ | **部分** | 搜索/URL 有 `SubsonicClient.kt:53-111`；缺口：密码明文、无连接测试、iOS 无实现、WebDAV 无原生（高优先遗留） |
| 9 | Android 能否构建正式候选 APK？ | **Debug BUILD_PASS / Release BLOCKED** | Debug APK SHA 见 §4；Release 因无签名密钥 BLOCKED |
| 10 | Windows 能否构建安装包？ | **BLOCKED** | 缺 cargo，`buildNucleusAudioCaptureLibrary` 失败（§4） |
| 11 | 自动化测试是否全部通过？ | **是（开发侧）** | desktop 1049 + androidHost 925 + App 34 = 2008 项 0 失败；iOS 编译 PASS；真机/UI 测试 NOT_RUN |
| 12 | 还剩哪些只能依赖真实设备和网络验证？ | 见 release-candidate-checklist §6 | Android 真机出声、家庭 Wi-Fi/移动数据直连、声学身份、音频焦点/蓝牙/耳机/网络恢复、iOS Simulator |

**K2 完成判定**：产品功能盘点 17 模块全部有状态；普通用户体验/搜索/许可/Provider/下载/本地/播放器等开发侧核心能力有代码+测试证据；全量自动化测试 2008 项 0 失败；安全扫描 0 高危命中。**发布候选 Android Debug APK 与 Desktop Kotlin 编译状态明确，Release 签名与 Windows 完整包因环境依赖 BLOCKED 并如实记录。真实设备与网络项全部 NOT_RUN，按纪律不涂绿。**

K2 的开发侧目标达成；下一步冻结候选 SHA 并进入 K3 真机/真实网络验收。所有 NOT_RUN/BLOCKED 项均有明确原因与解除条件，详见 `release-candidate-checklist.zh-CN.md`。

