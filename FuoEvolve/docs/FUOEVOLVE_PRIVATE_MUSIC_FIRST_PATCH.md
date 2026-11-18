# FuoEvolve 私人音乐改造：第一阶段

## 本次修改

- 新安装默认启用网易云音乐、QQ 音乐和 Bilibili，增加中文歌曲搜索来源。
- YouTube Music 继续保留在音源列表中，但不默认启用；其网络可达性和服务可用性依地区而异。
- Android 内置 `providers.json` 与共享层默认音源配置保持一致。
- 多音源搜索改为并发执行；单个来源异常时保留其他来源结果，并把失败归属到对应音源。协程取消仍会正常向上传播。
- 增加共享层单元测试，固定默认音源策略，避免后续改动意外回退。
- 修正搜索聚合器与当前 `ProviderSearchResults.failure` 数据契约的对接，避免继续使用旧的 `errorMessage` 字段。

## 兼容性说明

本次只调整新安装/无既有音源配置时的默认值。已经安装过旧版本、并且保存了 `enabled_provider_ids` 的用户，其显式设置不会被静默覆盖。升级后如仍只看到网易云，请进入“设置 - 音乐源”，手动启用 QQ 音乐和 Bilibili。

## 尚未完成

这不是“免费解锁全曲库”改造。各平台仍按自身账户权限、地区、歌曲授权和实际接口能力提供播放资源。本次没有绕过会员、DRM 或下载权限，也没有声称热门歌曲均可免费无损播放。

下一阶段建议（已在后续两轮完成，记录见 [personal-music-roadmap.zh-CN.md](personal-music-roadmap.zh-CN.md)）：检查统一搜索的并发、错误聚合和跨源歌曲匹配，增加歌手/专辑/时长/版本校验，并为每个音源补充未登录、免费账户、无权限、接口异常等测试。

## 验证状态

- 已检查 Kotlin 默认配置与 Android JSON 配置的一致性。
- 已新增默认音源单元测试。
- 后续已在本地补齐全量构建与测试：Gradle 9.5.0 可正常使用，`core:model`、`playback:api`、`feature:*`、`shared`、各 provider、`desktopRuntime`、`androidApp` 的测试全部通过，详见 [personal-music-roadmap.zh-CN.md](personal-music-roadmap.zh-CN.md) 的「本轮实际执行的验证」。

> 本文只记录第一阶段的范围，后续的版本匹配、账户音质、统一音质模型与投屏连播都不在这里，见路线图文档与 [cast-dlna.zh-CN.md](cast-dlna.zh-CN.md)。
