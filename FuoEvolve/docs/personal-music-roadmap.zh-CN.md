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

## LX 音源脚本运行时（2026-11-18 晚，Phase 0 完成）

### 结论

App 内嵌 JS 引擎执行 LX Music 自定义音源脚本**技术可行，已在本仓库实测通过**。
新增模块 `:provider:lxruntime`，8 项测试在 desktop 与 Android hostTest 双端全绿。

### 引擎选型（原方案 §8.3 的答案）

| 项 | 结论 |
| --- | --- |
| 依赖 | `io.github.dokar3:quickjs-kt:1.0.15`（Apache-2.0，Maven Central） |
| 覆盖 | android（arm64-v8a / armeabi-v7a / x86 / x86_64 全带 .so）、jvm、iosArm64、iosSimulatorArm64 |
| 超时中断 | `evaluationTimeoutMillis` 由 native 侧强制中断，`while(true){}` 实测可被打断 |
| 前置状态 | **该依赖此前已用于 `:provider:ytmusic` 的 `YtMusicJsChallengeSolver`**，不是新引入 |

### 协议基准：以官方移动端文档为准

原方案称「不能仅凭社区脚本猜协议」。实际官方文档已给出移动端的**权威 API 子集**，比桌面端小一个数量级：

- `lx.env` 固定 `mobile`；`EVENT_NAMES` 仅 `inited` / `request` / `updateAlert`
- `lx.request(url, options, cb)`，回调 `(err, resp, body)`；社区脚本普遍写 `(err, resp) => resp.body`，本实现两者都兼容
- `lx.utils.buffer.from` / `bufToString`：**仅** `base64` / `hex` / `utf8`
- `lx.utils.crypto`：**仅** `aes-128-cbc` / `aes-128-ecb` / `md5` / `randomBytes` / `rsaEncrypt`
- `lx.utils.zlib`：**官方标注移动版未实现**，因此不需要 Node 兼容层
- 宿主环境 API：**只有** `setTimeout` / `clearTimeout`

据此，原方案担心的「Buffer/crypto/zlib 全量 Node 兼容」不成立，工作量主要落在 `SetAVTransportURI` 之外的协议映射与 Provider 接入。

### 与原方案的偏差（不照抄的部分）

| 原方案 | 实际做法 | 原因 |
| --- | --- | --- |
| 3 个平台 runtime 实现类 | 单一 `LxScriptRuntime`（commonMain） | quickjs-kt 已用 KMP 抹平平台差异，再抽 expect/actual 是多余抽象 |
| `provider/sourceupdate` 先行 | 暂缓 | 原方案自己承认「需真实脚本样本」，前置未满足 |
| 强制 HTTPS | 允许明文、**拒绝私网** | 国内音乐接口多为 http（NSC 已放行明文）；真正的边界是 SSRF |
| 9 个文件拆 LxScriptModels/Runtime/Adapter/Mapper… | 先 5 个文件收敛职责 | 未接入 Provider 前拆细只会产生空壳 |

### 已验证项（`:provider:lxruntime`）

| 测试 | 覆盖 |
| --- | --- |
| `initialize keeps only music sources` | `local` 与非 `music` 类型被排除，音质按官方顺序归一 |
| `requestMusicUrl returns the url` | 加载 → inited → `request(musicUrl)` → 拿到 URL |
| `script without inited is rejected` | 无 `inited` 判为初始化失败，不当可重试 |
| `script that never registers request` | 未注册 handler 时派发明确报错 |
| `busy loop is interrupted` | 死循环被 QuickJS 中断，不拖死播放 |
| `lx request goes through the host and rejects private addresses` | 网络由宿主代理；`192.168.x.x` 等私网被拒（SSRF） |
| `buffer helpers support hex utf8 and base64` | 三种编码往返一致 |

### 关键实现点

1. **Promise 取值**：脚本回调按官方要求返回 Promise，而 quickjs-kt 的普通 `evaluate` 不等待 Promise。
   改为以 ES module 执行并利用顶层 `await`，结果落回全局变量再读。这是本模块唯一非显然的技巧。
2. **超时只交给 QuickJS**：JS 求值是阻塞调用而非挂起点，协程 `withTimeout` 无法打断它，
   在虚拟时间调度器（测试）下还会误判超时。因此 `evaluate` 只用 `evaluationTimeoutMillis`，
   协程超时仅用于 `lx.request` 的网络等待。
3. **crypto 明确失败**：`aesEncrypt` / `md5` / `rsaEncrypt` / `randomBytes` 目前抛
   「尚未实现」而不是返回错值。依赖这些原语的脚本会明确报错，不会解出错误 URL。

### Phase 1 已完成部分（见下一章）

- `LxScriptProvider` 已接入 Provider Registry、搜索链路与设置页
- 脚本安装、启停、隔离与远程自动更新均已实现
- crypto 原语仍是明确失败：需要它的脚本会报错，不会解出错误 URL

---

## 六、受控音源目录与脚本自动更新（Phase B/C）

> 依据：`FuoEvolve 中国大陆优先音源目录与自动接入实施方案 v1.0`
> 结论先行：建立 **FuoEvolve 自己维护的受控目录**，社区仓库只当候选来源；
> **首版目录 `sources: []` 保持空数组**，因为没有任何脚本通过完整审核。

### 6.1 新增模块

| 模块 | 职责 | 关键约束 |
| --- | --- | --- |
| `provider/lxruntime` | LX 脚本执行（QuickJS）+ 脚本 Provider | 只执行 `APPROVED` 且已启用的脚本；网络经宿主代理并校验域名白名单 |
| `provider/sourceupdate` | 目录拉取、验签、静态扫描、版本仓库、退避策略 | 只装 `autoInstall && APPROVED`；失败绝不动 active 指针 |
| `provider/jamendo` | 开放曲库搜索/播放 Provider | 标注 `jamendo-mp3`，不冒充原曲，默认不启用 |
| `config/source-catalog` | 目录 JSON + 签名 + schema + 候选审核记录 + 签名工具 | 私钥不入库；`candidates.json` 客户端不读 |

### 6.2 信任链

```
客户端内置公钥（SourceUpdateDefaults.TRUSTED_CATALOG_KEYS）
   └─ 验 source-catalog.json.sig（Ed25519，独立 .sig 文件，不做 JSON 规范化）
        └─ 解析目录结构（schemaVersion=1、ID 正则、https、非私网、autoInstall 必须 APPROVED）
             └─ 下载脚本 → SHA-256 比对 → 静态扫描（eval/动态 import/Node/WebSocket/卡密/混淆/未声明域名）
                  └─ staging 落盘 → 切 active → 冒烟测试（真实 QuickJS 初始化）
                       └─ 失败：有上一版本则回滚，没有则移除
```

### 6.3 为什么签名单独一个文件

把签名写进 JSON 就要先定义"规范化序列化"，而规范化一旦实现有偏差，
就会出现"本机验得过、别的实现验不过"的隐性分歧。独立 `.sig` 文件签的是
**原始字节**，任何实现都只需要 `verify(bytes, sig, pubkey)`，没有第二种解释。

### 6.4 首版目录为空，但机制必须可验证

`config/source-catalog/source-catalog.json` 的 `sources` 是空数组。为了让这条链路
不是"写完没人跑过"，做了两件事：

1. 用仓库外的 Node 私钥签了一份 `catalogVersion=42` 的**测试夹具目录**，
   在 `commonTest` 里走完整链路（拉取 → 验签 → 解析 → 下载 → 安装 → 回滚）。
2. `PublishedCatalogTest`（desktopTest）用 **JDK 自带 Ed25519** 独立验证仓库里
   那份真实目录能被 **客户端内置公钥** 验证通过——产线会犯的"换 key 忘同步"
   这类错误，在 CI 就会被拦下来。

### 6.5 更新节奏与平台差异

| 平台 | 调度 | 说明 |
| --- | --- | --- |
| Android | WorkManager 周期任务（24h，`NetworkType.CONNECTED`）+ 启动补查 | 系统可延迟；真正的"要不要检查"由策略判定，任务被提前唤醒只是空转 |
| 桌面 | 应用启动时 `checkIfDue()` | 关掉应用不承诺执行 |
| iOS | 每次进入前台 `checkIfDue()` | 系统不保证每日后台执行，按文档口径只承诺前台补查 |

策略：正常间隔默认 24 小时（设置页可选 12/24/48/168）；失败退避 1h → 4h → 12h → 24h；
目录请求带 ETag/If-None-Match；镜像连续失败 2 次熔断 30 分钟。

### 6.6 设置项（全部可空，旧设置文件可解码）

`sourceAutoUpdateEnabled`(true) / `sourceUpdateIntervalHours`(24) / `sourceMirrorPolicy`("hybrid") /
`sourceCatalogVersion`(null) / `installedScriptVersions`(空) / `quarantinedScriptIds`(空)。

`sourceCatalogVersion` 与 `installedScriptVersions` **只是展示镜像**：真正的安装状态在
`sources/index.json`，决策一律读 index，避免设置文件与真实状态分叉。

### 6.7 音源优先级

原生 Provider → 已审核 LX 脚本 → 开放曲库（Jamendo）→ 用户自建服务，顺序体现在
`ProviderComposition.factories()` 与 `DEFAULT_PROVIDER_ORDER_IDS`。

`lxscript` **不在默认启用集合**里：空目录时它只会让搜索多一个永远为空的来源；
但目录第一次真正装上已批准脚本时，`SourceUpdateHost` 会同时更新设置与内存注册表
把它打开——否则"自动启用"只完成一半，用户还得自己去音源列表勾一下。

### 6.8 已验证项

| 测试 | 覆盖 |
| --- | --- |
| `ShaTest` / `CryptoTest` | SHA-256/512 标准向量；Ed25519 RFC 8032 TEST 1/2/3 + 200 字节多分块 + 篡改 |
| `SourceCatalogTest` / `SourceUpdateTest`（双端） | 解析、静态扫描、产物哈希、签名、安装事务、先冒烟后激活、回滚、退避、镜像熔断、协调器全链路 |
| `PublishedCatalogTest` | 仓库内真实目录 ↔ 内置公钥一致；`autoInstall` 为空 |
| `ResolverCatalogTest` | 解析器目录协议：kind/scheme/超时/重复 id/自托管告警 + 健康熔断 |
| `LxScriptProviderTest` / `LxTimerTest` / `LxNetworkPolicyTest`（双端） | 搜索委托、档位透传、FLAC 拒绝降级、目录裁剪、停用即失效、版本升级重载、原始字段透传、端到端身份核验、受控计时器、出站白名单边界 |
| `StoreLxInstalledScriptSourceTest` | index → 待执行脚本的过滤（启用 ∩ 批准 ∩ 非隔离 ∩ 文件存在） |
| `SourceUpdateSettingsTest` | 默认值、旧 JSON 解码、间隔夹取、空策略回落 |

### 6.9 已知限制

- 首版没有任何脚本被批准，所以 `lxscript` 搜索恒为空（这是设计结果，不是缺陷）。
- 搜索能力来自内置酷我搜索接口：LX 协议本身没有搜索事件。
- `crypto` 原语（md5/aes/rsa）未实现；依赖它们的脚本会明确失败。
- 目录托管在 `miaohongyun/my_FeelUOwn` 的 GitHub Raw；国内可达性靠 `ghproxy.net` / `gh-proxy.com` 两个公共镜像兜底，正式发布前建议自建镜像并固定 commit。

### 6.10 安全边界加固（代码审查后，2026-11-19）

对 master 源码逐行审查后修复了七处真实缺陷。这些不是"测试没覆盖"，而是边界语义本身就错，逐条列出以便复核：

| 缺陷 | 修复后行为 | 验证 |
| --- | --- | --- |
| 安装事务只看 `blocked`，WARN（`document.cookie`/`localStorage`/XHR）脚本仍可自动安装 | 事务内 BLOCK 与 NEEDS_REVIEW 一律拒绝；审核门槛（`APPROVED` + 许可证 + 非空 allowedHosts + autoInstall）下沉到事务自身，调用方绕不过 | `transactionRejectsWarnLevelStaticFindings` / `transactionRejectsUnapprovedOrHostlessEntries` |
| 新版本先切 active 再冒烟，冒烟期间 Provider 可能已加载坏版本 | 改为"先验证后激活"：写 staging → 独立实例冒烟 → 通过后才 writeVersion + 切 active；失败返回 `SmokeTestFailed`，active 从头到尾没动过 | `smokeTestFailureDuringUpgradeKeepsOldVersionActive` |
| 空 `allowedHosts` 在运行时放行全部网络，与目录提示"会被全部拒绝"正好相反 | 空名单直接拒绝一切请求；目录解析器同时拒绝 `autoInstall && allowedHosts=[]`（两道防线同向） | `LxNetworkPolicyTest`（8 项）/ `rejectsAutoInstallWithoutAllowedHosts` |
| 非标准 IPv4（`0x7f000001` / `2130706433` / `127.1` / `0177.0.0.1`）绕过字符串私网判断 | 出站前先归一化 IPv4 再判段，补 CGNAT/组播/保留段/元数据地址 | `rejectsNonStandardIpv4NotationsOfLoopback` |
| `runCatching` / `catch (Exception)` 吞掉 `CancellationException`，用户取消被当普通错误 | LX Provider、酷我搜索、目录拉取、Jamendo 客户端、事务冒烟统一 `catch (CancellationException) { throw }` | 各模块单测双端全绿 |
| 脚本缓存键只有 `version:source.length`，同长度改码不失效 | 改为 `id:version:SHA256(source)`；复用 `provider:sourceupdate` 的纯 Kotlin SHA-256 | `LxScriptProviderTest` |
| 搜索结果把 songId 赋给 `songmid`/`hash`/`strMediaMid` 所有键 → 《晴天》可能解析成别的歌 | `LxSongHit` 增加 `rawFields` 保留平台原始字段，按 identifier 缓存后原样透传给脚本；缺失时才回退 songId | `resolvePassesPlatformRawFieldsToScript` / `endToEndSearchThenLxRequestResolvesMatchingSongIdentity` |

另外补齐了官方要求的 `setTimeout`/`clearTimeout`：计时回调留在 QuickJS context 内执行，宿主的延时协程挂在专用 scope 上，`close()` 时整体取消（关闭后回调不再执行、不泄漏协程）。测试覆盖正常触发/延迟/Promise reject 回传/清除/并发/关闭失效/重载重置/重复加载不泄漏（`LxTimerTest` 7 项）。

> **2026-11-19 复查追加**：首轮 `clearTimeout` 只删 JS 侧标记、不通知宿主，被清除的长 timer 协程会一直挂到 runtime 关闭（真实资源泄漏）。修复为 `__lxDelay(id, ms)` + `__lxCancelTimer(id)`：每个 timer 用 `CompletableDeferred` 作取消信号，`select { onAwait / onTimeout }` 等待，`clearTimeout` 完成对应信号即精确唤醒退出。测试用 `activeTimerCount()` 直接断言登记表归零（**不**用"脚本未加载抛异常"冒充取消）。另有一个重要时序事实：quickjs-kt 的 asyncFunction 从 JS 调用到 Kotlin lambda 登记有约 100ms 调度延迟，且只有当某个 await 点 pump 了 job queue 时才推进——测试脚本的 handler Promise 必须由 timer 回调 resolve 才能稳定观测到 pending timer，这是 quickjs-kt 的调度特性，不是产品缺陷。

> **仍未证明的部分**：以上测试用的是符合官方协议结构的脚本 + MockEngine 真实响应结构，不是真实社区脚本（例如 LX 的酷狗/QQ 混淆脚本）跑真实网络。要验证真实脚本兼容性，需要把 1~2 个在用的 `.js` 文件接入做专项审查——这类脚本可能依赖尚未实现的 `crypto` 原语，属于"明确失败"而非"静默出错"。`DNS 解析结果检查`（防 DNS rebinding）跨端无法在 commonMain 用纯 Kotlin 实现，当前靠"域名白名单 + 逐跳重定向校验"约束，真机阶段需确认平台网络栈不会把白名单域名解析到内网。

### 6.11 LX 官方移动端 API 兼容矩阵（逐项核实，不虚构）

依据 LX Music 官方移动端自定义音源文档与 `LxBridge.kt` / `LxScriptRuntime.kt` 现有实现逐项核对。
**"已实现"仅指协议映射存在且有自动化验证，不等于"真实社区脚本兼容"**（见下方风险）。

| 官方 API | 本实现状态 | 验证方式 / 说明 |
| --- | --- | --- |
| `lx.env = 'mobile'` | 已实现 | shim 注入固定值 |
| `lx.EVENT_NAMES`（inited/request/updateAlert） | 已实现 | `LxScriptRuntimeTest` 读 inited；shim 支持 updateAlert 记录 |
| `lx.on(event, handler)` / `lx.send(event, data)` | 已实现 | 全部单测；注意 `inited` 是脚本主动 send，宿主只读 |
| `lx.request(url, options, cb)`，回调 `(err, resp, body)` | 已实现 | shim 同时给 `resp.body` 与第三参 body；`LxScriptRuntimeTest` 走宿主 Ktor + MockEngine |
| `lx.utils.buffer.from` / `bufToString`（base64/hex/utf8） | 已实现 | `buffer helpers support hex utf8 and base64` |
| `lx.utils.crypto.md5` | 已实现 | 纯 Kotlin `Md5.hex`，RFC 标准向量 + hashlib 交叉验证；独家音源用其做自检 |
| `lx.utils.crypto.randomBytes` | 未实现 | 调用即抛 `LxScriptException`（明确失败，不返回伪造随机数）；无已批准脚本依赖 |
| `lx.utils.crypto.aesEncrypt`（aes-128-cbc/ecb） | 未实现 | 同上；唯一依赖者無名/小熊猫均为 REJECT/QUARANTINE 且接口已失效 |
| `lx.utils.crypto.rsaEncrypt` | 未实现 | 同上 |
| `lx.utils.zlib.inflate` / `deflate` | 未实现 | 返回 rejected Promise；官方移动版本身也未实现 |
| 宿主 `setTimeout` / `clearTimeout` | 已实现（含库限制） | `LxTimerTest` 13 项；可取消、close 全释放；quickjs-kt session 语义限制见 §8.1 |
| 宿主网络能力 | 已实现（受限） | 仅 http/https、非空白名单、私网/IPv6/非标准 IPv4 拦截、逐跳重定向校验 |

**未验证 / 明确风险（无真实脚本时不能宣称兼容）**：

1. **真实社区脚本本身**：`config/source-catalog/third-party-raw/` 已保存 13 份社区 `.js` 原版并做静态审查，其中 8 份完成"初始化→URL 解析"的 **MockEngine 模拟测试**（见第七章）。但**没有任何一份完成真实网络解析**，混淆脚本、依赖未实现 `crypto` 原语的脚本在真机上的行为仍**未验证**。
2. **真实网络解析**：搜索/解析的自动化都用 MockEngine 返回结构。§10.1 对候选接口做了真实 DNS/HTTP 探测（属网络可达性，非协议解析），结论是多数候选端点已失效；真实返回字段与歌曲身份仍未验证。
3. **歌曲身份**：`endToEndSearchThenLxRequestResolvesMatchingSongIdentity` 验证的是"脚本自报 mid == 请求 mid"的闭环，依赖脚本如实核验；若真实脚本不核验身份，宿主层目前**不做**音频内容与点选曲目的二次比对。
4. **宿主 QuickJS 差异**：quickjs-kt 1.0.15 的 ES 特性覆盖、模块加载语义与真实 LX 脚本预期可能有差异，需真实脚本验证。

### 6.12 中国大陆网络适配与隐私审查（代码级核实）

| 审查项 | 结论 | 依据 |
| --- | --- | --- |
| GitHub Raw 主地址 + 2 个公共镜像 | 已实现 | `SourceUpdateDefaults.CATALOG_URL` / `MIRROR_URLS`（ghproxy.net、gh-proxy.com） |
| 超时 | 已实现 | 目录 15s（`KtorCatalogFetcher`）、脚本 10s 可配、搜索 8s、初始化 5s |
| 重试与退避 | 已实现 | 连续失败 1/4/12/24h 指数退避 + 抖动（`SourceUpdatePolicy`） |
| 镜像熔断 | 已实现 | 单镜像 2 次失败熔断 30 分钟，到期半开恢复（`SourceMirrorPolicy`） |
| 失败不影响已装脚本 | 已实现 | 目录拉取失败只累加失败计数，保留 `index.sources` 与当前 active（`SourceUpdateCoordinator`；测试 `skipsWhenCheckedRecentlyAndBacksOffAfterFailure`） |
| 单音源故障隔离 | 已实现 | 搜索用 `supervisorScope + async`，单 Provider 异常转成本源 `failure`，取消语义保留（`KotlinProviderRepository.searchAll`） |
| 签名/哈希校验 | 已实现 | Ed25519 验目录原始字节 + SHA-256 验脚本；独立 `.sig`；缺签名按策略拒绝 |
| 日志隐私 | **无泄漏路径** | sourceupdate / lxruntime / jamendo / source 目录零 `println`/`Log.*`；唯一涉及 cookie/token 的是静态扫描规则文本与注释，不打印请求头/Cookie/Token |
| 无代理 / 代理不可用降级 | 部分实现 | 客户端侧按"主地址→镜像→上次缓存"自动切换，无用户代理开关；镜像 `gh-proxy.com` 实测直连超时，靠熔断跳过。**真机在不同网络环境（无代理/代理失效/网络中断）的降级表现未验证** |
| DNS rebinding | **未实现** | 无法在 commonMain 纯 Kotlin 做 DNS 解析结果校验，靠域名白名单 + 逐跳重定向；需真机确认平台 DNS 不把白名单域名解析到内网 |

> 本章为代码级静态核实 + 已有单测证据，不含真机结论。Android 真机与真实音源验证仍缺位，见 §6.11 风险清单。

## 七、公开 LX 音源脚本审查与适配（2026-11-19）

### 7.1 取证与原则

从 `xzh767/lxmusic-source-all`、`ejungd/music` 下载 13 份真实社区脚本，原样保存在
`config/source-catalog/third-party-raw/`（记录来源路径/版本/SHA-256，详见
`config/source-catalog/THIRD_PARTY_REVIEW.zh-CN.md`）。**没有修改任何第三方脚本**。

核心原则：社区仓库是"候选来源"，不是"可信来源"。已签名的 `source-catalog.json` **保持空数组不变**
（私钥不入库，无法在无私钥环境重新签名）；审查结论写入客户端不读的 `candidates.json`，
全部 `autoInstall=false`，没有任何 APPROVED。

### 7.2 为兼容官方 API 补齐的宿主能力

| 补充 | 说明 |
| --- | --- |
| `console` | no-op（丢弃全部日志），避免第三方脚本日志（可能含 token/URL）进入宿主日志，同时消除 `ReferenceError` |
| `lx.utils.crypto.md5` | 官方移动端文档列出的原语；纯 Kotlin `Md5.kt`，标准向量由外部 hashlib 交叉验证 |

未实现：`crypto.randomBytes/aesEncrypt/rsaEncrypt`、`zlib`。依赖者明确报错。

### 7.3 实际验证结果（模拟，不是真实网络）

用真实脚本文件 + MockEngine 按各脚本公开 API 契约返回，8 份完成"初始化 → 播放地址解析 →
请求 ID 与返回 URL 内 ID 一致"：Huibq、肥猫不肥、野花、野草、至尊、梓澄二代、独家、ikun。
desktop 与 androidHost 双端均通过（`ThirdPartyScriptCompatTest`，含网络错误上抛与 close 释放）。

通过**执行**发现两个混淆脚本的真实域名（静态 grep 因字符串拆分看不到）：野花 `flower.tempmusics.tk`、
野草 `grass.tempmusics.tk`——这印证了"运行时域名白名单"与"静态扫描"必须互补。

未通过：聚合API(by lerd) 需真实 init 响应；無名依赖未实现的 aes/rsa 且有名称自检；
公众号音源2 初始化超时；六音 `new Function` 动态执行 + 重度混淆 → `REJECT_AUTO_CATALOG`；
小熊猫从 jsdelivr/GitHub 远程加载第二段 JS → `QUARANTINE_API_REVIEW`。

### 7.4 审查中发现并修复的并发缺陷

`LxScriptRuntime.close()` 原先无锁遍历 `timerSignals` 并 clear；而 asyncFunction 的 finally 在
`Dispatchers.Default` 上并发写同一 map。androidHost 实测 `ConcurrentModificationException`
（desktop 调度时序不同未触发）。修复：`close()` 用 `Mutex.tryLock()` 保护，拿不到锁就只靠
`timerScope.cancel()` 让各协程自行清理；测试改为等待异步清理后断言。

### 7.5 未验证风险（不得宣称真实可用）

1. 所有"通过"都是模拟响应；真实接口存活、格式、歌曲身份均未验证。
2. 裸 IP（至尊/梓澄）与 punycode 域名（肥猫/独家）的备案/证书/可用性未核实。
3. 混淆脚本只观测到一个域名，无法静态穷尽网络行为；需真机抓包。
4. 聚合API接口（CF）与测试音乐源未找到对应文件，未用其他音源冒名替代。

## 八、运行时可靠性与目录反重放（P0/P1）

### 8.1 timer 生命周期：真实缺陷与 quickjs-kt 限制

**反编译实证**（quickjs-kt 1.0.15 `QuickJs.class`）：
- asyncFunction 的协程挂在库自己的 `coroutineScope`（`jobDispatcher`），登记进 `asyncJobs`；
  `close()` 用 `AtomicBoolean` CAS 幂等、遍历 `asyncJobs` 逐个 `cancel()`，但**不 join**；
- resolve/reject JS Promise 前检查 `isClosed()`——close 后不会向已销毁 context 写 Promise。

**修复的真实缺陷**：
1. `timerScope` 原是外层可变属性，`load()` 在 `close()` 后重建；`__lxDelay` 在执行时才读它，
   旧 context 的 asyncFunction 有窗口把延时挂到**新 scope**。改为不可变 `RuntimeSession`：
   每次 load 独占 QuickJs / timerScope / timer表 / closed 标志，所有绑定闭包捕获该 session。
2. `close()` 原先 `signal.complete(Unit)` 会让 `select` 走 `onAwait` 正常返回 → JS `.then`
   回调有机会执行。改为 `signal.cancel()`，并在 session 关闭后拒绝新 timer 登记。

**必须记录的库限制（未彻底解决，实测 + 反编译双重确认）**：
1. async binding 的 Kotlin lambda 只在 JS `evaluate` 期间被调度；脚本**顶层 fire-and-forget**
   的 setTimeout 不会被后续 module evaluate 的 job queue pump 到——不登记宿主表、不执行宿主协程、
   不触发 JS 回调。
2. 更关键：quickjs-kt 在**每个 evaluation session 结束时 cancel 该 session 的全部 asyncJobs**
   （实测：requestMusicUrl 返回后 timer 表从 2 归零，长 timer 的 JS 回调再也不触发）。
   因此"close 前仍有 pending timer"在该库上不成立——库已先于 close 取消。
   这意味着真实脚本若把 setTimeout 用作跨 request 的后台心跳/轮询，在 quickjs-kt 1.0.15 上
   **无法按预期运行**。这是库的事件循环/session 语义，不是本层能"彻底修复"的。
3. 我们仍保留宿主侧兜底：session 独立、close 幂等、cancel timerScope、信号 cancel 而非
   complete；但不得宣称"已彻底解决 timer 生命周期"——库限制仍在，见上。

**测试**（`LxTimerTest`）：每条 close 场景四重证据——请求 Job 结束、activeTimerCount 归零、
所有 session closed 且无残留、JS 回调（`lx.request` 计数）未执行覆盖顶层/多 timer/
handler timer/clearTimeout 后 close/到期与 close 竞态/clear 与到期竞态/close 后 reload
隔离/双 close/20 次 load→request→close 循环。后台请求全部用 `Deferred` 持有并 `cancelAndJoin`。

### 8.2 目录反重放：catalogVersion 单调性

**真实缺陷**：`SourceUpdateCoordinator` 直接写 `catalog.catalogVersion`，从不与本地已接受
版本比较；`SourceCatalogUpdater` 取"第一个成功镜像"，不比较版本。旧签名目录可回退本地状态。

**修复**：
- `SourceIndex` 新增 `acceptedCatalogSha256`（带默认值，兼容旧索引）与 `isFirstInstall`。
- `SourceCatalogUpdater.load(url, etag, minCatalogVersion)`：签名有效的候选若版本低于下限
  一律拒绝；遍历所有允许镜像，选**版本最高**者返回；所有候选都被拒则明确 Failed（含"重放"字样）。
- `SourceUpdateCoordinator`：传 `index.catalogVersion` 作下限，Loaded 后再做一次单调性双保险；
  同版本内容变化（hash 不同）拒绝；首次安装放行；签名失败/Failed 不改索引版本与已装脚本。
- 版本恢复规则：应用重装/数据恢复导致索引丢失时判为首次安装，允许当前合法目录重新锚定。

`catalogVersion` 是目录版本，与脚本自身的 `version` 完全独立，二者不混用。

## 九、响应大小上限与多平台搜索（任务3/4）

### 9.1 响应大小上限：从"读后截断"改为"流式中断"

**真实缺陷**：三处响应读取都是先 `bodyAsText()` 把整个响应读进内存，再 `substring`/长度判断。
内存峰值发生在裁剪之前，被劫持/异常的超大响应可以直接打满内存。

**修复**：新增 `BoundedHttpBody.kt`（sourceupdate，lxruntime 已依赖），`readBodyBounded`：
1. Content-Length 明确 > 上限 → 提前拒绝，一个字节都不读；
2. 无 CL / 伪造为小值 / chunked → `bodyAsChannel()` 流式逐块读，累计字节数，超限立即
   `channel.cancel()` 中断，不读剩余数据；
3. 分块 ByteArray 累积后一次性 `decodeToString`，避免 UTF-8 多字节字符被块边界切断；
4. 取消（CancellationException）原样上抛，finally 确保释放底层连接。

四类**独立**上限常量（`HttpBodyLimits`，禁止互相借用）：
目录 JSON 1 MiB / 脚本下载 1 MiB / `lx.request` 响应 512 KiB / 搜索接口响应 512 KiB。
三处调用点（KtorCatalogFetcher / LxScriptRuntime.performHttp / KuwoSongSearcher 及各平台 Searcher）
分别接入，读取纳入各自的超时窗口。

**测试**（`BoundedHttpBodyTest` 6 项）：正常响应、CL 超上限提前拒绝、无 CL 流式截断、
虚假 CL（Ktor 层 Content-Length mismatch 校验 + 流式双保险）、超大 chunked、UTF-8 跨块不破损。

### 9.2 多平台搜索：适配器架构（模拟验证，非真实接口）

**真实缺陷**：默认只有 `KuwoSongSearcher`（kw）。脚本在 inited 声明 kg/tx/wy/mg 不等于播放器
能搜这些平台——旧实现 `if (platform !in searcher.supportedPlatforms) continue` 让这些平台
静默变成"没结果"，用户和 UI 都无法区分"不可搜索"与"搜不到"。

**修复**：
- `LxSongSearchRegistry`：平台→独立 Searcher 映射，`searchablePlatforms` 明确暴露真实可搜平台，
  未注册平台返回空且不冒充；单平台异常被隔离成空列表（取消上抛）。
- 新增 4 个独立 Searcher（各自 ID 字段映射，原始字段原样保留进 `LxSongHit.rawFields`）：
  kg=`FileHash`、tx=`songmid`、wy=`id`（毫秒 duration 转秒）、mg=`musicId`。
- `LxScriptMusicProvider` 改用 registry，按平台路由；搜索命中 `hit.platform` 必须等于请求平台，
  否则丢弃（防来源冒充）；新增 `searchablePlatforms()` 供 UI 标记"不可搜索"。
- **默认注册表仍只启用 kw**：其它平台虽已实现，但只有真实响应结构 fixture 的模拟测试，
  未验证接口存活/字段变化/鉴权要求，默认不注册，避免把未验证能力暴露给全体用户。

**验证等级**：`LxPlatformSearcherTest`（8 项）+ `LxMultiPlatformProviderTest`（4 项），
全部 **SIMULATED**（真实结构 fixture + MockEngine），未打真实接口。真实接口验证、
是否触发风控/会员限制、字段是否变化，均待后续独立任务，不得宣称真实可用。

## 十、真实接口可达性探测、应用接入闭环与 DNS/crypto 策略（任务5/6/7，2026-11-19）

### 10.1 真实接口可达性探测（任务5：只做可达性，不伪造协议成功）

**取证环境**：本机经 `127.0.0.1:7897` 代理。这**不是中国大陆直连**，因此"大陆可访问性"不能据此宣称；
DNS 用 Cloudflare DoH 取权威记录，HTTP 状态经代理观测，两者都只代表本机+该时刻。

对 8 个候选端点做了真实 DNS + HTTP 探测（`curl`，2026-11-19）：

| 候选 | 端点 | 权威 DNS | HTTP 观测 | 结论 |
| --- | --- | --- | --- | --- |
| Huibq | `lxmusicapi.onrender.com` | 解析正常（CNAME→Cloudflare） | **503 `Service Suspended`，页面明确 "suspended by its owner"** | 服务已停，接口不可用 |
| ikun | `api.ikunshare.com` | **NXDOMAIN**（DoH status 3） | TLS 握手失败/超时 | 域名失效，接口不可用 |
| 野花 | `flower.tempmusics.tk` | NXDOMAIN | TLS 握手失败 | 域名失效 |
| 野草 | `grass.tempmusics.tk` | NXDOMAIN | TLS 握手失败 | 域名失效 |
| 至尊 | `110.42.36.53:1314`（裸 IP） | 无域名 | 经代理 502（代理无法直连裸 IP:端口） | 本机不可达，需大陆真机 |
| 梓澄二代 | `103.239.247.51:9763`（裸 IP） | 无域名 | 经代理 502 | 本机不可达，需大陆真机 |
| 肥猫 | `music.xn--z7x900a.live`（punycode=肥猫） | A 记录存在 | 根路径返回 nginx 默认页，`/url/kw/228908/128k` 返回 404 | 域名在，但 API 路径不通 |
| 独家 | `88.lxmusic.xn--fiqs8s`（punycode=中国） | A 记录存在 | 根路径 `{"code":0,"msg":"success","data":null}`，但 `/url/...` 返回 404 | 服务存活，解析路径待核 |

**必须说清的边界**：
- 这些只是**网络可达性 + DNS 状态**，不是 `REAL_INIT` / `REAL_SEARCH` / `REAL_URL_RESOLVE` /
  `REAL_AUDIO_RESPONSE` / `REAL_TRACK_IDENTITY_VERIFIED`。本次**没有**让任何脚本在真实接口上完成
  "搜索→解析→音频响应→歌曲身份一致"全链路，因此**没有任何候选达到可提升审核状态的等级**。
- 判定：审查表 13 份脚本（其中 8 份做了可达性探测；`candidates.json` 另含早期候选共 21 条）全部维持原 `reviewStatus`、`autoInstall=false`，`source-catalog.json` 仍为空数组。
- "值得继续投入"的客观结论：Huibq/ikun/野花/野草接口已失效，投入产出比最低，暂缓；独家/肥猫域名仍解析、
  独家根路径有 JSON 响应，是**唯一**值得在大陆真机上复核解析路径的候选；裸 IP 两项必须真机+抓包。
- 未绕过任何会员/付费/访问控制，仅探测公开端点连通性；punycode 域名解码为"肥猫"/"中国"已记录。

### 10.2 应用接入闭环（任务6：补齐主动运行时回收）

**核查结论**：LX 功能**已真正接入**三端生产路径，不是孤立模块。证据链：
- 真实实现 `StoreLxInstalledScriptSource`（`shared/.../source/SourceCatalogRuntime.kt:201`）从
  `SourceVersionStore` 读取"已启用+已批准+非隔离"脚本；`EmptyLxInstalledScriptSource` 只作默认兜底/测试。
- `LxScriptProviderFactory` 经 `ProviderComposition → KotlinProviderRepository.providerMap → FuoProviderGraph`
  注册；Android/iOS/Desktop 宿主都显式注入 `sourceCatalogRuntime.scripts`。
- 首个脚本装好后 `SourceUpdateHost.enableLxProviderIfNeeded()` 同时更新内存 registry 与持久化设置。
- 状态落在 `sources/index.json`，重启后由 `ProviderCatalogFeature` 恢复；设置页区分已安装/已启用/已停用/已隔离/错误。
- 待审核脚本在安装层就被拒绝，故 UI 不展示"待审核"——这是安全设计，不是遗漏。

**本轮修复的真实缺口**：`LxScriptProvider.releaseAll()` 全项目无调用点；停用/隔离/更新脚本后，
QuickJS context 只靠下一次 `search()/resolve()` 的 `pruneHandles()` **惰性**回收。用户关掉脚本后，
旧 context 会一直驻留到下次使用，与"停用即释放"的语义不符。

**修复**：
- `LxScriptMusicProvider.releaseScript(scriptId)`：按 id 立即 `runtime.close()` 并从 handle 表摘除，
  幂等（重复释放返回 false）；同时清掉该 id 的连续失败计数，重新启用时状态干净。
- `ProviderRegistryRepository.releaseDynamicScriptRuntime(scriptId)` 新增默认空实现（内建 Provider 无需处理），
  `KotlinProviderRepository` 覆盖为"仅当 LX Provider 已实例化才释放"，避免为回收而创建空 Provider。
- `SourceUpdateHost`：仅在**用户显式**停用（`setScriptEnabled(false)`）、隔离（`setScriptQuarantined(true)`）
  时主动释放；**后台自动更新不主动 close**（`checkForUpdates` 由启动/每日任务触发，可能撞上用户正在
  进行的 `resolve()`，close 会抽走 context 造成当次解析失败）。更新场景仍由 handle 身份里的源码
  SHA-256 保证"版本一变、下次使用时自然淘汰旧 context"，已满足"更新自动淘汰旧 runtime"。
- 新增 `SourceUpdateHostRuntimeReleaseTest`（recording registry 断言按 id 调用）与
  `LxScriptProviderTest.releaseScriptClosesHandleAndNextSearchRebuildsIt`（断言被释放的
  QuickJS session 确实 closed、再次搜索重建新 runtime）。

**已知时序边界（第二轮审查发现并记录）**：`releaseScript` 与 `resolve()` 里对 runtime 的使用
不在同一把锁内；用户恰好在解析进行中点停用时，那一次解析会返回 null（异常被吞掉，不 native 崩溃）。
这是显式停用语义下可接受的代价，且后台更新路径不调用 `releaseScript`，不会在无感知时打断播放。

**仍未实现（如实记录）**：脚本删除/回滚目前只改 store，靠下次使用的 cacheKey 变化淘汰旧 runtime；
未做"删除即主动释放"（删除入口未在应用层暴露，属后续独立项）。

### 10.3 DNS 与 crypto（任务7：按真实需求，明确失败，不虚报防护）

**DNS rebinding**：commonMain 无法可靠做解析后 IP 校验；项目**没有**注入 `DnsResolver`/平台 socket hook，
TLS 证书验证保持系统默认不被削弱。现状是三层**字符串层**防护：
- `LxNetworkPolicy`（最严）：空白名单拒绝全部、http/https、userinfo 伪装、IPv6 字面量、非标准 IPv4
  归一化后判私网、逐跳重定向重新校验——这是 `lx.request` 的实际边界。
- `SourceCatalogModels.urlProblem`（目录条目）：https + localhost/私网 IPv4，强度弱于前者。
- `SourceMirrorPolicy.isAllowed`（下载通道）：https + allowlist。

**本轮修复的真实缺口**：`SourceMirrorPolicy.isAllowed` 原为 `if (allowlist.isEmpty()) return true`（fail-open）——
漏配内置名单时会放行任意主机。已改为**空白名单拒绝全部**，与任务9"空白网络白名单生产环境拒绝全部网络"
一致；补 `emptyAllowlistFailsClosed` 测试，并把依赖默认空名单的测试夹具改为显式 `TEST_ALLOWLIST`。

**不得宣称的部分**：DNS rebinding（校验时看字符串、连接时由 OkHttp/Darwin 自行解析）在 commonMain
**未实现**，各平台是否会被解析到内网地址仍需真机 DNS 抓包确认。三套 URL 策略强度不一致也如实保留，
不在本轮做跨模块重构（无真实可用脚本，重构收益低于风险）。

**crypto 策略**：按"仅实现有官方协议依据 + 真实脚本需求"的原则逐项核对第三/七章审查的 13 份脚本：
- 已实现且有真实需求：`md5`（独家音源自检用）、`buffer`（base64/hex/utf8）。
- 未实现且**无已批准真实脚本依赖**：`randomBytes` / `aesEncrypt` / `rsaEncrypt`——唯一依赖它们的
  **無名**（name 自检失败）与**小熊猫**（远程加载第二段 JS）都不是 APPROVED，且接口已失效。
  本轮**不盲目补齐**。这些原语在桥接层调用即抛 `LxScriptException`，明确失败，不返回伪造随机数/错值。
- `zlib` 官方移动端也标注未实现，维持 rejected Promise。
- 修正文档与代码注释里两处过时描述：roadmap 曾写"`md5` 未实现"（实际已实现）、
  `LxScriptRuntime` 头注释曾写"支持 aes/rsa"（实际不支持）——均以本节为准。

### 10.4 审核记录一致性修正（2026-11-19）

第二轮核对发现 `candidates.json` 的 `testLevel` 与实际结果不一致：聚合API（MockEngine 返回 `{}`）、
無名（名称自检未过）、公众号音源2（初始化 5s 超时）三者**初始化均失败**，却都记成了
`SIMULATED_INIT_AND_URL_PARSE`。修正：
- 在 `candidates.json` 增加 `testLevelDefinitions`，明确 10 级单调等级
  （`NOT_OBTAINED` < `STATIC_ONLY` < `RUNTIME_LOAD` < `SIMULATED_INIT` < `SIMULATED_URL_RESOLVE`
  < `REAL_INIT` < `REAL_SEARCH` < `REAL_URL_RESOLVE` < `REAL_AUDIO_RESPONSE`
  < `REAL_TRACK_IDENTITY_VERIFIED`），并规定 `testLevel` 必须是**已完成的最高等级**，
  不得按预期或部分测试推断。
- 三者下调为 `RUNTIME_LOAD`（能加载但未发出有效 inited）；8 份早期仅登记的仓库候选标
  `NOT_OBTAINED`；真正通过兼容测试的 8 份用更精确的 `SIMULATED_URL_RESOLVE`。
- 每条补齐 `testLevelEvidence` / `declaredPlatforms` / `simulatedUrlResolvePlatforms` /
  `requiredHostApis` / `discoveredDomains` / `staticReview` / `simulatedResult` /
  `realTestResult` / `license`，共 21 条齐全。
- **实测通过平台只记 kw**：兼容测试虽断言 inited 声明的全部 5 个平台存在，但 URL 解析断言
  只对各脚本声明平台的第一个（kw）执行，其余平台未做解析验证，不得记为"已通过"。
- `THIRD_PARTY_REVIEW.zh-CN.md` 表格同步增加"实测通过平台/testLevel"两列，与 JSON 完全一致。
- 全部维持非 APPROVED、`autoInstall=false`，`source-catalog.json` 仍为空数组。

### 10.5 多平台搜索启用、真实验证阶梯、回滚闭环（任务5-8，2026-11-19）

**任务5 至少完成 kw+kg**：`KugouSongSearcher`（FileHash 稳定 ID、rawFields 原样保留）此前已实现且
模拟测试通过。本轮经真实接口探测确认 `https://songsearch.kugou.com/song_search_v2` HTTP 200
且返回真实《晴天》数据，故把 kg 加入生产默认注册表：`LxSongSearchRegistry.default()` 现返回
kw+kg；`LxScriptProviderFactory` 默认注入该注册表。tx/wy/mg 仍只有模拟 fixture、未做真实接口，
**不默认注册**。新增 2 项测试：真实捕获响应的 FileHash 身份解析、默认注册表仅暴露 kw+kg。
注意：`REAL_SEARCH`（搜索）与脚本的 `REAL_URL_RESOLVE`/`REAL_AUDIO_RESPONSE` 是不同层级，
不得因搜索可达就宣称脚本真实可用。

**任务6 真实 init→音频响应**：新增 opt-in 测试 `RealInterfaceVerificationTest`（desktopTest）。
默认不跑，`FUOE_REAL_VERIFY=1` + `LX_VERIFY_PROXY` 才执行真实网络，避免 CI 依赖端点存活。
固化 9 级阶梯并断言"不得越级"。实际以 Kotlin 测试执行：`kg 搜索 REAL_SEARCH ok=true`，
首条 FileHash=B3A52A7A…/晴天/周杰伦/269s，与 curl 取证一致。合规边界：音频只做 HEAD 探测、
不下载完整商业音乐，故**本轮无任何候选达到** `REAL_URL_RESOLVE`/`REAL_AUDIO_RESPONSE`/
`REAL_TRACK_IDENTITY_VERIFIED`；失效端点（503/NXDOMAIN/裸 IP/404）审核结论锁定，不得提升。

**任务7 安装/启用/播放/禁用/回滚闭环**：此前安装（审核门槛+冒烟+先验证后激活）、启用（首个脚本
自动开 LX Provider）、播放（resolve 走 Provider）、禁用（releaseScript 主动释放）已闭环。补最后一环
回滚：`SourceCatalogRuntime.rollbackScript(id, reason)` 暴露底层 `store.rollback`，
`SourceUpdateHost.rollbackScript()` 在回滚**成功后立即**调 `releaseDynamicScriptRuntime` 释放旧 context
（否则回滚后到下次搜索前仍执行失败的旧版本）。新增 2 项测试：有上一版本时 active 切回+清
previousVersion+记 lastError+释放 runtime；无上一版本时 no-op 且不释放。至此五环闭合。

**任务8 DNS/crypto**：本轮复核无新增缺口，维持上一轮结论——DNS 三层字符串层防护 + fail-closed，
DNS rebinding 在 commonMain 未实现如实记录；crypto 仅 md5/buffer，AES/RSA/randomBytes 无已批准
脚本依赖、桥接层明确抛错，不盲目补齐。

### 10.6 第二轮审查修复：Timer 生命周期与索引损坏反重放（2026-11-19）

用户按实际代码审查，指出上一轮提交说明"close 用 signal.cancel + 等待协程退出"与代码不符，
以及反重放在索引损坏时存在缺口。本轮修复两项，其余审查项（真实验证测试语义、等级定义条数、
搜索重复请求）如实记录为未处理，不擅自扩大范围。

**P0 Timer 生命周期（`LxScriptRuntime.kt`）**：
- 审查属实：旧 `close()` 用 `timerScope.cancel()` + `tryLock()` 拿不到就跳过清理，
  且 `quickJs.close()` 紧随其后，没有等待宿主 timer Job 真正退出；
  `load()` 中 session 在 evaluate 成功后才发布，存在 load/close、load/load 的覆盖窗口。
- 修复：
  1. 新增 `lifecycleMutex`，`load()` 与 `close()` 全程持锁串行，消除两个覆盖窗口；
  2. `RuntimeSession` 新增 `timerJobs: MutableSet<Job>`，`__lxDelay` 的等待 Job 登记其中，
     session 真正持有全部宿主 Job（此前只有 signal）；
  3. `close()` 改为 suspend（去掉 AutoCloseable——所有调用方都已在协程上下文），
     顺序固定为：置 closed 门闩 → cancel 所有 signal → cancel timerScope →
     `joinAll(timerJobs)` 等待全部 Job 退出 → 最后 `quickJs.close()`；
  4. 更新类头/方法注释与实现一致，不再声称"等待协程退出"却没做。
- `LxTimerTest` 新增 4 项真实竞态测试（共 17 项）：timer 活动期直接 close 并 join（不再先
  `pump.await()` 等自然结束）、两个协程同时 close、并发 load/load 只留一个存活 session、
  close 与 load 竞态不留未关闭 session。

**P1 索引损坏反重放（`SourceVersionStore.kt` / `SourceUpdateCoordinator.kt`）**：
- 审查属实：旧 `loadIndex()` 解析失败时 `getOrElse { SourceIndex() }` 静默回退默认索引，
  被判首次安装，丢失 catalogVersion/acceptedCatalogSha256 锚点，可接收旧的签名目录。
- 修复：
  1. 新增独立锚点 `sources/anchor.json`（`SOURCE_ANCHOR_PATH`，仅存版本+内容哈希），
     `saveIndex()` 在版本>0 或有哈希时同步写入；
  2. `loadIndex()` 严格区分三种状态：索引与锚点都不存在才是真正首次安装；索引存在但损坏
     或索引缺失但锚点存在时，从锚点恢复并置 `recoveredFromAnchor=true`；锚点也损坏时保守
     标记异常态、版本取 0，但**绝不**判首次安装；
  3. `SourceIndex.isFirstInstall` 排除 `recoveredFromAnchor`；
  4. Coordinator 在恢复态下拒绝版本不高于锚点的目录（旧版本/同版本都拒），只有严格更新的
     版本能通过并重新锚定。
- 新增 `CatalogIndexCorruptionTest` 8 项（首次安装/锚点写入/索引损坏恢复/索引缺失恢复/
  拒绝旧目录/拒绝同版本/接受更高版本重锚/索引与锚点皆损坏）。
- **明确未封住的边界**：若锚点文件与索引同时损坏或被清除（如卸载重装、数据清除、无备份的
  恢复），本地没有任何可信版本锚点，只能重新接受当前合法目录并重新锚定；这是"本地状态
  全部丢失"场景的固有限制，无法在客户端单方面封住，需服务端配合（下发单调版本并要求
  客户端回传当前版本）才能进一步收紧。

**验证**：sourceupdate 95 项、lxruntime 全量 desktop 测试通过（含新增 12 项），
androidHost/iOS/desktopApp/APK 见本轮提交说明。

### 10.7 真实验证阶梯修正与独立公开曲库端到端验证（2026-11-19）

用户采纳"独立解析器"方向，并要求修正审查指出的真实验证测试语义问题。本轮两项：

**A. 修正真实验证阶梯（`RealInterfaceVerificationTest.kt`）**：
审查属实：`if (step.ok) { assert... }` 在真实搜索失败时不失败；`audioLike` 用 `len > 0`
会把 HTML 错误页当音频；只做 HEAD 不证明 GET 能拿音频；阶梯只有 9 级，与 candidates.json
的 10 级定义不一致。修复：
- 测试分两类：`network probe`（仅记录，失败不 gate）与 `verification`（`FUOE_REAL_VERIFY=1`
  时 `assertTrue(step.ok)`，绿色必然代表真实成功）。
- `REAL_AUDIO_RESPONSE` 改用 Range GET 取 16B，严格校验 2xx + Content-Type 为 audio/* 或
  octet-stream + 字节以 ID3("ID3")/MP3 帧同步开头；删除 `len > 0` 判定。
- 音频验证需显式 `FUOE_REAL_AUDIO_URL`，未配置就 SKIP，不伪造通过。
- 阶梯修正为 10 级（含 NOT_OBTAINED），与 candidates.json.testLevelDefinitions 一致，
  并断言"只有搜索证据不得越级到音频/身份"。

**B. 独立公开曲库端到端验证（Jamendo）**：
选择 Jamendo 而非大陆真机：它是 CC 授权开放曲库、API 有公开文档，无商业平台授权风险。
- 模型与解析补 `licenseCcUrl`（授权元数据，API 字段 `license_ccurl`），端到端验证其非空。
- 新增 opt-in `JamendoEndToEndTest`（desktopTest），用真实 HTTP（经代理）+ 生产解析逻辑，
  实际执行（`FUOE_REAL_VERIFY=1 LX_VERIFY_PROXY=...`）证据：
  - `REAL_SEARCH`: id=1348699 / "Planet Bubus" / Alexander Morozov / 541s / 共 3 条
  - 授权元数据: `http://creativecommons.org/licenses/by-nc-nd/3.0/`
  - `REAL_AUDIO_RESPONSE`: HTTP 206 / audio/mpeg / Content-Range 0-15/11572736 / ID3 魔数 49443304
  - `REAL_TRACK_IDENTITY_VERIFIED`: 音频 URL 的 trackid 与搜索 id 一致
  - `REAL_URL_RESOLVE`: trackById 重取后 id/曲名一致
- 合规：音频仅 Range 取 16B，未下载完整音乐；允许 3 次重试应对 Jamendo 偶发空结果。
- 这是**一等公民 Provider 的真实链路验证**，不改变 candidates.json（13 份 LX 第三方脚本
  仍 autoInstall=false、source-catalog.json 仍空数组）。

**验证**：lxruntime desktopTest（含修正的 5 项阶梯测试）、jamendo desktopTest（2 项端到端
opt-in 实跑通过）、全模块回归见本轮提交说明。

### 10.8 第二轮审查续修：求值/close 分层、锚点原子性、生产 API 路径（2026-11-19）

用户按实际代码再审，本轮三项：

**P0 求值与 close 并发（LxScriptRuntime.kt / LxTimerTest.kt）**：
- 审查要求"正在 request 时 close 不销毁 native context"。尝试过用 lifecycleMutex 串行化
  求值，但因 asModule 顶层 await 挂起时 native evaluationTimeout 无法中断（无 JS 指令在跑），
  close 会卡到脚本 timer 自然到期——**此路不通，已撤销**。
- 采用分层方案：宿主侧 closeSuspend 取消+join 本 session 全部 timer Job，再 quickJs.close()
  由库 cancel 它登记的求值 asyncJob（库保证 close 后不再写 Promise）。
- 如实记录库限制：quickjs-kt 1.0.15 的 close() **不 join** 求值协程，cancel 后立即销毁 native
  context，宿主层无法彻底消除底层竞态。新增测试 `close during in-flight evaluation does not
  corrupt native context` 验证求值得以可预期异常结束、无 disposed/invalid-context/segfault 特征，
  这是该库下能实际验证的最强证据，不虚报"完全消除"。

**P1 锚点写入原子性（SourceVersionStore.kt / SourceUpdateCoordinator.kt）**：
- 审查属实：saveIndex 先写 index 后写锚点，写锚点失败会让下次损坏恢复时版本倒退。
- 修复：改为**先写锚点（只增高水位，取 max(现有, 新版本)）再写 index**；loadIndex 读主索引
  成功后用锚点高水位校正落后的 index（新增 `versionCorrectedByAnchor` 标记）；Coordinator 在
  恢复/校正态都拒绝不高于锚点版本的目录。任意一步写入失败都不倒退。
- 新增 3 项测试：index 写失败保住锚点版本、低版本写不倒退锚点、index 落后被 load 校正。

**P1 Jamendo 生产 API 路径 + 授权严格化**：
- 新增 `JamendoProductionPathTest`：`FUOE_REAL_VERIFY=1` 时**完全走生产 JamendoApiClient.search()
  /trackById() 的 HTTP 路径**（Ktor+OkHttp，仅用 JVM 系统属性注入代理），实跑通过：search 3 条、
  trackById 重取身份一致。与旧测试（HttpURLConnection+生产解析）互补，构成完整生产端到端。
- 音频严格化：断言 HTTP **206**（非任意 2xx）、Content-Range 起点 0/结束 15、实际字节数 16，
  排除服务端忽略 Range 返回 200 整段的情况。实跑证据：206 / bytes 0-15/11572736 / ID3 魔数。
- 等级定义：`RealInterfaceVerificationTest` 改为**读取 candidates.json.testLevelDefinitions 比对**
  （不再各自硬编码），并断言 note 无过时"九级"措辞；同步修正 note 里的九级旧文字。
- 授权严格化：`JamendoTrack` 新增 `licenseCode`/`licenseSummary`（解析 by-nc-nd 等条件），
  Provider 的 providerTags 保留具体 CC 类型 + 原始链接 + 适用条件说明，不再笼统标
  `"open-catalog"`（NC=非商用、ND=禁演绎，不等同无条件开放）。补 2 项单测。

**合规**：音频仅 Range 取 16B，未下载完整商业音乐；source-catalog.json 仍 0 条、21 候选
autoInstall=false/无 APPROVED。

### 10.9 第三轮审查收尾：Range 严格断言、等级唯一来源、许可证逐项标注（2026-11-19）

用户按"再做三项"要求复核，本轮三项都是对 10.8 已做内容的**补齐遗漏点**，不改安全边界：

**P1 Range 响应严格断言（RealInterfaceVerificationTest.realAudioRange）**：
- 10.8 只在 JamendoEndToEndTest 强制了 206+Content-Range，通用的 `realAudioRange()` 仍是
  `code in 200..299` 的宽松判定——服务端忽略 Range 返回 200 整段也会被记为成功。
- 收紧为三重严格条件：`HTTP 206` + `Content-Range: bytes 0-(N-1)/total`（起点/结束与请求区间一致）
  + 实际字节数恰好 == N；三者与 audio/* + 容器魔数同时满足才算 REAL_AUDIO_RESPONSE。
- 验证测试断言消息同步为"206+Content-Range(0-15)+audio/*+容器魔数+16B"，与 Jamendo 端到端口径统一。
- 追加审查修正：`readNBytes(N)` 最多读 N 字节、无法发现服务端返回超长正文，改为读 `N+1` 且断言
  恰好得到 N 字节（多出的 1 字节用于识别超长响应，魔数判定只看前 N 字节）；Content-Range 解析
  total 并要求 `total > end`，避免接受"区间与总长矛盾"的响应。通用验证与 Jamendo 端到端两处同步。

**P2 审核等级唯一来源（RealInterfaceVerificationTest）**：
- 10.8 的阶梯测试虽读了 candidates.json，却又在测试侧硬编码了一份 10 级 `expected` 列表比对，
  仍是"两套定义"——权威文件改动会与测试脱节。
- 删除硬编码清单：抽出 `loadAuthoritativeLevels()` 作为**唯一来源**加载器（找不到/格式错误一律
  fail，不退化内置清单）。阶梯测试只从文件读名称/数量/顺序并用语义锚点校验单调；新增
  `candidate levels and flags match authoritative definition` 测试，逐一核对 21 候选的 testLevel
  都来自权威集合、无 APPROVED、autoInstall=false。

**P2 许可证逐项标注（JamendoModels.licenseSummary / JamendoApiClientTest）**：
- 10.8 的 `licenseSummary` 用 when 分支，纯 BY 落到"请查看原始条款"未写署名；BY-NC-SA 等
  组合只按首个匹配分支标注，会漏掉部分限制。
- 改为逐项解码 BY(署名)/NC(非商业)/ND(禁演绎)/SA(相同方式共享)，组合时**列出全部适用条件**，
  CC0/PD 单独说明、无链接保守提示未知。新增 `licenseSummaryLabelsEveryCcCondition` 覆盖
  纯 BY / BY-SA / BY-NC-ND / 无链接四种情形。

**验证与合规**：desktopTest 全绿；音频仍只 Range 取 16B 未下载完整音乐；source-catalog.json
仍为空数组、21 候选无 APPROVED/autoInstall=false。本轮不推送。

### 10.10 Jamendo 开放曲库产品化：接入实际使用流程（2026-11-19）

用户决策：client_id 保留内置写死；CC 许可证只在"曲目详情/正在播放"页展示，搜索列表保持简洁。
基于架构梳理（Provider 已注册、搜索/解析/播放链路已通），本轮只补缺口，不重写 Provider：

**① 许可证 UI 展示（详情/播放页）**：
- `PlayerFormatting.kt` 新增纯函数 `licenseInfoFromTags(providerTags)` → `TrackLicenseInfo`
  （code/summary/url/shortLabel），只在有 `cc:` 标签时返回非 null，商业来源不误标。
- `PlayerDialogs.kt` 新增 `TrackLicenseDialog`：明确"CC 授权开放曲库、非商业原曲"，逐项列
  BY/NC/ND/SA 条件 + 原始许可链接，不去重、不笼统。
- 正在播放页 `RuntimeFullPlayer` 的 info tag 区加可点击 "CC BY-NC-ND" chip → 弹窗；
  曲目详情 `ProviderTrackDetailRoute` content 顶部加许可条件行 → 同一弹窗。搜索列表 TrackRow 未改。

**② 异常处理（失败可感知，不再静默空结果）**：
- `JamendoSearchResult` 新增 `failureKind`（None/MissingClientId/Network/Upstream）。
- `JamendoApiClient.fetch` 改为返回 `FetchResult.Success/Failure`：**显式检查 HTTP 状态码**
  （Ktor 默认不因 4xx/5xx 抛异常，错误页否则被解析成空）、超时与网络异常归 Network。
- Provider 层映射到通用 `ProviderFailure`：无 client_id→ContentUnavailable、Network→Network、
  HTTP→UpstreamContractChanged；正常空结果仍是 failure=null，不污染统一搜索。
- 复用既有链路 `ProviderSearchResults.failure → errorMessage → 搜索 UI message`，无需改 UI 层。

**③ 接线缺陷修复（本轮最有价值发现，由链路测试暴露）**：
- `JamendoProviderFactory.create` 原忽略 `dependencies.http`，各自 `createSharedProviderHttpClient()`，
  绕过统一网络层（代理/缓存/测试注入全失效）。链路测试首跑 0 结果即暴露此问题。
- 修复：`ProviderHttpClient` 新增只读 `client` 访问器；Factory 改为传入 `dependencies.http.client`。
  其他 Provider 本就走 dependencies.http，无需改动。

**④ 平台兼容性核查（只读）**：Jamendo 为 https MP3；Android（ExoPlayer DefaultHttpDataSource，
cleartext 放行）、iOS（AVPlayer，NSAllowsArbitraryLoads=true）、Desktop（libmpv 直接加载 URL）
均可消费，无需改播放器。

**⑤ 测试（明确区分 Mock 与真实网络）**：
- `TrackLicensePresentationTest`（4）、`JamendoApiClientTest` 新增（无凭据/HTTP500/网络/正常空）
  、`JamendoSearchToPlaybackFlowTest`（2，**Mock 验证**：统一搜索→CC 标签随 track 传递→
  resolveTrack 路由回 Jamendo→拿到播放 URL；真实网络由 opt-in 端到端测试负责，不互相冒充）。
- 回归：provider runtime/jamendo/lxruntime、feature playback/search desktopTest 全绿；
  shared desktopTest 350 项 0 失败；三端编译见交付记录。

**合规**：client_id 仍内置（用户决策保留）；未新增 LX 音源、未下载完整商业音乐；
真实现播放验证仍 opt-in，Mock 链路测试不冒充真实播放。

### 11. 产品化转向：开箱即用（2026-11-19）

用户明确从"修边界测试"转向"让家人开箱即用"。本轮做第一优先级，不重写已有 Provider：

**① Jamendo 默认启用 + 一次性升级迁移**：
- `DEFAULT_ENABLED_PROVIDER_IDS` 加入 `jamendo`（家人安装即可搜到，无需进设置页找开关）。
- 老用户升级问题：持久层已存 `enabledProviderIds` 时改默认值不生效。新增一次性迁移标记
  `providerDefaultsMigrationVersion`（AppSettings + PersistedSettingsV1 双向映射），
  `migrateProviderDefaults()` 只在版本落后时把 `PROVIDER_DEFAULTS_ADDED_IN_V1`（jamendo）
  补入并回写版本号——**只补一次**，用户之后可自由关闭（v1 后不再补回）。
  新装/损坏恢复直接写最新版本跳过迁移；从旧存储 legacyLoader 来的用户走迁移。
- 搜索/列表来源行 `sourceLabel` 对带 `cc:` 标签的曲目追加"开放曲库·非原曲"，避免误认商业原曲。

**② 下载一键最高质量（家人只点一次）**：
- `DownloadQualitySelectionPort` 新增 `downloadBest()`：不弹窗，直接以 `Highest` 档入队
  （Highest 在 Provider 侧自动从无损回退到高音质，恰好是"默认无损优先、不可用降级"）。
- `BoundDownloadActionPort.download()`（所有下载按钮入口）改走 `downloadBest()`；
  `requestSelection()` 保留为"更多选项/下载（选择音质）"显式入口，搜索列表菜单里加了该项。
- 一键下载会清掉任何已打开的选择窗，避免状态残留。

**③ 网络响应体大小上限加固（P2，防卡顿/OOM，非安全审计工程）**：
- provider/runtime 新增 `BoundedBody.kt`（`readProviderBodyBounded`）：Content-Length 预判 +
  流式累计超限即取消，512 KiB JSON 上限；`ProviderNetwork.execute` 与 JamendoApiClient 改用。
- 两份 lossless client（Android/desktop 的 Jamendo/InternetArchive/Subsonic）新增
  `BoundedJsonRead.kt`：最多读 MAX+1 字节识别超长响应，超限按请求失败降级；音频流下载走独立通道不受影响。

**本轮明确未做（需用户拍板/真机，非遗漏）**：
- **LX 音源自动更新目录**：用户想"不让家人手动导入脚本"，但与既有安全边界（source-catalog 空数组、
  21 候选无 APPROVED、不自动装未审核脚本）直接冲突；此前真实验证显示候选端点大多失效/有 Risk。
  需用户指定可信且真实可用的脚本后才按门禁流程入库，本轮不擅自开绿灯。
- **开放曲库匹配分级（高可信/有疑问）**、**断点续传/离线播放**：第三优先级，后续阶段。
- **真机真实验收**（真实搜索/播放/FLAC 下载、三端）：无设备，仅提供 opt-in 验证脚本，不冒充。

**测试**：迁移（4）、默认集、sourceLabel 标注、下载一键（2）、有界读取（runtime 3 + desktop 4）
均通过。本轮不推送。

### 12. LX 音源分批接入与社区音源自动发现框架（2026-11-19，工作分支 work/lx-discovery-framework）

用户计划书明确：不绕过安全门禁、不伪造验证、不自动安装未审核脚本、先做不依赖私钥的工作、
只在集合仓工作分支开发、不改公钥/不关验签/不动 master。本轮完成阶段 A 审查 + 阶段 C 框架。

**基线处理**：集合仓工作分支 `fc05530` 停在 a213522；本地 FuoEvolve 的 fa7a5e8（产品化）尚未同步。
先把 fa7a5e8 的 32 个文件同步进工作分支（内容一致、仅 CRLF/LF 差异），保证基线不丢改动。
所有改动只在 `work/lx-discovery-framework`，master 不动。

**阶段 A：候选审查报告**（写入 config/source-catalog/candidates.json，21→23 条，全非 APPROVED / autoInstall=false）：

| 候选 | 取证 | 静态结论 | 处置 | 真实等级 |
|---|---|---|---|---|
| 长青 changqing v1.3.0 / 27KB | sha256 6a94298…ed6de，域名 yinyue.haitangw.net + 13413.kstore.vip | **BLOCK**：membership-bypass（@name 长青SVIP音源，2 处 SVIP） | REJECT_AUTO_CATALOG，不改 BLOCK 规则强行放行 | STATIC_ONLY |
| 非常刀 feichangdao v5 / 17.8KB | sha256 49486ca…64284d，12 域名含裸 IP 175.27.166.236、t.me，hires/flac24bit/master 映射 | BLOCK 规则未命中，但需逐个审授权与数据流 | NEEDS_REVIEW，禁止通配符白名单，不做商业付费音质真实下载 | STATIC_ONLY |
| 玉宁熙 yuningxi v1.2.5 / 144KB | sha256 5dd4752…d275f79，URL 字符串拼接致域名静态不可见；声明 2s/4 次限频 | BLOCK 未命中，域名需专项逆向 | NEEDS_REVIEW，禁止放宽 SSRF，遵守限频不并发 | STATIC_ONLY |
| Fish v1.9.0 / 639KB | eval/Function 60 处、控制流平坦化 | 无法静态审计 | QUARANTINE_OBFUSCATED，不执行不安装 | STATIC_ONLY |
| Flower 野花 v1 / 10KB | \x 编码 1241 处 | 无法静态审计 | QUARANTINE_OBFUSCATED，不执行不安装 | STATIC_ONLY |

可接入功能拆分结论：长青的 SVIP 定位是 BLOCK 根因，如需接入只能去掉会员解锁卖点后单独评估；
非常刀/玉宁熙的合规部分（标准 LX 事件结构）可进入架构兼容性验证，但音频能力以真实验证为准，
本轮均只到 STATIC_ONLY，未执行脚本、未做真实搜索/播放。

**阶段 C：社区音源自动发现框架**（新增代码，全部在 provider/sourceupdate）：
- `CommunityCatalogModels.kt`：社区清单模型 `{sources:[{id,name,url}]}`、DiscoveredCandidate/CandidateStore、
  审核状态 DISCOVERED/QUARANTINED/NEEDS_REVIEW/APPROVED/REJECTED/STALE、验证等级 STATIC_REVIEW→REAL_LOSSLESS_VERIFIED
  （isReal 从 REAL_SEARCH 起）、风险等级、URL 规范化（gh-proxy 前缀剥离/百分号解码）、三类去重
  （规范化 URL / 仓库路径 / SHA256 预留）、清单解析校验（空 sources / 非法字段 / id 重复一律拒绝）。
- `CommunityCatalogDiscovery.kt`：`discover()` 复用 `CatalogFetcher` 主地址→镜像、失败用上次成功缓存、
  解析/去重/STALE 标记；`scanQuarantined()` 复用 `SourceStaticPolicy` 扫描隔离区脚本。
  **隔离红线**：状态只会写 DISCOVERED/QUARANTINED，绝不写 APPROVED；不执行脚本、不安装、
  不扩白名单（提取域名只进候选元数据供人工审）、不覆盖受控目录 source-catalog.json；
  清单读取失败/结构非法/空 sources 一律保留现有候选不清空。
- `CommunityCatalogDiscoveryTest.kt`（12 项）：镜像回退、三类去重、缓存回退不清空、STALE 不删除、
  BLOCK 自动隔离且不批准、干净扫描不改状态、解析器拒绝非法清单；以及四项审查意见回归：
  镜像别名仍在清单不误标 STALE、STALE 仅恢复到 DISCOVERED 不碰人工结论、
  不同仓库相同末尾路径不误合并、URL 只规范化协议/主机而保留路径大小写、扫描核验 SHA256 不符整条拒绝。

**file:line 数据链路（现有能力，未重写）**：
- LX 加载 `provider/lxruntime/.../LxScriptRuntime.kt:127`（load）、`:166-207`（inited 必需）；
  shim `LxBridge.kt:44-192`；搜索适配器 `LxSongSearchRegistry.kt:58-59`（默认仅 kw+kg）；
  质量回退 `LxScriptProvider.kt:369-382`（刻意不用 flac24bit）。
- 门禁 `SourceStaticPolicy.kt:46-62`（BLOCK 判据）；安装事务 `SourceVersionStore.kt:295-365`
  （审核→SHA256→静态扫描→staging→冒烟→切 active）；多镜像验签 `SourceCatalogUpdater.kt:159-238`；
  签名 `config/source-catalog/tools/sign-catalog.mjs`（私钥不入库、环境变量传入）。
- 验证十级定义在 candidates.json:testLevelDefinitions；真实 Range 验证 `RealInterfaceVerificationTest.kt`
  （opt-in FUOE_REAL_VERIFY=1，只读 16B，不下载完整商业曲目）。

**交付边界澄清（重要，避免夸大）**：本轮完成的是「发现框架」，**不是**「完整自动发现产品功能」。
- 框架完成 = 可读取社区清单、主地址→镜像回退、解析校验、去重登记候选、静态扫描隔离区脚本、
  状态流转与 STALE 判定，全部可离线测试。
- 产品接入完成 = 应用启动时自动触发发现、定时任务周期执行、UI 展示候选并提示人工审核、
  持久化审核记录并回流。**这些均未接入**——`CommunityCatalogDiscovery` 目前没有任何生产调用点，
  既未挂到 App 启动流程，也没有定时器或界面入口；候选区 store 只在测试中读写。
- 因此不得宣称「App 已能自动发现/自动更新音源」。下一步若要产品化，需在宿主容器
  （AndroidAppContainer/DesktopAppHost/IosAppHost）注入并调度该框架，另列任务实施。

**审查意见四项修复（2026-11-20）**：
1. 镜像去重与 STALE 一致性：`DiscoveredCandidate.aliasIds` 保存被归并的别名 id；STALE 判定把主 id
   与别名一起计算（别名仍在清单即不算消失）；重新出现仅 STALE→DISCOVERED，QUARANTINED/REJECTED/
   APPROVED/NEEDS_REVIEW 等人工结论一律不自动改写。
2. 去重防误合并：`repoPath()` 升级为完整 `<owner>/<repo>/<path>`（保留大小写），非 GitHub raw 地址
   返回空串且不参与路径去重——不同仓库相同末尾路径不再误合并；`normalizeUrl()` 只小写协议与主机，
   路径大小写原样保留（区分大小写的服务器不再被误判为同一 URL）。
3. 扫描哈希核验：`scanQuarantined()` 内部按脚本文节计算实际 SHA256，与传入期望值不符则整条拒绝写入
   （返回 Sha256Mismatch，候选保持原样）；写入时一并记录 version、rawUrl、scannedAtMillis 与实际哈希。
4. 边界澄清：见上，明确区分框架完成与产品接入完成。

**未做（需项目所有者/私钥，非遗漏）**：正式目录签发（私钥不在可访问位置，缺 FUOEVOLVE_CATALOG_KEY）；
Fish/Flower 离线解混淆；非常刀/玉宁熙域名逆向与授权确认；真机搜索/播放/下载验收；
断点续传/离线播放/跨源匹配；发现框架的产品化接入（启动调度/UI/审核记录回流）。
全部门禁与验签保持原样，未关闭、未降配、未改公钥。

## §13 阶段 D：Android 社区音源发现与审核中心（work/android-community-discovery）

**目标边界**：把已完成的 CommunityCatalogDiscovery 框架接入 Android App，实现"发现 → 隔离 →
审查"，并做非常刀/玉宁熙的 LX 架构兼容性验证。**不是**商业音源正式上线；不批准、不安装、
不扩白名单、不真实下载商业音质。

**D1 数据流（file:line，均已核实）**：
- 候选存储 `<filesDir>/source_catalog/community/candidates.json`：注入 `FuoEvolveApplication.kt:23`
  `installSourceStorageDirectory(File(filesDir,"source_catalog"))` + `SourceFileStorePlatform.android.kt:15-19`；
  与受控 `sources/index.json` 同根不同子树。
- 后台调度模板 `AndroidSourceUpdateWorker.kt:24-54`（24h + NetworkType.CONNECTED + KEEP）；
  Application enqueue `:24`；启动补查 `AndroidAppContainer.kt:46-49`。
- 导航：自研 `AppRoute`（`AppState.kt:20-45`）+ `AppNavigator`（`:57-80`）；NavHost 分支
  `AppNavHost.kt:211`；返回 `AppBackCoordinator.kt:109-114`；shell route `AppShellComposition.kt:88-97`。
- 设置页：内部 `FeatureSettingsRoute`，"音源与账号"分类渲染 `SettingsFeatureScreen.kt:532`。
- LX 加载/初始化/解析 `LxScriptRuntime.kt:127/166/214`；SSRF `LxNetworkPolicy.kt:29+`（空名单拒绝一切）。

**D2～D4 实现**：
- `provider/sourceupdate/CommunityDiscoveryRepository.kt`（commonMain）：发现刷新包装、人工审核
  （隔离/拒绝/重新审核，写 rejectReason）、隔离区下载（SHA256 核验，不符整条拒绝）、
  删除候选只删 `community/quarantine/` 前缀不碰正式目录；**任何路径禁止写 APPROVED**（check 断言）。
- `shared/feature/community/`：`CommunityDiscoveryController`（StateFlow UI 状态）、`Factory`
  （硬编码 shellsec 主地址+镜像、followRedirects=false、非信任根）、`Screen`（候选列表+详情弹窗+
  人工动作）、`Entry`（CompositionLocal 入口）。
- 接线：`AppUiGraph` 加可选 `communityDiscovery`（默认 null，不破坏其它平台）；`AppRoute.CommunitySources`；
  NavHost/backCoordinator/shell 各加分支；设置页 Sources 分类加"社区音源发现与审核"入口；
  AndroidAppContainer 注入真实 Repository+Controller；`AndroidCommunityDiscoveryWorker`（24h 后台发现）。
- **UI 展示红线**：`CommunityDiscoveryPresentation.reviewLabel` 把 DISCOVERED 显示为"已发现（待审核）"，
  CLEAN 显示"静态扫描通过"，验证等级非 REAL 一律追加"（非真实音频验证）"，绝不显示成已批准。

**D5 架构验证（CommunitySourceArchitectureTest，desktop+androidHost 双份；MockEngine 零真实网络）**：
| 脚本 | SHA256 | QuickJS 加载/初始化 | 声明平台 | SSRF/隔离 |
|---|---|---|---|---|
| 非常刀 v5 | `49486ca…64284d` | 通过（SIMULATED_INIT） | wy/tx/kw/kg/mg | 12 域名需显式白名单，裸 IP 不自动放行 |
| 玉宁熙 v1.2.5 | `5dd4752…d275f79` | 通过（SIMULATED_INIT） | tx/wy/kw/kg/mg | 域名静态不可见，空名单 fail-closed 拒绝全部请求 |
| 长青 | 原文与审查记录一致 | **未加载** | — | 保持 BLOCK(SVIP) 隔离 |
| Fish/Flower | — | **未加载** | — | 保持 QUARANTINE_OBFUSCATED 隔离 |

**验证等级诚实声明**：D5 只达到 `LX_RUNTIME_LOAD` / `SIMULATED_INIT`，**不是** REAL_*；未真实网络、
未解析真实播放地址、未下载任何音频、未验证音质。非常刀 flac24bit/master/hires 映射与玉宁熙 hires/atmos
仅为接口声明，不代表真实可用或"无损母带"。请求频率（玉宁熙 2s/4 次）本轮未压测（无真实请求）。

**新增缺口/未做**：隔离区"受控下载器"（把脚本正文从可信 URL 下载到隔离区）尚未接入 UI——
`scanScript` API 已就绪但页面无下载入口，网络下载由后续任务按受控通道补齐；真机 Android 页面/Runtime
加载需设备；人工审核记录持久化在候选 store（rejectReason），未做独立审计日志。
不改 master、正式目录、公钥、验签；未自动批准/安装、未扩白名单、未绕付费、未把模拟写成真实。

## §14 阶段 D 修复：后台等待 + 隔离下载闭环 + 并发保护（work/android-community-discovery）

**P1-1 Worker 等待**：`AndroidCommunityDiscoveryWorker.doWork()` 直接 `await` 同一 Repository 实例的
`suspend refresh()`，发现/解析/保存全部完成才返回；网络失败 `Result.retry()`、清单结构非法/空
`Result.failure()`（不无限重试）、`CancellationException` 重抛。UI 手动刷新与 Worker 共用
`AndroidAppContainer.communityDiscoveryRepository` 单一实例；24h/CONNECTED/KEEP 保留。
结果分类抽成纯函数 `workerDisposition()` 便于 commonTest 验证。

**P1-2 隔离下载闭环**：新增 `CommunityScriptDownloader`（shared/feature/community）——HTTPS-only、
SSRF 危险目标检测（复用 `LxUrlSafety` 公开门面，私网/IPv6/metadata/裸IP 拒绝）、禁重定向（Factory
配 `followRedirects=false`，3xx 一律拒）、`readBodyBounded` 1MiB 预判+流式双保险、15s 超时、
异常分类。URL 只允许候选已登记的 rawUrl/mirrorUrls（不支持手动任意 URL，比任务书更严格），
提交时校验 URL 归属。下载在锁外 → 实际 SHA256 核验（不符整条拒绝、不留半成品）→ 写
`community/quarantine/` → 自动静态扫描 → 报告展示（实际哈希/大小/版本/扫描时间/命中规则/风险）。
无预期哈希时标 `hashUnverified=true`（UI 提示"未经来源确认"）。绝不自动加载 LX/安装。

**P2-1 并发保护**：Repository 用单一 `Mutex` 串行化所有候选库读改写（发现合并/审核/重审/扫描提交/
删除）；网络下载在锁外，提交时重读最新候选再锁内写；人工 REJECTED/QUARANTINED 优先于并发发现；
取消信号不被吞。5 项屏障竞态测试（CompletableDeferred，非随机延时）。

**P2-2 窄屏 UI**：详情改 `verticalScroll` 可滚动；审核操作从单行 Row 改纵向 Column（下载/重新扫描/
隔离/拒绝/删除各自整行，不溢出）；删除二次确认（点两次才删）；下载/审核/扫描期间按钮禁用防重复；
错误按 NETWORK/HASH_MISMATCH/STATIC_BLOCK/STORAGE/SECURITY_REJECTED 分类提示。

**验证**：sourceupdate 121 / lxruntime 80 / shared 373 项 0 失败；三端编译
（Metadata/Android/iOS Simulator/Desktop）+ androidApp + desktopRuntime + lxruntime iOS 全绿。
新增测试：并发 5、下载器 7、Worker 决策 1、UI 逻辑 3。无 Compose UI 测试依赖，未引入新依赖；
窄屏布局由三端编译保证，UI 状态逻辑用 Controller 单测验证。master/正式目录/公钥/验签未改。

## §15 阶段 D.1：发现流程解耦与安全专项复核（2026-11-20）

**目标**：架构加固 + 回归验证，不新增音源。基线 `36804ea` → 新提交见下。

**P0 发现流程解耦（消除锁内网络）**：`CommunityCatalogDiscovery` 拆成两段——
`fetchRemoteCandidates(listUrls)`（阶段 A，纯网络+解析，**不读写 store**，可在 Mutex 外调用）与
`mergeFetchedCandidates(fetched)`（阶段 B，调用方持锁，重读最新 store 再合并写回）。
`CommunityDiscoveryRepository.refresh()` 改为「锁外 fetch → ensureActive → 锁内 merge」，
网络等待期间 Mutex 空闲，人工审核/删除/扫描提交不再被慢网络阻塞。
`discover()` 保留为测试兼容入口（非原子），生产路径只走 Repository。

**删除=永久忽略（tombstone）**：`CandidateStore` 新增 `ignoredIds`（默认空，旧数据安全迁移）。
`deleteCandidate` 把主 id + 别名写入墓碑；合并时命中墓碑的条目直接跳过，
杜绝"删除后一条在途旧刷新把候选复活"。需人工 `unignore()` 才能重新纳入。
合并时 URL 未变的候选保留 sha256/扫描时间/静态结论等审查证据，不被空发现结果抹除；
预期哈希非法（非 64 位 hex）拒绝提交。

**P1 下载器安全复核**：新增 `validateDownloadUrl`，在 LX SSRF 门面之上补严格语法边界：
HTTPS-only、拒绝 userinfo / 异常端口（仅 443）/ 尾随点 / 百分号编码主机 / 控制字符，
并覆盖 localhost、私网、回环、链路本地、IPv6 字面量、IPv4-mapped IPv6、
非标准 IPv4 表示（0x7f000001 / 127.1 / 2130706433）、元数据地址。
3xx 一律拒绝（客户端 followRedirects=false）、Content-Length 预判 + 流式双限、15s 超时、
取消信号上抛；下载器只到内存不落盘，哈希核验/隔离区写入在 Repository 锁内完成。
**已知边界**：commonMain 无法做 DNS 解析后逐 IP 校验，DNS rebinding 无法彻底防住；
缓解为"只下载候选登记 URL + 体积/时间硬限制"，不声称已彻底防护。

**P1 Worker / P2 一致性复核**：确认 Worker 直接 await Repository.suspend refresh、
CancellationException 重抛、网络错误 retry / 清单非法 failure、24h+CONNECTED+KEEP 未变、
UI 与 Worker 共用同一 Repository 单例。所有候选库读改写均经同一 Mutex；
扫描的内部 store 读写在锁内被 Repository 串行化；重复 refresh 幂等不复制候选、不丢人工状态。

**验证**：新增 11 项解耦并发测试（门闩构造确定性时序，无随机 delay）+ 3 项下载器安全测试 +
幂等测试；sourceupdate 132 / lxruntime 80 / shared 376 项 0 失败（以实际执行为准）；
Android/iOS Simulator/Desktop/androidApp/desktopRuntime/Metadata 编译见提交记录。
未引入新依赖，未删测试、未放宽任何安全断言。master/正式目录/公钥/签名未改；
无自动批准/安装；长青/非常刀/玉宁熙/Fish/Flower 未接入真实音源。


## §16 阶段 F：合规音源真实功能验证与受控接入准备（2026-11-20）

**目标**：验证静态审查通过的候选是否真的能搜索/解析/播放，解决网络权限兼容，建立评分与淘汰，准备受控接入材料；
保持所有绕过与不可验证候选阻断。基线 `1eb8e60`（E3–E5），父提交 `dbcead9`（E0–E2），分支 `work/android-community-discovery`，未改 master。

**F0 真实验证（如实，含失败）**：至尊=502（裸IP/HTTP/1314，权限 deny-all）、HUIBQ=503（服务被所有者暂停）、
gdstudio=搜索200+URL解析200+音频206（audio/mpeg，仅读响应头）。三者脚本只有 `musicUrl` action、**无 search**，
故都无法升级 REAL_SEARCH；gdstudio 解析域名是第三方 CDN（m701.music.126.net），非其授权域名，单列风险。
真实播放一律 NOT_RUN（需用户有权访问且明确控制，HTTP 206 不冒充播放）。

**F1 初始化调用链**：ikun（可选 checkUpdate）真实 TLS 握手失败；juhe 初始化必须联网（init.conf），
按候选授权单域名而非放宽全局白名单，303 动态端点不可信 fail-closed；animemusic 接口返回 code 500；
Flower 混淆+裸IP初始化 AggregateError，QUARANTINE 保持。区分了初始化错误/权限拒绝/脚本自身错误。

**F 网络权限工程**：新增 `CandidateNetworkPolicy`（candidateId/allowedHosts/schemes/ports/maxBytes/timeouts/
redirect/credential），候选级独立授权、禁通配符、默认 HTTPS+443、SSRF 复用 LxUrlSafety、禁 userinfo/编码host/
尾随点/IPv6/裸IP、SHA 绑定（脚本一变审批失效，`loadWithCandidatePolicy` 双重校验）。
DNS rebinding 遗留风险如实声明未彻底解决。

**F3 调度与 F4 UI**：`SourceFallbackScheduler`（最大尝试3/冷却60s/失败原因/平台隔离/结果防冒充/非递归/不后台全量），
门禁=≥2 真实搜索通过，**当前未开**故无主备建议；`CommunityQualityScorer`（6维权重+样本量约束+真实/模拟分栏），
候选均 UNVERIFIED 或 BLOCK，无默认音源。UI 详情页分栏展示 LX模拟/真实搜索/URL解析/音频响应/真实播放、样本量、
失败原因、评分、接入建议、主备选择（仅写 UI state，不启用/安装）；顶部声明调度器门槛与诚实边界。

**测试/编译**：新增 CandidateNetworkPolicyTest / PhaseFVerificationTest / PhaseFPresentationTest；
sourceupdate 153 / lxruntime 93 / shared 385 desktopTest 全绿；Android / iOS Simulator / Metadata（sourceupdate、lxruntime）编译通过；
`shared:compileCommonMainKotlinMetadata` 因上游 AppNavHost 的 navigation3 compose 依赖报既有错误（与本改动无关），
但 `shared:compileKotlinIosSimulatorArm64` 与 `shared:compileAndroidMain`、`shared:desktopTest` 均通过，证明 commonMain 改动跨端无 JVM-only 泄漏。
未改 master/正式目录/公钥/签名；无自动批准/安装；BLOCK 候选未绕过。详见 `docs/community-source-audit.zh-CN.md` §12。


## §17 阶段 G0：LX Runtime action 协议与搜索调用链审计（2026-11-20）

基线 `3432eb6`（阶段 F 最终提交），分支 `work/android-community-discovery`，未碰 master。用户提出分 G0-G3 四子阶段，G0 优先于继续找源。

**核心结论**：LX 自定义音源协议（移动端）本身没有 `search` action（`LxModels.kt:24-26` 只有 musicUrl/lyric/pic），
产品运行时只有 `requestMusicUrl`（`LxScriptRuntime.kt:239`），没有 requestSearch；`initialize()` 只认含 musicUrl 的源（:218）。
搜索由宿主侧 `LxSongSearcher` 注册表完成（`LxScriptProvider.kt:117-149`），**完全不调用脚本**；脚本只在 `resolve()` 用 musicUrl 换 URL（:151-187）。
JS 桥 `__lxDispatch`（`LxBridge.kt:182-190`）action 无关——G0 用自写测试脚本实测证明桥能派发 search 并拿回结构化结果，
但产品代码路径没有任何地方调用它。**因此"找实现 LX search 的音源"方向在当前协议下无落点**：不是候选偷懒，是协议没定义 + 产品无调用链/结果模型。继续批量抓源只会撞同一堵墙。

**五交付物**（详见 `docs/lx-action-protocol-audit.zh-CN.md`）：①全部 action 清单（inited/request/updateAlert 事件 + musicUrl/lyric/pic，其中只有 musicUrl 被产品调用）；
②每个 action 参数/返回/错误约定；③UI→脚本搜索调用链（SearchFeature→providerRepository.searchAll→KotlinProviderRepository→LxScriptMusicProvider.search→LxSongSearchRegistry 宿主适配器，脚本仅在 resolve 阶段被调用）；
④已知可工作测试脚本 + 审计探针 `dispatchForAudit`（internal，不改协议不伪造真实结果）实测桥层兼容 search；⑤候选能力矩阵（DECLARED/LOADED/REAL_CALL_PASSED 三态）——声明 search 的只有 QUARANTINE 的 feichangdao，与可加载集合不相交。

**对 G1 的约束**：承认协议无 search；改找"合规公开搜索接口 + musicUrl 解析脚本"组合，或启动 G2 定义脚本 search 协议/调用链/结果模型；
不把声明 search 的 QUARANTINE 脚本当突破口，不把 MusicFree 插件直接当 LX 音源，不放宽 SSRF/会员。


## §18 阶段 G1：寻找真正实现 LX search 的合规候选（2026-11-20）

基线 `19fe2cf`（G0），分支 `work/android-community-discovery`，未碰 master。

**G1-0 先核对官方规范**：LX 桌面/移动文档均确认协议无 search action（桌面："其他源只支持 musicUrl"，local 才 musicUrl/lyric/pic；移动："action 目前只有 musicUrl"）。G0 结论获上游规范双重确认。

**找到的候选**（GitHub Code Search 去重后 4 个，取真实文件/计 SHA/静态审查/隔离加载）：
溯音 suyin（d10c9c28…，pdone 无 LICENSE，脚本内 handleSearch kw/mg，隔离 LOADED）、
幻音 huanyin（4a811292…，pdone 无 LICENSE，searchMiguMusic，LOADED）、
Ciallo~（fd16333b…，guoyue2010 **Apache-2.0**，searchMusic 走 api.s0o1.com，LOADED）、
非常刀 feichangdao（QUARANTINE 保持，禁止执行）。三者确实在**脚本内**实现 search（非仅上游 API）。

**结论：没有"真正实现 LX search 的合规候选"。** 四道门槛全未过：①LX 协议无 search action、产品无调用链，实现也无处可用；②suyin/huanyin 无 LICENSE，仅 Ciallo 有 Apache-2.0；③依赖 oiapi.net/api.xcvts.cn/api.s0o1.com 第三方聚合 API 授权不明；④实证 Ciallo 脚本解析 `data.data.id`（对象）但真实 API 返回数组 → search 实返空（脚本 bug），suyin 靠 message 文本兜底脆弱。未升级任何候选为 REAL_SEARCH_PASSED/ELIGIBLE；未执行 QUARANTINE、未做真实 search、未放宽 SSRF、未接入。

**对 G2/G3**：脚本 search 是新协议+新调用链+新结果模型工程（G2），不是换音源可解；当前架构下可用搜索仍走宿主 LxSongSearcher 注册表（kw+kg）。新增 license 字段与 PhaseG1 台账/隔离加载测试。不把 MusicFree 插件当 LX 音源、不设 gdstudio 默认、不放行 juhe 动态 URL、不启动自动主备。


## §19 阶段 G2：MusicFree 合规来源发现与受控适配层（2026-11-20）

基线 `408b75e`（G1），分支 `work/android-community-discovery`，未碰 master。用户条件指令："发现值得接入的来源再做 G2 适配器"。

**发现结论：有值得接入的来源。** 官方 `maotoumao/MusicFreePlugins`（GPL-3.0、149 commits、维护至 2025-11）权威 plugins.json（sha256 a9b2d83b…）+ 12 插件正文真实下载计 SHA。MusicFree `plugin.d.ts` 原生定义 `search(query,page,type)->{isEnd,data[]}` 与 `getMediaSource`，正好补上 LX 协议缺失的 search——但属另一套协议（CommonJS + Node 模块），需适配层。12 插件静态审查零 VIP/会员绕过/混淆/eval/裸IP；唯一 cookie 命中是 bilibili 匿名 buvid3/buvid4 访客标识（非会员凭据，需人工复核）。官方 README 声明过滤 VIP/试听/付费。

**两类来源**：①用户自建库 Navidrome/WebDAV（Subsonic/WebDAV，内容属用户本人，最合规；但端点用户配置常为私网/裸IP，不为它放宽 SSRF）②固定具名域名 Audiomack（api.audiomack.com，可生成候选级授权）③动态端点 YouTube（响应体决定 URL，fail-closed）。

**适配层（新模块 :provider:musicfree，不改 LX 核心）**：MusicFreeProtocol（数据形状映射+fail-closed+强制HTTPS）/MusicFreePluginRegistry（12 插件真实 SHA/版本/GPL/能力/域名/证据台账）/MusicFreePluginPolicy（复用 CandidateNetworkPolicy：SHA 绑定+域名+SSRF，自托管/动态 deny-all）/MusicFreeAdapter（纯函数映射+能力/授权/URL 门禁，Mapped/Denied+等级诚实）。不执行 CommonJS、不发网络、不下音频、不写凭据、不注册为 Provider、无安装按钮。等级标注 `DECLARED+MAPPED`/`REAL_URL_RESOLVE 候选`，不冒充真实搜索/播放。

**边界**：CommonJS/Node 模块隔离执行环境未实现；自托管/动态端点受控授权流程未实现；未做真实网络调用、无状态升级为 REAL_*_PASSED；DNS rebinding 风险不变。测试 PhaseG2AdapterTest 10 项；回归 sourceupdate157/lxruntime101/musicfree10 全绿；iOS Sim/Metadata/Android 编译通过。详见 `docs/community-source-audit.zh-CN.md` §14。
