# FeelUOwn 下载插件

FeelUOwn 下载插件，不提供（也不建议提供）安装包，需要的使用者可以自己下载安装。

## 第二版功能说明
### 下载按钮
下载按钮会集成到歌曲的右键菜单里面。

### 下载歌曲
只支持下载 v2 model 的歌曲。下载歌曲的时候，如果歌曲已经存在，则跳过下载。
下载歌曲时，会自动给歌曲添加 id3 tag 信息。

如果需要将文字（从繁体）自动转成简体，可以安装 `pip3 install inlp` 依赖，并配置
```python
app.config.dl.CORE_LANGUAGE = 'cn'
```

### 歌曲文件默认命名规则
`{title}__{artists_names}__{album_name}__{kbps}__{duration_ms}.{ext}`
`title`, `artists_names`, `album_name` 默认值为 unknown。
当任一一个值含有 `__` 分隔符的时候，都会进行转义。
kbps 默认值为 0kbps，duration_ms 默认值为 0。

### 真无损校验

下载完成后会自动校验文件是不是「真无损」，而不是 320kbps MP3 转码出来的假 FLAC：

- **容器层**：codec 本身就是有损格式（mp3/aac/opus）→ 直接标记
- **频谱层**：有损转码在截止频率处留下几十 dB 的陡崖，真实录音是连续滚降
- **位深层**：声称 24bit 但只有 16bit 有效精度 → 标记为假 Hi-Res

需要系统安装 `ffmpeg` / `ffprobe`。装了 numpy 会快很多：`pip install 'fuo_dl[lossless]'`。
没装 ffmpeg 时只按编码格式判定，不会报错。关闭方式：

```python
app.config.dl.VERIFY_LOSSLESS = False
```

### 开放无损音源

`fuo_dl.open_sources` 可以从 CC 授权 / 公共领域曲库下载真无损，不涉及任何会员绕过：

```bash
python -m fuo_dl.open_sources search "beethoven moonlight"
python -m fuo_dl.open_sources download "beethoven moonlight" --index 1 --dest ~/Music
```

支持 Internet Archive（公共领域录音，无需 key）和 Jamendo（独立音乐，需要免费申请的
`client_id`，可用环境变量 `JAMENDO_CLIENT_ID` 传入）。下载完会自动跑一遍真无损校验，
没通过会提示「不要当作无损收藏」。

### 点击下载时自动补无损

点击下载按钮后，如果原音源给的不是无损格式（mp3/aac）或者根本取不到流，会自动拿
「标题 + 艺人」去开放曲库搜一遍，命中就弹框让你挑版本，选中后下载并自动校验。
没命中或你选了「跳过」就按原音质正常下载。

```python
app.config.dl.OPEN_SOURCE_FALLBACK = False   # 关掉这个回退
app.config.dl.JAMENDO_CLIENT_ID = "..."      # 启用 Jamendo
```

匹配规则偏保守：标题归一化后必须相等，或较长一方包含较短一方且长度 ≥ 6 个字符；
有艺人信息时优先保留艺人匹配的版本。宁可漏掉，也不下到翻奏版或同名不同曲。

**注意**：开放曲库只覆盖古典、爵士、现场录音、独立音乐。主流流行专辑搜不到，
这部分仍然只会下到 320k。

### 播放时自动补无损

打开插件后，播放链路会自动接管：

1. 原音源本身就是无损（flac/alac/wav…）→ 原样播放，不做任何额外请求；
2. 这首歌之前已经缓存过无损文件 → 直接播放本地文件；
3. 原音源是有损流 → 去开放曲库找同名曲目，下载并跑一遍真无损校验。
   **第一次播放会等它准备好再播**，默认最多等 10 秒；超时则先按原音质播放，
   后台继续下载，下次播放直接用缓存
   （`<FeelUOwn CACHE_DIR>/fuo_dl/lossless`，索引存在同目录的 `index.json`）。
4. 当前歌曲一开始播放，就在后台为**下一首**预取无损（`PLAY_PREFETCH_NEXT`），
   切歌时通常已经命中缓存，不用再等。原音源本身无损的下一首会跳过预取。

等这么久是因为一首 FLAC 动辄几十 MB；不想等就调小等待时间，0 表示不等待。
检索在独立线程里跑，单个 HTTP 请求超时 3 秒。

```python
app.config.dl.PLAY_OPEN_SOURCE_FALLBACK = False   # 关掉播放时的无损补充
app.config.dl.PLAY_WAIT_TIMEOUT = 0               # 首次播放不等待，后台准备
app.config.dl.PLAY_PREFETCH_NEXT = False          # 关掉下一首预取
```

### 自定义播放地址

开放曲库覆盖不到的歌，可以自己贴地址：在歌曲列表右键 →「播放自定义音频地址…」，
支持 `http/https` 音频直链和本地文件路径。保存过的地址会写到
`<FeelUOwn DATA_DIR>/fuo_dl/custom_media.json`，下次启动仍可直接选择播放，
也可以在同一个菜单里删除。

首次使用时会预置几条公开授权的测试音源，方便验证播放/下载链路：

| 条目 | 来源 | 许可 |
|---|---|---|
| FLAC 真无损测试 | IETF CELLAR FLAC test files（经 jsDelivr） | CC0 1.0 |
| Kevin MacLeod - Mesmerizing Galaxy | incompetech.com | CC BY 4.0 |
| Kevin MacLeod - Lord of the Rangs | incompetech.com | CC BY 4.0 |
| Kevin MacLeod - Adeste Fideles | incompetech.com | CC BY 4.0 |

CC BY 条目如需二次分发请保留作者署名；测试完可在菜单里直接删除。

播放自定义地址时，下载按钮下载的就是该地址本身，文件名取地址里的文件名。
这条路径只做播放和记录，不涉及任何来源解析或会员绕过。

## Roadmap

- [ ] (#A) 在 FeelUOwn UI 反馈当前文件下载进度
- [ ] (#B) 可以配置并发度，目前是一个一个文件下载
- [ ] (#B) 给 fuo 命令行工具添加 `dl` 子命令，比如 `fuo dl fuo://xxx/songs/1`
- [ ] (#A) 提供一个 cli 工具来方便 debug
- [ ] (#B) 程序重启可以从上次的临时文件恢复

## Changelog

### v0.3 - 2022-01-23

* 适配 v3.8.1 的 FeelUOwn
* 修复部分已知问题

### v0.2 - 2020-10-18

* 修复下载内容和文件名不符的重大 bug
* 默认使用基于 requests 的多线程下载器

### v0.1 - 2020-09-07

* 支持 FeelUOwn v3.5.3
* 提供 CurlDownloader 来简单的下载歌曲
