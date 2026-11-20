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


---

## 13. 阶段 G1：寻找真正实现 LX search 的合规候选（2026-11-20）

> 基线：`19fe2cf`（阶段 G0）　分支：`work/android-community-discovery`　未碰 master。
> 先做 G1-0：以 LX 官方桌面/移动文档核对 G0 结论——**两版协议均无 search action**：
> - 桌面文档原文："`sources[...].actions`：支持的 actions 数组，`local` 源可用值为 `musicUrl`、`lyric`、`pic`，**其他源只支持 `musicUrl`**"；"`action` 请求操作类型……当为 `musicUrl`…`lyric`…`pic`…"（无 search）；
> - 移动文档原文："`action` 请求操作类型，**目前只有 `musicUrl`**，即获取音乐 URL 链接"。
> 因此 G0"协议无 search、产品无调用链"的结论由上游规范**双重确认**，不是本仓实现缺失。

### 13.1 找到了什么：确实有脚本在脚本内实现 search（非仅上游 API）

GitHub Code Search（`actions: ['musicUrl', 'search']`）命中 215 个结果，绝大多数是 pdone/guoyue 同一批脚本的镜像。
去重后对 4 个候选取真实文件、计 SHA、静态审查、隔离加载（空 allowedHosts + MockEngine，零真实网络）：

| 候选 | 版本 | SHA256(前12) | 来源仓库 | 许可 | DECLARED | LOADED（隔离） | 脚本内 search 实现 | 合规判定 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 溯音 suyin | v1 | d10c9c28823b | pdone/lx-music-source | **无 LICENSE** | `['musicUrl','search']` | ✅ tx/wy/kw/mg | `case 'search'→handleSearch`（kw/mg），走 oiapi.net/api.xcvts.cn | 不合规 |
| 幻音 huanyin | v3 | 4a81129240ec | pdone/lx-music-source | **无 LICENSE** | `['musicUrl','search']` | ✅ tx/kw/wy/mg | `action==='search' && mg→searchMiguMusic`，走 api.xcvts.cn | 不合规 |
| Ciallo~ | 0721 opt | fd16333bcf6 | guoyue2010/lxmusic- | **Apache-2.0** | `['musicUrl','search']` | ✅ wy | `case 'search'→searchMusic`，走 api.s0o1.com | 有许可但有 bug（见下） |
| 非常刀 feichangdao | v5 | 49486cab064e | pdone/lx-music-source | 无 LICENSE | `['musicUrl','search','lyric']` | ❌ QUARANTINE，禁止执行 | 有 handleSearch，但含裸IP 175.27.166.236 + t.me + 多第三方 API | 阻断保持 |

**"脚本内实现"判定方法**：不仅看 `inited.actions` 声明，还逐个核对 request 处理器里是否真有
`case 'search'` / `action==='search'` 分支，并追踪它调用的 URL——这三者确实自己解析关键词并返回歌曲结构，
不是把"上游 API 有 search"误当成脚本 search（区别于阶段 F 的 gdstudio）。

### 13.2 但"真正实现"≠"合规"更≠"可用"——四道硬门槛全部未过

1. **协议层不成立**：LX 官方桌面/移动协议都没有 search action。这些脚本的 search 是**社区非标准扩展**，
   LX Music 客户端与本项目 Runtime 都不会派发它（G0 已证产品调用链只到 `musicUrl`）。写了 search 也无人调用。
2. **来源/许可门槛**：suyin/huanyin/feichangdao 来自 pdone——**仓库无 LICENSE**（实测 LICENSE 返回 404），
   仅"内容源于网络"，不满足 G1"明确许可说明"。只有 Ciallo 的 guoyue2010 仓库是 Apache-2.0。
3. **数据链路不可信**：suyin/huanyin 依赖 `oiapi.net`/`api.xcvts.cn`，Ciallo 依赖 `api.s0o1.com`——
   均为第三方聚合 API，服务条款/授权/稳定性未经确认，且把账号/请求交给未知后端。
4. **真实结构对不上（实证）**：
   - Ciallo：`api.s0o1.com/API/wyy_music?msg=晴天&sm=10` 实测 HTTP 200、`data` 是**数组**；
     脚本却只解析 `data.data.id`（单对象）→ search 实际返回空列表。脚本自身 bug，非网络问题。
   - suyin：`oiapi.net/api/Kuwo` 实测 200，但返回体是 `message` 文本 + `data` 混合结构，脚本靠
     `parseKwFromMessage` 兜底解析，脆弱且易随上游改版失效。未做真实 search 调用验证（属 G3）。

**未升级任何状态**：四个候选都未标 `REAL_SEARCH_PASSED`、未标 `ELIGIBLE_FOR_CONTROLLED_TEST`；
suyin/huanyin/ciallo 只到 `LX_RUNTIME_LOAD_PASSED`（隔离加载级），feichangdao 保持 QUARANTINE。
未执行 QUARANTINE 脚本、未发真实 search 请求、未放宽 SSRF、未接入任何脚本。

### 13.3 G1 结论（明确回答任务）

- **是否存在"真正实现 LX search 的合规候选"？** —— **没有。**
  有 3 个脚本在脚本内真实现了 search 且能隔离加载，但：(a) LX 协议无 search action、产品无调用链，
  实现也无处可用；(b) 两个无 LICENSE、一个第三方 API 依赖；(c) 唯一有 Apache-2.0 的 Ciallo 实测有解析 bug。
  没有任何一个同时满足"协议内 + 来源清晰 + 有许可 + 真实可用"。
- **不把 MusicFree 插件当 LX 音源**：本阶段未引入任何 MusicFree 插件（协议不同，见阶段 E §9.2）。
- **不因 init.conf 已授权就放行 juhe 动态 URL**（阶段 F 红线保持）；**不设 gdstudio 为默认**（无 search action）。
- **对 G2/G3 的建议**：若目标是"脚本提供搜索"，这是 G2 的**新协议 + 新调用链 + 新结果模型**工程
  （定义 LX 扩展 search action、宿主派发、结果校验、候选级授权），不是换音源能解决的；
  若目标是"当前架构下可用的搜索"，应继续走宿主侧 `LxSongSearcher` 注册表（默认 kw+kg），
  并为有价值的 musicUrl 解析脚本补 G3 真实 URL/音频/播放验证。G1 不启动自动主备调度。

### 13.4 测试与登记

- 新增候选登记：suyin/ciallo（新），huanyin（从 UNVERIFIABLE 按真实取证升级）；`license` 字段入台账。
- 新增测试：`PhaseG1SearchCandidateTest`（隔离加载 3 项，QUARANTINE 不执行）、`PhaseG1RegistryTest`（4 项台账不变量）；lxruntime 101 / sourceupdate 157 全绿，iOS Sim/Metadata/Android 编译通过。
- `build/phase-g1-isolated-load.txt` 记录三候选 DECLARED/LOADED 实况。
- 未改协议、未加产品 search API、未做真实 search 调用、未接任何脚本、未碰 master。


---

## 14. 阶段 G2：MusicFree 合规来源发现与受控适配层（2026-11-20）

> 基线：`408b75e`（阶段 G1）　分支：`work/android-community-discovery`　未碰 master。
> 用户条件指令："如果发现 MusicFree 生态中有值得接入的来源，再做 G2 适配器。"
> 本节先给出**发现结论**，再给出据此实现的适配层；红线：不把 MusicFree 插件当 LX 音源、
> 不改全局 SSRF、不放行动态端点、不自动批准/安装、不挂默认 provider。

### 14.1 发现结论：有值得接入的来源，但要分清两类

以官方 `maotoumao/MusicFreePlugins` 为准（GPL-3.0、149 commits、最近维护 2025-11-02），
实际下载权威 `plugins.json`（1580B，sha256 `a9b2d83bba47adca…`）与全部 12 个插件正文并逐条计 SHA：
Audiomack / 歌词网 / 歌词千寻 / Navidrome / suno / udio / 猫耳FM / 快手 / 音悦台 / YouTube / bilibili / WebDAV。

**与 LX 的关键差异（G0 结论的正面回应）**：MusicFree 插件协议 `types/plugin.d.ts` **原生定义了 `search`**：
- `search(query, page, type) -> { isEnd?, data: IMusicItem[] }`（type ∈ music/album/artist/sheet）
- `getMediaSource(musicItem, quality) -> { url?, headers?, userAgent?, quality? }`
- 另有 `getMusicInfo` / `getLyric` / `getAlbumInfo` / `getTopLists` 等。
这正补上 LX 协议缺失的 search 能力——但它是**另一套协议**（CommonJS + axios/crypto-js/webdav/he/cheerio Node 模块），
FuoEvolve 的 QuickJS Runtime 无法直接加载，必须走适配层。

**静态审查结果（全部 12 插件）**：
- **零** VIP / SVIP / 会员绕过 / 混淆 / `eval` / `new Function` / 裸 IP 初始化命中；
- 唯一风险标记是 bilibili 的 9 处 cookie，但来源为 `api.bilibili.com/x/frontend/finger/spi`
  公开接口发放的**匿名 `buvid3`/`buvid4` 访客标识**，非用户凭据、非会员 Cookie——仍列为需人工复核项，不自动接入；
- 官方 README 明确"过滤掉所有 VIP、试听、付费歌曲"，`plugins.json` 已不含 QQ/网易等主流商业平台插件。

**值得接入的两类来源**：
1. **用户自建库插件（Navidrome / WebDAV，最合规）**：用 Subsonic / WebDAV 开放协议访问**用户本人服务器**上的音乐，
   内容完全由用户拥有；搜索/URL 解析由插件原生实现。**但**：端点由用户 `userVariables.url` 配置，
   常为私网/裸 IP，静态不可审——按红线不为它们放宽 SSRF，归入"需独立受控端点授权（本阶段未实现）"。
2. **固定具名域名插件（Audiomack 等）**：源码有静态可审 HTTPS API 域名（api.audiomack.com），
   可生成候选级网络权限（SHA 绑定 + 域名白名单），是**当前适配层能安全落地的对象**。
3. **动态端点插件（YouTube）**：解析 URL 由上游响应体决定，不预先授权，同样 fail-closed。

**结论**：MusicFree 生态里确实有值得接入的来源（原生 search + 来源/许可清晰 + 无会员绕过），
尤其自建库类内容是用户有权访问的合规内容；但它们**不能通过放宽 SSRF 来接入**，
必须靠独立适配层 + 候选级授权，且自托管/动态端点还需额外的受控端点授权流程。

### 14.2 实现的受控适配层（新模块 `:provider:musicfree`）

不修改 LX Runtime 核心协议（`LxProtocol`/`LxScriptRuntime`/`LxNetworkPolicy` 全部不动），新增独立模块：

| 文件 | 职责 |
| --- | --- |
| `MusicFreeProtocol.kt` | 把 `plugin.d.ts` 的 search/mediaSource/lyric 数据形状映射成中性模型；解析失败 fail-closed，不伪造条目；mediaSource 强制 HTTPS |
| `MusicFreePluginRegistry.kt` | 12 个官方插件的真实 SHA256/版本/GPL-3.0/能力/静态域名/风险证据台账；manifest SHA 常量 |
| `MusicFreePluginPolicy.kt` | 复用阶段 F [CandidateNetworkPolicy]：SHA 绑定 + 具名域名 + SSRF；自托管/动态/无静态域名一律 deny-all，不放宽 |
| `MusicFreeAdapter.kt` | 纯函数适配：search/mediaSource/lyric → 内部模型；每次都过能力门禁 + 授权门禁 + URL 策略；结果分 `Mapped`/`Denied` 并标注等级 |

**关键设计**：
- **不执行 CommonJS 插件代码、不发网络请求、不下载音频、不写凭据**——适配层只做"数据映射 + 门禁"；
- **不注册为 Provider**：不挂 `ProviderComposition`、不进 `DEFAULT_ENABLED_PROVIDER_IDS`、无安装按钮；
- 固定域名插件（Audiomack）生成 `CandidateNetworkPolicy(allowedHosts={api.audiomack.com}, SHA 绑定)`，
  自托管（Navidrome）与动态端点（YouTube）生成 deny-all 决策并给出"不放宽 SSRF"的明确原因；
- 播放 URL 必须再通过 `policy.rejectionReason(url)`，未授权域名/明文 HTTP/私网 IP/userinfo 全部拒绝；
- 等级诚实：搜索映射标注 `DECLARED+MAPPED（未做真实搜索调用）`，URL 映射标注 `REAL_URL_RESOLVE 候选
  （未验证音频响应/真实播放）`——**不冒充真实搜索/真实播放**。

### 14.3 未解决项与边界（如实）

- 常驻障碍：CommonJS + Node 模块依赖（axios/crypto-js/webdav/he/cheerio）需独立的隔离执行环境，本阶段未实现；
- 自托管/动态端点的受控授权流程未实现，Navidrome/WebDAV 暂不能进入真实网络执行；
- 未做任何真实搜索/URL/音频/播放调用，未升级任何状态为 `REAL_*_PASSED`；
- DNS rebinding 遗留风险不变；manifest 与插件 SHA 为 2026-11-20 抓取快照，版本更新即需重新取证。

### 14.4 测试与编译

- 新增 `PhaseG2AdapterTest`（10 项）：台账完整性、SHA 变更失效、自托管/动态端点 deny-all、
  URL 私网/明文/未授权域名/userinfo 拒绝、search/mediaSource/lyric 映射、能力门禁、解析失败不伪造。
- 回归：sourceupdate 157 / lxruntime 101 / musicfree 10，全绿 0 失败 0 跳过。
- 编译：musicfree 的 iOS Simulator / commonMain Metadata / Android 全部通过（含 `--rerun-tasks`）。


---

## 15. 阶段 G3：MusicFree 合规来源真实端到端验证（2026-11-20）

> 基线：`5022842`（阶段 G2）　分支：`work/android-community-discovery`　未碰 master。
> 性质：**真实网络验证**（OAuth 1.0 公开 consumer key `audiomack-js`，脚本内公开写死，非用户凭据/非会员绕过），
> 与 MockEngine 严格分栏。只读公开端点 + 严格超时 + Range 0-0 仅读 1 字节响应头。
> 未下载商业音频正文、未提供 Cookie/Token、未做会员/访问控制绕过、**未真实播放**。
> 成功与失败请求、错误码、域名拒绝原因全部列出——不只挑成功报告。

### 15.1 真实搜索验证（Audiomack，HTTP 请求，非 Mock）

| 关键词 | HTTP | 结果数 | 首条 / 说明 |
| --- | --- | --- | --- |
| 晴天 | 200 | 5 | id=10921920 / 晴天 / 周杰伦 / 叶惠美 |
| 周杰伦 | 200 | 5 | 珊瑚海 / 周杰伦 |
| weeknd（英文） | 200 | 5 | 平台支持英文 |
| 周杰伦 晴天（歌手+歌名） | 200 | 5 | — |
| nonexistent_xyz_9981（不存在） | 200 | 0 | 空结果符合预期，不伪造为失败 |
| `<>?%`（特殊字符） | 200 | 0 | 未报错 |
| 20×`a`（长关键词） | 200 | 2 | — |
| 空关键词 | 200 | 0 | 符合预期 |

**结论**：`REAL_SEARCH_PASSED`（n=8），脚本声明 search 且实测可用。一次/单日样本，不宣称长期稳定。

### 15.2 URL 解析与身份匹配

| 项 | 结果 |
| --- | --- |
| `music/play/10921920` | HTTP 200 → `{signedUrl, playUuid}`；signedUrl scheme=https |
| 身份匹配 | 搜索"晴天"首条 id=10921920 == 解析用 id → **MATCH**（无错歌） |
| 无效 id 用例 | 本轮**未发请求**（NOT_PROBED），如实标注样本不足，不伪造失败数据 |
| 等级 | `REAL_URL_RESOLVE_PASSED`（n=1，身份匹配 MATCH） |

### 15.3 音频响应与关键域名拒绝（诚实核心）

| 端点 | HTTP | 结果 |
| --- | --- | --- |
| `music.audiomack.com`（signedUrl） | **206** | `audio/mp4` / `Content-Range: bytes 0-0/4364494`；仅读 1 字节响应头，未存音频 |
| 候选授权校验 | **拒绝** | 解析域名 `music.audiomack.com` ≠ 候选授权 `api.audiomack.com` → `DOMAIN_NOT_AUTHORIZED`，候选级网络策略会拒绝该播放 URL |

**这是本阶段最重要的诚实发现**：搜索、解析、身份匹配、音频响应全部真通过，
**但播放地址域名不在候选授权白名单内**——与阶段 F 的 gdstudio（解析到 `m701.music.126.net`）同类问题。
按红线**不为它放宽白名单**，因此播放链路仍不可用。这证明"能解析"与"能播放"是两件事。

### 15.4 真实播放与门禁

- `REAL_PLAYBACK = NOT_RUN`（错误码 `REQUIRES_USER_ACTION`）：HTTP 206 + audio/mp4 不等于真实播放；
  播放验证需在用户本人有权内容上明确操作，本阶段未执行。
- **调度门禁未开**：只有 1 个候选真实搜索通过（<2），不产生默认/备用音源建议，不启动自动主备。
- 状态纪律：未把任何候选升级为 `REAL_PLAYBACK_PASSED`；发布仍需人工审核 + 受控目录签名，无自动安装。

### 15.5 台账代码化与测试

- 新增 `MusicFreeG3Verification.kt`：逐请求台账（成功/失败/NOT_RUN、HTTP、错误码、域名拒绝原因），
  `summary()` 分栏输出 REAL_SEARCH / REAL_URL_RESOLVE / AUDIO_RESPONSE / NOT_RUN，附样本量与域名风险说明。
- 新增 `PhaseG3VerificationTest`（6 项）：空/失败样本如实记录、身份匹配、域名拒绝不放宽、播放 NOT_RUN、
  门禁关闭、台账 SHA 与登记表一致。
- 回归：sourceupdate 157 / lxruntime 101 / musicfree 16（+6）全绿；musicfree iOS Sim/Metadata/Android 编译通过。

### 15.6 阶段 G 总体结论

- **G0**：LX 协议无 search（协议/调用链缺口，不在桥）。
- **G1**：无真正实现 LX search 的合规候选。
- **G2**：MusicFree 生态有值得接入的来源（原生 search、无会员绕过），建成受控适配层（不放宽 SSRF）。
- **G3**：Audiomack 真实搜索/解析/身份匹配/音频响应通过，但解析域名不在授权白名单 → 播放链路不可用；播放 NOT_RUN；门禁未开。

**核心答案**：合规音源"真正可用"仍差最后一环——**受控的播放域名授权**（解析域名与静态授权域名不一致），
以及 CommonJS 隔离执行环境。两者未解决前，不设默认/备用音源、不自动安装、不冒充真实播放。


---

## 16. 阶段 H0：MusicFree Provider 安全执行环境架构审计（2026-11-20）

> 基线：`32176e5`（阶段 G3）　分支：`work/android-community-discovery`　未碰 master。
> **只审计，不重构**。本节所有结论带 `文件:行号`，作为 H1/H2 的设计输入；未新增可运行代码。

### 16.1 模块依赖图

```
:provider:musicfree (G2 新增，本次审计对象)
  ├─ :provider:runtime    → KotlinMusicProvider/BaseKotlinProvider/ProviderHttpClient/ProviderNetwork
  ├─ :provider:lxruntime  → CandidateNetworkPolicy / LxUrlSafety （复用候选级授权，不改其核心协议）
  ├─ :provider:sourceupdate → CommunitySourceRegistry / BoundedHttpBody
  ├─ kotlinx.coroutines.core / serialization.json / ktor.client.core
:provider:lxruntime (QuickJS 执行环境现状)
  ├─ quickjs-kt 1.0.15 (io.github.dokar3) → Android .so / JVM / iOSArm64 / iOSSim 四套产物
  ├─ :provider:runtime / :provider:sourceupdate
  └─ Android host test 用 dependencySubstitution 把 quickjs-kt-android → quickjs-kt-jvm
```
musicfree 依赖 lxruntime 只为复用授权策略；**不调用 LxScriptRuntime 的执行 API**（G2 适配层是纯数据映射，见 §14）。

### 16.2 Provider 调用时序图（现状，LX 路径）

```
lx.request(url,opts,cb)  [BR:146-165]
 → __lxHttp(JSON)  asyncFunction  [RT:452-456]
 → performHttp()  [RT:522-573]
    ├ LxNetworkPolicy.rejectionReason(url, allowedHosts)  [RT:530; NP:24-37]
    ├ followRedirects=false 手动逐跳 [RT:582-615]，每跳重新校验 [RT:607-611]
    └ readBodyBounded 512KiB 流式 [RT:543; BB:50-71]
 → JS Promise → callback [BR:156-163] → __lxDispatchError → Kotlin [RT:268-271]
```
QuickJS 生命周期：`load()` 每次新建 `RuntimeSession`(QuickJs.create + evaluationTimeoutMillis) [RT:342-344]，
`load`/`close` 全程 lifecycleMutex 串行 [RT:105-106]，`close()` 五步顺序释放门闩/timer/scope/job/context [RT:362-386]。

### 16.3 宿主能力清单（暴露给 JS 的全部能力）

| JS 可见名 | 作用 | 网络/绕过风险 |
| --- | --- | --- |
| `lx.request`→`__lxHttp` | 唯一出网口 | 必经 SSRF+逐跳重定向+体积/超时；JS 无 fetch/XHR/WebSocket/socket |
| `__lxBufferFrom/BufToString/Md5` | 编解码/哈希 | 无网络 |
| `__lxCryptoUnsupported` | randomBytes/aes/rsa 明确抛错 | 无网络，不解错误 URL |
| `__lxDelay/__lxCancelTimer` + `setTimeout/clearTimeout` | 计时器，最多 64 个 | 无网络，可取消 |
| `console` no-op | 丢弃日志防泄漏 | 无网络 |
| `lx.on/send/EVENT_NAMES`、探针 `__lxInited/__lxDispatch/__lxHasHandler/__lxUpdateAlert` | 事件与宿主探针 | 无网络；`__lxUpdateAlert` 无 Kotlin 消费方 |

### 16.4 安全边界（已具备，可复用）

- **每脚本独立 QuickJS context**；cacheKey=`id:version:SHA256` [PR:262-269]，prune/release/releaseAll 完整 [PR:195-260]。
- **native `evaluationTimeoutMillis` 中断死循环** [RT:396-406]；网络 `withTimeoutOrNull` [RT:540-546]。
- **SSRF**：空白名单拒一切 [NP:13-16]；归一化 0x7f000001/2130706433/127.1 等变体 [NP:94-139]；RFC1918/回环/链路本地/169.254.169.254 元数据/CGNAT/组播全拒 [NP:148-177]。
- **逐跳重定向白名单**：followRedirects=false，每跳重新 rejectionReason，最多 3 跳 [RT:582-615]。
- **512KiB 流式有界读取**，伪造 Content-Length 无法绕过 [BB:50-71]。
- **安装前静态扫描**拦截 eval/new Function/import/require/fs/process/child_process/WebSocket [SP:47-61]。
- **凭据策略**：候选级 policy 只允许 NONE [CandidateNetworkPolicy:27,51-53]。

### 16.5 未解决风险 / 当前缺失（H1/H2 必须处理或如实声明）

1. **无 `require` / 模块系统 / 模块缓存**：JS shim 全文无 require [BR:44-192]；库自带 ModuleLoader 未启用 [RT:342 单参 create]。→ H1 要建受控 CommonJS，需在隔离 context 内实现 allowlist require + 按 Provider 隔离缓存。
2. **库支持但代码未用的加固抓手**：memory limit / max stack size / 主动 interrupt / gc / memoryUsage [quickjs-jvm.jar javap 实证]。→ H1 应启用并测试。
3. **跨 request 的 timer/后台心跳无法可靠实现**：quickjs-kt 1.0.15 每个 evaluation session 结束会 cancel 其 asyncJobs；无公开 job-queue 驱动 API；顶层 fire-and-forget setTimeout 不会运行 [DOC:493-503]。→ **如实声明为库限制，不伪称隔离**。
4. **close() 不 join 库的求值协程**：宿主只 join 自己的 timer Job [RT:372-386]；库 cancel 后即销毁 native context。
5. **无统一 ProviderNetworkGateway**：LX Provider 自行 `createSharedProviderHttpClient()` [PR:68,79]，绕开统一层 `ProviderHttpClient.client` 的代理/测试注入钩子 [PN:260-267]。→ H2 要建网关并让所有 Provider 网络经它。
6. **DNS rebinding**：SSRF 只在 URL/字符串层，Ktor/OkHttp 不固定解析 IP，无连接层 IP 校验。→ **如实保留，不宣称 commonMain 已解决**。
7. `__lxUpdateAlert` 有 JS 定义无 Kotlin 消费方 [BR:174-176]。
8. 三端网络引擎差异：Android/Desktop=OkHttp（Dispatcher maxRequests=64/perHost=8，连接池 8/5min）[ProviderNetwork.android/desktop.kt:10-22]；iOS=Darwin [.ios.kt:9-11]。quickjs 三端均有产物，Android host test 替换为 JVM 产物 [lxruntime/build.gradle.kts:39-47]。

### 16.6 H0 结论

现有 LX 执行环境已具备"单脚本上下文隔离 + SSRF + 逐跳重定向 + 有界读取 + 可取消计时器"的坚实基础；
但**不能直接当作 MusicFree 的安全执行环境**，三大缺口：①无 CommonJS require/模块隔离；②memory/stack 限制未启用；③无统一网络网关（当前 LX 自建 client）。
H1/H2 要在**新模块内**补齐这三点（不污染 LX 核心协议），H3 单独审查播放域名，H4 播放闭环，
DNS rebinding 与库 timer 限制全程如实声明。H0 到此为止，未做任何重构。


---

## 17. 阶段 H1：CommonJS 隔离执行环境（2026-11-20）

> 基线：`f0ba4e3`（H0）　分支：`work/android-community-discovery`　未碰 master、未推送。

### 17.1 实现（新代码，不改 LX 核心协议）

在 `:provider:musicfree` 新增真实 QuickJS 沙箱（依赖 quickjs-kt 1.0.15，与 lxruntime 同版本/同 Android host-test 替换规则）：
- `MusicFreeSandbox.kt`：资源 [Limits]（脚本512KiB/模块256KiB/模块数32/超时5s/内存8MiB）、[EngineCapabilities]（如实回报各平台能强制的限制）、[ModuleRegistry]（require allowlist + SHA 绑定）、`requireRejection`/`preflightRequires`。
- `MusicFreeSandboxSession.kt`：每个 Provider 独占 `QuickJs.create(Dispatchers.Default)` native context；设置 `evaluationTimeoutMillis`（native 可中断死循环）与 `memoryLimit`；require 走宿主 binding，模块由宿主以 ESM `addModule` 预载到本 session 私有缓存（不读全局 registry，跨线程不可见）；宿主能力仅 md5 纯计算；`execute()` 预检→初始化→受控求值，失败分级 REJECTED/TIMEOUT/CANCELLED/ERROR；`close()` 幂等释放 native context 与缓存。

### 17.2 逐条对应任务书与实测

| 要求 | 实现/测试 | 状态 |
| --- | --- | --- |
| 独立实例/等效隔离 | 每 session 独立 QuickJs Context；`h1_moduleCacheIsolatedBetweenProviders`/`h1_concurrentSessionsIndependent` | ✅ |
| require allowlist | ModuleRegistry 登记 + `requireRejection`/`preflightRequires`；`h1_maliciousRequireRejectedFailClosed`（fs/child_process/axios/路径穿越全拒） | ✅（JS↔Kotlin 字符串封送见 17.4） |
| 禁文件/进程/远程加载 | shim 显式不提供 fetch/XHR/WebSocket/process/fs/eval 包装；宿主 binding 只 md5 | ✅ |
| 模块缓存按 Provider 隔离 | session 私有不可变快照；跨 session 不共享 | ✅ |
| 执行时间/内存/任务/响应限制 | evaluationTimeoutMillis 中断死循环、memoryLimit、脚本/模块字节上限、模块数上限；`h1_infiniteLoopTerminatedByNativeTimeout`（300ms 超时→TIMEOUT）、`h1_scriptTooLargeRejected` | ✅ 时间/大小；内存为 native Context 层，能力如实回报 |
| 取消/超时/异常回收 | CancellationException 分级、interruptEvaluation、close 幂等；`h1_closeIsIdempotentAndRejectsFurtherExec` | ✅ |
| 异常 Provider 不影响其他 | `h1_exceptionInOneProviderDoesNotAffectAnother` | ✅ |
| 未审依赖不进环境 | 模块必须登记 SHA + note + auditedAt 才预载 | ✅ |

### 17.3 测试与编译

- `PhaseH1SandboxTest`（10 项）：allowlist fail-closed、预检、缓存隔离、死循环终止、正常执行+能力回报、超大脚本拒绝、异常隔离、close 幂等、require 门禁语义、并发 session 独立。
- 回归 musicfree 26（原16+10）全绿 0 失败；iOS Simulator / commonMain Metadata / Android 编译全部通过（`--rerun-tasks`）。
- 修复的跨平台问题：`@Volatile` 与无参 `toByteArray()` 在 Kotlin/Native 不可用 → 去掉 Volatile（快照在 mutex 内一次发布、binding 只读）并改 `encodeToByteArray()`。

### 17.4 如实声明的限制（不伪称已隔离）

1. **quickjs-kt 1.0.15 的 JS→Kotlin 字符串实参存在包装类型差异**：binding 收到的字符串 `toString()` 相同但 `equals/hashCode` 与纯 Kotlin String 不一致，直接作 Map key 查不到；已改为 `toString()` 文本匹配 + 提供 Kotlin 侧门禁语义测试，但 **JS 内 `require()` 的完整往返未作为通过项**——H1 只交付"allowlist + 隔离缓存 + 执行环境"基础设施，不宣称 CommonJS require 端到端已可靠。
2. **maxStackSize 在 commonMain 未暴露为可写属性**（JVM 字节码有 setter 但 KMP 公共 API 不稳定）→ `canSetStackSize=false` 如实回报，stack 上限未强制。
3. **memoryLimit 为 native Context 层限制**，是否对所有平台强制由 `capabilities` 回报，不虚报。
4. **跨求值后台 timer 不提供**（库无公开 job-queue 驱动 API，见 H0 §16.5）。
5. **官方插件（axios/crypto-js/webdav 等 Node 模块）当前不可执行**：沙箱只承载 JSON 可序列化 exports 的审计模块，插件 CommonJS 生态适配属后续阶段。
6. **DNS rebinding 与本沙箱无关**，仍按 H2 网关层如实保留。


---

## 18. 阶段 H2：ProviderNetworkGateway 宿主网络代理（2026-11-20）

> 基线：`1e729bb`（H1）　分支：`work/android-community-discovery`　未碰 master、未推送。

### 18.1 实现

新增 `ProviderNetworkGateway.kt`：Provider 网络请求的**唯一宿主出口**。
- `ProviderNetworkPermission` 绑定 Provider ID / 版本 / 脚本 SHA256 / 精确域名 / HTTPS+端口 /
  响应体积 / 超时 / 重定向策略 / 凭据策略；`isValidFor`/`validationProblem` 在 SHA 变化时使审批失效。
- 复用阶段 F `CandidateNetworkPolicy.rejectionReason`（SSRF、私网/回环/链路本地/元数据、裸 IP、
  userinfo、编码 host、尾随点、非授权域名、非 HTTPS/443 全部 fail-closed）。
- 默认拒绝：通配符域名（policy 构造期 require 拒绝）、明文 HTTP、协议相对 URL、凭据头（Cookie/
  Authorization/X-API-Key/Proxy-Authorization）、未校验重定向、响应体超限。
- 请求经注入 `executor` 执行（不自 new client，避免旁路客户端）；被拒请求**零网络**触达 executor。
- 重定向：DENY_ALL 默认；REVALIDATE_EACH_HOP 对下一跳重新跑完整策略，不自动跟随。
- 取消（CancellationException）原样传播；网络异常分级 Failed。

### 18.2 测试（PhaseH2GatewayTest，9 项，假执行器零真实网络）

授权请求通过且触达 executor / 未授权域名零网络拒绝 / 明文+私网+裸IP+metadata+通配符默认拒绝 /
凭据头与 userinfo 拒绝 / SHA 变更使权限失效 / 重定向 DENY_ALL 与逐跳重校验 /
响应体超限拒绝 / 取消传播 / 沙箱无旁路网络 API（宿主能力仅 md5/base64/now 三项）。

### 18.3 诚实限制

- **DNS rebinding 未在 commonMain 解决**：网关只做 URL/字符串层校验，Ktor/OkHttp 不固定解析 IP，
  无连接层 IP 绑定；本阶段如实保留风险，不宣称已彻底防御。
- 网关是**策略执行骨架 + 注入执行器**：真正三端 Ktor 客户端由宿主注入（H5 分别验证），
  gateway 本身不绑定具体 engine，避免在模块内再建旁路 client。
- `REVALIDATE_EACH_HOP` 需要调用方逐跳调用 `enforceRedirect`，当前默认 DENY_ALL；不做隐式跟随。

### 18.4 回归

musicfree 35（26+9）全绿 0 失败；iOS Simulator / commonMain Metadata / Android 编译通过（`--rerun-tasks`）。


---

## 19. 阶段 H3：Audiomack 播放域名审查（2026-11-20）

> 基线：`6714475`（H2）　分支：`work/android-community-discovery`　未碰 master、未推送。

### 19.1 取证（成功与受限如实）

- **G3 已实测**：signedUrl host=`music.audiomack.com`（HTTPS），Range 0-0 → 206 `audio/mp4`，
  Content-Range `bytes 0-0/4364494`；search/API host=`api.audiomack.com`（200）。
- **DNS（本轮系统 resolver 实测）**：
  - `api.audiomack.com` → `2a03:2880:f127:83:face:b00c:0:25de`, `98.159.108.71`
  - `music.audiomack.com` → `2a03:2880:f10d:183:face:b00c:0:25de`, `108.160.170.43`
  - 两者均落在 Meta/Facebook IP 段（`2a03:2880::/32` / `98.159.108.*` / `108.160.170.*`），
    但**主机名不同**；不能据同段 IP 推断播放域名获得同等授权。
- **服务条款（audiomack.com/about/terms-of-service 原文）**：禁止"Alter or modify any part of the Services"、
  禁止"Use any spider, readers, site search application, or other device to retrieve or index any portion of the Service
  … with the exception of major public search engines"、禁止"Distribute an illegal or unauthorized copy of another
  person's trademarked or copyrighted work"；Audiomack 可随时变更/暂停服务而不事先通知。
- **API 文档**（audiomack.com/data-api/docs）描述的是登录态数据 API，未见对"匿名签名 URL 供第三方 App 流媒体消费"的明确授权。
- **限制**：本轮本地 HTTPS 代理进程失效（仅 FIN_WAIT_2/CLOSE_WAIT，无 LISTENING），
  无法新增每跳重定向、URL Expires/X-Amz-Expires 签名参数、请求头要求的实时取证——**如实记为未复核**，不伪造。

### 19.2 决策：保持拒绝（不放行）

`MusicFreePlaybackDomainAudit.kt` 记录 `playbackHostDecision = DENIED(hostname=music.audiomack.com)`，
理由：①解析域名≠静态授权域名且为响应体动态端点；②服务条款禁止修改服务/非搜索抓取/分发未授权副本，
第三方客户端用签名 URL 播放未被明确授权；③API 文档未授权匿名签名 URL 消费；④本轮重定向/有效期未复核；
⑤目标地区可访问性与使用方式未确认；⑥同 IP 段不同主机名不能推断授权；⑦DNS rebinding 未解决。
`mayAllowPlaybackHost()` 对 Audiomack 播放域名恒 false；`playbackUrlRejectionReason()` 网关层拒绝该域名。
后续要求：官方书面授权/明确 API 条款 + 代理恢复后复核重定向与有效期 + 地区/下载vs流媒体确认 + 人工审核精确域名（非通配符）+ SHA 绑定。

### 19.3 测试与回归

`PhaseH3DomainAuditTest`（5 项）：解析域名≠授权域名、决策 DENIED 含条款/DNS 理由、播放域名不放行、
播放 URL 默认拒绝不放宽白名单、DNS 证据记录 Meta IP 段。musicfree 40（35+5）全绿；
iOS Simulator / Metadata / Android 编译通过（`--rerun-tasks`）。


---

## 20. 阶段 H4：真实播放闭环验证台账（2026-11-20）

> 基线：`26938e4`（H3）　分支：`work/android-community-discovery`　未碰 master、未推送。

### 20.1 实现与五级分栏

新增 `MusicFreePlaybackVerification.kt`：8 步状态机（搜索→身份匹配→URL解析→网络权限/域名授权→音频响应→播放器读取→解码输出→暂停/继续/Seek/切歌/取消），
五级严格分栏 `REAL_SEARCH / REAL_TRACK_IDENTITY_VERIFIED / REAL_URL_RESOLVE / REAL_AUDIO_RESPONSE / REAL_PLAYBACK`；
任一步失败即停，后续 NOT_RUN 并记录原因；`PlaybackVerificationReport.realPlaybackAchieved/haltedAtIndex` 供 UI/报告读取。

### 20.2 当前 Audiomack 闭环结论：第 4 步即停

| 步骤 | 状态 | 依据 |
| --- | --- | --- |
| 1 真实搜索 | PASSED | G3 真实 OAuth 搜索 n=8 |
| 2 身份匹配 | PASSED | G3 搜索首条 id=10921920 == 解析 id，MATCH |
| 3 URL 解析 | PASSED | G3 music/play/10921920 → signedUrl(https) |
| 4 网络权限/域名授权 | **FAILED** | H3 拒绝 music.audiomack.com（证据不足/条款未明确），不放宽白名单 |
| 5 音频响应（经授权闭环） | NOT_RUN | 第4步未通过（G3 的 206 是独立只读探测，不构成经授权闭环的音频响应） |
| 6 播放器读取 | NOT_RUN | 未到播放阶段 |
| 7 实际解码输出（REAL_PLAYBACK） | **NOT_RUN** | 需用户明确操作 + 有权内容 + 域名授权；HTTP206/Mock 解码不冒充 |
| 8 播放控制 | NOT_RUN | 播放未启动 |

达成等级：REAL_SEARCH / REAL_TRACK_IDENTITY_VERIFIED / REAL_URL_RESOLVE；
未达成：REAL_AUDIO_RESPONSE、REAL_PLAYBACK。`realPlaybackAchieved=false`，`haltedAtIndex=4`。

### 20.3 诚实边界

- 本阶段**没有真实播放器、没有解码输出、没有声音**，不把 G3 的 HTTP 206 或任何 Mock 解码写成真实播放。
- 即使未来播放域名获授权，`buildReport` 在 `realPlaybackDone=false` 时仍停在播放步、REAL_PLAYBACK 不达成——需用户明确控制下的真实播放验证。
- 状态机是验证台账/门禁，不是播放器实现；真正的跨平台播放器属应用主链路，此处不触碰。

### 20.4 测试与回归

`PhaseH4PlaybackVerificationTest`（7 项）：8 步记录、第4步停、后续 NOT_RUN、206 不冒充真实播放、
五级区分、域名授权后仍须用户操作、早失败停链。musicfree 47（40+7）全绿 0 失败；
iOS Simulator / Metadata / Android 编译通过（`--rerun-tasks`）。


---

## 21. 阶段 H5：三端验收与阶段 H 最终报告（2026-11-20）

> 基线：`32176e5`（G3）　分支：`work/android-community-discovery`　未碰 master、**未推送**（用户未授权）。

### 21.1 Git 基线与提交链

- 基线 HEAD=`32176e5`，父提交 `5022842`，工作区干净，远端=基线（H0 开始时 ls-remote 验证）。
- 阶段 H 五个独立提交链：`32176e5 → f0ba4e3(H0) → 1e729bb(H1) → 6714475(H2) → 26938e4(H3) → e230435(H4) → H5`。
- master 全程 `fc05530` 未改；正式目录/公钥/签名/自动批准机制未动。

### 21.2 H0–H5 完成情况

| 子阶段 | 交付 | 状态 |
| --- | --- | --- |
| H0 架构审计 | 依赖图/调用时序/宿主能力清单/安全边界+未解决风险（§16） | ✅ 只审计不重构 |
| H1 CommonJS 隔离 | 每 Provider 独立 QuickJS Context、allowlist require + 私有模块缓存、死循环 native 超时中断、内存/大小/模块数限制、取消/超时/异常分级、close 幂等（§17） | ✅ 基础能力；须看 21.6 限制 |
| H2 网络代理 | ProviderNetworkGateway 唯一出口、权限绑定 ID/版本/SHA/精确域名/HTTPS+端口/体积/超时/重定向/凭据、默认拒绝 SSRF/通配符/明文/裸IP/私网/凭据头/超限、SHA 变更失效（§18） | ✅ |
| H3 播放域名审查 | 真实 signedUrl 域名、DNS(Meta IP 段)、服务条款、API 文档取证；证据不足→保持拒绝 music.audiomack.com，不放宽白名单（§19） | ✅ DENIED |
| H4 播放闭环 | 8 步状态机 + 五级分栏；第4步域名授权拒绝后音频响应/播放器/真实解码/控制全 NOT_RUN；206 不冒充播放（§20） | ✅ 真实播放 NOT_RUN |
| H5 三端验收 | 见 21.4 | ✅（iOS 执行受环境限制，如实记录） |

### 21.3 实际测试命令与结果（全部真实执行）

- `./gradlew :provider:musicfree:desktopTest --rerun-tasks` → **47 tests, 0 failures, 0 skipped**。
- `./gradlew :provider:musicfree:testAndroidHostTest --rerun-tasks` → **47 tests, 0 failures, 0 skipped**。
- `./gradlew :provider:sourceupdate:desktopTest --rerun-tasks` → **157 tests, 0 failures**。
- `./gradlew :provider:lxruntime:desktopTest --rerun-tasks` → **101 tests, 0 failures**。
- 无跳过项（Desktop/Android host）；iOS 执行被 Gradle SKIPPED（见 21.4）。
- 失败与修复记录：H1 调试期 require 字符串封送/跨线程可见性问题、Kotlin/Native 无 `@Volatile`/无参 `toByteArray()`、H4 状态机断言——均已修复并最终全绿；过程不掩盖失败。

### 21.4 各平台编译/测试结果

| 平台 | 编译 | 测试执行 |
| --- | --- | --- |
| Desktop (JVM) | `compileKotlinDesktop` ✅ | `desktopTest` 47 ✅ |
| Android | `compileAndroidMain` ✅ | `testAndroidHostTest` 47 ✅（Android host/JVM；真机/设备测试`androidDeviceCheck`未运行——无设备） |
| iOS Simulator | `compileKotlinIosSimulatorArm64` + `compileTestKotlinIosSimulatorArm64` ✅ | `iosSimulatorArm64Test`/`linkDebugTestIosSimulatorArm64` **SKIPPED**（Windows 无 Xcode/模拟器，honest 记录；不以单平台代替） |
| Metadata | `compileCommonMainKotlinMetadata` ✅ | — |

### 21.5 网络域名及授权情况

- 授权：仅 `api.audiomack.com`（候选级精确域名，HTTPS/443，绑定脚本 SHA，来源 G3 静态审查）。
- 拒绝：`music.audiomack.com`（播放域名，H3 DENIED——证据不足/条款未明确/动态端点/地区未确认），网关层 `playbackUrlRejectionReason` 拒绝；**不放宽白名单、不用通配符、不绕过**。
- SSRF：私网/回环/链路本地/169.254.169.254 元数据/裸 IP/变体 IP/userinfo/编码 host/尾随点全拒（H2 测试实证）。
- DNS rebinding：commonMain 未做连接层 IP 绑定，**如实保留风险**。

### 21.6 沙箱实际隔离能力与遗留风险（不夸大）

已具备：每 Provider 独立 QuickJS native Context；require allowlist + 私有模块缓存；死循环 native 超时中断；内存上限（native Context 层）、脚本/模块/模块数上限；取消/超时/异常分级；close 释放；宿主无 fetch/XHR/WebSocket/文件/进程 binding。
遗留风险（如实）：
1. quickjs-kt 1.0.15 的 JS→Kotlin 字符串实参封送差异——**JS 内 require 完整往返未作通过项**，H1 只交付 allowlist + 隔离缓存 + 执行环境基础设施；
2. `maxStackSize` 在 commonMain 未暴露 → stack 上限未强制（`canSetStackSize=false`）；
3. 跨求值后台 timer 无法可靠实现（库无公开 job-queue 驱动 API）；
4. 官方插件依赖 Node 模块（axios/crypto-js/webdav），当前沙箱只承载 JSON 可序列化 exports，**插件生态执行未实现**；
5. DNS rebinding 未解决；
6. iOS 测试执行被环境跳过（仅交叉编译+测试源码编译通过）。

### 21.7 验证等级

- REAL_SEARCH ✅（G3 n=8 真实 OAuth 搜索）、REAL_TRACK_IDENTITY_VERIFIED ✅（id MATCH）、REAL_URL_RESOLVE ✅（G3 n=1）、
- REAL_AUDIO_RESPONSE：G3 独立只读探测 206（1 字节响应头），**但 H4 授权闭环内 NOT_RUN**，
- REAL_PLAYBACK：**NOT_RUN**（第4步域名授权拒绝；无真实播放器/解码/声音；206 与 Mock 均不冒充）。

### 21.8 是否具备进入阶段 I 的条件

**不具备（按当前红线维持）。** 阻塞项：①播放域名 `music.audiomack.com` 未获授权（H3 DENIED，需官方书面授权/明确 API 条款 + 代理恢复后复核重定向与有效期 + 地区确认）；②CommonJS 完整执行生态未实现（JS require 往返封送、Node 模块适配、跨求值 timer 限制）；③iOS 真机/模拟器测试未在本环境执行。
可继续的安全工作（不需放行）：补 H1 require 封送适配、接入更多"固定具名域名 + 明确条款"的合规来源、三端在具备环境的机器上补测。**不为完成阶段而放行域名、不设默认音源、不自动安装。**


---

## §22 阶段 I：MusicFree Provider CommonJS 兼容性与沙箱补齐（2026-11-20）

基线核对（任务书要求，不推测）：本地 HEAD=`9c80297`（H5），父链 `9c80297→e230435→26938e4→6714475→1e729bb→f0ba4e3→32176e5(G3)` 完整，工作区干净；**live ls-remote 显示远端 `work/android-community-discovery=9c80297`、master=`fc05530`**。因此"H 未推送"是历史时点描述，"远端停留 G3"是旧信息——上一轮用户授权后 H0-H5 已推送，远端现为 H5。本阶段按任务书"未经明确授权不推送"全程只本地提交。

提交链（本地，未推送）：`9c80297 → 6a99fc7(I0-I1) → 53eceb8(I2) → 1356b93(I3) → 4a441d9(I4) → ec8636a(I5)`。

### 22.1 I0：quickjs-kt 1.0.15 字符串封送独立取证（真实 QuickJS，非 Mock）

新增 `PhaseI0MarshallingTest`（desktop/androidHost 各 13 项全绿）。覆盖普通字符串、Unicode 中文、特殊字符、JSON 字符串、20KiB 大字符串、null/undefined、异常返回、多次调用、跨 Context 调用，另补 binding 返回值往返、asyncFunction 参数、ESM 模块内 binding 调用。

**实测结论（推翻 H1 记录）**：`runtimeType=kotlin.String, isString=5/5, equalsLiteral=5/5, rawMapHit=5/5, textMapHit=5/5`。同步/异步/ESM 三种路径的字符串实参均为真实 `kotlin.String`，`equals/hashCode` 正常、可直接作 Map key。**H1 §17.4 所述"包装类型差异致 equals/hashCode 不一致"在本轮当前版本所有已测路径均未复现**——判定为历史调试期问题（H1 最终代码已用 `toString()` 归一规避，本轮实测证明该归一在非必需情况下也无害）。唯一新发现：quickjs-kt 1.0.15 的 `addModule+import` 可能触发同一 ESM 模块多次求值（实测一次 import 调用 binding 2 次），已如实记录为库行为、测试不强求调用次数。

### 22.2 I1：CommonJS require 完整往返（真实加载，非 Kotlin 侧等价语义）

新增 `PhaseI1CommonJsTest`（各 9 项全绿）。**根因修复**：H1 用 `evaluate<String>(..., asModule = true)` 读取模块 exports，但该模式在 quickjs-kt 1.0.15 不回传求值结果，导致 `modulesByName` 值全为 null，JS 内 require 必然报"未预载"——这就是 H1 只敢验证 Kotlin 侧门禁、从不真实调用 JS require 的真正原因。I1 改为非 asModule 的 IIFE：每模块独立 `module/exports` 对象，结果写回 globalThis 再普通 evaluate 读回。

覆盖：module.exports / exports 别名 / require 完整往返并读取导出值 / 模块缓存（重复 require 同一对象）/ 受控相对路径 `lib/helper`（拒绝 `..` 与绝对路径）/ 未登记与路径穿越 fail-closed / 循环依赖不挂死 / 跨 Provider 缓存隔离。全部真实通过（循环依赖按"预载期空对象桩断开、不伪称 Node 完整语义"如实标注）。

### 22.3 I2：Node 模块安全子集（真实取证驱动，非凭空实现）

**真实需求取证**（此前 G2 台账只有集体描述、缺 per-plugin 清单）：经 7897 代理从 Gitee `v0.1/dist` 下载 6 个官方插件正文并静态扫描 require。SHA 核对结果——audiomack `1b1f49fc`、geciwang `b9535714`、navidrome `2f1e930e`、webdav `64e00a41`、youtube `75b8a113` 均与 G2 台账一致；**bilibili 取证 `3e49093f` ≠ 台账 `930e3258`（版本漂移），按 fail-closed 拒绝纳入**。真实依赖矩阵：audiomack=axios/cheerio/crypto-js/dayjs，bilibili 另需 he（已排除），geciwang=axios/cheerio，navidrome=axios/crypto-js，webdav=webdav，youtube=axios。红线扫描零会员绕过/eval/process/动态 require。

新增 `NodeCompatModules.kt`（每模块支持范围/不支持范围/安全限制的可审查清单）与 `NodeCompatShims.kt`（events/querystring/url/path/util/crypto/buffer/dayjs/he/cheerio 共 10 个纯 JS 安全子集）。文件系统/进程/原生网络/动态加载一律不提供。关键实现修正：SHA1 padding 长度字段为 **big-endian**（误用 MD5 的 little-endian 曾导致值错误），修复后 MD5('abc')=`90015098…`、HMAC-SHA1(RFC2202)=`de7c9b85…`、Base64=`3UdwPYFenLcwWyKXpH85mLmXAMw=` 全部与 Python hmac 实算一致。crypto/events/cheerio 等导出函数无法走 JSON 字符串往返，新增 `allowFunctionExports` 通道：审查模块在 JS Context 内以真实对象注册到 `__mfModules`，不经 JSON（模块仍 SHA 绑定、无宿主能力）。`PhaseI2NodeModulesTest` 各 10 项全绿。

### 22.4 I3：异步任务与 timer（宿主发信号，回调留 JS 侧）

新增 `executeAwait`（ESM 顶层 await + 结果写回 globalThis，与 LX DISPATCH 模板同模式）、`__mfDelay/__mfCancelTimer` async binding、`__mfModules` 之外注入 setTimeout/clearTimeout/setInterval/clearInterval（企业 64 个上限）。回调始终在 JS Context 执行，宿主只提供"延时到了"信号；close 取消信号 + cancel scope + join 等待宿主 Job 退出后再销毁 Context。`PhaseI3TimerTest` 各 8 项全绿，含加严断言：setInterval 实测重复 tick 2~10 次、超限 timer 返回 id=0 被拒。

**诚实限制不变**：quickjs-kt 1.0.15 的 async binding 只在 evaluate 期间被 pump，顶层 fire-and-forget setTimeout 在求值返回后不会运行——因此跨求值后台 timer 仍需 `executeAwait` 驱动，不伪称支持常驻心跳。

### 22.5 I4：沙箱边界复核（10 项 + 新攻击面）

新增 `PhaseI4SandboxBoundaryTest`（各 10 项全绿）：Context 独立、模块缓存隔离、require allowlist fail-closed、无 fs/process/fetch/XHR/WebSocket/eval/Function 能力、无原生网络 binding、函数导出表篡改不能获宿主能力、超时+内存+栈能力、lifecycle close 幂等并拒绝后续执行、深递归被中断。

**关键结论修正**：H0/H1 记"maxStackSize 在 commonMain 未暴露、未强制"。I4 用编译探针（`MaxStackProbe`，已删）在 Desktop + iOS Simulator **均编译通过**，证实公共 API `quickJs.maxStackSize` 实际存在。现真正启用并按读回值判定 `canSetStackSize`。实测 `I4_CAPS memory=true stack=true interrupt=true`——三项能力全部真实可用。这是对本阶段此前报告错误结论的明确更正。

### 22.6 I5：真实插件兼容性测试（真实正文 + Gateway 唯一出口）

新增 `MusicFreePluginCompatVerification.kt`（七级验证等级台账 + deny-all 权限 + 经 Gateway 的 axios 网络桥接）、`PhaseI5PluginCompatTest`（desktop/androidHost 各 5 项全绿）。5 个 SHA 匹配插件正文落盘为测试资源，`.gitattributes` 加 `-text` 规则防止 Windows checkout 换行转换破坏 SHA（已 `git check-attr` 验证 text=unset，blob 与工作区 SHA 一致）。

真实运行输出（非 Mock）：
- `I5_GECIWANG_INIT={"platform":"歌词网","keys":[...,"search","getLyric"],"ver":"0.0.0"}`
- `I5_AUDIOMACK_INIT={"platform":"Audiomack","keys":[...,"search","getMediaSource","getAlbumInfo",...]}`
- `I5_NAVIDROME_INIT={"platform":"Navidrome","keys":[...,"search","getMediaSource"]}`
- `I5_GECIWANG_SEARCH={"ok":false,"reason":"网络被 Gateway 拒绝：插件 mf-geciwang 尚无具名域名人工授权，网络默认拒绝（不放宽白名单）"}`

即：geciwang/audiomack/navidrome 三个真实插件在隔离沙箱内**初始化 + require 成功**（COMMONJS_REQUIRE_PASSED 实证）；geciwang 真实 `search('晴天',1,'lyric')` 触发 axios 请求，因 `zh.followlyrics.com` 无具名域名授权被 ProviderNetworkGateway 拒绝，executor 零触达（零真实网络）。**没有为兼容性放宽任何白名单**；axios 是唯一内建注入模块且唯一出口是 Gateway（`injectedModules` 显式登记、受控非绕过）。webdav/动态端点维持 deny-all。

### 22.7 CommonJS 兼容能力矩阵（阶段 I 最终交付 1）

| 能力 | 等级 | 证据 |
| --- | --- | --- |
| 字符串封送（同步/异步/ESM） | 正常，非问题 | I0 13×2 全绿，rawMapHit=5/5 |
| JS require 完整往返 | COMMONJS_REQUIRE_PASSED | I1 9×2 + I5 三插件真实初始化 |
| module.exports / exports | 通过 | I1 |
| 相对模块路径 | 通过（受控，拒绝 .. 与绝对） | I1 |
| 循环依赖 | 不挂死，空对象桩断开（非 Node 完整语义） | I1（如实标注） |
| 模块缓存 / 跨 Provider 隔离 | 通过 | I1 / I4 |
| 10 个 Node 子集模块 | 通过（RFC/Python 向量核对） | I2 10×2 |
| 函数导出模块 | 通过（独立 __mfModules 通道） | I2 / I5 |
| setTimeout/clearTimeout/setInterval/clearInterval | 求值期间通过 | I3 8×2（加严断言） |
| Promise/async-await | 通过（executeAwait 驱动） | I3 |
| 跨求值后台 timer | **未支持（库限制）** | I3 如实声明，需 executeAwait 驱动 |
| context/module/require/资源/超时/内存/栈边界 | 通过 | I4 10×2 |
| 真实插件初始化 + require | COMMONJS_REQUIRE_PASSED | I5 三插件真实正文 |
| 真实搜索 / URL / 音频 / 播放 | **NOT_RUN（域名未授权，零网络）** | I5 Gateway 拒绝；G3 结论不变 |

### 22.8 Node 模块支持清单（交付 2）

| 模块 | 支持 | 不支持 / 安全限制 |
| --- | --- | --- |
| events | on/emit/off/once/removeAllListeners | 无高水位/继承；纯内存 |
| querystring | parse/stringify 字符串键值 | 无嵌套/数组；有界长度 |
| url | URLSearchParams、parse 的 host/path/query | 无 WHATWG 全解析/file:；纯字符串 |
| path | posix join/basename/dirname/extname | 无 fs/绝对路径/win32 盘符 |
| util | format(%s/%d/%j)、inspect 极简 | 无 promisify/全量 inspect |
| crypto-js | md5/sha1/HmacSHA1 + Base64/Hex/Utf8 | 无 AES/RSA/PBKDF2/随机源/密钥托管 |
| buffer | from(string,enc).toString、byteLength | 无文件/网络 Buffer/allocUnsafe |
| dayjs | unix(ts).format('YYYY-MM-DD'...) UTC | 无时区库/相对时间/本地时钟 |
| he | HTML 实体 encode/decode（命名+数字） | 未知实体原样保留（fail-safe） |
| cheerio | load(sel).text/attr/find/eq/length 只读 | 无 DOM 修改/事件/选择器全兼容；不执行 HTML 脚本 |
| axios | GET(url,{params,headers}) 经 Gateway | 无 POST/PUT/DELETE/拦截器/代理/凭据；域名 SHA 绑定 deny-all |
| webdav | 登记但不启用 | 动态端点静态不可审 → deny-all，不放宽 SSRF |
| stream | 不提供（真实调用面未出现） | 按需再评估，不无条件实现完整 Node API |

### 22.9 三端测试与编译报告（交付 3/4）

- Desktop `desktopTest`：**101 项，0 失败 0 跳过**（含 I0 13 + I1 9 + I2 10 + I3 8 + I4 10 + I5 5 + 既有 G/H 回归）。
- Android host `testAndroidHostTest`：**101 项，0 失败 0 跳过**（同上；本地 JVM 产物替换 quickjs-kt-android→jvm）。
- iOS：`compileKotlinIosSimulatorArm64` + `compileTestKotlinIosSimulatorArm64` + `compileKotlinMetadata` 以 `--rerun-tasks` 全执行通过（32 任务）；**`iosSimulatorArm64Test` 未执行——Windows 无 Xcode/模拟器，只报告交叉编译与测试源码编译，不称 iOS 运行测试通过**。
- 全部安全测试用真实 QuickJS Context，非 Mock；网络相关用假执行器证明"拒绝零触达"，未放宽任何白名单。

### 22.10 未解决风险（交付 6，如实）

1. **真实播放仍 NOT_RUN**：`music.audiomack.com` 域名未授权（H3 DENIED），G3 音频 206 为独立只读探测，H4 授权闭环第 4 步即停；本阶段未触碰该红线。
2. 跨求值后台 timer 依赖库异步 pump 语义，常驻心跳/后台任务未支持（需 executeAwait 驱动或宿主调度设计）。
3. DNS rebinding 在 commonMain 未解决（连接层 IP 绑定缺失）。
4. iOS 运行测试未在本环境执行（仅交叉编译 + 测试源码编译）。
5. axios 内建注入模块对象在 JS 侧可被脚本改写（`__mfModules` 为 JS 对象），但写入的只是 JS 函数、无法获得宿主能力，且跨 Provider Context 隔离；该边界如实记录，不标为"完全不可篡改"。
6. bilibili 插件版本漂移（SHA 不符台账）已排除；webdav/动态端点维持 deny-all；官方 Node 生态（axios 真实依赖 http/crypto-js 完整实现）仅覆盖真实调用面子集，非完整兼容。
7. 未发生任何真实音频下载/播放、未设默认音源、未自动安装、未放行付费墙、未推送任何分支。

### 22.11 是否建议进入统一 Provider 调度阶段

**不建议（按任务书门槛）**。CommonJS 基本能力（I0-I2）、隔离（I4）、真实插件初始化/require（I5）已达到要求，但"真实插件测试"的搜索/URL/音频/播放四关因域名未授权仍为 NOT_RUN（I5 Gateway 拒绝实证），且跨求值 timer / DNS rebinding / iOS 运行测试三项限制未解决。建议下一步先取得"固定具名域名 + 明确条款 + SHA 绑定"的播放授权，并在有 Xcode 的环境补 iOS 运行测试，再评估统一调度。**不为完成阶段而放行域名、不设默认、不自动安装、不推送。**



---

## §23 阶段 J0：ProviderNetworkGateway 网络出口整改（2026-11-20）

基线：本地与远端 work 分支均 `3f0d758`（I 最终），master=`fc05530`；ls-remote 确认远端未前进，工作区干净。按任务书只改工作分支、不碰 master、不 reset/强推、本阶段只本地提交。

### 23.1 整改前后差异（Gateway）

| 项 | 整改前（H2/I5） | 整改后（J0） |
| --- | --- | --- |
| executor 契约 | `suspend (Request)->Response`，body 是整块 String | `NetworkExecutor` 返回 `RawResponse`（status/headers/**BodyStream**），3xx 原样上交 |
| 重定向 | `enforceRedirect()` 只返回策略判定、发哨兵 `Response(0,"")`，**不真正发下一跳**；底层 client 默认自动跟随 | 重定向控制内聚到 `get()`：底层 `asProviderNetworkExecutor()` 用 `config{followRedirects=false}` 关闭自动跟随；3xx 先 cancel body，DENY_ALL 直接拒，逐跳模式每跳重新执行协议/域名/端口/方法/凭据/策略/SHA 完整校验 + 次数上限(0..8) |
| 响应限额 | executor 返回后 `encodeToByteArray().size` 事后检查（整块已在内存） | Content-Length 预检（超限 0 读取即拒）+ BodyStream 流式累计，超限立即 `cancel()` 释放；不无限缓存 |
| 超时/取消 | 无请求级超时；取消靠 executor | `withTimeout(requestTimeoutMs)` → TimeoutCancellationException 转 Failed；CancellationException 原样上抛不吞 |
| SHA 绑定 | `gatewayBridge(currentSha:String)` 由调用方传固定字符串，可与真实脚本脱钩 | `gatewayBridge(scriptBytes:ByteArray)` 每次现场 `Sha256.hex(实际字节)`；权限同时绑定 providerId/版本/SHA/域名/方法/端口/体积/超时/重定向策略，传固定常量冒充即拒 |
| axios 参数 | 手写字符游标扫 params、正则抽 headers、手写 JSON 拼接 | kotlinx.serialization `parseToJsonElement` 解析；UTF-8 form 编码（未保留字符原样/空格→+/其余 %HH）；正确处理已有 query（? / & / 空）、headers、contentType 语义；异常一律抛出 |

### 23.2 DNS 与连接安全审查（J0 2.5，如实）

- Android：`HttpClient(OkHttp)` + 显式 Dispatcher/ConnectionPool；Desktop 同为 OkHttp；iOS 为 Darwin。三者都在 `installProviderClientDefaults()` 装 HttpTimeout（connect 10s / request 30s / socket 30s）。
- **DNS 解析结果与实际连接目标的一致性校验/绑定：三端均未实现**。commonMain 的 URL 层策略（域名白名单、裸 IP/私网/元数据拒绝、userinfo/编码 host 拒绝）只在**字符串层**生效；连接层由引擎做 DNS，无法保证解析结果不被 DNS rebinding 替换。
- Android OkHttp 可通过 `Dns` + 自定义 `ConnectionSpec`/`SSLSocketFactory` 做 IP 固定，Desktop 同理，iOS Darwin 需 `NSURLSession` 层 hook——均需平台专属实现，**本阶段未实现，明确记为未解决风险**，不标为通过。
- 重定向层面已消除"通过 3xx 切到未登记域名"的字符串层风险（J0 23.1），但 DNS rebinding 属连接层，与其正交、仍保留。

### 23.3 J0 测试与证据

新增 `PhaseJ0GatewayTest`（14 项，desktop/androidHost 各全绿）：
- 重定向：DENY_ALL 跨域只请求首跳（证明 executor 未先行跟随）、逐跳拒跨域/私网/裸 IP、循环重定向被上限拦截（首跳+2 跟随=3 次后停）、同域逐跳跟随成功。
- 流式限额：Content-Length 预检 0 读取即 cancel、流式累计超限立即 cancel、正常与截断响应语义。
- 超时/取消：executor 不返回→Failed(超时)、CancellationException 原样上抛。
- 编码：中文/空格/&/+/%/引号/emoji 的 UTF-8 form 向量、已有 query 追加、桥接保留 status/data/contentType 且 URL 编码正确。
- 权限绑定：现场字节 SHA 一致放行、固定 SHA 冒充被拒、未授权方法/凭据头被拒。

修复 I4 遗留测试问题（任务书 J7 要求）：`i4_noFileSystemOrProcessCapabilities` 原循环未真正执行探测表达式，现逐条执行并断言；如实记录 QuickJS 内置 `eval/Function` 为引擎语言能力（沙箱未主动暴露包装，但无法从全局移除），并验证 eval 内仍无 process 等宿主能力。

H2 测试适配新契约（保留全部原断言语义，重定向用例改为经 get() 真实验证 3xx）；I5 测试 executor 构造改 `fromStringExecutor`，SHA 改现场字节计算（不再硬编码常量）。

验证：desktopTest **115 项 0 失败**（101 旧 + 14 J0）、testAndroidHostTest **115 项 0 失败**；iOS `compileKotlinIosSimulatorArm64` + `compileTestKotlinIosSimulatorArm64` 以 `--rerun-tasks` 通过，`iosSimulatorArm64Test` 未执行（Windows 无 Xcode/模拟器，NOT_RUN）。

### 23.4 结论

J0 五项整改（重定向/流式限额/axios 参数/SHA 绑定/DNS 审查）中前四项已实现并有真实测试证据；DNS→连接的 IP 绑定三端未实现，如实列为未解决风险。本阶段未接入真实 Ktor client 到生产 Provider（那是 J1），未做真实网络请求，不把 HTTP 206/初始化冒充搜索或播放。未推送。



---

## §24 阶段 J1（收窄范围）：Gateway 共用网络底座真实验证（2026-11-20）

用户收窄 J1 范围：不批量接入插件，优先验证所有 MusicFree 来源共用的网络底座——Gateway 的重定向与流式限额。基线：本地与远端 work 分支均 `3f0d758`(I 最终)，J0 提交 `50f1871` 本地在、未推；master=`fc05530`。本阶段只本地提交。

### 24.1 为什么要补这一层

J0 的 14 项 Gateway 测试**全部使用假执行器**（`fromStringExecutor` / 自定义 `NetworkExecutor`），新增的 `asProviderNetworkExecutor()`（Ktor OkHttp + `config{followRedirects=false}`、body 包 `BodyStream`）**从未被真实执行过**。任务书 J0 2.1 明确要求"必须证明底层 executor 确实没有先行跟随"——假执行器无法给出该证据。本阶段用**真实 socket HTTP server（`com.sun.net.httpserver`）+ 真实 Ktor OkHttp client**补上。

### 24.2 诚实分层（不为测试放宽生产策略）

`Gateway.get()` 的生产策略禁止明文 HTTP + 裸 IP + 私网（CandidateNetworkPolicy / staticUrlRejection），完整 get() 链路**无法**打本地 127.0.0.1 server；为测试放宽即违反红线。因此分两层：
- **executor 契约层（真实网络）**：直接调 `asProviderNetworkExecutor().execute()`，验证 3xx 原样上交、Location 原样上报、真实 Content-Length 头、真实 body 流式读取、cancel 释放。
- **Gateway 决策层（真实数据 + 受控执行器）**：把从真实 wire 抓到的 302/Location、Content-Length、body 字节喂给 `get()`（合规域名 api.example.com），验证 DENY_ALL 拒跨域、Content-Length 预检 0 读取、无 Content-Length 时流式累计超限并 cancel。
- 两层均非 Mock：请求真发到 socket server、响应真来自 wire。

### 24.3 对照实验铁证（底层确实不先行跟随）

`j1_realExecutorDoesNotFollowRedirect`：同一 302 server（`/start`→302 Location `/final`，`/final`→200 "FINAL_BODY"）——
- 对照 A：默认 `HttpClient(OkHttp){}` 自动跟随，得到 200 "FINAL_BODY"，`/final` 命中 1 次；
- 实验 B：`asProviderNetworkExecutor()` 包装后得到原始 **302**、Location=`/final`，**`/final` 命中 0 次**。
这直接证明 `followRedirects=false` 真实生效、底层没有先行跟随（不是"调用 enforceRedirect 就宣称保护"）。

### 24.4 发现并修复的真实缺口

`j1_realStreamBodyCancellationReleasesResource` 实测发现：Ktor `channel.cancel(null)` 后再次 `readAvailable` 仍返回已缓冲数据（实测 256B），并未立即停止产出——`cancel()` 未兑现"取消立即生效、不再产出 body"的契约。修复 `ProviderNetworkGatewayKtor.kt`：BodyStream 加 `cancelled` 标志，`cancel()` 置位并取消底层 channel，`read()` 置位后直接返回 -1、异常按 EOF 处理（不把取消异常伪装成 body 数据）。修复后 5 项全绿。注：`@Volatile` 因 KMP commonMain/iOS 兼容性未使用（cancel/read 为顺序调用）。

### 24.5 测试与验证

新增 `PhaseJ1RealNetworkBaseTest`（desktopTest/androidHostTest 各 5 项）：真实重定向对照、真实 Location 驱动 Gateway 拒跨域、真实 Content-Length 上报与流式读取、真实 wire 响应驱动 Gateway 预检+流式限额、cancel 释放与连接复用。构建文件为两个 JVM 测试源集加 `ktor-client-okhttp`（真实引擎，非 Mock）。

全量回归：desktopTest **120 项 0 失败**（J0 115 + J1 5）、testAndroidHostTest **120 项 0 失败**；iOS `compileKotlinIosSimulatorArm64` + `compileTestKotlinIosSimulatorArm64` 以 `--rerun-tasks` 通过，`compileKotlinMetadata` SKIPPED（本模块无 metadata target），`iosSimulatorArm64Test` 未执行（Windows 无 Xcode/模拟器，NOT_RUN）。

### 24.6 结论与未解决

共用网络底座的重定向（不先行跟随 + 逐跳策略 + 上限）与流式限额（Content-Length 预检 + 流式累计 + cancel）已有**真实网络证据**，并修复了一个真实的 cancel 契约缺口。仍未解决：DNS→连接的 IP 绑定（DNS rebinding，三端未实现，J0 §23 已记）；iOS 运行测试 NOT_RUN；本阶段未接入任何插件、未做真实搜索/URL/音频/播放（不把底座验证冒充搜索或播放）。未推送。

