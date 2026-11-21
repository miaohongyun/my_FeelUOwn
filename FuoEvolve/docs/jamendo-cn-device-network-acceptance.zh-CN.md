# 阶段 K1.1：Jamendo 中国大陆设备与网络最终验收

> 阶段定位：**只补证据，不扩功能**。K1 结论保持"部分完成"。
> 本阶段完成与否由**设备与网络证据**决定，不由代码提交数量决定。
> 核心原则：没有 Android 真机、家庭宽带或移动数据时，完成可完成部分并如实标记 NOT_RUN，不制造模拟测试替代真实环境。

测试执行时间：2026-11-21 10:26–10:30 GMT+8。
执行环境：Windows 桌面，内网 `192.168.6.12`，默认网关/DNS `192.168.6.1`，DNS 后缀 `dev.internal`（**公司内网，非家庭宽带**）。

---

## 1. Git 基线与提交链（K1.1.0）

| 项目 | 值 |
|---|---|
| 当前分支 | `work/android-community-discovery` |
| HEAD 完整 SHA | `ab349de2f936bde7e8ab105d3ea6f93720e94cb7`（K1 最终报告提交） |
| 父提交 | `f03569530a2aee94fd59146d432abb5b9a24542f` |
| K1 三提交在历史中 | `f819fcb`（resolve 修复）、`f035695`（真实播放测试）、`ab349de`（报告）均 `IN_HISTORY=YES` |
| 远端 work 分支 | `ab349de2f936bde7e8ab105d3ea6f93720e94cb7`（与本地 HEAD 一致，含 K1 基线） |
| master | `fc05530c09966dedacb413168a1dc6fda0d3afe1`（未动） |
| 工作区 | 干净（审计阶段无改动） |

远端包含 K1 基线，满足继续条件；未执行任何 reset/rebase/force push。本阶段基线审计未发现需修复项，因此**无代码改动、无新增代码提交**——符合"完成与否不由提交数量决定"。

## 2. K1.1.0 审计结论：resolve 三次重试实现

审计 `JamendoMusicProvider.kt:89-118`、`JamendoApiClient.kt:59-75`、`JamendoApiClient.kt:30/116-139`、`PlaybackStartCoordinator.kt:286/343/357`、`SearchFeature.kt:154-382`：

| 审计项 | 结论 |
|---|---|
| 重试上限 | PASS — `for (attempt in 1..TRACK_BY_ID_MAX_ATTEMPTS)`，`TRACK_BY_ID_MAX_ATTEMPTS=3`（`:97/:147`） |
| 退避 | PASS — `delay(TRACK_BY_ID_RETRY_DELAY_MS)`，`=300L`，仅非末次执行（`:106/:148`） |
| 每次超时 | PASS — `trackById` 内 `withTimeoutOrNull(timeoutMillis)`，`timeoutMillis=8_000L`（`:120/:30`） |
| 取消响应 | PASS — `catch (CancellationException) { throw e }` 立即传播；`delay` 可取消（`:100-101`） |
| 是否重复发起已取消请求 | PASS — 取消通过异常立即跳出循环，不会进入下一次 `trackById` |
| 是否重试永久错误 | 无有害重试 — `trackById` 将所有 HTTP/网络/解析失败统一退化为 `null`（`JamendoApiClient.kt:74-75` 注释明确，`:79-80` FetchResult.Failure→null），从不抛非取消异常；因此 resolve 的 `catch(Exception)` 实际不触发，重试对象是"null/空 audioUrl"（可重试的瞬时失败）。永久错误（如下架/合约变更）最终在 `:108` 返回 null，不产生错误结果、不无限重试 |
| 并发点击是否互相覆盖 | PASS（静态）— 播放侧 `activePlaybackTransaction()?.id != transaction.id` 丢弃旧事务（`:286/343/357`），搜索侧 `searchGeneration`+`searchJob?.cancel()`+generation 校验 latest-wins（`SearchFeature.kt:179-382`）。真机快速点击场景本身仍 NOT_RUN（见 §3） |
| Desktop K1 证据可复现 | PASS — `desktopApp/build/k1-evidence.txt`（2026-11-21 10:06）仍在，`FUOE_K1_REAL_PLAYBACK=1` 测试入口仍在（`JamendoRealPlaybackTest.kt:37/49`） |

**审计判定**：三次重试有上限、有退避、有超时、有取消，无无限重试、不产生错误结果。无需修复，未顺手重构无关模块。

## 3. K1.1.1 Android 真机正式播放验收 — **NOT_RUN**

| 设备要求 | 结果 |
|---|---|
| 至少一台真实 Android 手机/平板 | **无设备** — `adb devices` 输出 `List of devices attached`（空），daemon 已启动但无连接设备 |

无真机，以下所有场景均 **NOT_RUN**，且**未**用模拟器、Android Host Test、JVM 测试或 APK 构建替代：

- 冷启动 → 普通搜索页 → 真实关键词 → 选择曲目 → 正式 `resolve()` → 正式播放控制器 → ExoPlayer → 音频输出（完整链路）
- 连续播放 ≥60 秒 position 推进、暂停 ≥5 秒再继续、切到第二首、停止释放
- App 前后台切换、音频焦点变化、耳机拔插/蓝牙断连、网络断恢复、快速点击两曲防覆盖
- 至少三轮完整播放、至少两首不同曲目

**音频输出核验**：`AUDIO_OUTPUT_VERIFICATION = NOT_RUN`。无设备即无人工听音条件，不宣称 Android 真正播放完成。

**验证等级（Android）**：`REAL_SEARCH` / `REAL_RESULT_MAPPING` / `REAL_URL_RESOLVE` / `REAL_AUDIO_RESPONSE` / `REAL_PLAYBACK_DECODE` / `REAL_PLAYBACK_AUDIO_OUTPUT` / `REAL_TRACK_IDENTITY_VERIFIED` / `ACOUSTIC_IDENTITY_VERIFIED` 全部 **NOT_RUN**（Android 平台无真机证据）。

## 4. K1.1.2 中国大陆家庭宽带直连验收 — **NOT_RUN**

本机 IP `192.168.6.12`、网关/DNS `192.168.6.1`、DNS 后缀 `dev.internal`，为**公司内网**，任务书明确"不能用公司内网或云服务器代替家庭宽带"。无真实家庭宽带 Wi-Fi 环境。

以下全部 **NOT_RUN**：家庭宽带 DNS/TCP/TLS 分层、API 搜索、`trackById` 重取、音频 Range、正式 App 播放 ≥60 秒、暂停/继续/切歌、两个时间段 × 三曲目重复。

**网络等级**：`CN_HOME_BROADBAND_DIRECT = NOT_RUN`。

## 5. K1.1.3 中国大陆移动数据直连验收 — **NOT_RUN**

无 Android 真机、无中国大陆 SIM 卡，无法关闭 Wi-Fi 切换到移动数据，也无法确认运营商/网络制式/信号。

以下全部 **NOT_RUN**：中国移动/联通/电信（均无 SIM）、每运营商三关键词搜索、每运营商两曲目 ≥60 秒 ExoPlayer 播放、各层 DNS/TCP/TLS/API/URL/音频/播放器结果。

**网络等级**：`CN_ANDROID_MOBILE_DIRECT = NOT_RUN`。

## 6. K1.1.4 Jamendo 偶发空结果专项验证 — **已完成（本阶段唯一新增真实证据）**

### 6.1 方法

- 端点：`https://api.jamendo.com/v3.0/tracks/?client_id=<公开值>&format=json&limit=5&search=<kw>&audioformat=mp32`
- 关键词：`ambient`、`piano`、`love`，每个 **10 次**。
- 两种网络分别独立执行：**直连**（`ProxyHandler({})`，本机内网出口）、**代理对照**（`http://127.0.0.1:7897`，仅 `PROXY_TEST`，不计入直连通过率）。
- 每次记录：HTTP 状态、响应体字节、results 数量、耗时、首条 ID；分类：`OK` / `HTTP200_ZERO_RESULTS` / `SERVER_HTTP_ERROR` / `NETWORK_FAIL` / `PARSE_FAIL`。

### 6.2 直连结果（CN_DIRECT_INTRANET，非家庭宽带）

| 关键词 | OK/10 | 空结果/10 | 失败 | 最小/中位/最大耗时 | 空结果响应体 | 有结果首 ID |
|---|---|---|---|---|---|---|
| ambient | 6 | **4** | 0 | 0.878 / 1.040 / 1.304 s | 105 B | 1348698 |
| piano | 6 | **4** | 0 | 0.908 / 1.064 / 1.455 s | 105 B | 1369762 |
| love | 6 | **4** | 0 | 0.890 / 1.013 / 1.372 s | 105 B | 1504575 |

逐次（`#序号 http/字节/results/分类/耗时`）：

```
ambient  #01 200/105/0/ZERO/1.029  #02 200/18739/5/OK/1.149  #03 200/18735/5/OK/1.021  #04 200/18741/5/OK/1.304
         #05 200/18745/5/OK/1.075  #06 200/18731/5/OK/1.051  #07 200/105/0/ZERO/0.919  #08 200/105/0/ZERO/0.878
         #09 200/18737/5/OK/1.080  #10 200/105/0/ZERO/0.933
piano    #01 200/105/0/ZERO/0.934  #02 200/105/0/ZERO/0.908  #03 200/18705/5/OK/1.095  #04 200/105/0/ZERO/0.913
         #05 200/18697/5/OK/1.455  #06 200/105/0/ZERO/0.935  #07 200/18701/5/OK/1.072  #08 200/18699/5/OK/1.071
         #09 200/18707/5/OK/1.154  #10 200/18695/5/OK/1.056
love     #01 200/105/0/ZERO/0.898  #02 200/105/0/ZERO/0.929  #03 200/105/0/ZERO/0.890  #04 200/105/0/ZERO/1.161
         #05 200/18809/5/OK/1.341  #06 200/18799/5/OK/1.372  #07 200/18815/5/OK/1.009  #08 200/18803/5/OK/1.058
         #09 200/18805/5/OK/1.009  #10 200/18817/5/OK/1.017
```

### 6.3 代理对照（PROXY_TEST_7897，不计入直连）

| 关键词 | OK/10 | 空结果/10 | 失败 | 中位耗时 |
|---|---|---|---|---|
| ambient | 7 | 3 | 0 | 1.586 s |
| piano | 7 | 3 | 0 | 1.580 s |
| love | 8 | 2 | 0 | 1.656 s |

### 6.4 结论与风险

1. **空结果并非偶发，而是高频波动**：直连三关键词各 **40%（4/10）** 返回 HTTP 200 但 `results=0`（响应体固定 105B）；代理 20–30%。这是 Jamendo 公开 API 的服务端行为，本地网络与代理均复现，**不是客户端 bug、不是 DNS/TLS 问题**（60 次请求全部 HTTP 200，零网络失败、零 4xx/5xx、零解析失败）。
2. **可可靠区分**：空结果=`200+105B+results=0`；与网络失败（`000`+异常）、服务端错误（4xx/5xx）、解析失败形态完全不同。
3. **对重试策略的影响**：单次空结果概率约 0.4，三次重试独立假设下全空概率约 `0.4³≈6.4%`（实际可能因后端状态相关而更高）。即"点播放"仍有非零概率拿不到结果。K1 的三次重试是必要的最小编解，但**不能把 40% 波动说成已解决**。
4. **重试不会掩盖合法空结果**：由 §2 审计，重试上限固定 3 次、有退避/超时/取消，最终仍返回 null 并显示"没有搜索结果"（`SearchFeature` 三态区分，K0/K1 已核），不残留旧结果、不无限重试。
5. **不作弊声明**：未伪造结果、未用旧缓存冒充本次搜索、未因波动放宽 Gateway 或改网络策略。

## 7. K1.1.5 Desktop 播放证据补充 — 保留 K1 原始证据

- 本环境**无音频设备**（无扬声器/耳机输出条件）。保留 K1 原始 `ao=null` 无声解码证据（`desktopApp/build/k1-evidence.txt`），**未修改**该证据制造音频输出成功。
- `REAL_PLAYBACK_DECODE = PASS`（K1 已证：position 推进 30060ms / 30095ms，status=Playing，真实 libmpv 解码）。
- `REAL_PLAYBACK_AUDIO_OUTPUT = NOT_RUN`（无音频设备、无人工听音）。
- `ACOUSTIC_IDENTITY_VERIFIED = NOT_RUN`（无声学指纹）。

## 8. 统一验证等级汇总（不得合并）

### 8.1 能力等级

| 等级 | Desktop | Android 真机 | 说明 |
|---|---|---|---|
| REAL_SEARCH | PASS（K1 直连） | NOT_RUN | 真实 API 结果 |
| REAL_RESULT_MAPPING | PASS（K1） | NOT_RUN | 标题/作者/ID 映射 |
| REAL_URL_RESOLVE | PASS（K1） | NOT_RUN | trackById 重取时效 URL |
| REAL_AUDIO_RESPONSE | PASS（K1，206 audio/mpeg） | NOT_RUN | 音频响应 |
| REAL_PLAYBACK_DECODE | PASS（K1，ao=null 解码 >30s） | NOT_RUN | 解码与 position 推进 |
| REAL_PLAYBACK_AUDIO_OUTPUT | NOT_RUN（无音频设备） | NOT_RUN（无真机） | 需人工听音 |
| REAL_TRACK_IDENTITY_VERIFIED | PASS（ID 关联级） | NOT_RUN | 非声学级 |
| ACOUSTIC_IDENTITY_VERIFIED | NOT_RUN | NOT_RUN | 无声学指纹 |

### 8.2 网络环境等级

| 环境 | 状态 |
|---|---|
| CN_HOME_BROADBAND_DIRECT | **NOT_RUN**（公司内网，非家庭宽带） |
| CN_ANDROID_WIFI_DIRECT | **NOT_RUN**（无真机） |
| CN_ANDROID_MOBILE_DIRECT | **NOT_RUN**（无真机/SIM） |
| PROXY_TEST（7897，仅对照） | 已执行：空结果 2–3/10/关键词；**不计入直连通过率** |

## 9. 失败原因与遗留风险

- **A 类（Android 真机播放）**：NOT_RUN，原因=无 `adb` 连接设备。无设备即无完整链路、无音频输出核验。
- **B 类（家庭宽带）**：NOT_RUN，原因=仅公司内网 `192.168.6.x`，任务书禁止以内网代替家庭宽带。
- **C 类（移动数据）**：NOT_RUN，原因=无真机、无 SIM。
- **稳定性新发现**：Jamendo 搜索空结果率约 **40%（直连）/20–30%（代理）**，为服务端波动；三次重试后仍有约 6.4% 全空概率。这是当前最影响"点开就能听"的现实风险。
- **未覆盖**：家庭宽带/移动数据的 DNS 污染与 SNI 情况未测（内网出口不代表运营商出口）；真机 ExoPlayer 焦点/耳机/前后台行为未测；声学身份核验未做。
- **未降低标准**：未关闭 TLS 校验、未写死 IP、未强制代理、未放宽 Gateway/SSRF、未用备用 URL 冒充、未用 Host Test/编译冒充真机、未把代理计入直连。

## 10. 最终产品判定与阶段结论

任务书的判定问题：**"一个没有开发经验的中国大陆家庭成员，安装正式 App 后，在家里的 Wi-Fi 或手机移动网络下，是否真的能搜到 Jamendo 曲目并从手机扬声器或耳机听到音乐？"**

本阶段**无法回答"是"**——因为没有一台真机、没有一条家庭宽带、没有一张 SIM，也就没有"从手机扬声器听到音乐"的任何证据。手上的证据只到：内网直连下桌面 libmpv 能真实解码 30 秒（无声），以及搜索存在 40% 空结果波动。

**阶段结论：K1.1 未完成（A/B/C 全部 NOT_RUN，仅 D 稳定性部分完成）。**

- A Android 真机播放：NOT_RUN
- B 中国大陆家庭宽带：NOT_RUN
- C 中国大陆移动数据：NOT_RUN
- D 稳定性：部分完成（空结果已量化、重试机制审计通过；真机取消/空结果展示场景 NOT_RUN）

按标准，只有 A/B/C 全部通过才能宣布"Jamendo 已完成中国大陆目标环境的开箱即用播放验收"。本阶段**不宣布完成**，也不降低标准。下一步需要的是真实设备与网络条件，而不是更多代码提交或模拟测试。

## 11. 本阶段改动清单

- 新增本文档 `docs/jamendo-cn-device-network-acceptance.zh-CN.md`；更新 `docs/README.md` 索引、`docs/personal-music-roadmap.zh-CN.md` 阶段记录。
- **无代码改动**：K1.1.0 审计未发现需修复项（重试实现合规），遵循"发现需修复才单独提交、不顺手重构"。
- 未修改 master、未改写历史。
