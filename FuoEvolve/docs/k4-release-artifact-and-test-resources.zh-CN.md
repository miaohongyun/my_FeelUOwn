# 阶段 K4：正式发布制品与真实验收资源落实

> 阶段唯一目标：把 K2 开发完成度转化为可交付的 Android Release APK，并取得解除 K3 NOT_RUN 所需的设备、网络、人员资源。
> 核心纪律：**开发完成度 ≠ 交付完成度**。没有实际取得的资源不得标记 PASS；购买计划、口头承诺、文档清单不等于资源到位。不伪造、不用 Debug 冒充 Release、不用模拟器/编译/HTTP 206 冒充真实播放。
> 执行时间：2026-11-21 12:35 GMT+8。

---

## 0. 冻结基线核验（K4.0）

| 项目 | 实测值 | 结论 |
|---|---|---|
| 工作分支 | `work/android-community-discovery` | PASS |
| 本地 HEAD | `03e483906435a5a49d07f5109ffee6508b3cdecf`（K3） | PASS |
| 远端 work | 已核对与本地一致（本轮推送前） | PASS |
| K3 提交 `03e4839` | `git cat-file -t` = commit | PASS |
| K2 冻结代码 `865918c` | `git cat-file -t` = commit | PASS |
| master | `fc05530c09966dedacb413168a1dc6fda0d3afe1`（未动） | PASS |
| 工作区 | `git status --porcelain` = 0 行（干净） | PASS |
| K2/K3 报告 | 两份文档均在 `docs/` 且内容完整 | PASS |

**冻结纪律**：K2 代码 SHA 未修改；本阶段除文档外无业务代码改动，故不存在"新构建冒充原始 K2 产物"的风险。K3 的 Debug APK（`versionName=d901958-dirty`）仍只是工程诊断件，不是交付凭证。

---

## 1. 正式 Release 制品（K4.1）

### 1.1 签名环境 — BLOCKED

| 检查项 | 实测结果 |
|---|---|
| `FUO_SIGNING_STORE_FILE` | UNSET |
| `FUO_SIGNING_STORE_PASSWORD` | UNSET |
| `FUO_SIGNING_KEY_ALIAS` | UNSET |
| `FUO_SIGNING_KEY_PASSWORD` | UNSET |
| 项目内 `*.jks` / `*.keystore` / `*signing*.p12` | 无 |
| `local.properties` | 不存在（签名值另一来源也缺） |
| `gradle.properties` 签名项 | 无 |

签名接线本身**已就绪且正确**：`androidApp/build.gradle.kts:49-58,91-118` 通过 `signingValue()` 从环境变量或 `local.properties` 读取四项，四项齐备才创建 `fuo` signingConfig 并挂到 `release` buildType；缺一则 `hasFuoSigningConfig=false`，release 不挂签名（不会误签，也不会崩溃）。

**缺的是密钥本身**。按任务书 §3.1：正式密钥必须由项目所有者控制、仅本地使用、不上传/不提交/不写入日志/不硬编码。当前无人值守环境无法安全取得项目所有者的正式 keystore，因此 `RELEASE_SIGNING = BLOCKED`。**不自行生成密钥后宣称正式签名完成**。

### 1.2 Release 构建 — BLOCKED

依赖 §1.1 的正式签名。无正式密钥时构建出的 release 包将是无签名 APK，不算"正式签名 Release 安装包"，故不执行、不以之充当交付制品。构建环境可供所有者复现：

| 项 | 值 |
|---|---|
| JDK | 25（`/e/mypro/.tools/jdk-25.0.5+8`；bash PATH 中无 `java`，需指定 `JAVA_HOME`） |
| Gradle | 9.5.0（wrapper `distributionUrl`） |
| Android Gradle Plugin | 9.1.1（`gradle/libs.versions.toml`） |
| applicationId / minSdk / targetSdk | `org.feeluown.mobile` / 24 / 35（`build.gradle.kts:63-70`） |

### 1.3 制品验证与可复现性 — NOT_RUN / 部分就绪

无 Release APK，故 `apksigner verify`、证书指纹、`aapt dump badging`、DEX/ZIP 检查对**正式制品**均未执行。

对既有 **Debug** APK 的完整文件哈希核验在 K3 已四重完成：36,861,777 字节，SHA256 `22c80fb2aac84b64aabf7b559c2a2e4e1a07fe4fa2788496658d4af70bf65b1a`（流式/整读/`sha256sum` 一致），签名者 `CN=Android Debug`。**该结论仅适用于 Debug 件，不能继承给任何未来 Release 制品。**

可追溯机制已具备：Git SHA + 工作区 clean + versionCode/versionName 由 git 派生 + 证书指纹可在签名后读取。字节级可复现构建不作宣称（环境差异未验证），但源码、配置、签名身份三者可追溯。

---

## 2. 真实验收资源（K4.2–K4.6）

| 资源 | 实测 | 结论 |
|---|---|---|
| Android 实体机 | `adb devices -l` 可运行（`/d/Android/SDK/platform-tools/adb`），但设备列表为空 | BLOCKED |
| 家庭宽带 Wi-Fi | 本机仅以太网 `192.168.6.12`、网关/DNS `192.168.6.1`（公司内网），无家庭路由器、无 WLAN 接口 | BLOCKED |
| 大陆 SIM / 移动数据 | 无真机、无 SIM | BLOCKED |
| 人工听音人员 | 无人值守 CLI 环境，无真人 | BLOCKED |
| 非开发者验收人员 | 无真人 | BLOCKED |
| Release APK 安装 | 无 Release APK、无真机 | BLOCKED |
| 首次启动/权限/默认配置/升级安装验证 | 依赖上述资源 | BLOCKED |

这些是物理设备、运营商网络与自然人，**无法由代码或文档补齐**，也不接受"计划/订单/承诺"替代。

---

## 3. 工程安全回归（K4.8）— PASS

本阶段无业务代码改动（HEAD 仍 `03e4839`、工作区干净），故不重复 2000 项历史测试。冻结代码的安全边界未变，引用 K2 已验证结论并确认未被本阶段触碰：

- 下载许可默认拒绝未知许可；Jamendo BY-NC-ND 逐曲许可（playLicense=true / downloadLicense=false）未改（`canDownloadTrack` 门控仍在）。
- `ProviderNetworkGateway` 仍为沙箱脚本唯一网络出口；SSRF/私网裸 IP/凭据拒绝、HTTPS/443、逐跳重定向默认 DENY_ALL、流式限额未放宽。
- TLS 校验未关闭；无新增通配符授权、未经审核音源或默认音源策略变更；无凭据/私钥进入仓库（§1.1 已扫描确认）。
- Windows 包仍因缺 cargo 为 BLOCKED（不阻塞 Android 主线）。

---

## 4. K3 立即启动条件（K4.7）— K3_READY = BLOCKED

| 条件 | 状态 |
|---|---|
| Release APK 正式签名验证 PASS | BLOCKED（无正式密钥） |
| APK 身份完整 SHA256 与源码 SHA 绑定 | BLOCKED（无新 Release 制品） |
| Android 真机 adb 可见并能读设备信息 | BLOCKED |
| 家庭 Wi-Fi 真实连接成功 | BLOCKED |
| SIM 移动数据可用 | BLOCKED |
| 人工听音人员已落实 | BLOCKED |
| 普通用户已落实 | BLOCKED |
| Release APK 实际安装成功 | BLOCKED |

**全部 8 项缺失**。按任务书 §9，任何一项缺失即 `K3_READY=BLOCKED`，不得把计划、承诺、购买订单算作资源到位。

---

## 5. 十二问最终结论

| # | 问题 | 结论 |
|---|---|---|
| 1 | 正式 Release 是否成功构建？ | BLOCKED（无正式签名密钥） |
| 2 | 正式签名是否验证通过？ | BLOCKED（同上） |
| 3 | APK 是否来自干净固定 SHA？ | BLOCKED（工程侧就绪；无新 Release 产物，旧 Debug 为 dirty 构建） |
| 4 | APK SHA256 是否对应完整文件？ | PASS（仅针对 K2 Debug 件四重核验；不代表 Release） |
| 5 | Android 实体机是否到位？ | BLOCKED |
| 6 | 家庭 Wi-Fi 是否到位？ | BLOCKED |
| 7 | 大陆 SIM 是否到位？ | BLOCKED |
| 8 | 人工听音人员是否到位？ | BLOCKED |
| 9 | 普通用户是否到位？ | BLOCKED |
| 10 | Release APK 是否实际安装成功？ | BLOCKED |
| 11 | 是否具备立即执行 K3 的条件？ | BLOCKED（K3_READY=BLOCKED） |

---

## 6. K4 完成判定

**K4 = 部分完成 / BLOCKED。** 开发完成度（K2）与交付完成度是两件事，本报告不混为一谈：冻结基线、签名接线、安全边界、Debug 件哈希核验已到位；但正式签名、正式构建、真机、家庭宽带、SIM、真人听音与独立验收**全部未取得**。按任务书 §13，未满足任一条件即不得宣称完成；按 §14，资源缺失时**停止在明确 BLOCKED 状态**，不继续写模拟测试或重复准备文档。

### 解除 BLOCKED 的最小条件（需项目所有者提供）

1. 正式 keystore + 四项 `FUO_SIGNING_*`（本地安全环境设置，密钥不进仓库/聊天/日志）。
2. 一台开启 USB 调试的实体 Android 手机，可读 `ro.product.model` / `ro.build.version.release` / `ro.product.cpu.abi`。
3. 一条真实家庭宽带 Wi-Fi（非公司内网、非 VPN/代理/开发机转发）。
4. 一张大陆运营商 SIM，可关闭 Wi-Fi 仅用移动数据。
5. 至少一名人工听音人员 + 至少一名非开发者验收人员。

上述齐备后，从 `865918c`（或经正式变更纪律生成的新候选 SHA）构建并签名 Release，读取完整 SHA256/证书指纹，绑定确切制品直接进入 K3.2–K3.7，不再增加准备阶段。旧 APK 的验收证据不自动继承给新制品。

---

## 7. 本阶段改动清单

- 新增本文档 `docs/k4-release-artifact-and-test-resources.zh-CN.md`；更新文档索引、中文 README、发布候选清单、roadmap。
- **无业务代码改动**，未修改 master，未改写历史，未生成/提交任何密钥，未伪造任何 PASS。
