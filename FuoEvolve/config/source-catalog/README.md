# 受控音源目录

这里发布 FuoEvolve **唯一信任**的音源清单。客户端不会直接订阅任何社区仓库，只会读取本目录；
社区仓库（`pdone/lx-music-source`、`shellsec/lx-music-source`）只作为**候选来源**，人工审核后
才可能进入 `source-catalog.json`。

## 文件

| 文件 | 作用 | 客户端是否读取 |
| --- | --- | --- |
| `source-catalog.json` | 已批准清单（当前为空，见下） | 是 |
| `source-catalog.json.sig` | 目录原始字节的 Ed25519 签名（Base64） | 是 |
| `source-catalog.schema.json` | 目录结构约束 | 否 |
| `candidates.json` | 抽样审查记录（8 个脚本的结论） | 否 |
| `resolver-catalog.json` | 允许接入的解析器（Jamendo 等） | 否（首版只作协议说明，Phase 3 接入时读取） |
| `tools/sign-catalog.mjs` | 签名/验签工具 | 否 |

## 为什么 `sources` 是空的

首版没有任何第三方脚本通过完整审核，因此**没有任何脚本可以被自动安装**。
这不是遗漏，而是设计：自动更新机制一旦把未审核脚本推到家人设备上，就不再可控。
空数组让"目录同步、签名、沙箱、权限限制、失败回滚"这套机制可以先跑通，
等某个脚本真正过完门禁，再把条目加进来。

## 加入一个新脚本的门禁

1. **来源可追溯**：必须是公开仓库里的具体文件，记录 `sourceRepo` + `sourcePath`。
2. **静态扫描通过**：`SourceStaticPolicy` 会拒绝 `eval`/`new Function`/动态 `import`、
   Node 宿主能力、`WebSocket`、卡密、SVIP 定位、`_0x` 混淆、以及**未在 `allowedHosts` 声明的域名**。
3. **域名全部声明**：脚本运行时只能请求 `allowedHosts` 里的域名，宿主代理执行，脚本拿不到 socket。
4. **许可证明确**：`license` 为 `unknown` 只能进隔离区，不能设 `autoInstall: true`。
5. **运行时冒烟**：在 `provider/lxruntime` 里能初始化、能对目标平台返回可播放 URL；
   连续 3 次通过才允许 `autoEnable: true`。
6. **审核状态 = `APPROVED`**，且 `reviewedAt` 填写审核日期。
7. **每次脚本升级都要重跑上面 1–6**：旧版通过不代表新版可信。
8. 明确排除：会员权益绕过、内嵌卡密、需要用户 Cookie 转发给第三方、高度混淆无法审计。

`SourceCatalogParser` 会在客户端再做一遍 2/3/4/6 的机械校验：`autoInstall: true` 但状态不是
`APPROVED` 会被直接判为非法目录（整份拒绝，而不是跳过该条目）。

## 发布流程

```bash
# 1. 修改 source-catalog.json（务必保持 LF 换行，签名是对原始字节做的）
#    仓库根的 .gitattributes 已对 config/source-catalog/*.json 标记 -text，
#    防止 Windows 的 core.autocrlf 在检出时转成 CRLF 让验签永久失败。
# 2. 重新签名（私钥不入库，用环境变量传入）
FUOEVOLVE_CATALOG_KEY=<64位十六进制私钥种子> \
  node tools/sign-catalog.mjs --sign --catalog source-catalog.json

# 3. 本地验签
node tools/sign-catalog.mjs --verify \
  --pub ac38e1f21af2fe5cc4d1683cffc824b16b41ad54d33577f92ae55263efa9cb05 \
  --catalog source-catalog.json

# 4. 提交 json 与 .sig 两个文件（必须成对更新）
```

客户端内置的公钥在
`provider/sourceupdate/src/commonMain/kotlin/org/feeluown/mobile/sourceupdate/SourceUpdateDefaults.kt`。
换密钥需要同时改代码并发版，因此密钥泄露的代价是「必须发新版」，请妥善保管私钥。

## 客户端拿到的地址

默认目录地址指向本仓库的 raw 地址，并配两个公共 GitHub 代理作为**下载通道**：

```
https://raw.githubusercontent.com/miaohongyun/my_FeelUOwn/master/FuoEvolve/config/source-catalog/source-catalog.json
```

代理只影响"能不能下载到"，不影响"下载到的是否可信"——脚本仍要过 SHA-256 与目录签名。
代理域名写死在客户端 allowlist 里，不接受目录远程下发新的代理地址。

## 国内网络下的现实

`raw.githubusercontent.com` 在国内经常不可达，因此：

- 拉取失败会依次尝试代理镜像；
- 全部失败时**保留上次成功的目录与已安装脚本**，只把失败次数加一，按 1h/4h/12h/24h 退避重试；
- 真正的稳定做法是自建 CDN 或对象存储，把 `SourceUpdateDefaults.CATALOG_URL` 换成自己的地址。
