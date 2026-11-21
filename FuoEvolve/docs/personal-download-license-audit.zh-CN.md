# 个人下载能力、许可判断与下载链路安全加固（K5-D）

> 阶段定位：个人使用、个人收藏、个人欣赏、非商业用途。下载是独立用户操作，不等同于在线播放，也不等同于商业授权。
> 核心原则：**个人下载是产品功能；非商业是使用目的；下载权限必须逐曲判断。三者不能混为一谈。**
> 基线：工作分支 `work/android-community-discovery`，HEAD `dc196ff129165db52a2ca42fabd67d4a38adcdf8`。
> 执行时间：2026-11-21。

---

## 0. 结论先行

| 维度 | 结论 |
|---|---|
| 单一布尔值许可模型 | 已废弃为唯一判断，新增逐曲决策模型 |
| Feature 层门禁 | 存在（原有），默认改为 fail-closed |
| Repository 层门禁（风险 A） | **已修复**：三端在 download/恢复/重试/真正下载前统一检查 |
| Android 恢复任务许可重查（风险 B） | **已修复**：恢复/重试重新决策，许可快照持久化，无快照按 UNKNOWN 拒绝 |
| Jamendo 逐曲许可映射 | 已精确识别六种 CC 许可；下载权限保持 UNKNOWN（待核实），不放行 |
| UI 三态 | 已提供"可下载/待核实/来源禁止"标签 |
| 自动化测试 | 855 项（多 target 累计）0 失败；三端编译/测试源码编译通过 |
| 网络安全边界 | 未改动 Gateway/SSRF/TLS；无新增音源；无凭据/私钥入库 |
| **PERSONAL_DOWNLOAD** | **PARTIAL** —— 门禁与模型落地，但无可核实到"允许个人下载"的真实来源，故当前无曲目实际可下载 |

诚实说明：**没有任何一首曲目被本阶段判定为"允许个人下载"**。 Jamendo 等来源的作品许可允许播放/署名/非商业，但"当前下载方式的平台授权"未取得逐曲充分证据，按纪律保持 UNKNOWN 默认拒绝。这符合"许可证据不足时只对已核实歌曲开放下载，其余待核实"——当前已核实可下载的集合为空集。`PERSONAL_DOWNLOAD=PASS` 的验收条件中"明确允许个人下载的歌曲能够成功保存"因缺乏真实授权来源而无法满足，故判 PARTIAL，不冒充 PASS。

---

## 1. K5-D.0 真实下载调用链（审计结果，均带文件:行号）

### 1.1 结构化许可与门控定义

| 项 | 位置 |
|---|---|
| `TrackLicense` 字段定义 | `core/model/.../TrackLicense.kt:20` |
| `canDownloadTrack()` / `downloadDenialReason()` | `core/model/.../TrackLicense.kt` （现委托 `evaluateDownloadPermission`） |
| `trackLicenseFromTags()` CC 标签解析 | `core/model/.../TrackLicense.kt:62` |

### 1.2 Feature 层（第一层）

- `feature/download/.../DownloadFeature.kt:184-198` `owner.download()`：
  `isProviderTrack` → `canDownload`（许可门控，在 resolve 之前）→ 反馈 → `resolver.resolve` → `repository.download`。
- `feature/download/.../DownloadFeature.kt:62` `DownloadFeatureOperations.canDownload` —— 本次由默认 `true` 改为**默认 `false`（fail-closed）**。
- `shared/.../feature/download/DownloadFeatureFactory.kt:79-81` 生产绑定：`canDownload = canDownloadTrack`，`downloadDenialMessage = downloadDenialReason`。

### 1.3 Repository 层（第二层，本次新增）

| 平台 | 文件 | download 入口 | 恢复/重试 | 真正下载前 |
|---|---|---|---|---|
| Android | `androidApp/.../AndroidDownloadRepository.kt` | `:96-106` | `restart()` `:189` | `runTask()` `:224` |
| Desktop | `shared/.../desktopMain/.../DesktopDownloadRepository.kt` | `:85-93` | `restart()` `:190` | `runTask()` `:242` |
| iOS | `shared/.../iosMain/.../IosDownloadRepository.kt` | `:52-60` | `resume()` `:139` | `runTask()` `:95` |

三端均通过统一 `DownloadRepository.downloadAuthorization`（默认 `::evaluateDownloadPermission`，fail-closed）检查；测试可显式注入，生产一律走统一决策。

### 1.4 创建下载任务的全部入口

- Feature 唯一入队：`DownloadFeature.kt:195` `repository.download(track, payload)`。
- 三端 enqueue 建任务：Android `:106`、Desktop `:151`、iOS `:62`。
- 恢复/重试再入队：Android `restart()`、Desktop `restart()`、iOS `resume()→download()`（均已有门禁）。

### 1.5 审计发现的两个风险（均已修复）

- **风险 A（Repository 可被绕过）成立**：原 `canDownloadTrack` 生产调用点仅 `TrackLicense.kt` 定义与 `DownloadFeatureFactory.kt:79` 绑定；三个 Repository 的 `download/enqueue/runTask` 只检查 `sourceType`，可直接调用绕过。**本次修复**：三端加第二层门禁。
- **风险 B（Android 恢复不重查许可）成立**：原 `resume/retry = restart()` 仅翻转状态；`load()` 反序列化 `JSONObject.toTask()` 构造的 `MusicTrack` 丢失 `license`（为 null）。**本次修复**：持久化完整许可快照，恢复/重试重新决策，无快照按 UNKNOWN 拒绝。

---

## 2. K5-D.1 TrackLicense 最终字段语义

保留原 10 字段（licenseType/licenseUrl/attribution/playLicense/downloadLicense/commercialUseAllowed/derivativesAllowed/redistributionAllowed/licenseVerifiedAt/licenseSource），新增 5 字段：

| 字段 | 默认 | 语义 |
|---|---|---|
| `personalCopyAllowed` | false | 是否允许个人非商业原样复制保存 |
| `platformDownloadAllowed` | false | 来源是否提供官方下载接口且条款允许该下载方式 |
| `downloadPermissionStatus` | UNKNOWN | `ALLOWED_PERSONAL` / `CONDITIONAL_NONCOMMERCIAL` / `UNKNOWN` / `DENIED` |
| `downloadPermissionEvidence` | null | 下载权限证据（许可链接/API 字段/条款出处） |
| `downloadPermissionReason` | null | 拒绝/待核实的简短原因 |

统一决策 `evaluateDownloadPermission(track)` 是**唯一权威判断**，放行必须同时满足：
`downloadLicense && personalCopyAllowed && platformDownloadAllowed && status∈{ALLOWED_PERSONAL, CONDITIONAL_NONCOMMERCIAL}`，且 CONDITIONAL 分支额外要求非商业 + 署名。
- `commercialUseAllowed=false` 单独既不推出可下载，也不推出不可下载。
- ND 只限制演绎，不因"原样再分发"字段误判为禁止个人原样保存。
- 决策不读取 `playLicense`——可播放与可下载彻底分离。
- 全部条件默认保守值，未知保持 UNKNOWN，不自行推测。

`DownloadPermissionDecision.toUi()` 映射为三态：`CAN_DOWNLOAD` / `VERIFYING` / `NOT_AVAILABLE`。

---

## 3. K5-D.2 Jamendo 逐曲许可映射证据

- API 字段：`JamendoApiClient.kt:109` `licenseCcUrl = item.text("license_ccurl")`，逐曲来自当前曲目对象。
- 精确识别（`JamendoModels.kt`）：新增 `licenseKind`（按标准六种 CC 逐一匹配：BY / BY-SA / BY-NC / BY-NC-SA / BY-ND / BY-NC-ND / CC0 / PD；非 `creativecommons.org` 域或未知组合返回 null）与 `licenseVersion`（从 URL 提取 3.0/4.0）。不再靠 `contains("NC")`/`contains("ND")` 粗略判断许可类型本身。
- 逐曲映射（`JamendoMusicProvider.kt:140`）：`licenseType="CC <kind> <version>"`，保留完整 `licenseUrl`/`attribution`；`commercialUseAllowed`、`derivativesAllowed` 按许可精确赋值；`redistributionAllowed` 修正为"标准 CC 许可在署名前提下允许原样再分发，ND 不等于禁止原样分享"。
- **下载权限**：`downloadLicense=false`、`personalCopyAllowed=false`、`platformDownloadAllowed=false`、`downloadPermissionStatus=UNKNOWN`、`downloadPermissionEvidence=license_ccurl`、`downloadPermissionReason="平台下载接口授权尚未逐曲核实"`。**未把固定的 downloadLicense 改成 true**——作品是 CC、存在 downloadUrl 字段，均不构成"当前下载方式获授权"的证据。
- 结论：Jamendo 曲目当前全部 `VERIFYING`（待核实），不可下载。

---

## 4. K5-D.3 / K5-D.4 双层门禁与恢复核验

- **第一层（Feature）**：`canDownload` 默认由 true 改为 false；生产覆写为逐曲决策；拒绝时不入队、不 resolve、不发网络。
- **第二层（Repository）**：统一接口 `DownloadRepository.downloadAuthorization`（`shared/.../core/model/RepositoryContracts.kt`），默认 `::evaluateDownloadPermission`，配套 `isDownloadBlocked()` 辅助。三端在 `download()`、`restart()/resume()`、`runTask()` 三处检查；被拒置 Failed 并写原因，不 resolve、不请求、不尝试备用地址/重定向。
- **持久化恢复**：
  - Android：新增 `TrackLicense.toJson()/jsonToLicense()`，`DownloadTask.toJson()` 写 `license` 快照，`toTask()` 恢复；旧任务无字段 → null → UNKNOWN 拒绝。
  - Desktop：新增 `DesktopLicenseSnapshot` 可序列化 DTO，随 `DesktopDownloadTaskSnapshot` 持久化/恢复。
  - iOS：现有分隔字段持久化**未扩展许可快照**，恢复的 track 无 license → runTask 门禁按 UNKNOWN 拒绝（安全侧保守，但不具备"许可变化后精确重判"能力，列为遗留）。
- **禁止绕过**：直接调用 Repository、恢复历史任务、retry、换音质重入队、持久化旧任务，全部在 runTask/download 门禁覆盖。

---

## 5. K5-D.5 个人下载产品体验

- `DownloadActionPort.downloadPermission(track)` 返回 `DownloadPermissionUi(label, reason)`，UI 可区分"可下载 / 待核实 / 来源禁止"，不含 CC/NC/ND 术语。
- 点击被拒项：通过既有 `publishFeedback(downloadDenialMessage)` 显示简短原因（"下载权限尚未核实"/"来源明确禁止"等）。
- 下载成功保留信息：`DownloadRecord`（title/artists/album/source/uri/大小/时间）+ 任务 `license` 快照（许可类型/链接/署名/状态/证据）随任务持久化；署名写入内嵌元数据（`writeEmbeddedMetadataIfNeeded`，Android）与歌词文件。
- 产品措辞边界：个人下载不等于商业授权/永久版权/可公开分享/可改编/可重新上传——本阶段未新增任何此类宣传。

---

## 6. K5-D.6 下载资源解析审计（含未解决项）

- 区分播放地址与下载地址：**既有架构未完全区分**。下载在无 payload 时复用 `playbackProvider.resolve()` / `providerRepository.resolve()`（Android `:224`、Desktop `:242`、iOS `:98`），即播放解析路径；未优先使用来源的独立官方下载接口。
- 下载请求走 `URL(payload.url).openConnection()`（Android `:270`、Desktop `:328`），**未经 ProviderNetworkGateway**（既有实现）。本阶段未放宽、也未改造此路径。
- 失败真实性：异常保留原始 `failureMessage`（网络/HTTP/解析/校验分别记录）；`validateDownloadedAudio()`/Desktop `completedDownloadValidationFailure()` 校验容器，不把 HTTP 206 或部分响应直接当成功。
- 未伪造请求头/Cookie 绕限；未放宽白名单/SSRF/重定向/响应体大小（网络边界文件零改动）。
- **未解决遗留（如实记录，未在 K5-D 修复）**：① 下载请求尚未统一经 ProviderNetworkGateway；② 下载未优先使用来源官方下载接口、仍复用播放 resolve；③ iOS 未持久化许可快照。三项均不伪装为已完成，需后续阶段在取得真实可下载来源后专项处理——它们不阻塞"默认拒绝未知许可"的安全目标，但影响"逐曲精确重判"与"下载地址来源合规"的完备性。

---

## 7. K5-D.7 自动化测试结果

| 模块/target | 结果 |
|---|---|
| `core/model:desktopTest` | PASS（含 TrackLicenseTest 新增 7 项 K5-D 决策测试） |
| `provider/jamendo:desktopTest`（commonTest 同步） | PASS（含 licenseKind/licenseVersion 精确识别 3 项新测试） |
| `shared:desktopTest` | PASS（含 Desktop 仓库 3 项新测试：直接调用拦截、DENIED 拦截、恢复重查） |
| `feature/download:desktopTest` | PASS |
| `androidApp:testDebugUnitTest` | PASS |
| `androidApp:compileDebugKotlin` | PASS |
| `shared:compileKotlinIosArm64` / `compileTestKotlinIosSimulatorArm64` | PASS（iOS 无模拟器，执行 SKIPPED，如实） |

累计 855 项测试（多 target 重复计数）0 失败。全部 `--rerun-tasks` 执行，避免缓存掩盖。未用 Mock 冒充真实下载——所有新测试走真实 `evaluateDownloadPermission` 决策，下载管线测试用显式授权的曲目字段。

覆盖场景：明确允许 / 非商业条件 / BY-NC-ND 原样 / 仅播放 / UNKNOWN / 明确禁止 / 商业标记不推导 / ND 演绎限制 / 直接调 Repository / 恢复重查 / 许可变化 / 重试 / 取消 / 失败 / 旧数据默认拒绝——见各测试文件。

---

## 8. 各 Provider 下载许可覆盖情况

| Provider | 结构化许可 | 下载权限 | 说明 |
|---|---|---|---|
| Jamendo | 有（逐曲 CC，精确识别） | UNKNOWN / VERIFYING | 平台下载接口授权未核实，默认拒绝 |
| 商业来源（网易/QQ/B站/YT/ModeB 等） | 无结构化许可 | UNKNOWN / VERIFYING | 默认拒绝，不靠 tags 猜测会员权益 |
| LX / MusicFree 沙箱脚本 | 视脚本声明显式授权 | 默认 UNKNOWN | 需来源显式写入完整下载许可字段才放行；本阶段未新增/未变更 |

当前**可个人下载曲目集合为空**；**禁止/待核实**为全部现有曲目。没有任何曲目被误标为可下载。

---

## 9. 未解决的下载许可问题

1. 无真实来源提供"当前下载方式已逐曲授权"的充分证据 → 无可下载曲目，`PERSONAL_DOWNLOAD=PARTIAL`。
2. 下载请求未统一经 ProviderNetworkGateway、未优先用官方下载接口（§6 遗留）。
3. iOS 未持久化许可快照，恢复按 UNKNOWN 拒绝（安全但非精确重判）。
4. `licenseVerifiedAt` 在 Jamendo 仍为 null（逐曲许可来自 API 但未记录核验时间）。
5. 真实 UI 点击/真机下载/人工听音仍 NOT_RUN（属 K3/K5 真实验收范畴，资源未到位）。

---

## 10. 改动清单与安全确认

- 改动 14 个源码/测试文件（见 git diff）；无新增音源、未改默认音源策略、未改正式目录审批状态。
- 网络边界文件（Gateway/SSRF/TLS）零改动；无通配符授权、无凭据/私钥/token 入库（`git diff` 敏感扫描 0 命中）。
- master 未改；未生成/提交任何密钥；未伪造 PASS。
