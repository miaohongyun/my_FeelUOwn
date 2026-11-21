# 发布候选版本检查清单（K2 冻结基线）

> 用途：K2 结束后冻结一个发布候选版本，K3 只针对该固定 SHA 做真机/真实网络验收。
> 冻结意味着不再随意加功能；若 K3 发现问题需修复，代码 SHA 变化后必须生成新候选并重新执行受影响的验收项。
> 生成时间：2026-11-21。基线提交：`d901958`（K1.2）。

---

## 1. 已完成能力（开发侧有证据）

- **搜索与发现**：首页/全局搜索、中文/歌手/关键词、多音源并行、单源故障隔离、取消与 latest-wins、状态三态区分（`SearchFeature` 9 测试）。
- **播放**：Android ExoPlayer（MediaSession、generation 并发门、错误/缓冲状态、URL 脱敏）、Desktop libmpv（FFM Native Bridge、陈旧事件过滤、暂停/继续/停止/切歌、资源释放）；共享播放层 40+ 测试；Desktop 真实解码 >30s 证据（K1，`ao=null` 无声）。
- **音源与更新**：Provider 独立沙箱（幂等生命周期、native 超时中断死循环、资源上限、CommonJS 受控子集）、统一 Gateway（SHA 绑定/精确域名/HTTPS/SSRF/逐跳重定向/流式限额/超时取消）、Ed25519 签名 + SHA256 + 反重放 + 回滚的自动更新链。
- **许可（K2 本阶段）**：`MusicTrack.license` 结构化 9 字段；BY-NC-ND 逐曲编码（playLicense=true / downloadLicense=false / NC 禁商用 / ND 禁演绎）；下载门控默认拒绝未知许可（`TrackLicenseTest` 8 + 门控测试）。
- **下载/本地/歌单/历史**：队列/断点/持久化/重启恢复、本地扫描/元数据/迁移、`.fuo` 歌单、SQLDelight 听歌历史。
- **诊断**：AppLogger 统一脱敏（Cookie/Token/密码/URL 签名），Android/Desktop 最近 3 次启动日志 ZIP 导出。
- **引导/设置**：3 页中文引导、失败回滚、默认 5 源幂等迁移、排序持久化。

## 2. 支持平台与构建状态

| 平台 | 状态 | 说明 |
|---|---|---|
| Windows Desktop | 见 §4 | Compose Desktop + libmpv Native Bridge |
| Android | 见 §4 | Debug 可构建；Release 正式签名需本地密钥 |
| iOS | 见 §4 | 主代码/测试源码可编译；无 macOS/Xcode，Simulator 测试 NOT_RUN |

## 3. 安装与数据存储

- Android applicationId `org.feeluown.mobile`；versionCode/versionName 由 git 提交派生。
- 桌面配置与用户数据目录：`~/.FeelUOwn`（含 mode_b、source-catalog 等）。
- 更新方式：受控目录 Ed25519 签名 + SHA256 校验；发现/下载/审核/安装分离，未审核脚本不自动安装/批准。
- 安装方式：Android APK 安装；Windows 桌面安装包（见 §4 实际产物）。

## 4. 构建产物与 SHA256（K2 冻结）

| 产物 | 状态 | 路径 | SHA256 |
|---|---|---|---|
| Android Debug APK | BUILD_PASS | `androidApp/build/outputs/apk/debug/androidApp-debug.apk`（36,861,777 B） | `22c80fb2aac84b64aabf7b559c2a2e4e1a07fe4fa2788496658d4af70bf65b1a` |
| Android Release APK | RELEASE_SIGNING=BLOCKED（本地无签名密钥，`FUO_SIGNING_STORE_FILE` 等未配置；不用 Debug 冒充） | — | — |
| Windows 桌面包 | BLOCKED（`:desktopApp:createReleaseDistributable` 缺 cargo，`buildNucleusAudioCaptureLibrary` 失败；Desktop Kotlin/JVM 编译 BUILD_PASS） | — | — |
| 冻结 Git SHA | 见文末「冻结记录」（本阶段提交完成后填写完整 SHA） | — | — |

**构建状态定义**：BUILD_PASS = 构建任务成功≠真机运行通过；BLOCKED = 依赖条件缺失无法执行，不以静态审查代替。Debug APK 可安装用于真机验收，但不是正式签名发布版。

## 5. 安全说明

- Gateway 是沙箱脚本路径的唯一网络出口；内置 Provider/LX 各自走受控网络层（"唯一出口"边界已在审计报告 §1 模块 7 收敛表述）。
- 精确域名授权、禁通配符、HTTPS/443、SSRF/私网裸 IP 拒绝、凭据隔离、响应体流式限额、逐跳重定向（生产默认 DENY_ALL）、超时/取消——均有测试。
- **DNS rebinding 未解决**（commonMain 无连接层 IP 绑定），已在代码注释与审计报告公开记录。
- 下载默认拒绝未知许可；BY-NC-ND 不用于演绎/铃声/商用；不因播放成功推导下载许可。
- 安全扫描：源码无真实凭据/token、无私钥文件、无生产通配符放行、无关闭 TLS 校验痕迹（详见审计报告 §K2.6）。

## 6. 尚未经过真机验证的能力（K3 负责，全部 NOT_RUN）

- Android 真机正式 App 搜索→ExoPlayer→扬声器/耳机真实出声（无真机）。
- 家庭 Wi-Fi 与移动数据直连完整链路（仅公司内网）。
- 曲目声学身份验证、音频焦点/蓝牙/耳机/网络恢复的真机行为。
- 中国大陆家庭环境下的稳定性与空结果体验。
- iOS Simulator 测试（无 macOS/Xcode）。

## 7. 已知限制与遗留（不阻塞 K2 冻结，但需后续处理）

1. **下载许可默认拒绝的商业来源影响**：商业来源曲目无结构化许可，现默认不可下载；恢复需各 Provider 显式声明 `downloadLicense=true`（见审计报告 §2.3）。
2. **匹配增强**：无标题/歌手简繁互转、无歌手别名表、无显式四级命名分级（现有为评分+版本 rank）。
3. **音质 evidence 回填**：`ContainerVerified`/`SpectralVerified` 在统一模型已定义，但 lossless 管线未回填；iOS 无 FLAC 校验。
4. **NAS 安全**：Subsonic 密码明文持久化/UI 明文显示、无连接测试、iOS 无实现、WebDAV 无原生客户端（高优先遗留）。
5. **更新**：正式目录 `sources` 为空未签发（缺私钥，不在本环境）；无断点续传；更新期 App 关闭仅单元级覆盖。
6. **诊断**：无自动崩溃捕获；iOS 诊断 NoOp。
7. **设置体验**：设置加载失败未暴露到 UI 无重试；设置页 setProviderEnabled 失败未回滚偏好。
8. **陈旧配置**：`assets/providers.json` 与实际默认 5 源不一致且未被读取。

---

## 冻结记录（K2 → K3 交接）

> 本阶段提交完成后填写。K3 必须针对下列固定 SHA 验收；若修复导致 SHA 变化，须生成新候选并重跑受影响项目。

- K2 冻结代码基线完整 SHA：`865918c`（本阶段代码提交；文档提交不改动源码，K3 验收以该代码提交为准，完整 SHA 见提交链）
- 阶段基线父提交：`d9019584352e0aa6ab5cf4e5dad4c4ecca619e0a`（K1.2）
- 注：若需以最终 HEAD 作为冻结 SHA，以提交后 `git rev-parse HEAD` 为准；两次提交仅差文档，源码一致。
- Android Debug APK SHA256：`22c80fb2aac84b64aabf7b559c2a2e4e1a07fe4fa2788496658d4af70bf65b1a`
- Windows 桌面包 SHA256：BLOCKED（缺 cargo）
- 配置版本 / Provider 清单版本：内置公钥 `fuoevolve-2026-11`，正式目录 `sources` 为空（未签发）
- 测试报告版本：desktop 1049 / androidHost 925 / App 34 / iOS 编译 PASS（2026-11-21）

### K3 交接更新（2026-11-21）
- Debug APK SHA256 已用四重独立方法复核，确认对应完整 36,861,777 字节文件（流式/整读/sha256sum 一致，前 29 字节哈希不同），apksigner 显示签名者 `CN=Android Debug`（非 Release）。
- `RELEASE_SIGNING = BLOCKED`：`FUO_SIGNING_*` 环境变量与本地签名密钥均缺失；未生成自签名冒充正式签名，未上传/提交密钥。
- `WINDOWS_INSTALLER = BLOCKED`：cargo/rustc 缺失（同 K2）。
- K3.1 真机/家庭宽带/SIM/听音人员全部缺失 → K3.2–K3.7 全 NOT_RUN；**K3 未完成，不宣布开箱即用达标**。详见 `docs/k3-real-device-cn-acceptance.zh-CN.md`。
