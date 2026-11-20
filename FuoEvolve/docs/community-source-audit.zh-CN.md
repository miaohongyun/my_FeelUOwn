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
