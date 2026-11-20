# LX Runtime action 协议与搜索调用链审计（阶段 G0）

> 日期：2026-11-20　基线：`3432eb6`（阶段 F 最终提交）　分支：`work/android-community-discovery`
> 性质：**协议审计**。不改协议、不伪造搜索结果、不执行任何 BLOCK/QUARANTINE 脚本。
> 核心问题：LX Runtime 的 search 是"已实现只是候选没写"，还是"调用链有兼容性缺口"？

## 0. 结论先行（回答第三部分的核心问题）

**两者都不是。真相是：LX 自定义音源协议（移动端）本身就没有 `search` action——搜索从设计上就不是脚本事件，
而是宿主客户端按平台做的（宿主侧适配器 `LxSongSearcher`）。**

- 协议常量只定义了 `musicUrl` / `lyric` / `pic`（`LxModels.kt:24-26`），**没有 `ACTION_SEARCH`**。
- 产品运行时只有 `requestMusicUrl(...)` 一个派发入口（`LxScriptRuntime.kt:239`），
  没有 `requestSearch` / `requestLyric` / `requestPic`。
- `initialize()` 只保留 `actions` 含 `musicUrl` 的源（`LxScriptRuntime.kt:218`）——只声明 `search` 的源会被直接丢弃。
- Provider 的 `search()` 走 `LxSongSearchRegistry`（宿主侧公开搜索接口 kw/kg），
  **完全不调用脚本**（`LxScriptProvider.kt:117-149`）；脚本只在 `resolve()` 阶段用 `musicUrl` 把 songId 换成 URL（`:151-187`）。
- JS 桥 `__lxDispatch` 本身是 **action 无关**的（`LxBridge.kt:182-190`）：它把任意 `{action,source,info}` 交给脚本的
  `request` 处理器。G0 用已知可工作脚本实测证明：桥能把 `search` 派发下去并拿回结构化结果。

**因此**："继续找实现 LX search 的音源"这个方向在当前架构里找不到落点——不是候选偷懒没实现，
而是协议里没有这个 action、产品也没有调用链与结果模型。若要做"脚本 search"，这是**新协议 + 新调用链 + 新结果模型**
的工程（G2 适配层范畴），不是换一批音源就能解决的。**继续批量收集音源只会重复撞同一堵墙。**

## 1. 交付物一：LX Runtime 当前支持的全部 action 清单

| 层级 | 名称 | 协议常量 | 产品派发 API | 实际状态 |
| --- | --- | --- | --- | --- |
| 事件 | `inited` | `EVENT_INITED`（:18） | 由脚本 `send`，`initialize()` 读取（:191） | 支持；必须含 `sources` |
| 事件 | `request` | `EVENT_REQUEST`（:19） | `lx.on(EVENT_NAMES.request, handler)` 注册（LxBridge:139-141） | 支持；宿主经 `__lxDispatch` 调用 |
| 事件 | `updateAlert` | `EVENT_UPDATE_ALERT`（:20） | 脚本 `send`，宿主 `__lxUpdateAlert` 读取 | 支持；仅更新提醒 |
| action | `musicUrl` | `ACTION_MUSIC_URL`（:24） | `requestMusicUrl(...)`（:239） | **唯一被产品调用的 action** |
| action | `lyric` | `ACTION_LYRIC`（:25） | 无 | 常量存在但产品无派发入口；仅 `local` 源声明中出现 |
| action | `pic` | `ACTION_PIC`（:26） | 无 | 同上 |
| action | `search` | **不存在** | 无 | 不在协议内；社区脚本极少数声明（如 feichangdao，QUARANTINE），产品从不派发 |

平台键：`kw/kg/tx/wy/mg/local`（:22）。音质：`128k/320k/flac/flac24bit`（:28）。

## 2. 交付物二：每个 action 的参数、返回值和错误处理约定

### 2.1 `musicUrl`（唯一产品 action）

- **入参**（`LxScriptRuntime.kt:249-259`）：`{ source: <平台>, action: "musicUrl",
  info: { type: <音质>, musicInfo: { songmid/hash/strMediaMid/name/singer/albumName… } } }`。
  `musicInfo` 是宿主按平台拼的**字段超集**（`LxScriptProvider.kt:321-343`），原始字段原样透传，避免错歌。
- **返回值**：脚本 request 回调返回 Promise，解析为 URL 字符串；宿主只接受 `http://`/`https://`
  开头的字符串或 `{url}` 对象（`extractUrl`，:371-396）；拿不到 URL 抛 `LxScriptException`（:273-274）。
- **错误**：`__lxDispatchError` 非空即抛 `LxScriptException`（:268-271）；超时抛 `LxScriptTimeoutException`；
  网络/SSRF 拒绝抛 `LxScriptException`。上层 `resolve()` 捕获异常后 `null` → 换下一个脚本（:166-185）。
- **音质**：`LxQuality.candidates(policy, declared)` 取"用户策略 ∩ 脚本声明"，默认不尝试 `flac24bit`（:369-381）。

### 2.2 `lyric` / `pic`

- 协议有常量（`LxModels.kt:25-26`），但**运行时无 `requestLyric` / `requestPic` 方法**，
  宿主不派发、不解析返回结构。`initialize()` 仍会把声明了 `lyric/pic` 的 `music` 源收录（只要含 `musicUrl`），
  但这两个 action 在产品里等同"声明了也不会被调用"。
- 一些脚本在 `local` 源声明 `['musicUrl','pic','lyric']`（如 zhizun:242），而 `local` 被 `initialize()`
  显式排除（:191-201，注释"本地曲库自己做，不接入"），因此连 `musicUrl` 都不会在本地源上被调。

### 2.3 `search`（协议外）

- 协议无常量、无 API、无结果模型。社区脚本极少数声明（feichangdao.js 的 `actions:['musicUrl','search']`，
  内部 `case 'search': return handleSearch(...)`，但该脚本处于 QUARANTINE，**未执行**）。
- G0 审计探针（`dispatchForAudit`，内部方法 :289）证明：桥层可派发 `search` 并拿回 `{songs:[{songId,title,artist,album}]}`；
  但这只是"桥能做"，产品代码路径里没有任何地方调用它——`LxProtocol` 没有 search 常量，`initialize` 不认 search-only 源。

## 3. 交付物三：搜索调用从 UI 到脚本的完整调用链

```
用户搜索框
  → SearchFeature.searchText() / dispatch(SearchAction)           feature/search/.../SearchFeature.kt:100,140
  → providerRepository.searchAll(keyword, providerId)              :65 接口 / :323 调用
  → KotlinProviderRepository.searchAll()                           shared/.../KotlinProviderRepository.kt:109
  → 各 Provider.search(keyword)，其中 lxscript:
       LxScriptMusicProvider.search()                               LxScriptProvider.kt:117-149
         ├─ enabledScripts() 取已批准脚本                            :235
         ├─ 对每个脚本 grantedPlatforms()（目录批准 ∩ 脚本声明）       LxScriptProvider...:43-45
         ├─ LxSongSearchRegistry.canSearch(platform)                :130  ← 未注册平台跳过（不可搜索）
         ├─ activeRegistry.search(query, platform)                   :131  ← 宿主侧适配器，不是脚本！
         │    → KuwoSongSearcher / KugouSongSearcher 等公开搜索接口   LxSongSearch.kt / LxPlatformSearchers.kt
         │    → LxSongHit(songId,title,artist,album,rawFields)       LxSongSearch.kt:26-40
         ├─ rememberRawFields(identifier, rawFields)                 :136
         └─ 产出 MusicTrack(identifier="平台:songId", tag=lxscript)   :135-144
  → 用户点播放 → Provider.resolve(track, qualityPolicy)             :151
       → musicInfoFor() 拼超集字段（含 rawFields）                   :321-343
       → LxScriptRuntime.requestMusicUrl(platform, musicInfo, q)     :167 → :239
       → __lxDispatch({action:"musicUrl",...})                       LxBridge.kt:182-190
       → 脚本 request 处理器 → lx.request(...) → 宿主 __lxHttp        LxBridge.kt:146-165
       → 返回 URL → PlaybackPayload                                  :174-182
```

**关键断点**：搜索段（:131）走的是宿主适配器，**没有任何一步调用脚本的 `request` 处理器**；
脚本第一次也是唯一一次被调用是在播放解析段（:167），且 action 固定为 `musicUrl`。
所以"脚本声明了 search"与"产品能 search"之间没有连线。

## 4. 交付物四：已知可工作的测试脚本与实测结论

测试：`provider/lxruntime/src/commonTest/.../LxActionProtocolAuditTest.kt`，夹具脚本 `searchCapableScript`
自写、返回确定性假数据（**明确是测试夹具，不是真实音源，也不是伪造的真实搜索结果**）。5 项断言：

1. `g0_protocol_has_no_search_action`：协议只有 3 个 action 常量，无 search。
2. `g0_declared_and_loaded_search_capable_script`：脚本声明 `['musicUrl','search']`，load+initialize 成功保留（DECLARED+LOADED）。
3. `g0_bridge_can_dispatch_search_but_product_cannot_reach_it`：审计探针把 `search` payload 派发到脚本，
   拿回 `{songs:[2条]}` 且 title/songId 正确（**桥层 REAL_CALL_PASSED**）；对照 `requestMusicUrl` 只能发 musicUrl。
4. `g0_initialize_rejects_search_only_source`：只声明 `search` 的源被 `initialize()` 拒绝（产品不认 search-only）。
5. `g0_provider_search_uses_registry_not_scripts`：默认注册表只有 kw+kg，未注册平台返回空且不碰脚本。

**实测结论**：桥接层**兼容** search 派发（协议 gap 不在桥）；缺口在"协议没定义 + 产品没有 search 调用链/结果模型/注册入口"。

## 5. 交付物五：候选脚本能力矩阵（DECLARED / LOADED / REAL_CALL_PASSED）

来源：E3 脚本快照 `inited.actions` 原文 + E3 LX 模拟加载结果 + 阶段 F 真实探测台账。
|BLOCK/QUARANTINE|候选|DECLARED|LOADED（E3）|REAL_CALL_PASSED（F0 真实探测）|说明|
|---|---|---|---|---|---|
| — | gdstudio | `['musicUrl']` | ✅ SIMULATED_INIT | URL解析=PASS(200)/音频响应=PASS(206)；**search 未声明也未通过** | 上游 API 有 search 但不是 LX action |
| — | HUIBQ | `['musicUrl']` | ✅ SIMULATED_INIT | URL解析=FAIL(503 服务暂停) | 无 search |
| — | 至尊 zhizun | `['musicUrl']`（local 另有 `pic/lyric`） | ✅ SIMULATED_INIT | URL解析=FAIL(502, deny-all) | 无 search；非标端点 |
| — | ikun-v515 | `['musicUrl']` | ❌ 空 allowedHosts 拒 checkUpdate | 真实 TLS 握手失败 | 无 search |
| — | juhe | `['musicUrl']`（init 来自服务器） | ❌ 初始化需 init.conf 被拒 | 动态 303 端点不可信 | 无 search |
| — | animemusic | `['musicUrl']` | ❌ checkUpdate 被拒 | 接口 code 500 | 无 search |
| QUARANTINE | Flower | 混淆不可静态判定 | ❌ AggregateError | 未执行 | 裸 IP 初始化 |
| QUARANTINE | feichangdao | 公开快照 `['musicUrl','search','lyric']` | ❌ 未加载（隔离） | 未执行 | **唯一声明 search 的样本，但处于隔离，禁止执行** |
| BLOCK | 长青/六音/墨澜/星澜/元力菌 | 会员绕过/混淆/越权 | ❌ 未加载 | 未执行 | 保持阻断 |

矩阵结论：**声明了 search 的候选恰是 QUARANTINE 的 feichangdao（不可执行）；所有允许进入加载的候选都只声明 musicUrl。**
"DECLARED search"与"可加载/可真实验证"两个集合不相交——这是 G1 选源时必须面对的结构性事实。

## 6. G0 完成标准对照与对 G1 的约束

| 验收项 | 状态 |
| --- | --- |
| 明确完整 action 定义及调用链 | ✅ 第 1-3 节，含文件:行号 |
| 测试脚本能验证真实 search 调用 | ✅ 审计探针证明桥可派发 search；同时证明产品无入口（不伪造真实结果） |
| 来源/版本/SHA/许可可追溯 | ✅ 继承阶段 E/F 台账，本阶段未新增来源 |
| 声明能力与真实能力分开 | ✅ 第 5 节矩阵三态分列 |
| 适配层不污染核心协议 | ✅ 仅新增 internal 审计探针，未改 `LxProtocol`、未加 search 常量 |
| 网络安全候选级授权 | ✅ 复用阶段 F `CandidateNetworkPolicy`，本阶段无网络放宽 |
| 搜索/解析/音频/播放分验 | ✅ 继承阶段 F 分栏台账 |
| 正式接入人工审核 | ✅ 无自动批准/安装 |
| 只提交工作分支不碰 master | ✅ 基线 `3432eb6`，分支 `work/android-community-discovery` |

**对 G1 的明确约束**：寻找"真正实现 LX search"的合规音源，前提是**先承认当前 LX 移动协议没有 search action**。
G1 应改为寻找"有合规公开搜索接口 + 有 `musicUrl` 解析脚本"的组合，或明确启动 G2 适配层去定义脚本 search
协议/调用链/结果模型。**不要**把声明 `search` 的 QUARANTINE 脚本（feichangdao）当突破口，
也不要把 MusicFree 插件直接当 LX 音源。若维持现有协议，则"脚本 search"在 G0 证据下**不存在可达成路径**，
应如实记入 G1 结论，而不是继续抓脚本。

## 7. 测试与提交

- `./gradlew :provider:lxruntime:desktopTest --rerun-tasks`：全绿 98 项（含 5 项 G0 审计测试）。
- 未修改 `LxProtocol` 常量、未新增产品 search API、未执行任何 BLOCK/QUARANTINE 脚本、未放宽 SSRF、未碰 master。
