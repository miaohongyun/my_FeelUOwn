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

| 脚本 | 版本 | 来源路径 | 平台 | 宿主新增需求 | 静态安全 | 初始化 | 模拟解析 | 判定 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Huibq_lxmusic源 | v1.2.0 | xzh767: `huibq.js` | kw/kg/tx/wy/mg | 无 | 单一 API `lxmusicapi.onrender.com`，`API_KEY=share-v2` 公开值，无 Cookie/动态执行 | 通过 | 通过（ID 一致） | NEEDS_REVIEW |
| 肥猫不肥 | — | xzh767: `肥猫不肥.js` | kw/kg/tx/wy/mg | console | API `music.xn--z7x900a.live`，`API_KEY=114514` 公开占位值；无动态执行 | 通过 | 通过 | NEEDS_REVIEW |
| 野花音源 | 1.0.0 | xzh767: `野花音源.js` | kw/wy/mg/tx/kg | 无 | 高度混淆（字符串拆分），实际解析域名在执行时才暴露：`flower.tempmusics.tk`；无 Cookie/动态执行命中 | 通过 | 通过（补域名后） | NEEDS_REVIEW |
| 野草音源 | 1.0.0 | xzh767: `野草音源.js` | kw | 无 | 同类混淆，实际域名 `grass.tempmusics.tk` | 通过 | 通过（补域名后） | NEEDS_REVIEW |
| 至尊源 | v0.8.4_beta5 | xzh767: `zhizun.js` | kw/kg/tx/wy/mg | console | 裸 IP `http://110.42.36.53:1314`，`API_KEY` 空；无动态执行 | 通过 | 通过 | NEEDS_REVIEW |
| 梓澄公益音源二代 | 1.2.3.4.5 | xzh767: `梓澄公益音源二代v1.2.3.4.5.js` | kw/kg/tx/wy/mg | console | 裸 IP `http://103.239.247.51:9763`，`API_KEY=114514` 公开占位值 | 通过 | 通过 | NEEDS_REVIEW |
| 聚合API接口(by lerd) | 3 | xzh767: `聚合API V3(by lerd).js` | 需真实 init 响应 | 无 | 单域名 `lerd.sukimon.me`；init 必须真实响应 `code=200` 才 send `inited` | 失败（MockEngine 返回 `{}`） | 未测 | NEEDS_REVIEW |
| ikun音源 | v515 | xzh767: `ikun_latest.js` | kw/kg/tx/wy/mg | console | API `api.ikunshare.com`，公开分享型 key；成功码为 `200`（与其他源不同） | 通过 | 通过（按 200 契约） | NEEDS_REVIEW |
| [独家音源] | 4 | ejungd: `[独家音源] v4.0.js` | kw/kg/tx/wy/mg | md5 + console | 域名 `88.lxmusic.xn--fiqs8s`；114KB，md5 仅用于自检 | 通过 | 通过 | NEEDS_REVIEW |
| 無名 | v0.0.3 | xzh767: `無名v0.0.3.js` | 多平台 | md5/aes/rsa | 依赖 `crypto` 原语（aes/rsa 未实现）；有 name/description 防篡改自检 | 失败（自检） | 未测 | NEEDS_REVIEW |
| 微信公众号：洛雪音乐 | 2 | xzh767: `公众号音源2.js` | 多平台 | md5 + console | init 时 `crypto.md5` 自检后仍需网络，5s 初始化超时（`InternalError: interrupted`） | 失败（超时） | 未测 | NEEDS_REVIEW |
| 六音音源 | v1.2.1 | xzh767: `sixyin1.2.1.js` | — | — | **333KB 重度 `_0x` 混淆 + `new Function(` 动态执行**；自检失败提示去官网下载新版 | 失败 | 未测 | REJECT_AUTO_CATALOG |
| 小熊猫音源 | v1.1.1 | xzh767: `小熊猫v1.1.1.js` | — | — | `updateUrl: https://xxx.com` + 从 jsdelivr/GitHub **远程拉取其他 JS**；属动态加载第二段代码 | 失败 | 未测 | QUARANTINE_API_REVIEW |

`gongzhonghao2.js` 与本清单"独家音源"不同，是我额外下载的候选样本，一并记录便于复核。

## 待寻找（不得冒名替代）

- **聚合API接口（CF）**：主仓库未发现明确标注 CF 的版本；`聚合API接口(decrypt).js` 不是 CF 版，未纳入。
- **测试音乐源**：未找到明确对应文件，未纳入。

## 未验证风险

1. 上述"通过"均为脚本自身 API 契约的模拟响应；真实接口是否仍存活、返回格式是否一致、ID 是否命中同一首歌，均未验证。
2. 裸 IP 与 punycode 域名（至尊/梓澄/肥猫/独家）的可用性、证书与备案情况未核实。
3. 混淆脚本（野花/野草）只在执行层观测到一个域名，无法静态穷尽其全部网络行为；需要真机抓包复核。
4. `crypto.aes`/`rsa` 未实现，無名等脚本即便名称校验通过，运行时仍会在需要解密处失败。
