# 公开 LX 音源脚本审查报告（2026-11-19）

## 取证方式

- 来源仓库：`xzh767/lxmusic-source-all`（主）、`ejungd/music`（辅助，独家音源）。
- 下载方式：GitHub Raw 直取 `main` 分支原始字节，未做任何改写。
- 原始文件保存于 `third-party-raw/`，SHA-256 见下文；本仓库保存的即"原版"。
- 测试资源 `provider/lxruntime/src/desktopTest/resources/third-party/` 是同一批文件的副本，仅用于自动化测试。

**重要声明**：本报告的所有播放地址测试均使用 MockEngine 按脚本公开 API 契约返回，属于**模拟测试通过**。
没有对任何真实音乐接口发起请求，因此**不能**据此宣称"真实音源可用"。所有条目在 `candidates.json`
中均为 `autoInstall: false`，`source-catalog.json`（已签名）保持空数组不变。

## 宿主能力补齐（为适配官方 API，非伪造）

| 补充项 | 原因 | 实现 |
| --- | --- | --- |
| `console` | 多数社区脚本用 console 打日志；原先未注入导致 `ReferenceError` 初始化失败 | no-op console（`LxBridge`），日志全部丢弃，不泄漏 token/URL |
| `lx.utils.crypto.md5` | 官方移动端文档列出的 API；多个脚本用它做自身完整性校验 | 纯 Kotlin `Md5.kt`，标准向量测试（外部 hashlib 交叉验证） |

仍**未实现**：`crypto.randomBytes` / `aesEncrypt` / `rsaEncrypt`、`zlib.inflate/deflate`。
依赖这些的脚本会明确报错，不会返回错值。

## 逐脚本审查结论

判定说明：`NEEDS_REVIEW` = 模拟测试通过但未做真实网络验证，不得自动安装；
`QUARANTINE_*` = 有明确风险需专项审查；`REJECT_AUTO_CATALOG` = 不具备进入目录条件。

**测试等级（`testLevel`，与 `candidates.json` 一致，必须表示已完成的最高等级）**：
`NOT_OBTAINED`（仅仓库登记，脚本未取证）< `STATIC_ONLY`（静态审查，未成功执行）< `RUNTIME_LOAD`（能加载但未通过完整初始化）< `SIMULATED_INIT`（MockEngine 初始化成功）< `SIMULATED_URL_RESOLVE`（模拟初始化 + URL 解析 + ID 一致）< `REAL_INIT` < `REAL_SEARCH` < `REAL_URL_RESOLVE` < `REAL_AUDIO_RESPONSE` < `REAL_TRACK_IDENTITY_VERIFIED`。
`SIMULATED_*` 均为 MockEngine 模拟，不等于真实接口；下方"真实接口可达性探测"仅为网络可达性，不构成任何 `REAL_*` 提升。

| 脚本 | 版本 | 来源路径 | 平台声明 | 实测通过平台 | 宿主新增需求 | 静态安全 | 初始化 | 模拟解析 | testLevel | 判定 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Huibq_lxmusic源 | v1.2.0 | xzh767: `huibq.js` | kw/kg/tx/wy/mg | kw | 无 | 单一 API `lxmusicapi.onrender.com`，`API_KEY=share-v2` 公开值，无 Cookie/动态执行 | 通过 | 通过（ID 一致） | SIMULATED_URL_RESOLVE | NEEDS_REVIEW |
| 肥猫不肥 | — | xzh767: `肥猫不肥.js` | kw/kg/tx/wy/mg | kw | console | API `music.xn--z7x900a.live`，`API_KEY=114514` 公开占位值；无动态执行 | 通过 | 通过 | SIMULATED_URL_RESOLVE | NEEDS_REVIEW |
| 野花音源 | 1.0.0 | xzh767: `野花音源.js` | kw/wy/mg/tx/kg | kw | 无 | 高度混淆（字符串拆分），实际解析域名在执行时才暴露：`flower.tempmusics.tk`；无 Cookie/动态执行命中 | 通过 | 通过（补域名后） | SIMULATED_URL_RESOLVE | NEEDS_REVIEW |
| 野草音源 | 1.0.0 | xzh767: `野草音源.js` | kw | kw | 无 | 同类混淆，实际域名 `grass.tempmusics.tk` | 通过 | 通过（补域名后） | SIMULATED_URL_RESOLVE | NEEDS_REVIEW |
| 至尊源 | v0.8.4_beta5 | xzh767: `zhizun.js` | kw/kg/tx/wy/mg | kw | console | 裸 IP `http://110.42.36.53:1314`，`API_KEY` 空；无动态执行 | 通过 | 通过 | SIMULATED_URL_RESOLVE | NEEDS_REVIEW |
| 梓澄公益音源二代 | 1.2.3.4.5 | xzh767: `梓澄公益音源二代v1.2.3.4.5.js` | kw/kg/tx/wy/mg | kw | console | 裸 IP `http://103.239.247.51:9763`，`API_KEY=114514` 公开占位值 | 通过 | 通过 | SIMULATED_URL_RESOLVE | NEEDS_REVIEW |
| 聚合API接口(by lerd) | 3 | xzh767: `聚合API V3(by lerd).js` | 需真实 init 响应 | 无 | 无 | 单域名 `lerd.sukimon.me`；init 必须真实响应 `code=200` 才 send `inited` | 失败（MockEngine 返回 `{}`） | 未测 | RUNTIME_LOAD | NEEDS_REVIEW |
| ikun音源 | v515 | xzh767: `ikun_latest.js` | kw/kg/tx/wy/mg | kw | console | API `api.ikunshare.com`，公开分享型 key；成功码为 `200`（与其他源不同） | 通过 | 通过（按 200 契约） | SIMULATED_URL_RESOLVE | NEEDS_REVIEW |
| [独家音源] | 4 | ejungd: `[独家音源] v4.0.js` | kw/kg/tx/wy/mg | kw | md5 + console | 域名 `88.lxmusic.xn--fiqs8s`；114KB，md5 仅用于自检 | 通过 | 通过 | SIMULATED_URL_RESOLVE | NEEDS_REVIEW |
| 無名 | v0.0.3 | xzh767: `無名v0.0.3.js` | 多平台 | 无 | md5/aes/rsa | 依赖 `crypto` 原语（aes/rsa 未实现）；有 name/description 防篡改自检 | 失败（自检） | 未测 | RUNTIME_LOAD | NEEDS_REVIEW |
| 微信公众号：洛雪音乐 | 2 | xzh767: `公众号音源2.js` | 多平台 | 无 | md5 + console | init 时 `crypto.md5` 自检后仍需网络，5s 初始化超时（`InternalError: interrupted`） | 失败（超时） | 未测 | RUNTIME_LOAD | NEEDS_REVIEW |
| 六音音源 | v1.2.1 | xzh767: `sixyin1.2.1.js` | — | 无 | — | **333KB 重度 `_0x` 混淆 + `new Function(` 动态执行**；自检失败提示去官网下载新版 | 失败 | 未测 | STATIC_ONLY | REJECT_AUTO_CATALOG |
| 小熊猫音源 | v1.1.1 | xzh767: `小熊猫v1.1.1.js` | — | 无 | — | `updateUrl: https://xxx.com` + 从 jsdelivr/GitHub **远程拉取其他 JS**；属动态加载第二段代码 | 失败 | 未测 | STATIC_ONLY | QUARANTINE_API_REVIEW |

`gongzhonghao2.js` 与本清单"独家音源"不同，是我额外下载的候选样本，一并记录便于复核。

## 待寻找（不得冒名替代）

- **聚合API接口（CF）**：主仓库未发现明确标注 CF 的版本；`聚合API接口(decrypt).js` 不是 CF 版，未纳入。
- **测试音乐源**：未找到明确对应文件，未纳入。

## 未验证风险

1. 上述"通过"均为脚本自身 API 契约的模拟响应；真实接口是否仍存活、返回格式是否一致、ID 是否命中同一首歌，均未验证。
2. 裸 IP 与 punycode 域名（至尊/梓澄/肥猫/独家）的可用性、证书与备案情况未核实。
3. 混淆脚本（野花/野草）只在执行层观测到一个域名，无法静态穷尽其全部网络行为；需要真机抓包复核。
4. `crypto.aes`/`rsa` 未实现，無名等脚本即便名称校验通过，运行时仍会在需要解密处失败。

## 真实接口可达性探测（2026-11-19，任务5）

对上述候选做了真实 DNS（Cloudflare DoH）+ HTTP（`curl` 经 `127.0.0.1:7897` 代理）探测。
**环境非中国大陆直连**，故"大陆可访问"不可据此宣称；以下仅为该时刻本机可达性：

| 候选 | 端点 | 观测 | 判定 |
| --- | --- | --- | --- |
| Huibq | lxmusicapi.onrender.com | DNS 正常，HTTP 503 "Service Suspended by its owner" | 服务已停 |
| ikun | api.ikunshare.com | DoH NXDOMAIN，TLS 握手失败 | 域名失效 |
| 野花 | flower.tempmusics.tk | DoH NXDOMAIN | 域名失效 |
| 野草 | grass.tempmusics.tk | DoH NXDOMAIN | 域名失效 |
| 至尊 | 110.42.36.53:1314 | 经代理 502（代理不直连裸 IP 端口） | 本机不可达，需真机 |
| 梓澄二代 | 103.239.247.51:9763 | 经代理 502 | 本机不可达，需真机 |
| 肥猫 | music.xn--z7x900a.live | A 记录存在，根路径 nginx 默认页，`/url/...` 404 | API 路径不通 |
| 独家 | 88.lxmusic.xn--fiqs8s | A 记录存在，根路径 `{"code":0,...}`，`/url/...` 404 | 服务存活，路径待核 |

**边界**：这只是网络可达性，不是 `REAL_INIT`/`REAL_SEARCH`/`REAL_URL_RESOLVE`/`REAL_AUDIO_RESPONSE`/
`REAL_TRACK_IDENTITY_VERIFIED`。没有任何候选完成真实全链路，故**全部维持原状态、autoInstall=false**，
`source-catalog.json` 保持空数组。"值得继续投入"的结论：失效域名暂缓；独家/肥猫值得大陆真机复核解析路径；
裸 IP 两项需真机抓包。详细等级定义见 `docs/personal-music-roadmap.zh-CN.md` §10.1。


## 真实接口验证阶梯执行结果（2026-11-19，任务6）

新增 opt-in 真实验证测试 `provider/lxruntime/src/desktopTest/.../RealInterfaceVerificationTest.kt`，
设 `FUOE_REAL_VERIFY=1` 且可选 `LX_VERIFY_PROXY=http://127.0.0.1:7897` 才执行真实网络；
常规 CI 不打真实接口。10 级单调阶梯固化在 `candidates.json.testLevelDefinitions`，
测试断言"等级不得越级"：即便真实搜索成功，也只能记 `REAL_SEARCH`，未做音频 HEAD 不得记
`REAL_AUDIO_RESPONSE`，未做身份比对不得记 `REAL_TRACK_IDENTITY_VERIFIED`。

实际执行（`FUOE_REAL_VERIFY=1 LX_VERIFY_PROXY=... ./gradlew :provider:lxruntime:desktopTest
--tests '*RealInterfaceVerificationTest'`）输出：

- `kg 搜索: REAL_SEARCH ok=true HTTP 200; 首条 FileHash=B3A52A7A958BF0AED0EBFBA2E9A818B7
  title=晴天 artist=周杰伦 duration=269s; 共 5 条` —— **酷狗公开搜索端点真实可用**，
  已据此把 kg 纳入生产默认注册表（kw+kg，见 LxSongSearchRegistry.default）。
- 失效端点审核结论锁定：huibq=503 被所有者暂停、ikun/野花/野草=NXDOMAIN、
  至尊/梓澄=裸 IP 经代理不可达、肥猫=公开 /url/ 路径 404。这些脚本**不得**因本地能加载
  就标记任何 `REAL_*`。
- **合规边界**：真实音频只用 HEAD 探测 Content-Type/长度，不下载完整商业音乐；
  本轮无任何候选达到 `REAL_URL_RESOLVE`/`REAL_AUDIO_RESPONSE`/`REAL_TRACK_IDENTITY_VERIFIED`。
  脚本播放地址的真实性仍需大陆真机 + 合法授权后单独验证。
