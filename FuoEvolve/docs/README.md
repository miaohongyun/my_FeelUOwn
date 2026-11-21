# 文档索引

按「想知道什么」查找，而不是按文件名。用户操作说明在仓库根目录的 [USAGE.md](../USAGE.md)。

## 我想使用这个应用

| 文档 | 内容 |
| --- | --- |
| [USAGE.md](../USAGE.md) | 安装、首次启动、音源与账号、播放、无损增强、下载、投屏、本地音乐、识曲、歌单迁移、故障排查 |
| [FUOEVOLVE_PRIVATE_MUSIC_FIRST_PATCH.md](FUOEVOLVE_PRIVATE_MUSIC_FIRST_PATCH.md) | 私人音乐改造第一阶段的变更范围与兼容性说明 |

## 我想了解这轮私人改造做了什么

| 文档 | 内容 |
| --- | --- |
| [personal-music-roadmap.zh-CN.md](personal-music-roadmap.zh-CN.md) | 改造目标、已落地代码、双向版本匹配、账户音质探测、统一音质模型、受控音源目录与脚本自动更新、验证记录 |
| [community-source-audit.zh-CN.md](community-source-audit.zh-CN.md) | 阶段 E0–E2 社区音源全量候选台账：来源取证、SHA-256、静态审查、长青/Fish/Flower/聚合/插件生态结论 |
| [source-readiness-audit.zh-CN.md](source-readiness-audit.zh-CN.md) | 阶段 K0 来源就绪度审计：Jamendo/Internet Archive/Subsonic 授权四权分离、中国大陆网络直连实测、七级真实能力、默认候选结论 |
| [jamendo-playback-verification.zh-CN.md](jamendo-playback-verification.zh-CN.md) | 阶段 K1 Jamendo 正式播放闭环：Desktop libmpv 真实解码 >30s 证据、曲目 ID 关联、中国大陆网络矩阵、K2 许可边界 |
| [jamendo-cn-device-network-acceptance.zh-CN.md](jamendo-cn-device-network-acceptance.zh-CN.md) | 阶段 K1.1/K1.2 中国大陆设备与网络验收：Android 真机/家庭宽带/移动数据 NOT_RUN、resolve 重试审计、空结果率观测与估算订正、K1.2 五项真实资源缺口与取得安排 |
| [k2-product-readiness-audit.zh-CN.md](k2-product-readiness-audit.zh-CN.md) | 阶段 K2 产品功能盘点与发布候选审计：17 模块成熟度、结构化许可模型+下载门控、全量质量门禁（2008 项 0 失败）、12 问最终审计 |
| [release-candidate-checklist.zh-CN.md](release-candidate-checklist.zh-CN.md) | 阶段 K2 发布候选清单：已完成能力、构建产物 SHA256、Release 签名/Windows 包 BLOCKED、已知限制、K3 冻结交接 |
| [k3-real-device-cn-acceptance.zh-CN.md](k3-real-device-cn-acceptance.zh-CN.md) | 阶段 K3 冻结版本真实验收：APK SHA256 四重核验、Release 签名独立门禁、真机/家庭宽带/SIM/听音资源缺失 NOT_RUN、阶段未完成判定 |
| [cast-dlna.zh-CN.md](cast-dlna.zh-CN.md) | 投屏模块的分层、SSDP/SOAP 链路、连播判定、代理流、手机静音、已知限制 |

## 我想改架构

| 文档 | 内容 |
| --- | --- |
| [architecture.md](architecture.md) | 编译期与所有权边界总览（P2–P5 之后） |
| [app-shell-architecture.md](app-shell-architecture.md) | 应用外壳：状态归属、展示接线、返回协调、平台能力 |
| [p0-architecture-migration.md](p0-architecture-migration.md) | P0 低风险结构调整 |
| [p2-architecture-roadmap.md](p2-architecture-roadmap.md) | P2：从宽控制器门面到 app / feature / playback 所有权 |
| [p4-architecture-roadmap.md](p4-architecture-roadmap.md) | P4：削减 `:shared` 这个集成单体 |
| [p5-provider-modularization.md](p5-provider-modularization.md) | P5：音源编译期模块化（非运行期插件发现） |
| [player-controller-refactor.md](player-controller-refactor.md) | `FuoPlayerController` 兼容门面的渐进迁移 |
| [playback-logical-resolved-boundary.md](playback-logical-resolved-boundary.md) | 逻辑曲目与已解析播放源的身份分离 |
| [playback-state-consistency.md](playback-state-consistency.md) | 播放状态必须成立的不变量 |

## 我想发布或打包

| 文档 | 内容 |
| --- | --- |
| [desktop-foundation.md](desktop-foundation.md) | 桌面端架构基线（Windows / macOS / Linux） |
| [desktop-packaging.md](desktop-packaging.md) | Nucleus/Tao + GraalVM Native Image 打包 |
| [aur-publishing.md](aur-publishing.md) | AUR 双包（源码构建 / 二进制重打包）发布 |
| [app-links.md](app-links.md) | GitHub Pages 资源深链 |

## 我想改界面

| 文档 | 内容 |
| --- | --- |
| [refined-expressive-design-system.md](refined-expressive-design-system.md) | 视觉语义：颜色、形状、层次，不覆盖用户主题 |
| [ui-motion-system.md](ui-motion-system.md) | Material 3 Expressive 动效的使用范围 |
| [provider-brand-icons.md](provider-brand-icons.md) | 音源品牌图标：本地内置、统一圆角、运行时不拉第三方图 |

## 我想了解某个功能

| 文档 | 内容 |
| --- | --- |
| [playlist-migration.md](playlist-migration.md) | 歌单迁移流程、持久化与检查点、各平台后台能力 |
| [listening-history-roadmap.md](listening-history-roadmap.md) | 听歌历史与统计的规划 |
| [cast-dlna.zh-CN.md](cast-dlna.zh-CN.md) | 投屏实现与排障 |
| [community-source-audit.zh-CN.md](community-source-audit.zh-CN.md) | 社区音源全量台账、静态审查、LX模拟验证、阶段 F 真实网络验证（含失败请求/错误码/域名拒绝） |
| [lx-action-protocol-audit.zh-CN.md](lx-action-protocol-audit.zh-CN.md) | LX Runtime action 协议与搜索调用链审计（G0：协议无 search / 桥层兼容但产品无调用链 / 能力矩阵） |
| [community-source-audit.zh-CN.md](community-source-audit.zh-CN.md) | 追加 G1 合规候选（§13）/ G2 MusicFree 来源发现与受控适配层（§14） |
| [community-source-audit.zh-CN.md](community-source-audit.zh-CN.md) | 阶段 I CommonJS 兼容性/Node 子集/沙箱边界/真实插件验证（§22），含封送与 maxStackSize 旧结论修正 |
| [community-source-audit.zh-CN.md](community-source-audit.zh-CN.md) | 阶段 J0 Gateway 网络出口整改（§23）：重定向/流式限额/axios 编码/SHA 绑定/DNS 审查 |
| [community-source-audit.zh-CN.md](community-source-audit.zh-CN.md) | 阶段 J1（收窄）Gateway 共用网络底座真实验证（§24）：真实 Ktor OkHttp 对照实验 + cancel 缺口修复 |
| [community-source-audit.zh-CN.md](community-source-audit.zh-CN.md) | 阶段 J1.1 BodyStream 异常语义与生命周期修复（§25）：异常不再吞成 EOF + 串行契约 + 生命周期 |
| [community-source-audit.zh-CN.md](community-source-audit.zh-CN.md) | 阶段 J2 单一音源 Audiomack 真实搜索接入（§26）：正式 Provider/真实搜索/模型映射/验证等级 |

## 阅读顺序建议

- 第一次接触代码库：`architecture.md` → `app-shell-architecture.md` → 目标功能所在 `feature/*` 模块。
- 只关心私人改造：`personal-music-roadmap.zh-CN.md` → `cast-dlna.zh-CN.md`。
- 只关心发布：`desktop-packaging.md` 或 `aur-publishing.md`。
