# my_FeelUOwn

FeelUOwn 相关代码的个人集合与本地改造备份。

| 目录 | 说明 |
|---|---|
| `FeelUOwn/` | 桌面版 FeelUOwn（Python + PyQt6），与上游 feeluown/FeelUOwn v5.1.3 一致 |
| `FuoEvolve/` | 移动端（Kotlin + Compose Multiplatform），含本地改造：下载选音质、DLNA 投屏/连播、Mode B 智能音源路由（Jamendo/iTunes/电台公开源）等 |
| `feeluown-cast/` | 桌面端 DLNA 投屏插件（本地改造，SSDP 发现 + SOAP 控制 + 本机代理流） |
| `feeluown-download/` | 下载插件，含下载前选音质功能（本地改造） |
| `mode_b/` | Python 端 Mode B 智能音源路由实现（独立可运行 + 单测 + 真网冒烟脚本） |
| `source_router/` | Python 端音源路由早期实现 |

> 注意：FeelUOwn 上游项目为 GPL-3.0，README 声明不得用于任何商业用途，请遵守其许可协议。

本仓库仅用于个人代码备份与同步，不含任何密钥/凭证文件。
