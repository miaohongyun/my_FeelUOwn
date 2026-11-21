# Jamendo 正式播放闭环与中国大陆网络验收（阶段 K1）

> 产品目标不变：中国大陆普通家庭成员安装 App 后，不配置音源、不写 JS、不配服务器，打开即可搜索并播放。
> K1 只解决两件事：① Jamendo 真实搜索结果能否经 App 正式播放链路被正式播放器真实解码播放；
> ② Jamendo 在中国大陆目标网络（家庭宽带/移动数据/真机）能否不依赖代理稳定搜索与播放。
> 不接入新音源、不开放下载、不改默认音源策略。

执行时间：2026-11-21。执行设备：内网 Windows（DNS `192.168.6.1` / `opnsense` 网关），JDK25。

---

## 1. Git 基线与提交链

- 基线 HEAD=`73368bf93824ac49eb87f7006c86493097ed33e0`（K0），父=`8e8c428`（J2.5）。
- K0 提交包含在 HEAD（`git merge-base --is-ancestor` = YES），工作区干净。
- 远端（只读 `ls-remote`）：work=`73368bf`、master=`fc05530c09966dedacb413168a1dc6fda0d3afe1`（未动）。
- 不改历史、不修改 master。

## 2. Desktop 正式播放器真实播放证据（K1.1）

**环境搭建（为真实解码，非放宽策略）**：本机无 C 编译器/7z/native bridge。为跑通生产 libmpv 后端，下载并 SHA-256 校验：
clang+LLVM 20.1.8 便携包（`f229769f…`）、libmpv dev bundle（`273c079b…`）、libmpv runtime（`f259b5fd…`），
用 clang/lld 编译生产 native bridge（`fuoevolve_mpv_bridge.dll`，184KB，1 个无害告警），
按生产 `preloadPackagedWindowsMpvRuntime` 顺序加载 DLL。全部下载 SHA 校验通过（fail-closed）。
工具装在用户目录 `~/.k1tools`，不写系统目录、不改 Gateway/TLS/SSRF 策略。

**测试**：`desktopApp/src/test/.../JamendoRealPlaybackTest.kt`，opt-in `FUOE_K1_REAL_PLAYBACK=1`，默认 SKIP。
链路：正式 `JamendoMusicProvider.search`（真实网络）→ 正式 `resolve()`（`trackById` 重取时效 URL）
→ 生产 `DesktopMpvPlaybackEngine` + 生产 `FfmMpvBackend` + native libmpv → 真实解码。
`ao=null`（无声解码，无音频设备），不下载整曲、不用 Mock、不把测试 URL 直接喂播放器。

**真实证据**（`desktopApp/build/k1-evidence.txt`，两次独立运行一致）：
```
K1_REAL_SEARCH        ambient → 20 条；首条 jamendo:1348698 / Music vast vast sky / Alexander Morozov
K1_REAL_URL_RESOLVE   host=prod-1.storage.jamendo.com trackid=1348698（URL 脱敏）
K1_REAL_PLAYBACK      status=Playing start=53 max=30113 advanced=30060ms（第二次 117→30212 / 30095ms）
K1_CONTROL            events=[StartFile(entry=1), FileLoaded(path=https://prod-1.storage.jamendo.com/), PlaybackRestart]
                      statuses=[Loading, Playing, Paused]；pause/resume/stop 全通过
K1_IDENTITY           PASS（播放会话与 Jamendo 曲目 ID 关联一致）
K1_ACOUSTIC_IDENTITY  NOT_RUN（无音频指纹交叉验证）
K1_HUMAN_LISTENING    NOT_RUN（ao=null 无声解码，本机无扬声器/耳机）
```
**结论**：libmpv 真实进入 Playing、播放位置连续推进 **>30 秒**、真实事件链完整、控制操作正常、资源 finally 释放。
这是"正式 Provider → 正式播放引擎 → 生产 native 播放器"的真实解码证据，非 HTTP/字节校验冒充。

**诚实边界**：测试在正式引擎层驱动，未走 App 搜索页点击→`PlaybackStartCoordinator` 的 GUI 入口（依赖 GUI/AppScope，headless 无法组装）；
不冒充 UI 端到端。`ao=null` 是解码播放，不是音频设备输出，不是人工听音。

## 3. Android 真机 ExoPlayer 验收（K1.3）

**NOT_RUN**。`adb devices` 无设备。不以 `testAndroidHostTest`（10 项通过）或 Android Kotlin 编译通过替代真机播放。
生产链路已审计存在：`AndroidNativeAudioEngine` → `FuoPlaybackService`（ExoPlayer + AudioFocus 第二参 + 耳机拔插监听 + 前台服务），
但无真机运行证据。缓冲/前后台/焦点/耳机拔插的 30 秒连续播放：**NOT_RUN**。

## 4. 曲目身份关联核验（K1.2）

关联链全部一致（同一首 `jamendo:1348698`）：
- 搜索结果原始 ID：`1348698`
- `MusicTrack.id`=`jamendo:1348698`，`providerId`=`jamendo:1348698`（无独立 `originalId`——普通搜索不填，K0 已记录模型如此）
- `trackById` 重取结果 id=`1348698`（与搜索一致）
- 播放 URL `trackid=1348698`（与搜索一致）
- 播放器实际加载 `https://prod-1.storage.jamendo.com/?trackid=1348698...`（脱敏同上）
- 播放期间关联 `MusicTrack`=`jamendo:1348698`（`playResolved(logical, resolved, payload)`）

`REAL_TRACK_IDENTITY_VERIFIED` = **PASS（会话与 ID 关联级）**。
`ACOUSTIC_IDENTITY_VERIFIED` = **NOT_RUN**（未实现播放后音频指纹与目标曲目参考的交叉验证）。
按任务书要求，不把 URL trackid 一致夸大为声学级身份验证。

## 5. 中国大陆网络矩阵与重复数据（K1.4）

**环境如实声明**：本机仅内网 Windows（DNS `192.168.6.1`），**无法确认为家庭宽带**——不做推断。
家庭宽带 / Android 家庭 Wi-Fi / Android 移动数据（移动/联通/电信）/ 另一独立网络 / iOS 真机：**均 NOT_RUN**。
不能覆盖的环境不外推为全国可用。

能实际测得的内网 Windows 直连（强制无代理，`--noproxy`/`ProxyHandler({})`）：

| 项 | 直连结果 | 备注 |
| --- | --- | --- |
| DNS `api.jamendo.com` | OK → Cloudflare `104.26.12.43`/`104.26.13.43`/`172.67.73.205` | 无污染 |
| TCP/TLS API | 0.23s / 0.46s | 直连可达 |
| API 搜索（2 轮 × 3 关键词） | 全部 HTTP 200；ambient 5/5 条、piano 5/5 条；love 轮1=0、轮2=5 条 | **证实偶发空结果** |
| DNS 音频域 | OK → Jamendo 自有 `141.94.x` | 无污染 |
| 音频 HTTP 响应 | 直连 206 + `Content-Range bytes 0-15/…` + `audio/mpeg` | K0 与 K1 均复现 |
| 正式播放 | 直连下 libmpv 解码 >30 秒（见第 2 节） | 不依赖本机代理 |

**直连与代理严格分开**：本阶段真实搜索、URL 重取、音频响应、正式播放全部在**直连**下完成，未使用 7897 代理。
代理仅在环境搭建时用于下载工具链（与 App 播放链路无关，不构成"代理成功"证据）。

**失败样本/波动**：Jamendo 公开 API 的 `/tracks/?id=` 端点偶发返回空（同一 ID 有时 `results_count=0`），
搜索 `search=` 也偶发 0 条（love 轮1）。单次样本不承诺长期稳定。

## 6. 最小修复（阻断性问题）

K1 实测发现生产 `JamendoMusicProvider.resolve()` 只调用一次 `trackById`，而该端点偶发空结果会导致
**用户点播放偶发失败**（真实产品缺陷，非测试问题）。按"阻断性问题先做最小修复"：
在 `resolve()` 内对 `trackById` 加有限重试（最多 3 次、间隔 300ms），保留取消传播、仍重取不使用旧 URL，
不改变权限/来源/默认策略。修复后 Jamendo 单测与真实播放测试均重新通过（见第 10 节）。

## 7. 失败处理与用户体验（K1.5，静态+单测证据）

- 网络/DNS/TLS 失败：Jamendo `JamendoFailureKind` 分类，UI 显示可理解错误；无 Compose UI 自动化测试（NOT_RUN）。
- API 超时/限流与网络错误分离：`JamendoApiClient` 超时/HTTP 状态映射为不同 failure。
- URL 重取失败：`resolve()` 每次 `trackById` 重取，返回 null 时不播放旧 URL（K1.6 单测覆盖）。
- 音频加载失败：libmpv `EndFile(ERROR)`/`Failure` → `publishBackendFailure` → `status=Error + errorMessage`，不永久 Loading。
- 用户取消/切歌：SearchFeature `searchGeneration` latest-wins + 播放 `playbackGeneration` 隔离旧请求。
- 播放结束：`EndFile(EOF)` → `status=Ended`，状态收敛。
- Provider 不可用：`searchAll` 用 `supervisorScope` 逐 Provider try/catch→failure，不伪造结果、不自动切未验证来源。
- 无复杂自动故障转移（任务书不要求）。

## 8. 验证等级汇总（分栏，禁止合并）

| 等级 | 结论 |
| --- | --- |
| REAL_INIT（正式 Provider 初始化） | PASS |
| REAL_SEARCH（正式 Provider 真实搜索） | PASS |
| REAL_RESULT_MAPPING | PASS |
| REAL_URL_RESOLVE（trackById 重取） | PASS（含最小修复后） |
| REAL_AUDIO_RESPONSE | PASS |
| REAL_PLAYBACK（Desktop 正式引擎真实解码 >30s） | **PASS** |
| REAL_TRACK_IDENTITY_VERIFIED（ID 关联级） | PASS |
| ACOUSTIC_IDENTITY_VERIFIED | **NOT_RUN** |
| 人工听音 | **NOT_RUN**（ao=null 无声） |
| Android 真机播放 | **NOT_RUN**（无设备） |
| 家庭宽带/移动数据直连 | **NOT_RUN**（仅内网 Windows） |

## 9. 测试设备、系统、网络与时间

- 设备/系统：内网 Windows，JDK25（`E:/mypro/.tools/jdk-25.0.5+8`），native libmpv 2（shinchiro 20261111 pin）。
- 网络：内网出口（DNS `192.168.6.1`），直连；无家庭宽带/移动数据/真机。
- 时间：2026-11-21 09:41–10:06（GMT+8），两次独立真实播放运行。

## 10. 构建与回归（每项分开）

- Jamendo Provider 单测 `desktopTest`：**14 项 0 失败**（默认，修复后重跑）。
- Jamendo `testAndroidHostTest`：**10 项 0 失败**。
- shared `desktopTest`：**388 项 0 失败**（既有 Provider 与默认搜索无退化）。
- Desktop App 测试（含真实播放 opt-in）：**1 项 0 失败**（修复后重跑，位置推进 30095ms）。
- Jamendo 真实网络测试（opt-in）：K0 已验 2 项；本阶段真实播放测试内含真实 search/resolve。
- iOS 主代码+测试源码+shared 编译 `--rerun-tasks`：**BUILD SUCCESSFUL**（`compileKotlinIosSimulatorArm64` 等）。
- Android 主代码编译 `compileDebugKotlin`：**BUILD SUCCESSFUL**。
- Desktop 构建：真实播放测试已完整编译并运行（main+test Kotlin 均过）；完整 native 打包未执行（无 cargo）。
- Android 完整 APK 构建：**NOT_RUN**（无 cargo/真机，Kotlin 编译层已过）。
- Android 真机播放 / iOS Simulator 测试：**NOT_RUN**。

## 11. 阶段结论（分层，不合并）

### A. 播放闭环验收
- Desktop 正式播放链路 >30 秒：PASS。
- 曲目 ID 与播放会话关联一致：PASS。
- pause/继续/stop/释放：PASS。
- 播放失败正确恢复：PASS（静态+单测，无真机 UI 自动化）。
- **Android 真机 >30 秒：NOT_RUN**。
→ 跨平台播放闭环**未全部通过**（缺 Android 真机）。

### B. 中国大陆网络验收
- 内网 Windows 直连完成搜索+正式播放：PASS（但非确认家庭宽带）。
- 家庭宽带直连 / Android 移动数据直连：**NOT_RUN**。
- 不依赖本机代理：PASS（本阶段链路全直连）。
- 重复数据与失败样本：有（2 轮 × 3 关键词，偶发空结果已记录）。
→ 目标网络验收**不完整**。

### 总判定：**K1 部分完成**
有确凿的 Desktop 正式播放器真实解码证据（>30 秒）与 ID 关联核验，但 Android 真机、家庭宽带、移动数据三大目标环境均 NOT_RUN。
按任务书分层标准，不能宣布"K1 完成"，也不能把 Jamendo 描述为"已验证的开箱即用播放来源"——
只能说：**Jamendo 在 Desktop 正式播放链路已真实解码验证通过，目标网络与真机覆盖待补**。

## 12. 下一步明确方向（不降低标准）
1. Android 真机走正式 App 路径连续播放 30 秒，验缓冲/焦点/耳机/前后台（关闭 K1.3 NOT_RUN）。
2. 在中国大陆家庭宽带、Android 移动数据（移动/联通/电信）分别重复搜索+播放（关闭 K1.4 NOT_RUN）。
3. 补声学指纹或参考音频元数据交叉验证（关闭 ACOUSTIC_IDENTITY NOT_RUN）。
4. 环境具备扬声器/耳机时补人工听音（关闭 HUMAN_LISTENING NOT_RUN）。
5. 连续多时段样本验证长期稳定（当前仅两次近时段样本）。

## 下载/K2 边界声明
- 本阶段**未开放 Jamendo 下载**；BY-NC-ND 作品不得改作铃声、混音或其他演绎用途；可播放 ≠ 可下载/可缓存再分发/可商用。
- **K2 独立建模结构化许可字段**（不与 K1 混）：`licenseType`、`licenseUrl`、`attribution`（署名文本/作者/要求链接）、
  `playLicense`、`downloadLicense`、`commercialUseAllowed`、`derivativesAllowed`、`redistributionAllowed`、
  `licenseVerifiedAt`/`licenseSource`。默认拒绝未知许可的下载；不能因 providerTags 中有 CC 标签就推断所有使用权已满足。
- **K2 完成前，Jamendo 继续只开放当前已验证的播放能力**，不开放下载。

提交：K1 分小提交（最小修复 / opt-in 真实播放测试 / 报告），不改写历史、不推送（除非用户授权）。
