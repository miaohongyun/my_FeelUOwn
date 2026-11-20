# 社区音源扩展审查报告（阶段 E0–E2）

**日期**：2026-11-20 ｜ **基线**：`work/android-community-discovery` @ `46806dd`（父 `36804ea`）
**范围**：E0 盘点与登记、E1 来源获取与 SHA-256 校验、E2 静态审查与专项分析。
**未做**（属 E3–E5）：真实搜索/播放验证、聚合后端逐个验证、MusicFree→LX 适配实现、管理 UI 增强。

---

## 0. 安全原则与诚信声明

- **发现自动化、审核半自动化、安装受控自动化**；“不因家庭使用取消基本安全边界”。
- 本报告所有 SHA-256 均为**实际下载文件**的本地计算值；未获取的文件一律标 `UNVERIFIABLE` 且**不填 SHA、不填下载链接**。
- 静态审查只做正则取证，**不代表“扫描通过=安全”**；不执行任何脚本、不做真实音频下载、不绕会员/付费墙。
- 长青保持 BLOCK，**未关闭 `membership-bypass` 规则**；Fish/Flower 保持隔离；非常刀/玉宁熙仅到静态与历史架构模拟级。
- 未修改 master、正式音源目录、公钥、签名；无自动批准或安装。

---

## 1. E0 基线与 ZIP 核对

### 1.1 Git 基线（任务书指定命令均已执行）

| 检查项 | 实际值 |
|---|---|
| 分支 | `work/android-community-discovery` |
| HEAD | `46806ddbe9642766802b04cbab42017fe43825ae` ✓ 与基线一致 |
| 工作区 | 干净 |
| remote | `origin = https://github.com/miaohongyun/my_FeelUOwn.git` |

### 1.2 `FuoEvolve-phase9.zip` 核对结论

ZIP 位于 `/e/谷歌下载/FuoEvolve-phase9.zip`，2.1 MB / 1398 文件，文件日期 **2026-11-16/17**（早于阶段 C/D/E）。

| 核对项 | ZIP 中 | 当前 Git 分支 |
|---|---|---|
| `provider/sourceupdate` 模块 | **无** | 有 |
| `CommunityDiscoveryRepository` | **无** | 有 |
| `CommunityScriptDownloader` | **无** | 有 |
| `CommunityCatalogDiscovery/Models` | **无** | 有 |
| `ignoredIds` 墓碑机制 | **无** | 有（D.1） |
| `LxUrlSafety` / `QUARANTINE_PREFIX` | **无** | 有 |

**处置**：ZIP 是旧快照，与 `46806dd` 基线不一致。按任务书规则，**未用 ZIP 覆盖工作分支、未把旧文件复制回新分支**，以 Git 当前分支为唯一开发基线，ZIP 仅作辅助参考。当前 Git 确实含 D.1 实现，故继续开发。

---

## 2. 全量候选登记表（31 条）

统一台账代码：`provider/sourceupdate/.../CommunitySourceRegistry.kt`。每个候选有唯一 `candidateId`，来源类型、SHA、状态、证据齐备。下表为摘要（完整 SHA 见代码与取证 JSON）。

### 2.1 A. 此前五个候选（本阶段重新取证）

| ID | 名称 | 版本 | 字节 | SHA-256(前16) | 静态结论 | 状态 |
|---|---|---|---|---|---|---|
| changqing | 长青SVIP音源(二改修复版) | v1.2.0 | 44876 | `48d5fed8b46344e9` | membership-bypass + 2806 混淆 | **BLOCK** |
| feichangdao | 非常刀 | — | 17846 | `49486cab064e64d5` | 正则干净，裸IP/t.me/12域名 | QUARANTINED |
| yuningxi | 玉宁熙 Pro | — | 144621 | `5dd4752c16c17f7b` | 域名拼接不可见 | QUARANTINED |
| fish-music | Fish | 1.9.0 | 639619 | `a711e152c4f28c32` | 47587 混淆 | **OBFUSCATED** |
| flower-pdone | 野花 Flower | 1 | 10057 | `15c166987edde41c` | 域名静态不可见 | QUARANTINED |

**交叉验证**：非常刀 SHA `49486cab…64284d7`、玉宁熙 `5dd4752c…d275f79` 与历史记录完全一致（文件未变），历史取证可信。

### 2.2 B. 新增独立音源

| ID | 名称 | 版本 | 字节 | SHA-256(前16) | 状态 | 备注 |
|---|---|---|---|---|---|---|
| xinghai | 星海音乐源 | — | 22466 | `1f1aa4759c63496b` | **BLOCK** | card-key（卡密访问认证音源） |
| sixyin | 六音 SixYin | — | 333015 | `a407ffe0ab23c29c` | **BLOCK** | new Function + 16316 混淆 |
| yecao | 野草 | 1 | 9176 | `d694275f382427d3` | QUARANTINED | 与 grass-pdone 同 SHA（重复） |
| nianxin | 念心音源 | 1.0.2 | 26672 | `86f6c05f8fef7169` | **BLOCK** | 1241 混淆 |
| qdy | 全豆要聚合 | 9.3 | 31090 | `213f73232562b528` | **BLOCK** | membership-bypass |
| huibq | HUIBQ | — | 2727 | `a9adeb456690b523` | FETCHED | pdone 官方；与 shellsec/huibi 不同文件 |
| tongyi | 统一音乐源 | — | — | — | **UNVERIFIABLE** | 未定位可验证来源 |
| molan | 墨澜聚合 | 2.3.6 | 583536 | `b6da06bf9a1730c4` | **BLOCK** | 38839 混淆 |
| yiyin | 忆音音源 | — | — | — | **UNVERIFIABLE** | 仅第三方博客提及 |
| huanyin | 幻音 | — | — | — | **UNVERIFIABLE** | 同名 App 众多，身份不明 |
| kulao | 裤佬SVIP | — | 16108 | `58e8cb1403a0eb39` | **BLOCK** | SVIP / membership-bypass |
| dujia-v7 | 独家音源 V7 | 7 | 64094 | `79a5c4703e060eaf` | **BLOCK** | membership-bypass；与 lx-pdone 同文件 |
| zhizun | 至尊源 | — | 10089 | `ddc095aca3dc4df5` | FETCHED | xzh767 仓库，静态干净 |

### 2.3 C. 系列与功能型

| ID | 名称 | 状态 | 备注 |
|---|---|---|---|
| ikun-v515 | ikun 音源 | FETCHED | pdone 官方 4991B；公益版/其他版本未定位，未混登 |
| mygo | MyGO | UNVERIFIABLE | “全平台母带”为宣传，不写入产品能力 |
| lingchuan | 聆川 | UNVERIFIABLE | 无可验证来源 |
| miyin | 弥音 | UNVERIFIABLE | 无可验证来源 |
| chunriying | 春日影 | UNVERIFIABLE | 无可验证来源 |
| lanyin | 澜音 | DISCOVERED | MusicFree 订阅站插件形态，需展开 |
| xiaoxiongmao | 小熊猫 | QUARANTINED | 历史发现远程加载第二段 JS；Token 配置型 |
| feicai-gongshe | 废材公社 | QUARANTINED | xzh767 内部源 18463B；Token 配置型 |

### 2.4 D. MusicFree 插件生态

| ID | 名称 | 状态 | 备注 |
|---|---|---|---|
| musicfree-z-series | Z 系列插件合集(称14) | UNVERIFIABLE | 未找到官方清单，不伪造插件名/SHA |
| yuanli-decompiled | 元力菌 SVIP 反编译版 | **BLOCK** | 依赖 13413.kstore.vip（长青同域）+ 反编译 + SVIP |
| yunyun-music | 云云音乐 | UNVERIFIABLE | 无可验证 LX 来源 |
| musicfree-subscription-hebijunge | MusicFree 订阅站(57) | DISCOVERED | 插件为 MusicFree API，需适配层 |

### 2.5 E. 聚合/全能型

| ID | 名称 | 字节 | SHA-256(前16) | 状态 | 备注 |
|---|---|---|---|---|---|
| stellarwave | 星澜 StellarWave | 74386 | `d186346cec8f813d` | **BLOCK** | 87 后端声明 + card-key（含具体卡密串）+ 合并墨澜框架 |

---

## 3. E1 来源获取与文件校验方法

- **主来源**：联网下载 `shellsec/lx-music-source` 的 `sources.json`（5640B，24 条真实脚本 URL），逐条通过代理下载脚本正文。
- **补充来源**：`pdone/lx-music-source`（flower/sixyin/huibq/lx/ikun/grass/juhe latest.js）、`xzh767/lxmusic-source-all`（至尊/废材公社）。
- **来源发现**：Web 检索核实仓库与发布页（星澜、MusicFree 订阅站、元力菌等），只用原作者仓库/官方订阅地址/有版本记录的来源，不采信搜索摘要当脚本内容。
- **校验**：对每个实际文件本地计算 SHA-256、字节数、`@name/@version` 注解、静态域名与混淆标记数；同名不同版本分别登记（如长青历史 `6a94298…` 与本轮 `48d5fed8…` 二改版）。
- **去重**：按 SHA-256 分组，`grass` 与 `grass-pdone`（`d694275f…`）、`lx` 与 `lx-pdone`（`79a5c470…`）为同一文件。
- **未执行**：所有脚本仅静态读取，**未在 LX Runtime 执行、未安装、未下载任何商业音频**。

取证数据：`provider/sourceupdate` 测试资源外的离线目录（不入库）；台账固化在 `CommunitySourceRegistry.kt`。

---

## 4. E2 静态审查与专项分析

### 4.1 审查判据（沿用 SourceStaticPolicy，未放宽）

BLOCK 规则：`eval`/`new Function`/动态 import、Node 宿主能力（require/fs/process/child_process）、WebSocket 隧道、卡密、会员绕过（SVIP/解锁会员）、`_0x` 混淆≥20。WARN：浏览器 cookie/storage、XMLHttpRequest、无限循环。所有规则**保持原样**。

### 4.2 统计（33 份实际脚本）

| 静态结论 | 数量 | 代表 |
|---|---|---|
| BLOCK | 15 | 长青、六音、墨澜、Fish、念心、yuxi、 stellarwave 等 |
| REVIEW | 8 | 非常刀取干净但域名待审、玉宁熙、appx64 等 |
| CLEAN（正则层） | 10 | flower、grass、gd-studio、juhe、feichangdao 等 |

> 注意：“CLEAN”仅表示现有正则未命中，不等于安全——如 flower/grass 的真实域名需运行时才暴露。

### 4.3 长青专项（重点对象）

- **文件**：`changqing.js`，v1.2.0，`48d5fed8…`，44876B。
- **问题代码定位**：`@name 长青SVIP音源(二改修复版)`（第 2 行）；`@mail 微信公众号: 元力菌`（元力菌为已知 SVIP 反编译来源）；`@update_url https://13413.kstore.vip/lxmusic/changqing.json`（与历史 BLOCK 域名一致）；全文 **2806 处 `_0x` 控制流平坦化混淆**。
- **可拆分性分析**：会员解锁卖点即 BLOCK 根因；混淆后无法逐行分离出“独立合规搜索/歌曲信息功能”，无法可靠拆分。
- **结论**：**保持 BLOCK**，历史 `membership-bypass` 证据保留，不关闭规则、不把 SVIP 包装成普通音质增强。本轮文件 SHA 与历史 `6a94298…` 不同（二改修复版），作为同名不同版本分别登记。

### 4.4 Fish / Flower 混淆分析

- **Fish v1.9.0**：`a711e152…`，639619B，**47587 处 `_0x`**。无法静态审计网络与凭据行为；历史域名 `flower.tempmusics.tk` 靠执行才暴露。保持 OBFUSCATED，不执行、不安装。
- **Flower/野花**：`15c16698…`，10057B，域名静态不可见（字符串拆分）。保持 QUARANTINED，需运行时受控逆向域名。

### 4.5 Token 类专项（小熊猫、废材公社）

- 仅检查 Token 接口与配置机制，**未索取或使用任何真实凭据**。
- 设计原则（写入台账）：不硬编码/不上传清单服务器/不日志输出完整 Token/不在 AI 对话索取/仅用本人有权凭据；若用于绕过付费或访问控制则不接入。
- 小熊猫历史发现**远程加载第二段 JS**（QUARANTINE_API_REVIEW），保持隔离。

### 4.6 `card-key`/`membership-bypass` 命中人工复核

部分命中为**配置说明**而非内嵌密钥（如 xinghai“卡密可选”、doubao“公益版免卡密”），但 stellarwave 注释确实出现具体卡密串 `6C1F-53W0-GRKI-EVFG`。台账区分“规则命中”与“人工确认风险”：凡涉及卡密解锁“仅认证用户音源”或 SVIP，一律保持 BLOCK 待确认，不因是注释就放行。

### 4.7 通过/阻断/无法验证名单

- **静态审查通过（可进入 E3 架构验证）**：无——“CLEAN”者均因域名待审或混淆历史保持 QUARANTINE，未直接放行。
- **阻断（BLOCK/OBFUSCATED）**：changqing、xinghai、sixyin、nianxin、qdy、molan、kulao、dujia-v7、yuanli-decompiled、stellarwave、fish-music 等。
- **隔离（QUARANTINED）**：feichangdao、yuningxi、flower-pdone、yecao、xiaoxiongmao、feicai-gongshe。
- **无法验证（UNVERIFIABLE）**：tongyi、yiyin、huanyin、mygo、lingchuan、miyin、chunriying、musicfree-z-series、yunyun-music。

---

## 5. 聚合与订阅生态拆解（E4 前置登记）

### 5.1 星澜 StellarWave

- 仓库 `lick563/Xinglan-aggregate-sound-sources`，发布页 v3.1.1.1 声明 **87 后端**：QQ 26 / 网易 19 / 酷我 15 / 酷狗 15 / 咪咕 12；v4.0.0 合并墨澜框架；并发 Fallback + LRU 缓存(TTL6h/300)、FFAPI 通用后端、`@tx_cookie/@wy_cookie` 配置。
- 已登记具体后端示例：`FFAPI`/`QQ越权`/`ygking`/`残像`/`星海酷我`/`yunmge`/`星海酷狗`/`念心`/`yuafeng`(需 apikey)/`星海咪咕`。
- **Fallback/超时**：发布页声明有 LRU 与超时控制，但“99.9%+ 成功率”“最大尝试次数”“是否循环回退/重试风暴”**无复核数据**，仅记录为项目宣传，未写成已验证。本阶段**未逐个验证后端**。

### 5.2 MusicFree 生态

- `hebijunge/musicfree-plugins`：订阅站称 57 插件，分类订阅 + 多线路，站点自述 sha256 逐条核对（其自身声明，未经本仓复核）。
- `Z 系列(14)`、`元力菌反编译`、`云云音乐`：无可靠原始清单或依赖 BLOCK 域名。
- **协议差异**：MusicFree 插件 API ≠ FuoEvolve LX API；适配需格式转换/API 适配/QuickJS 兼容/依赖与权限评估。**不允许一键批量安装未审插件**，不为兼容而改 LX 安全策略。

---

## 6. 缺失来源清单（无法验证，待用户/后续提供）

统一音乐源、忆音、幻音、MyGO、聆川、弥音、春日影、Z 系列 14 插件清单、云云音乐——未定位到原作者仓库/官方发布页/可验证原始脚本，全部 `UNVERIFIABLE`，未伪造任何来源或 SHA。

---

## 7. E0–E2 完成标准对照

| 完成标准 | 状态 |
|---|---|
| 全部候选已登记 | ✅ 31 条（覆盖任务书全部清单，野花在 A/B 重复取一条） |
| 可获得的来源已获取并计算 SHA-256 | ✅ 33 份实际脚本，SHA 本地计算，交叉验证历史一致 |
| 每个候选有明确审查状态 | ✅ 见登记表与第 2 节 |
| 高风险候选有具体阻断证据 | ✅ 见第 4 节（代码位置/域名/混淆计数） |
| 通过审查者完成 LX 兼容性验证 | ⏸ 属 E3，本阶段未执行（无“通过即放行”项） |
| MusicFree/聚合完成必要拆解 | ⏸ 已登记结构，逐后端/逐插件验证属 E4 |
| 测试与构建结果真实可复核 | ✅ 见提交记录 |
| 无未授权会员绕过 | ✅ 长青保持 BLOCK，未关闭规则 |
| 无未经批准的正式安装 | ✅ 全部候选仅入台账/隔离，未安装 |

**结论**：E0–E2 完成的是**可管理、可追踪、可验证的候选台账与安全审查**；不是“所有来源都能播放”，也没有为追求数量放宽任何门禁。通过审查的候选仍需 E3 起逐项真实验证与用户明确批准，方可进入受控接入。

---

## 8. E3 LX Runtime 架构兼容性验证（SIMULATED_INIT）

对 E2 静态无 BLOCK 命中的候选，按登记表 SHA256 核对原文后，在 MockEngine（零真实网络）下执行
`LxScriptRuntime.load + initialize`，只验证事件接口/QuickJS 加载/必需 Host API/平台声明/SSRF fail-closed。
测试：`provider/lxruntime/src/desktopTest/.../PhaseEArchitectureTest.kt`（4 项），输出 `build/phase-e-architecture.txt`。

| 候选 | SHA 核对 | 加载初始化 | 声明平台 | 验证等级 |
|---|---|---|---|---|
| 至尊 zhizun | ✓ | 成功 | kw/kg/tx/wy/mg | LX_RUNTIME_LOAD（SIMULATED_INIT） |
| HUIBQ | ✓ | 成功 | kw/kg/tx/wy/mg | LX_RUNTIME_LOAD（SIMULATED_INIT） |
| gdstudio（额外发现） | ✓ | 成功 | wy/kw/tx/kg/mg | LX_RUNTIME_LOAD（SIMULATED_INIT） |
| ikun-v515 | ✓ | 失败：初始化即触发 lx.request，空 allowedHosts 被拒 | — | 未升级（FETCHED） |
| juhe / animemusic | ✓ | 失败：同上（SSRF fail-closed 生效） | — | 未升级 |
| flower 野花(pdone) | ✓ | 失败：AggregateError | — | 未升级（QUARANTINED） |

**结论**：失败用例恰恰证明空白名单拒绝一切请求的门禁真实有效，不是"网络没跑起来"。
成功者仅到 `LX_RUNTIME_LOAD` / `SIMULATED_INIT`——**未做真实搜索、未解析真实播放地址、未下载音频**，
不得据此声称可播放或音质达标。登记表仅给 3 个成功者升级状态并记录平台证据。

BLOCK/混淆候选（长青、Fish、Flower、六音、墨澜、元力菌等）**未进入** E3 可执行资源，测试断言其隔离未被解除。

## 9. E4 聚合与插件生态拆解

### 9.1 星澜 StellarWave（阻断升级为明确证据）

E4 从 v3.1.1.1 发布页取得实质证据，**阻断理由从"待确认"升级为"确认存在访问控制绕过"**：

- QQ越权模块**内嵌完整 Cookie**（qm_keyst、uin、pgv_pvid），三重策略：
  - 策略A `ut.y.qq.com` 的 CgiGetHotVkey；
  - 策略B `u.y.qq.com` 的 **platform=23 绕过**；
  - 策略C ut+key 增强兜底；
  - 明示用途："大幅提升 **VIP/付费歌曲** 获取成功率"，解锁 master/atmos。
- 残像 WY 解锁 jymaster 母带；yuafeng 酷狗需用户 apikey；`@tx_cookie/@wy_cookie` 会员 Cookie 配置。
- 后端 87（QQ26/网易19/酷我15/酷狗15/咪咕12），Fallback=并发前3+顺序其余、LRU(TTL6h/容量300)、音质降级链。
- **处置**：BLOCK 保持，不接入任何越权后端；"99.9%+ 成功率"无复核数据，仅为宣传。

### 9.2 MusicFree 插件订阅站（真实清单已展开）

实际下载 `plugins.json`（11029B，sha256 `6b642083b759ca773…c61edb3`），解析出 **57 个插件**（字段 name/url/version）：
QQ音乐 v1.9.26、酷我 v1.9.24、酷狗 v1.9.24、咪咕 v1.9.20、网易云 v1.9.27、汽水 v1.9.23、B站 v1.1.4、
喜马拉雅 v1.2.1、Jamendo v1.0.2、聚合 v1.9.19、JOOX v1.1.3，以及大量 DJ/听书类插件（完整清单见登记台账 children 与报告附件）。

**verifiedCount=57 仅指清单条目数已解析**，不代表插件正文已下载/审查/可用。插件为 MusicFree 协议（≠ LX API），
适配需：格式转换、API 契约适配、QuickJS 兼容、依赖与网络/存储权限、许可证核查；本阶段未批量安装任何插件。

### 9.3 Z系列 / 元力菌

- Z 系列 14 插件：未找到可验证官方清单，UNVERIFIABLE，不伪造插件名/SHA。
- 元力菌反编译：依赖 13413.kstore.vip（长青同域）+ SVIP + 来源/许可证不明，BLOCK 保持。

## 10. E5 管理 UI 增强

在 `CommunityDiscoveryScreen`（commonMain，三端共享）增强，保持窄屏与现有导航：

- **统计概览**：候选总数/已发现/已下载/静态通过/BLOCK/隔离/LX验证(模拟)/真实验证，8 类分列，明确区分"LX验证(模拟)"与"真实验证"。
- **筛选**：名称/域名关键词 + 审核状态按钮筛选（点同一状态取消）。
- **详情**：展示验证等级（非真实标注）、安装策略说明。
- **安装边界**：页面顶部与详情均以醒目文案声明——"安装/启用需单独人工审核与受控目录签名发布；本页不提供安装按钮，候选不会自动成为正式音源。" **不提供任何安装/启用按钮**，从 UI 上杜绝未批准安装。
- 既有能力保留：纵向滚动详情、审核操作纵向排列、删除二次确认、操作期禁用、错误分类提示。

## 11. E3–E5 完成标准对照

| 标准 | 状态 |
|---|---|
| 静态通过者完成 LX 架构验证 | ✅ 7 个候选验证，3 个 SIMULATED_INIT 通过，失败者如实记录 |
| Mock 不冒充真实 | ✅ 全程 MockEngine，报告明确 SIMULATED_INIT ≠ REAL_* |
| MusicFree/聚合完成必要拆解 | ✅ 星澜 87 后端结构+越权证据、MusicFree 57 插件真实清单 |
| UI 清楚展示状态 | ✅ 统计/筛选/验证等级/安装策略，无安装按钮 |
| 测试与构建真实可复核 | ✅ 见提交记录 |
| 无未授权会员绕过 | ✅ 星澜越权证据确认并 BLOCK，未接入 |
| 无未经批准安装 | ✅ 无安装接口、无安装按钮、候选仅入台账/隔离 |

**两轮总结**：E0–E2 建立 31 候选台账与静态审查（33 脚本取证、长青 BLOCK 保持）；E3–E5 完成 LX 架构验证
（3 个 SIMULATED_INIT 通过、SSRF 门禁实测生效）、聚合/插件生态拆解（星澜越权确认+BLOCK、MusicFree 57 插件清单）、
管理 UI 增强（统计/筛选/安装边界）。**全程未执行真实商业音频下载、未绕会员、未自动批准或安装、未改 master/正式目录/公钥/签名。**


---

## 12. 阶段 F：合规音源真实功能验证与受控接入准备（2026-11-20）

> 本节是**真实网络验证报告**，与 E3 的 MockEngine 模拟验证严格分栏。
> 验证方式：本机 HTTPS 代理出网 + 只读公开端点 + 严格超时（8–15s）+ 响应体/响应头有限读取。
> 未下载任何商业音频文件、未提供 Cookie/Token、未做会员/访问控制绕过、未保存音频正文。
> **一次成功不等于长期稳定，HTTP 200/206 ≠ 真实播放。** 所有失败请求、错误码、域名拒绝原因一并列出。

### 12.1 F0 第一批（至尊 / HUIBQ / gdstudio）真实结果

先重新核对来源/版本/SHA/平台/域名（与 E2 登记表一致，SHA 未变，故沿用审查结论）：

| 候选 | 版本 | SHA256(前12) | 声明域名 | 静态 | E3 LX模拟 | 脚本 action |
| --- | --- | --- | --- | --- | --- | --- |
| 至尊 zhizun | v0.8.4_beta5 | ddc095aca3dc | `110.42.36.53:1314`（裸IP+HTTP+非标端口） | PASS | SIMULATED_INIT | **仅 musicUrl** |
| HUIBQ | v1.2.0 | a9adeb456690 | `lxmusicapi.onrender.com` | PASS | SIMULATED_INIT | **仅 musicUrl** |
| gdstudio | 1.0.1 | 05631b29ff18 | `music-api.gdstudio.xyz` | PASS | SIMULATED_INIT | **仅 musicUrl** |

**关键结论：三者 `inited.sources[].actions` 都只有 `musicUrl`，没有任何一个实现 LX `search` action，
因此都不可能通过 LX 接口升级为 `REAL_SEARCH_PASSED`。** 这是脚本能力边界，不是网络或权限问题。

真实请求明细（成功失败都列）：

| 候选 | 阶段 | 端点 host | HTTP | 错误码 | 结论 |
| --- | --- | --- | --- | --- | --- |
| 至尊 | 真实搜索 | 110.42.36.53 | **502** | HTTP_502 | 失败；且无 search action；网络权限已 deny-all（裸IP/HTTP/1314） |
| 至尊 | URL解析 | 110.42.36.53 | **502** | HTTP_502 | 失败；`/url/wy/{id}/{quality}` 空响应 |
| HUIBQ | 真实搜索 | lxmusicapi.onrender.com | — | NO_SEARCH_ACTION | 不支持（无 search action） |
| HUIBQ | URL解析 | lxmusicapi.onrender.com | **503** | SERVICE_SUSPENDED | 失败；"This service has been suspended by its owner" |
| gdstudio | 真实搜索 | music-api.gdstudio.xyz | **200** | — | 通过；`types=search` 返回合法 JSON 数组，首条「屋顶 / 周杰伦、温岚、吴宗宪」（n=1；仅为上游 API 搜索，非 LX search action） |
| gdstudio | URL解析 | music-api.gdstudio.xyz | **200** | — | 通过；`types=url(id=5257138,br=320)` 返回 HTTPS 地址，br=320，size=12764517 |
| gdstudio | URL解析(异常) | music-api.gdstudio.xyz | 200 | EMPTY_URL | 无效 id → `{"url":"","br":-1}`，错误返回符合预期 |
| gdstudio | 音频响应 | m701.music.126.net | **206** | — | Range 0-0：206 Partial Content / audio/mpeg / Accept-Ranges；仅读 1 字节响应头，未存音频 |
| gdstudio | 真实播放 | — | — | — | NOT_RUN（需用户本人有权访问且明确控制，HTTP 206 不冒充播放） |

**域名一致性发现**：gdstudio 解析出的播放地址域名是 `m701.music.126.net`（第三方 CDN），
**并非**其授权域名 `music-api.gdstudio.xyz`。这符合"上游代理返回第三方 CDN"的常见形态，
但意味着播放地址域名不在候选授权列表内，播放链路的域名安全无法用本候选权限覆盖，需单列风险。

### 12.2 F1 第二批（ikun / juhe / animemusic / Flower）初始化与调用链分析

| 候选 | 初始化是否必须联网 | 具体调用链 | E3 空 allowedHosts 失败的真实原因 | 真实探测 | 结论 |
| --- | --- | --- | --- | --- | --- |
| ikun | 否（可选 checkUpdate） | `UPDATE_ENABLE=true` → `checkUpdate()` → `lx.request(/script/lxmusic?key=&checkUpdate=)`；`inited` 同步发送 | 初始化期 checkUpdate 被空名单拒绝（门禁生效） | api.ikunshare.com **TLS 握手失败**（schannel，HTTP=000，2.2s） | 区分错误类型：候选权限已授权该域名，真实失败是 TLS 而非权限；不接入 |
| juhe | **是**（init 载荷来自服务器） | 顶层 `h(A+'/init.conf').then(r=>{ if(code!==200) throw; send(inited, r.data.init) })`；musicUrl 走 303 响应体内二次动态 URL | init.conf 是初始化必需请求，空名单直接导致初始化失败 | init.conf=**200**（code=200，含 sources）；但动态 DNS `dpdns.org` + 303 二次 URL 由响应体决定 | 仅授权 init 域名；二次动态 URL 静态不可审、fail-closed；不接入 |
| animemusic | 否（可选 checkUpdate） | `UPDATE_ENABLE=true` → `checkUpdate()` → `/script/lxmusic?checkUpdate=&key=` | 初始化 checkUpdate 被空名单拒绝 | 端点=200 但 `{"code":500,"message":"不支持的平台: (空)"}`，约 9.8s | 慢且返回错误；不升级等级，不接入 |
| Flower | 是（配置远程加载） | 混淆脚本 `Promise.all([L(裸IP 97.64.37.235), L(gist/npm)])` → 失败即 AggregateError | 裸IP+远程加载被 deny-all 拒绝 | E3: **AggregateError** | 重度混淆、QUARANTINE 保持，禁止执行 |

**F1 结论**：这四个都不应通过"放宽全局白名单"来救活。ikun 的真实失败是 TLS（与权限无关）；
juhe 的初始化确实需要网络，正确做法是**按候选授权 init.conf 单个域名**而非全局放开，且其 303 动态端点仍不可信；
animemusic 接口返回错误；Flower 混淆+裸IP，必须保持隔离。所有失败均如实记录，未因"想让它初始化成功"放宽任何门禁。

### 12.3 网络权限报告

实现 `CandidateNetworkPolicy`（`provider/lxruntime/.../CandidateNetworkPolicy.kt`）：
- 字段：candidateId / allowedHosts / allowedSchemes / allowedPorts / maxResponseBytes /
  connectTimeout / requestTimeout / redirectPolicy / credentialPolicy / authorizationBasis；
- 每个候选独立授权、不继承；**禁止通配符**；默认仅 HTTPS + 443；
- SSRF 复用 `LxUrlSafety`（localhost / 私网 / 回环 / 链路本地 / 元数据地址一律拒绝）；
  另拒绝 userinfo、百分号编码 host、尾随点、IPv6 字面量、裸 IP 域名、非授权端口；
- 权限与版本+SHA 绑定：`validateAgainstRegistry()` 在登记表无 SHA / 候选未登记 / SHA 变化时一律失效；
  `LxScriptRuntime.loadWithCandidatePolicy()` 先校验审批有效、再校验脚本实际字节 SHA 一致，否则拒绝加载；
- credentialPolicy 只有 NONE：禁止把任何用户 Cookie/Token 写入候选网络权限；
- 重定向默认 DENY_ALL（本阶段不启用 REVALIDATE_EACH_HOP）。

| 候选 | 授权域名 | 授权依据 | 被拒请求/原因 |
| --- | --- | --- | --- |
| gdstudio | music-api.gdstudio.xyz | E2 静态无 BLOCK + 唯一 API 域名 | http 明文 / 非443端口 / 其它域名全拒 |
| HUIBQ | lxmusicapi.onrender.com | 同上 | 同上 |
| ikun-v515 | api.ikunshare.com | 同上 | 同上 |
| animemusic | animemusic.bzxhkj.com | 同上 | 同上 |
| juhe | api.music.lerd.dpdns.org（仅 init.conf） | 初始化必需 | 303 动态二次 URL 未审不授权 |
| 至尊 | 无（deny-all） | 裸IP+HTTP+1314 不合规 | 全部请求拒绝，附 denialReason |
| Flower | 无（deny-all） | QUARANTINE + 混淆+裸IP | 全部请求拒绝 |

**DNS rebinding 遗留风险**：commonMain 仍无法在连接层做"解析 IP ↔ 连接 IP"绑定，
本阶段**不声称已解决**；通过"仅具名域名 + HTTPS + 候选级最小授权 + 请求限流"缓解，
且真实验证仅限只读公开端点，不做需要该防护的敏感操作。

### 12.4 质量评分与主备建议

评分维度（权重）：搜索成功率25 / 结果准确性20 / URL解析20 / 播放稳定性20 / 错误处理10 / 来源可信5。
诚实约束：无真实样本维度记 0；样本 <3 的维度封顶；播放分必须有真实播放样本；一次成功不判 EXCELLENT；
真实播放数据与模拟数据分开；FLAC 不等于母带。评分器见 `CommunityQualityScorer`。

| 候选 | 真实搜索 | URL解析 | 音频响应 | 真实播放 | 评分 | 等级 | 建议 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| gdstudio | PASSED(n=1) | PASSED(n=2,含1异常) | PASSED(n=1,206) | NOT_RUN(n=0) | 依代码实算 | UNVERIFIED（播放样本不足） | 限定平台使用/继续观察，不做默认 |
| HUIBQ | NOT_SUPPORTED | FAILED(503 停服) | — | — | 依代码实算 | UNVERIFIED→暂不接入 | 暂不接入（服务已暂停） |
| 至尊 | FAILED(502) | FAILED(502) | — | — | 依代码实算 | BLOCK（网络权限拒绝） | 暂不接入/阻断 |
| ikun/juhe/animemusic | FAILED | — | — | — | 依代码实算 | UNVERIFIED | 暂不接入 |
| Flower / 长青 / 星澜 / 六音 等 | — | — | — | — | BLOCK | BLOCK | 保持阻断，不绕过 |

**门禁（F3）**：至少两个合规候选 `REAL_SEARCH` 通过才启用主备调度器。当前仅 gdstudio 一项真实搜索 PASSED
（且它不是 LX search action），**门禁未打开**，因此不产生任何"建议主音源/建议备用音源"，
UI 明确显示"主备调度器未启用（真实搜索通过 <2）"。`SourceFallbackScheduler` 的
最大尝试次数（默认3）、冷却（默认60s）、失败原因记录、平台隔离、结果身份防冒充、非递归、
不后台全量请求均已实现并有离线测试。**没有任何候选达到"默认音源"门槛。**

### 12.5 淘汰与保持阻断

- **至尊**：唯一端点裸IP+明文HTTP+非标端口，网络权限 deny-all，真实探测 502 → 淘汰。
- **HUIBQ**：服务被所有者暂停（503）→ 淘汰（若将来恢复需重新取证 SHA/来源/静态审查/真实验证，不继承旧结论）。
- **gdstudio**：解析能力真实可用，但无 search action、解析域名非授权域名、播放未验证 → 暂作为"限定平台使用/继续观察"，不设默认。
- **ikun/juhe/animemusic**：TLS失败 / 动态端点不可信 / 接口错误 → 暂不接入。
- **长青（会员绕过）/ 星澜（内嵌Cookie+platform=23 越权）/ 元力菌反编译 / Flower（混淆+裸IP）**：BLOCK 保持，
  未关闭任何绕过规则、未伪装危险函数、未拆分 Cookie 后端当普通音源接入。

### 12.6 完成标准对照

| 标准 | 状态 |
| --- | --- |
| 第一批完成真实验证或明确失败原因 | ✅ 至尊 502 / HUIBQ 503 / gdstudio 200+206，均含错误码与域名拒绝原因 |
| 候选级最小网络权限，无全局放开 | ✅ CandidateNetworkPolicy + deny-all 占位 + SHA 绑定 |
| URL 解析与真实播放严格区分 | ✅ REAL_URL_RESOLVE / REAL_AUDIO_RESPONSE / REAL_PLAYBACK 分立，播放 NOT_RUN |
| 有真实数据与样本量 | ✅ 台账逐请求含 host/状态/错误码/响应时长/样本量；未把一次成功写成稳定 |
| 至少形成主备建议或如实报告 | ✅ 如实报告：门禁未开，无候选达默认/主备门槛 |
| 调度器有最大尝试与冷却 | ✅ SourceFallbackScheduler + 离线测试 |
| UI 展示真实验证等级 | ✅ 详情页分栏展示模拟/真实各阶段、样本量、失败原因、评分、接入建议、主备选择（仅记录） |
| BLOCK 候选未绕过 | ✅ 长青/星澜/元力菌/Flower 保持阻断 |
| 无未经批准安装 | ✅ 无安装按钮，主备选择仅写入 UI state，不启用/安装 |
| 测试与编译真实可复核 | ✅ sourceupdate 153 / lxruntime 93 / shared 385 desktopTest 全绿；Android/iOS Sim/Metadata 编译通过 |
| DNS rebinding 遗留风险 | ✅ 如实声明未彻底解决，限制真实验证范围 |
