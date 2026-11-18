# FuoEvolve 私人音乐中心改造记录

本文记录面向 Android + Windows 个人使用场景的改造目标与验收边界。

## 产品目标

- 中文流行音乐搜索覆盖率优先。
- 播放和下载请求当前账户有权访问的最高音质。
- 优先真正无损；没有无损时回落到音源可提供的最高档。
- Android 与 Windows 保持一致的搜索、匹配和音质策略。
- 不绕过会员、DRM、访问控制或平台版权限制。
- 本地文件/NAS 用于管理用户合法取得的音乐，不向外部提供曲库服务。

## 已落地的代码改造

### 音源默认配置

新安装默认启用 `netease`、`qqmusic`、`bilibili`；`ytmusic` 保留为可选音源。默认顺序与 Android `providers.json` 保持一致。

### 搜索容错

全局搜索按来源并行执行。单个音乐源失败时，将错误作为该来源的空结果合并，不丢弃其他来源的成功结果；本地音乐库搜索失败也不会阻断在线来源。取消搜索仍然向上抛出，避免取消被误判成普通错误。

每个独立来源增加 12 秒超时：某个服务端卡住时，不再拖住其他已经返回的来源；全局搜索仍保留 25 秒总时限。单来源超时会转成该来源的错误结果，成功来源照常展示。部分来源失败但有结果时，搜索状态提示“部分音源不可用”。

### 音质默认策略

新安装的 Wi-Fi 和移动网络音质策略默认请求 `Highest`。该策略表示向音源请求最高可用档位，不代表歌曲必然有无损资源；最终由各音源按账户权限与实际资源回落。

### Android FLAC 筛查与取消处理

- 无损候选匹配增加歌名、歌手与时长硬门槛，并排除未请求的现场、翻唱、伴奏、纯音乐等版本，优先避免误播。
- 本地 `file://` 地址不再一律当作无损；只有识别到 FLAC/APE/ALAC/WavPack/DSF/DFF 等扩展名，或音质元数据明确标记无损编码时，才跳过增强。
- FLAC STREAMINFO 解析增加测试覆盖。
- 网络读取、频谱分析和播放/下载增强在协程取消时会继续传播 `CancellationException`，不再把用户取消误判为普通音源故障。
- 质量标签区分“仅容器检查通过”和“频谱启发式检查通过”。频谱检测不是无损母带真实性证明。

下载质量选择器本身仍保留“无损 / 最高可用”优先选项。

### Windows FLAC 播放链路（第一阶段）

- `desktopRuntime` 新增桌面端独立 FLAC STREAMINFO 解析器和 HTTP 头部探测器，不依赖 Android `MediaCodec`。
- Windows 播放 Provider 已接入可选装饰器：原音源照常解析，再依次尝试自建 Navidrome/Subsonic、Jamendo、Internet Archive；只有候选匹配通过且远端 FLAC 头部解析成功才切换播放。
- 用户关闭“自动寻找 FLAC 高音质源”时不发起额外检索；检索最多等待 8 秒，找不到候选、超时或网络失败时保留原音源；协程取消会继续向上传播。
- Windows 下载流程现在会在落盘前调用桌面运行时校验钩子：FLAC URL 或 FLAC 音质标签的下载必须通过 STREAMINFO 头部检查；若系统可用 FFmpeg，还会执行完整音频解码，解码失败或超时会拒绝保存。
- Windows 完整解码校验通过 `FUOEVOLVE_FFMPEG_PATH` 或 JVM 属性 `fuoevolve.ffmpeg.path` 指定 FFmpeg 可执行文件；未安装/不可启动 FFmpeg 时只执行容器校验，不会把它标记成“完整解码通过”。
- Android 下载在写入系统音乐库前会复检 FLAC STREAMINFO；应用重启恢复时也会重新读取已完成 FLAC 文件，发现缺失、头部损坏或参数异常会将任务恢复为失败状态，允许用户重试。
- 下载文件扩展名优先参考解析出的音质标签，因此 Subsonic `/stream.view` 这类端点也会正确保存为 `.flac`。
- 新增 Windows 独立 FLAC 磁盘缓存：优先命中本地已校验缓存，因此断网时可直接播放已缓存曲目；新发现的远端候选在后台下载，不阻塞当前播放。
- 缓存文件通过临时文件校验后才原子替换为正式 `.flac` 文件；支持按最近使用时间淘汰，默认上限 1.5 GiB；已知远端文件大小时会比对下载字节数。
- Windows 在 FFmpeg 可用时具备完整解码校验，但没有频谱启发式筛查；FFmpeg 不可用时降级为容器/STREAMINFO 检查。Android 下载复检目前也是容器/STREAMINFO 级别。任何这些检查都不能证明文件来自真正无损母带。

## 已增加的回归测试

- 新安装默认启用中文音乐发现来源。
- 新安装全局搜索会查询全部默认启用来源。
- 用户显式设置的搜索来源仍被尊重。
- 已禁用的来源不会进入搜索。
- 单个在线来源失败时，其他来源的结果仍然保留。
- 本地音乐库失败时，在线搜索结果仍然保留。
- 全局搜索中单个音源持续不返回时，会在 12 秒后隔离该来源，保留其他音源的成功结果；用户主动取消搜索仍会正常取消整个任务。新增 `globalSearchKeepsFastSourcesWhenOneProviderTimesOut` 回归用例；另用独立 Kotlin 协程运行 harness 验证慢源超时后快源结果仍能返回。
- 新安装的 Wi-Fi 与移动网络音质默认请求最高可用档位。
- Windows FLAC STREAMINFO 解析器新增有效头部、非 FLAC、截断头部、错误元数据块、零采样率与零采样数测试；文件校验器新增有效/无效文件测试。
- Windows 无损缓存新增缓存命中、损坏缓存自动删除、远端大小不符拒绝安装、超出上限时淘汰最久未使用文件的测试。
- Windows 下载扩展名测试覆盖 Subsonic `.view` 端点按 FLAC 音质标签保存，以及普通 MP3 保留原扩展名。
- Windows 下载仓库新增重启恢复测试：已完成的 FLAC 文件若在下载后损坏，重启加载时会被重新标记为失败，且不会继续作为已完成文件提供。
- Windows 新增 FFmpeg 完整解码验证器及“解码器不可用时明确降级”的测试；使用真实 FFmpeg 对有效 FLAC 与截断 FLAC 做了命令行冒烟验证，分别得到通过与拒绝结果。

## 2026-11-17 第二轮：双向版本匹配、账户音质与统一音质模型

### 跨音源版本匹配

- 新增 `core/model/src/commonMain/kotlin/org/feeluown/mobile/SongVersionMatch.kt`：把「现场 / Live」「翻唱 / Cover」「伴奏 / Instrumental」「混音 / Remix」「不插电 / Acoustic」「Demo」「独唱 / Solo」「变速版」「重制版」等中英标记统一到 `SongVersionMatcher`，Android 与 Windows 的无损候选匹配、跨音源智能替换共用同一套规则。
- 版本校验改为**双向**：候选多出标记（普通版被 Live / 翻唱顶替）会被拒绝，候选缺少请求中的标记（请求 Live 却拿到录音室版）同样会被拒绝。
- 标题比较前只去掉**末尾**的版本标记（`晴天 (Live)`、`晴天现场版`、`学不会 - LIVE`）；`Live Forever` 这类歌名开头的 Live 不受影响。跨语言时 `晴天 現場` 与 `晴天 (Live)` 可以互相匹配。
- 智能替换在打分前增加硬门槛：版本冲突、或同标题不同歌手（B 站除外，其 `artists` 是 UP 主）直接排除，不再只依赖 0.55 的加权分数。
- 回归用例：`SongVersionMatcherTest`、两端 `LosslessCandidateMatchingTest` 的双向用例、`PlaybackSmartReplacementPolicyTest` 的兼容性用例。

### 免费账户的实际音质

- 网易云：新增 `NeteaseQualityAvailability.kt`，只信账户维度字段 `downloadMaxbr` / `dl` / `playMaxbr` / `pl` / `st` / `*Level`；`maxbr` 仅作为旧版接口兜底。字段存在但为 0 时返回「无可用码流」，不再把「文件存在」当成「账号可取」。
- QQ 音乐：`availableQualities` 改为用真实 vkey 逐档探测，首个返回非空 `purl` 的档位即当前账户上限，较低档位按权限层级视为可用；结果按歌曲 + 账号缓存 5 分钟，避免反复打开弹窗重复探测。
- 修复空 `purl` 被拼成 CDN 根地址的问题：之前会让调用方以为取流成功，实际拿到的是不可播放的假地址。
- 下载音质弹窗区分三种结果：无法判断（保留通用档位）、部分可用（隐藏不可用档位并说明原因）、全部不可用（空列表 + 明确提示）。

### 统一音质模型

- `playback/api/AudioQualityProfile.kt`：`AudioQualityTier`（档位 / 请求策略 / 短标签 / 排序）+ `AudioQualityProfile`（编码、容器、码率、采样率、位深、声道、是否无损、证据来源）。
- `AudioFormatInfo` 增加 `sampleRateHz` / `channelCount` / `bitsPerSample`，并提供 `toQualityProfile()`；`PlaybackPayload` 增加 `qualityProfile`。
- 网易云 / QQ / B 站在解析出的播放载荷里带结构化音质；网易云与 QQ 标记为 `AccountVerified`（已用账户实际取流验证）。
- Android（Media3 `Format`）回填采样率与声道；Windows（mpv `audio-params`）解析 `samplerate` / `channel-count`；播放器「音频信息」展示采样率、位深、声道。
- `normalizedAudioQualityLabel` 优先使用结构化档位，字符串嗅探仅作兜底；位深只在容器或解码器明确给出时填写，不猜测。

## 后续开发顺序（更新）

1. ~~补充跨源匹配与版本标记测试~~ → 已完成双向版本校验与中英标记统一。
2. ~~审查 QQ / 网易云免费账户音质探测~~ → 已完成账户维度探测；仍建议用真实免费账号做一次人工核对。
3. ~~抽象共享音质模型~~ → 已落地 `AudioQualityProfile`；下一步可把该模型持久化进下载记录与离线曲库。
4. 为 Windows 增加频谱启发式筛查；Android 侧评估 MediaCodec 完整解码校验。
5. NAS / Subsonic 的跨平台配置、凭据安全存储与端到端播放验证。
6. Windows 安装包打包（`packageDistributionForCurrentOS`）实操验证。

## 本轮实际执行的验证（2026-11-17）

| 范围 | 命令 | 结果 |
| --- | --- | --- |
| 版本匹配模型 | `:core:model:desktopTest` | 11 项通过 |
| 音质模型 | `:playback:api:desktopTest` | 8 项通过 |
| 播放/替换 | `:feature:playback:desktopTest` | 149 项通过 |
| 搜索 | `:feature:search:desktopTest` | 9 项通过 |
| 下载 | `:feature:download:desktopTest` | 4 项通过 |
| 投屏 | `:feature:cast:desktopTest` | 43 项通过 |
| 组合层 | `:shared:desktopTest` | 330 项通过 |
| 网易云 | `:provider:netease:testAndroidHostTest` | 22 项通过 |
| QQ 音乐 | `:provider:qqmusic:testAndroidHostTest` | 25 项通过 |
| B 站 | `:provider:bilibili:testAndroidHostTest` | 13 项通过 |
| Windows 运行时 | `:desktopRuntime:test`（JDK 25） | 56 项通过 |
| Android 单测 | `:androidApp:testDebugUnitTest` | 34 项通过 |
| Android 打包 | `:androidApp:assembleDebug` | BUILD SUCCESSFUL，产出 37 MB debug APK |
| Windows 编译 | `:desktopApp:compileKotlin`（JDK 25） | BUILD SUCCESSFUL |
| 架构边界 | `checkArchitectureBoundaries` / `checkP4ContractBoundaries` / `checkCastFeatureBoundaries` / `checkAppLoggerUsage` / provider 边界 | 全部通过 |

合计 704 项测试，0 失败。

### 收尾时追加的两处判定调整
| 位置 | 调整 | 原因 |
| --- | --- | --- |
| `feature/playback/.../PlaybackSmartReplacementPolicy.kt`（`isReplacementCompatible`） | 原曲或候选缺歌手信息时视为「未知」放行，继续参与加权打分；仅当双方都有歌手且得分低于 `MIN_REPLACEMENT_ARTIST_SCORE` 才拒绝 | 之前缺字段被当作「不匹配」直接拒绝，会让 B 站标题型曲目、纯音乐、部分网易云翻唱条目永远拿不到替换机会 |
| `provider/netease/.../NeteaseQualityAvailability.kt` | 探测到无损档时同时补齐 `>>>` 与 `hq<>` 两个高档 | 账户能取 FLAC 时，320k 必然可取；之前只给 `>>>` + 低档，弹窗会漏掉 320k 选项 |

### 环境说明

- Gradle 9.5.0 与 JDK 17 用于绝大多数模块；`desktopRuntime` / `desktopApp` 声明 JDK 25 toolchain，本轮把 Temurin 25 放在 `E:/mypro/.tools/jdk-25.0.5+8`，通过 `-Porg.gradle.java.installations.paths=...` 指给 Gradle。
- `desktopRuntime` 的 FLAC 校验测试新增可注入的解码校验钩子：容器级用例不再依赖本机是否安装 FFmpeg；同时把最小文件阈值提高到 1 KiB，只有 STREAMINFO 头、没有任何音频载荷的「文件」会被判为截断。


## 投屏连播（2026-11-17 晚）

原始实现只把「当前这首歌」推给电视：渲染器只有一个 URI，手机端不动，电视播完就停在空 URI 上。

修复思路是让渲染器也成为一个时钟来源：

| 位置 | 改动 |
| --- | --- |
| `feature/cast/.../CastPorts.kt` | 新增 `CastTransportSnapshot`（state + position/duration）与 `CastTransportPort.transportSnapshot`（默认退化为只读状态） |
| `feature/cast/.../DlnaProtocol.kt` | 新增 `parseTimecodeMillis`，吃 `GetPositionInfo` 的 `HH:MM:SS` |
| `feature/cast/.../CastController.kt` | 播完检测 + 推进队列 + 结束整个会话；`castPollTicker()` 由 app 注入，控制器自己不持有时间 |
| `androidApp/.../AndroidCastTransport.kt` | `transportSnapshot` 组合 `GetTransportInfo` 与 `GetPositionInfo` |
| `shared/.../feature/cast/CastFeatureFactory.kt` | 注入 ticker；投屏期间把手机静音（`activeDevice` 维度），停止后还原；静默推进时保持暂停态 |
| `androidApp/.../AndroidNativeAudioEngine.kt` | 补上 `setVolume`（之前是空实现），并在 controller 重连后重新应用音量 |

判定细节（都是踩过的坑）：

- **`PLAYING → STOPPED` 才算播完**：`SetAVTransportURI` 之后电视常先回 `STOPPED`，必须先见过一次播放状态，否则会误跳下一首。
- **位置卡住才算播完**：少数电视播完仍报 `PLAYING`，只有 `position` 到达末尾且连续两次不变才推进，避免把最后两秒切掉。
- **手机已经切歌时不重复推进**：`pushedTrackId` 与 `currentTrack()` 对比，先动的一方说了算。
- **`remotePaused`**：面板上暂停过之后，后续推新 URI 只 `SetAVTransportURI` + `Pause`，不会自己响起来。
- **队列播完**：释放代理流、停掉看护、回到 Idle 并提示「队列已经播完了」。

这一轮的取舍：手机端在投屏期间仍然在播（静音），作为队列时钟，省电模式下也能保住前台服务；代理流与手机本地流会各自取一次上游音频，带宽是 2×。

## 死代码清理与文档补齐（2026-11-18）

清理原则：只删「没有调用点、删掉后编译与测试都能过」的代码，不动任何仍被引用的公开契约。

### 删掉的代码

| 位置 | 内容 | 依据 |
| --- | --- | --- |
| `shared/.../feature/settings/SettingsFeatureScreen.kt` | `ProviderCatalogSettings`（139 行）、`ProviderAccountSettings`（230 行）、`ProviderDisplaySettingsDialog`（41 行） | `Expressive` 版本已接管设置页，这三个旧实现全文件无调用 |
| 同上 | `providerDisplaySummary`、`providerShownIn`、`providerStatusText`、`providerLoginModeLabel`、`PLAYBACK_REPORTING_PROVIDER_IDS` | 只被上面三个旧实现引用，随它们一起失去调用者 |
| `feature/cast/.../CastController.kt` | `NoOpCastSourcePort` | 从未被注入；宿主用的是 `createCastControllerPort` 里的匿名实现 |
| `core/model/.../SongVersionMatch.kt` | `SongVersionMatcher.describe()` | 无调用、无测试 |
| `playback/api/.../AudioQualityProfile.kt` | `withEvidence()` | 无调用；证据在构造时确定，不需要二次改写 |
| `androidApp` / `desktopRuntime` 的 `lossless/InternetArchiveClient.kt` | `JsonObject.boolean()` 与 `booleanOrNull` 导入 | 解析用的是 `JsonPrimitive` 直接取值 |
| `androidApp/.../AndroidLocalMusicRepository.kt` | `KEY_LAST_REFRESH_AT` | 键值从未读写 |
| 7 个文件 | 9 处未使用 import（`Column`、`Spacer`、`Job`、`Parameters`、`JsonElement`、`jsonArray`、`double` 等） | 编译期无效、纯噪音 |

`SettingsFeatureScreen.kt` 从 2138 行降到 1682 行（净减 456 行，其中 374 行是本次删除、其余为行尾差异）。

### 复核方式

用脚本做两轮静态扫描，而不是靠肉眼：

1. **未引用私有声明**：匹配 `private fun/val/var`（含扩展函数接收者），统计同文件内的标识符出现次数，`<= 1` 视为孤儿；删完再扫，级联孤儿一并处理。
2. **未使用 import**：按简单名在非 import 行里做词边界匹配；排除 `getValue/setValue`（`by` 委托隐式使用）与 `as` 别名导入（真实名字在 body 里不出现）。

最终两项均为 0。首次扫描出现的 12 处「孤儿」里有 7 处是扩展函数被正则误判成接收者类型，属于脚本缺陷而非代码问题，已修正后重扫。

### 验证

| 范围 | 结果 |
| --- | --- |
| 12 个测试任务 | **704 项，0 失败**（数与清理前一致，说明删的都是无用代码） |
| `:shared` / `:feature:cast` / 三个 provider / `:androidApp` / `:desktopApp` 编译 | BUILD SUCCESSFUL |
| 未引用私有声明 / 未使用 import | 均为 0 |

### 文档

| 文档 | 变更 |
| --- | --- |
| [`USAGE.md`](../USAGE.md) | 从「无损增强 + 投屏」的片段重写为 16 章完整手册：首次启动、界面、音源与账号、搜索、播放（含音质与智能替换）、无损增强、下载、投屏、本地音乐、识曲、歌单迁移、设置速查、缓存与更新、网络限制、故障排查、FAQ |
| [`docs/cast-dlna.zh-CN.md`](cast-dlna.zh-CN.md) | 新增投屏技术文档：分层、SSDP/SOAP 链路、连播判定表、代理流与 `network_security_config` 的取舍、手机静音、平台差异、已知限制、测试注意事项、排障 |
| [`docs/README.md`](README.md) | 新增文档索引，按「想做什么」分组，而不是按文件名 |
| [`docs/FUOEVOLVE_PRIVATE_MUSIC_FIRST_PATCH.md`](FUOEVOLVE_PRIVATE_MUSIC_FIRST_PATCH.md) | 修正过时结论：原文写「无法下载 Gradle、不能报告编译通过」，现已补上真实验证结果并指向路线图 |
