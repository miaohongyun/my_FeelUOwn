# 阶段 K3：冻结版本真实设备与家庭环境验收

> 阶段唯一目标：用 K2 冻结版本，在真实 Android 手机 + 真实家庭 Wi-Fi + 中国大陆移动数据下，完成"普通用户打开 App → 搜索 → 手机扬声器真实出声"的完整验收。
> 核心纪律：本阶段只认真实证据；没有设备/网络/听音人员就如实 NOT_RUN，不写模拟测试、不推断、不涂绿、不降标准。
> 执行时间：2026-11-21 11:41–11:50 GMT+8。

---

## 0. 冻结基线与变更纪律（K3.0 第一份记录，完整 SHA）

| 项目 | 完整 SHA / 值 |
|---|---|
| 当前分支 | `work/android-community-discovery` |
| 当前 HEAD（K2 文档提交） | `f2199da2c7cae538d237f817214efe4896c2dd86` |
| 父提交 = K2 冻结代码基线 | `865918c8083e40e3e3c4c29bee9529f239a810cd` |
| K2 基线之父 | `d9019584352e0aa6ab5cf4e5dad4c4ecca619e0a` |
| master | `fc05530c09966dedacb413168a1dc6fda0d3afe1`（未动） |
| 远端 work | `f2199da2c7cae538d237f817214efe4896c2dd86`（与本地一致，含 K2 最终提交） |
| 工作区 | 干净 |
| K2 文档存在 | `docs/k2-product-readiness-audit.zh-CN.md`、`docs/release-candidate-checklist.zh-CN.md` 均在 |

K3 默认不修改业务代码。本阶段**无任何代码改动**——因为前置资源门禁未通过，未进入真实测试，也就没有真实设备/网络问题需要修复。所有验证维持 NOT_RUN/BLOCKED。

---

## 1. K3.0 APK SHA256 严谨核验（用户特别要求 #1）

针对上一轮口头总结中"29 字节算出的 SHA256"这一**表述错误**的更正与严谨复核：

**该表述是口误，K2 报告文档本身一直写的是正确的完整文件大小与哈希。** 本阶段用四种独立方法证明哈希对应完整 APK 文件：

| 方法 | 结果 |
|---|---|
| 文件大小 `os.path.getsize` | **36,861,777 字节**（35.15 MiB） |
| 方法 1：Python 流式分块 hashlib（逐 1MB 读完整文件） | 实际读取 **36,861,777 字节**（与文件大小一致）；SHA256 = `22c80fb2aac84b64aabf7b559c2a2e4e1a07fe4fa2788496658d4af70bf65b1a` |
| 方法 2：一次性整读 hashlib | `22c80fb2aac84b64aabf7b559c2a2e4e1a07fe4fa2788496658d4af70bf65b1a`（与方法 1 一致） |
| 方法 3：`sha256sum` 命令行 | `22c80fb2aac84b64aabf7b559c2a2e4e1a07fe4fa2788496658d4af70bf65b1a`（与两方法一致） |
| 对照证据：仅前 **29 字节** 的 SHA256 | `5428f4e1f7d7df12511a880060a41f40ca555417bd3f4e7b87a146a7696714ba` —— **与完整文件哈希不同**，直接证明报告中的哈希不是"29 字节"得出的 |

**APK 结构与身份**：
- ZIP 结构有效（`testzip` 无坏条目），519 个条目；含 `classes.dex`、`AndroidManifest.xml`、`resources.arsc`、`META-INF/`。
- aapt 读取：`package name='org.feeluown.mobile'`，`versionCode='36'`，`versionName='d901958-dirty'`，`minSdk=24`，`targetSdk=35`。
  - 诚实说明：`versionName` 带 `-dirty` 是因为 APK 在 K2 代码提交（865918c）之前、工作区有未提交改动时构建；这反映构建时的 git 状态，不改变 APK 内容哈希。K3 不重新构建 DEBUG APK 冒充不同版本。
- apksigner 验证（JAVA_HOME 修正为 Temurin 25 后）：`Signer #1 CN=Android Debug`，证书 SHA-256 `971df9ab8144be255c1bd9c48b7bae340b227dfe7ef0e7d70973c0f33b5e325f`。

**结论**：APK SHA256 `22c80fb2…65b1a` 经四重独立方法确认对应**完整 36,861,777 字节 APK 文件**；且签名者是 Android Debug 调试证书——明确**不是**正式 Release 签名版本。

## 2. K3.0 Release 签名门禁（用户特别要求 #2）

Release 签名作为**独立门禁**处理，Debug APK 仅用于工程诊断，绝不冒充家庭交付版本。

- 签名工具：`apksigner` 在 `D:\Android\SDK\build-tools\{34,36,36.1}` 可用；aapt/aapt2 可用。
- Release 签名环境变量：`FUO_SIGNING_STORE_FILE`、`FUO_SIGNING_STORE_PASSWORD`、`FUO_SIGNING_KEY_ALIAS`、`FUO_SIGNING_KEY_PASSWORD` **全部 UNSET**。
- 仓库内/本地签名密钥文件（`.jks`/`.keystore`，排除 build）：**无**。
- `androidApp/build.gradle.kts:49-115`：release 签名依赖上述环境变量，未配置时回退 debug 签名。

**判定：`RELEASE_SIGNING = BLOCKED`。** 本环境无法取得正式签名环境。按纪律：
- 我**不生成自签名密钥冒充"正式签名"**（那会伪造交付版本身份）。
- 我**不上传、不提交、不写日志**任何密钥材料。
- Debug APK 可用于设备兼容性/工程诊断，但**不能证明正式 Release 构建通过**，因此**不得宣布普通家庭正式交付验收通过**。

## 3. K3.0 Windows 安装包

- `cargo`/`rustc`：`command not found`，工具链仍缺失。
- 判定：`WINDOWS_INSTALLER = BLOCKED`（与 K2 一致）。按任务书，不让 Windows 阻塞 Android 核心验收；本阶段不构建 Windows 包，不以静态审查代替。
- Desktop Kotlin/JVM 编译在 K2/K3 回归中为 PASS（见 §8）。

## 4. K3.1 真实验收资源到位（第一道硬门禁）

实际探测（非推断）：

| 资源 | 探测方法 | 结果 |
|---|---|---|
| Android 实体手机 | `adb devices -l` → 空；`adb shell getprop ro.product.model` → `no devices/emulators found` | **缺失** |
| 家庭宽带 Wi-Fi | 本机 `192.168.6.12`、网关/DNS `192.168.6.1`、DNS 后缀 `dev.internal` = **公司内网** | **缺失** |
| 中国大陆 SIM / 移动数据 | 依赖实体 Android 设备（已缺） | **缺失** |
| 人工听音人员 | 无人值守 CLI 环境，无测试人员在场 | **缺失** |
| 可安装 APK | Debug APK 身份已核验（§1），但非 Release（§2） | 部分就绪 |
| USB 调试 + adb | adb 可用（v1.0.41 / 37.0.0），但无设备连接 | 部分就绪 |

**判定：关键真机/网络/听音资源全缺。** 按任务书，K3.2–K3.7 全部维持 NOT_RUN，不制造模拟验收结果。允许完成的安装包/工具链准备已完成（§1–§3）。**不宣布 K3 通过。**

---

## 5. K3.2–K3.7 真机/网络/用户验收：全部 NOT_RUN

因 §4 资源门禁未通过，以下阶段**未执行、未模拟、未推断**，逐项标记：

| 阶段 | 状态 | 缺什么 |
|---|---|---|
| K3.2 Android 真机正式播放（≥2 曲 × ≥60s × ≥3 轮、暂停/切歌/焦点/耳机/蓝牙/网络恢复、人工听音） | **NOT_RUN** | 无真机、无听音人员、无 Release APK |
| K3.3 家庭 Wi-Fi 直连（两时段 × ≥3 曲、DNS/TCP/TLS/API/resolve/音频/播放） | **NOT_RUN** | 无家庭宽带（仅公司内网）、无真机 |
| K3.4 移动数据直连（关闭 Wi-Fi、≥2 曲、两轮） | **NOT_RUN** | 无真机、无 SIM |
| K3.5 曲目 ID 关联 / 声学身份 / 下载门控真机回归 | **NOT_RUN**（下载门控有 K2 自动化测试 PASS，见 §8） | 无真机、无声学材料 |
| K3.6 真实搜索稳定性（≥3 关键词 × ≥10 次 Android 端） | **NOT_RUN**（K1.1 内网 Windows 观测数据不得冒充 Android 家庭网络） | 无真机/家庭网络 |
| K3.7 普通用户独立使用验收（非开发者参与） | **NOT_RUN** | 无真机、无测试人员 |

**REAL_PLAYBACK_AUDIO_OUTPUT、ACOUSTIC_IDENTITY_VERIFIED、CN_HOME_BROADBAND_DIRECT、CN_ANDROID_MOBILE_DIRECT、NORMAL_USER_ACCEPTANCE 一律 NOT_RUN。** 玩家状态、position 增长、HTTP 206、解码成功均未被当作真实声音证据。

---

## 6. 统一验证等级（逐项，不合并）

| 验证等级 | 结果 | 依据 |
|---|---|---|
| BUILD_PASS | **PASS** | K2/K3 `:androidApp:assembleDebug` 真实成功，APK 见 §1 |
| RELEASE_SIGNING_PASS | **BLOCKED** | 无签名密钥/环境变量（§2） |
| REAL_SEARCH（Android 正式 App） | NOT_RUN | 无真机 |
| REAL_RESULT_MAPPING | NOT_RUN | 无真机 |
| REAL_URL_RESOLVE | NOT_RUN | 无真机 |
| REAL_AUDIO_RESPONSE | NOT_RUN | 无真机 |
| REAL_PLAYBACK_DECODE（Android） | NOT_RUN | 无真机（Desktop 无声解码是 K1 证据，不冒充 Android） |
| REAL_PLAYBACK_AUDIO_OUTPUT | NOT_RUN | 无真机/听音人员 |
| REAL_TRACK_IDENTITY_VERIFIED | NOT_RUN（Android） | 无真机 |
| ACOUSTIC_IDENTITY_VERIFIED | NOT_RUN | 无声学材料 |
| CN_HOME_BROADBAND_DIRECT | NOT_RUN | 公司内网，非家庭宽带 |
| CN_ANDROID_MOBILE_DIRECT | NOT_RUN | 无 SIM/真机 |
| SEARCH_EMPTY_RESULT_STABILITY | NOT_RUN（Android 端） | K1.1 内网数据不作 Android 家庭网络结论 |
| NORMAL_USER_ACCEPTANCE | NOT_RUN | 无测试人员 |

Debug 与 Release 分开统计；Android 真机与 Desktop 分开统计；代理结果未计入任何直连等级。

---

## 7. 阶段判定：**K3 未完成（关键项全部 NOT_RUN/BLOCKED）**

按任务书十三节，12 个通过条件中：正式 Release 签名 BLOCKED、Android 真机播放/人工听音/家庭 Wi-Fi/移动数据/声学身份/普通用户验收全部 NOT_RUN。**没有任何一项关键真机或网络证据成立**，因此不得宣布"中国大陆家庭开箱即用达标"。

本阶段的诚实结论：**发布候选版本在开发侧已就绪（K2，2008 项自动化测试 0 失败、Debug APK SHA 可追溯），但"普通人真的能用"这个问题，在没有真机、家庭宽带、SIM 和听音人员之前无法回答，也不被伪造。**

---

## 8. K3.8 回归与安全门禁（冻结基线健康确认）

- 回归（K3 对冻结 HEAD 实测）：
  - 核心模块 desktopTest（core:model/download/search/playback/jamendo/lxruntime/sourceupdate/musicfree/runtime/providercatalog/shared）：BUILD SUCCESSFUL，汇总 **1049 项 0 失败 / 0 错误 / 0 跳过**。
  - `:androidApp:testDebugUnitTest` + `:core:model:compileKotlinIosSimulatorArm64` + `:shared:compileKotlinIosSimulatorArm64`：BUILD SUCCESSFUL（Android App 单测 34 项 0 失败；iOS 主代码编译 PASS）。
  - K2 全量基线：desktop 1049 + androidHost 925 + App 34 = 2008 项 0 失败；本阶段因无代码改动，未重复 androidHost 全量（沿用 K2 冻结基线证据）。
- 下载门控回归：`canDownloadTrack` 仍为严格 `track.license?.downloadLicense == true`，生产绑定已接入（`DownloadFeatureFactory.kt:79`），未被临时放开。
- 安全门禁（只读扫描 + 代码审查）：
  - 源码真实凭据/Token/私钥：0 命中。
  - 生产通配符域名放行 / 关闭 TLS 校验：0 命中。
  - 未新增音源、未改默认策略（Audiomack 仍非默认、不抢默认顺序）、未放宽 SSRF/域名/重定向、未把未知许可变允许下载、未把 NOT_RUN 改 PASS。
- 本阶段**无代码改动**，故无新 SHA、无需重新构建、无需对真机项重测（真机项本就 NOT_RUN）。

## 9. 解除 K3 NOT_RUN 的最小条件（交接清单）

1. 正式 Android Release 签名环境（本地安全密钥 + `FUO_SIGNING_*` 环境变量），产出可验证签名的 Release APK 及 SHA256。
2. 一台开启 USB 调试的实体 Android 手机（`adb devices` 可见，可读型号/Android 版本/ABI）。
3. 一条真实家庭宽带 Wi-Fi（非公司内网）。
4. 一张中国大陆运营商 SIM（移动/联通/电信），可关闭 Wi-Fi 仅用移动数据。
5. 至少一名人工听音人员（确认扬声器/耳机真实出声，并确认声音对应目标曲目）。
6. 至少一名非开发人员参与独立使用验收。

上述条件齐备后，按 K3.2→K3.7 顺序执行；所有真实测试必须绑定：Git SHA、APK SHA256、手机型号、Android 版本、网络类型、测试日期、具体曲目、真实操作结果。若测试中发现阻断问题，按冻结变更纪律（证据→根因→最小修复→回归→新 SHA→新 APK→受影响项重测）处理，绝不沿用旧版本结论。

## 10. 本阶段改动清单

- 新增本文档 `docs/k3-real-device-cn-acceptance.zh-CN.md`；更新 README 索引、中文 README、roadmap、发布候选清单（冻结交接）。
- **无业务代码改动**（资源门禁未通过，未进入真实测试，无阻断问题需修复）。
- 未修改 master、未改写历史、未伪造任何 PASS。
