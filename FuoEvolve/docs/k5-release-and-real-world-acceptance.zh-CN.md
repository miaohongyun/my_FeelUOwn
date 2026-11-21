# 阶段 K5：正式 Release 交付与 K3 真实验收

> 唯一目标：普通中国大陆家庭用户使用正式签名 APK，在真实 Android 手机与家庭 Wi-Fi / 大陆移动数据下完成首次启动、搜索、选曲、真实播放与人工听音验收。
> 核心纪律：物理资源与正式签名材料不到位即 `K5_RESOURCE_GATE=BLOCKED`；不运行模拟测试冒充真实验收，不通过新增代码/截图/文档把 BLOCKED 改成 PASS，不用"代码已完成/自动化测试全绿/报告已提交"替代真实交付验收。
> 执行时间：2026-11-21 12:42 GMT+8。

---

## 0. 冻结基线复核（执行前现查，完整 SHA）

| 项目 | 实测值 | 结论 |
|---|---|---|
| 工作分支 | `work/android-community-discovery` | 一致 |
| 本地 HEAD = K4 报告提交 | `dc196ff129165db52a2ca42fabd67d4a38adcdf8` | 一致 |
| 远端 work | `dc196ff129165db52a2ca42fabd67d4a38adcdf8`（ls-remote 现查） | 一致 |
| master | `fc05530c09966dedacb413168a1dc6fda0d3afe1`（未动） | 一致 |
| K2 冻结代码 | `865918c8083e40e3e3c4c29bee9529f239a810cd`（`cat-file`=commit） | 存在 |
| K3 提交 | `03e483906435a5a49d07f5109ffee6508b3cdecf`（`cat-file`=commit） | 存在 |
| K4 提交 | `dc196ff129165db52a2ca42fabd67d4a38adcdf8`（`cat-file`=commit） | 存在 |
| 工作区 | `git status --porcelain` = 0 行 | 干净 |

未使用短 SHA 猜测；以上均为完整 SHA 现查结果。冻结基线保持不变，未修改任何默认来源策略、网络安全策略、下载许可门控。

---

## 1. 资源到位门禁（§3，执行前现查）

| 资源 | 现查方法与结果 | 状态 |
|---|---|---|
| 正式签名材料 | `FUO_SIGNING_STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD` 全 `UNSET`；项目内无 `*.jks`/`*.keystore`/`*signing*.p12`；`local.properties` 不存在（签名值第二来源亦缺） | **BLOCKED** |
| Android 实体手机 | `adb devices -l`：仅 "List of devices attached"，无任何 `device` 条目 | **BLOCKED** |
| 大陆家庭 Wi-Fi | 仅以太网 `192.168.6.12`、网关/DNS `192.168.6.1`（公司内网）；WlanSvc=`Stopped`、无 WLAN 接口 | **BLOCKED** |
| 大陆移动数据 | 无真机、无可确认承载流量的 SIM，无法关闭 Wi-Fi 后用移动数据 | **BLOCKED** |
| 人工验收人员 | 无人值守 CLI 环境，无听音人员、无非开发者验收人员 | **BLOCKED** |

**五类资源全部缺失 → `K5_RESOURCE_GATE=BLOCKED`。** 按 §3.6，门禁未通过即停止正式验收：未构建 Release、未安装、未搜索、未播放、未运行任何模拟测试冒充真实验收。缺失项为物理设备、运营商网络与自然人，不能由代码、截图或文档补齐，不接受计划/订单/承诺替代。

---

## 2. 门禁结论（§1 六项）

| 结论项 | 结果 | 依据 |
|---|---|---|
| `RELEASE_ARTIFACT` | **BLOCKED** | 四项签名变量 UNSET、无 keystore，无法正式签名；无签名时构造的包为未签名件，不算交付制品，故未构建 |
| `ANDROID_DEVICE` | **BLOCKED** | `adb devices -l` 无设备，无实体机可安装 |
| `CN_HOME_WIFI` | **BLOCKED** | 仅公司内网，无家庭宽带、无 WLAN，禁止代替 |
| `CN_MOBILE_DATA` | **BLOCKED** | 无 SIM、无真机，无法验证移动数据承载 |
| `REAL_PLAYBACK_AUDIO_OUTPUT` | **NOT_RUN** | 无制品、无设备、无网络、无听音人员；不用 Playing 状态/position/HTTP 206 冒充 |
| `ORDINARY_USER_ACCEPTANCE` | **NOT_RUN** | 无非开发者验收人员与可安装制品 |
| `K3_FINAL` | **BLOCKED** | 关键项均无真实证据（详见 §5） |

---

## 3. 九级验证等级（§9，逐项不合并）

| 等级 | 状态 | 说明 |
|---|---|---|
| `REAL_INIT` | NOT_RUN | 无正式 APK、无真机 |
| `REAL_SEARCH` | NOT_RUN | 无制品、无真实家庭/移动网络 |
| `REAL_RESULT_MAPPING` | NOT_RUN | 同上 |
| `REAL_URL_RESOLVE` | NOT_RUN | 同上 |
| `REAL_AUDIO_RESPONSE` | NOT_RUN | 同上 |
| `REAL_PLAYBACK_DECODE` | NOT_RUN | 同上（K1 的 Desktop `ao=null` 解码证据不迁移到本阶段 Android 正式制品） |
| `REAL_PLAYBACK_AUDIO_OUTPUT` | NOT_RUN | 无人工听音，禁止用状态字段替代 |
| `REAL_TRACK_IDENTITY_VERIFIED` | NOT_RUN | 无真实播放链路 |
| `ACOUSTIC_IDENTITY_VERIFIED` | NOT_RUN | 无人工声学核验 |

全部 NOT_RUN，原因统一且真实：**门禁 BLOCKED，未进入任何真实验收环节**。没有任何一项被 HTTP 200、HTTP 206、URL 解析、Playing 状态、position 增长、ID 一致、代理成功、公司内网或 Wi-Fi 结果冒充。

---

## 4. 制品与绑定信息（§5）

门禁未通过，本阶段**未产生** Release APK，故无可填写的制品绑定值：

| 字段 | 值 |
|---|---|
| 源码完整 SHA | `dc196ff129165db52a2ca42fabd67d4a38adcdf8`（本次基线；K2 冻结代码 `865918c…`） |
| Release APK 完整 SHA256 | 无（未构建） |
| APK 包名 / versionCode / versionName | 无正式制品；工程配置 `org.feeluown.mobile` / git 派生 / git 派生 |
| 签名证书 SHA-256 指纹 | 无（未签名） |
| 构建环境与完整命令 | 未执行；环境细节见 `k4-release-artifact-and-test-resources.zh-CN.md` §1.2 |

既有 Debug APK（`22c80fb2…65b1a`，`CN=Android Debug`，`d901958-dirty`）仅供工程诊断，不作为 K5 交付制品，其任何结果均不计入本阶段。

---

## 5. 最终判定

- `K5_RESOURCE_GATE = BLOCKED`
- `K5_FINAL = BLOCKED`（物理资源与正式签名材料全部缺失，按 §12 判定）

**K5 未完成。** 未宣布 K3 完成，未对外讨论"普通中国大陆家庭用户能否开箱即用"——任务书明确该讨论仅在本阶段完成后方可进行。

### 解除 BLOCKED 的最小条件（按 §3 逐项，需项目所有者实际提供）

1. 正式 keystore + 四项 `FUO_SIGNING_*`（在所有者可信设备上本地配置；密钥/密码不进聊天、Git、日志、命令行参数）。
2. 一台 USB 调试已授权、状态为 `device`（非 unauthorized/offline）的实体 Android 手机，可读 `ro.product.model` / `ro.build.version.release` / `ro.product.cpu.abi`。
3. 一条真实家庭宽带 Wi-Fi（非公司内网/实验室/热点，无 VPN/代理/抓包中转），记录运营商、网络类型、测试时间与 DNS。
4. 一张大陆 SIM（移动/联通/电信），关闭 Wi-Fi 后确认流量实际经移动数据，记录运营商、4G/5G 与测试时间。
5. 一名实际听音人员 + 一名未参与开发的普通用户验收人员（可兼任但分别记录）。

五项齐备后，从 §0 确认的冻结 SHA（或按证据→根因→最小修复→回归纪律生成的新候选 SHA）构建正式 Release，绑定 §4 全部制品字段，按 §5.1→§5.5 顺序执行真实验收；任何失败保留原始类别，不用模拟与文档替代。

---

## 6. 本阶段改动清单

- 新增本文档 `docs/k5-release-and-real-world-acceptance.zh-CN.md`；更新文档索引、中文 README、发布候选清单、roadmap。
- **无业务代码改动**，未修改 master、未改写历史、未生成/提交密钥、未伪造任何 PASS、未运行模拟测试冒充真实验收。
