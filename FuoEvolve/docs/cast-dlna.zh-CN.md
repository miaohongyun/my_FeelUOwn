# 投屏（DLNA）设计与实现

本文记录「设备投放」的实现边界、链路与判定规则。面向维护者；用户视角的操作说明见 [USAGE.md](../USAGE.md) 第 8 节。

## 目标与非目标

**目标**：把手机正在播放的队列投到同一局域网的 DLNA 渲染器，并且能一首接一首地播下去；手机可以息屏或暂停，电视自己往下放。

**非目标**：

- 不做 QPlay / TME Connect 这类厂商私有协议（需要腾讯侧认证，接入不可行）。
- 不做电视端的遥控 UI；控制仍回到手机上的投屏面板。
- 不接管电视音量、EQ、画面设置，这些超出 AVTransport 的职责。

## 模块划分

投屏是一个独立 Gradle 模块 `:feature:cast`，只依赖 `:core:model` 与协程库，不依赖任何音源或播放器实现。

| 文件 | 职责 |
| --- | --- |
| `CastContracts.kt` | 对外契约：`CastControllerPort`、`CastState`、`CastStatus`、`NoOpCastControllerPort` |
| `CastPorts.kt` | 平台端口：发现、传输、代理流、曲目来源 |
| `CastController.kt` | 会话状态机：推送、看护、推进队列、结束会话 |
| `DlnaProtocol.kt` | SOAP 报文、DIDL-Lite 元数据、时间码与 XML 工具 |
| `SsdpProtocol.kt` | `M-SEARCH` 与 `LOCATION` 解析 |
| `MiniXml.kt` | 够用即可的 XML 解析器，避免为一个设备描述引入完整 XML 栈 |
| `DeviceDescriptionParser.kt` | 从设备描述里取 AVTransport 的 `controlURL`，并修正主机名 |

分层依赖是单向的：`CastController` 只认端口，具体实现由宿主注入。

```
:feature:cast  ←  :shared (CastFeatureFactory 绑定播放器)
      ↑
androidApp (SSDP / SOAP / 代理流 的平台实现)
```

`:shared` 的组合层负责把「播放器当前曲目 / 队列 / 音量」翻译成投屏需要的输入；`:feature:cast` 反过来不认识 `PlaybackSession`。

## 设备发现

1. 向 `239.255.255.250:1900` 发 `M-SEARCH`，**三个 ST 都发**：`ssdp:all`、`MediaRenderer:1`、`AVTransport:1`。Android TV 通常只回 `ssdp:all`，部分电视只回自己的类型，全发才能都发现。
2. 从响应头里取 `LOCATION`；丢弃 `NOTIFY` 与非响应报文。
3. 拉取设备描述 XML，遍历 `deviceList` 找 `AVTransport` 服务的 `controlURL`。
4. **主机名修正**：部分实现（例如 Windows Media Player 的 DLNA 服务）把 `controlURL` 写成 `ip6-localhost`，必须用 `LOCATION` 的 host:port 覆盖，否则会出现 `ECONNREFUSED`。

## 一次投屏的链路

1. 取当前曲目 → 通过 `PlaybackProviderSourcePort` 解析成 `PlaybackPayload`（带鉴权头与真实地址）。
2. 本机启动代理流，拿到一个局域网可访问的 URL（见下文）。
3. 组装 DIDL-Lite 元数据：标题、歌手、专辑、时长、MIME、DLNA profile。
4. `SetAVTransportURI` 写入地址，再 `Play`（若用户暂停过则只 `Pause`）。
5. 成功后释放上一条代理流，记录 `pushedTrackId`，状态置为 `Casting`。
6. 启动渲染器看护（ticker 默认 2 秒一次）。

推送串行化：队列跟随与看护循环可能同时触发，`pushMutex` 保证不会并发改写渲染器状态。

## 队列连播的判定

渲染器只有一个 URI，且手机在投屏期间被静音、可能处于暂停态，所以「这一首放完了」必须由电视自己告诉我们。看护循环每 2 秒取一次 `CastTransportSnapshot`（`GetTransportInfo` + `GetPositionInfo`）。

判定规则（每条都对应真实设备的行为）：

| 观察到的状态 | 处理 | 原因 |
| --- | --- | --- |
| `PLAYING` | 记 `rendererStarted = true`，同时记录位置 | 只有见过播放，后续的 `STOPPED` 才可信 |
| `PLAYING` 且位置到达末尾、且与上次位置相同 | 视为播完 | 少数电视播完仍报 `PLAYING`，只看状态会永远不推进 |
| `PAUSED_PLAYBACK` | 忽略 | 用户主动暂停，不是播完 |
| `STOPPED` 但还没见过 `PLAYING` | 忽略 | `SetAVTransportURI` 之后常先回 `STOPPED`，误判会跳歌 |
| `STOPPED` 且见过 `PLAYING` | 推进队列 | 正常播完 |
| 状态为空 / 请求失败 | 跳过本轮 | 单次 SOAP 失败不该结束会话 |

推进时还要避免「跳两首」：

- 若 `currentTrack().id != pushedTrackId`，说明手机已经自己切歌了，直接推当前曲目，**不再**调用 `playNext()`。
- 否则调用 `playNext()`，等播放器真正切到新曲目（最多约 3 秒）再推送。
- 单曲循环会把同一首交回来，此时用 `force = true` 重推同一个 URI。
- `advancing` 标志防止两个播完信号并发推进。
- 队列没有下一首时，`finishQueue()` 释放代理流、停掉看护、回到 Idle，并提示「队列已经播完了」。

## 代理流

电视要的是一个**不带鉴权头**的普通 HTTP 地址，而音源返回的地址常带 Cookie / Referer / 过期参数，所以手机必须自己中转：

- 路径形如 `http://<lan-ip>:<port>/stream/<token>/<sid>/audio.<ext>`，`token` 每次启动随机生成，`sid` 每首歌一个。
- 只处理这一条路径，其它请求直接 404；token 不匹配同样 404。
- 透传 `Range` 与 `If-Range`，把上游的 `206` / `Content-Range` / `Accept-Ranges` 原样转给电视，拖动进度条才能工作。
- `release()` 只移除 `sid`，已经建立的连接会自然结束。

**为什么用裸 Socket 而不是 `HttpURLConnection` 拉上游？** Android 的 `network_security_config` 对明文 HTTP 有白名单，电视的控制地址 `http://192.168.x.x:8200` 会被拦。这里的取舍是：局域网控制面用裸 Socket（`AndroidLanHttp`），不为了投屏放宽全局明文策略；代理流拉上游仍走 `HttpURLConnection`，与 App 自身播放保持同一套策略。

## 手机静音

投屏时手机继续播放（静音）有两个作用：它是队列的时钟，也是前台服务保活的依据。

- 静音维度是 `activeDevice != null`，**不是** `CastStatus.Casting`。中途解析失败会让状态短暂变成 `Error`，按状态判定会误把声音放出来。
- 停止投放后恢复投屏前的音量，而不是恢复成某个固定值。
- `AndroidNativeAudioEngine.setVolume` 原本是空实现（音量字段只存在于状态里），投屏静音在 Android 上不生效；现在通过 `MediaController.volume` 真正下发，并在 controller 重连后用 `desiredVolume` 重新应用。

## 面板上的暂停/播放

`togglePlayPause()` 读一次 `GetTransportInfo`：`PAUSED` 则 `Play`，否则 `Pause`。用户的意图记录在 `remotePaused`：

- 暂停后队列若因为别的原因为推进（例如电视被别处操作），新曲目只 `SetAVTransportURI` + `Pause`，不会突然响。
- 再次点播放会清掉 `remotePaused`。

## 平台差异

| 平台 | 状态 |
| --- | --- |
| Android | 完整实现：SSDP 发现、SOAP 传输、代理流、静音 |
| Windows / iOS | `NoOpCastControllerPort`，投屏面板不会出现可投放设备 |

## 已知限制

- 代理流与手机本地播放会各自向上游取一次音频，投屏期间带宽约为 2×。
- 只支持 AVTransport（`SetAVTransportURI` / `Play` / `Pause` / `Stop`），不支持 `Seek` 由手机端发起；进度调整请在电视遥控器上做。
- 不支持需要配对码或 DRM 的渲染器。
- 电视端播放失败（例如不支持该容器的 FLAC）时，手机上只能看到状态停在 `STOPPED`，会被当作播完并推进到下一首。
- 尚未在真实电视上完成系统化验证（见下）。

## 测试

`./gradlew :feature:cast:desktopTest`，覆盖：

- SSDP 报文与 `LOCATION` 解析、设备描述解析与 `ip6-localhost` 修正；
- DIDL-Lite 转义与时间码解析；
- 首投、切歌、暂停语义、`STOPPED` 误报、位置卡住、队列播完、同曲重推、双信号并发；
- 代理流在单元测试中以 fake 端口替代，真机行为需实机确认。

测试注意：`runTest` 里控制器会留常驻收集器，用例结尾必须 `stopCast()`，否则报 `UncompletedCoroutinesError`；默认 ticker 是 `emptyFlow()`，测试用 `MutableSharedFlow` 手动驱动，不要用无限 `delay` 循环。

## 排障

| 现象 | 先看什么 |
| --- | --- |
| 搜不到设备 | 手机与电视是否同网段；路由器是否开了 AP 隔离；电视的投屏/媒体共享是否开启 |
| 搜到但一点就失败 | 设备描述里的 `controlURL` 是否可达；是否 `ip6-localhost` 这类需要修正的地址 |
| 播一首就停 | 电视是否回过 `PLAYING`；位置是否在末尾卡住；队列是否真的还有下一首 |
| 电视没声音但手机在走 | 代理流的 `Content-Type` 与 DLNA profile 是否被电视接受 |
| 面板暂停后又自己响 | `remotePaused` 是否被后续 `Play` 清掉 |
