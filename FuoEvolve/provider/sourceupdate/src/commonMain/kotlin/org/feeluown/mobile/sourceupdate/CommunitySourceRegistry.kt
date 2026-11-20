package org.feeluown.mobile.sourceupdate

/**
 * 阶段 E 全量社区音源候选登记表（E0 盘点 / E1 取证 / E2 静态审查）。
 *
 * 这是**人工审查的取证台账**，不是信任根，也不是可安装目录：
 * - 每个候选有唯一稳定 [candidateId]；同名不同版本分别登记；
 * - [sha256]/[fileSize]/[version] 来自 E1 实际下载文件的计算，未获取的一律 null 且状态 UNVERIFIABLE；
 * - [status] 严格按证据升级，禁止未下载标 HASH_VERIFIED、未静态审查就标 LX_RUNTIME_LOAD_PASSED；
 * - 任何候选都**不写 APPROVED**——批准/安装必须走用户显式操作与受控目录签名发布流程。
 *
 * 数据来源（2026-11-20 实际联网取证）：
 * - shellsec/lx-music-source 主清单 sources.json（24 条，含真实脚本 URL）；
 * - pdone/lx-music-source 官方最新版（flower/sixyin/huibq/lx/ikun/grass/juhe）；
 * - xzh767/lxmusic-source-all（zhizun/废材公社）；
 * - Web 公开检索的仓库/发布页（星澜 lick563/Xinglan-aggregate-sound-sources、
 *   MusicFree 订阅站 hebijunge/musicfree-plugins 等）。
 * 清单原文与脚本快照保存在离线取证目录，报告见 docs/community-source-audit.zh-CN.md。
 */

/** 候选来源类型。 */
enum class CommunitySourceType {
    /** 独立 LX 脚本。 */
    STANDALONE,
    /** 聚合接口（包装多个后端）。 */
    AGGREGATOR,
    /** 插件订阅集合（含多个插件，非单一音源）。 */
    SUBSCRIPTION_SET,
    /** 需要用户自有 Token/Cookie 的配置型来源。 */
    TOKEN_CONFIG,
    /** 反编译版本，需额外核查来源/许可证/修改。 */
    DECOMPILED,
}

/** 审查状态（只上升不自动批准；每个状态都必须有对应证据）。 */
enum class CommunityAuditStatus {
    /** 已登记但未能获取可验证来源/文件。 */
    UNVERIFIABLE,
    /** 已登记，来源URL已知但尚未取证。 */
    DISCOVERED,
    /** 已下载到隔离区并计算 SHA256。 */
    FETCHED,
    /** 静态审查通过。 */
    STATIC_REVIEW_PASSED,
    /** 静态审查命中 BLOCK 规则。 */
    BLOCKED,
    /** 来源不明/混淆严重/需进一步调查，隔离。 */
    QUARANTINED,
    /** 关键行为无法判断。 */
    OBFUSCATED,
    /** LX Runtime 模拟加载通过（非真实音频）。 */
    LX_RUNTIME_LOAD_PASSED,
    /** 真实搜索验证通过（本阶段默认不做）。 */
    REAL_SEARCH_PASSED,
    /** 可进入下一轮受控测试（≠自动批准/安装）。 */
    ELIGIBLE_FOR_CONTROLLED_TEST,
    ;

    val isTerminalBlock: Boolean get() = this == BLOCKED || this == QUARANTINED || this == OBFUSCATED
}

/** 一条阶段 E 候选的完整取证记录。 */
data class CommunityCandidateRecord(
    val candidateId: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val sourceUrl: String? = null,
    val repositoryUrl: String? = null,
    val downloadUrl: String? = null,
    val version: String? = null,
    val sha256: String? = null,
    val fileSize: Long? = null,
    val discoveredAt: String = "2026-11-20",
    val sourceType: CommunitySourceType,
    val platforms: List<String> = emptyList(),
    val status: CommunityAuditStatus,
    /** 静态规则命中（ruleId -> 次数），来自离线扫描。 */
    val staticFindings: Map<String, Int> = emptyMap(),
    val obfuscationMarkers: Int = 0,
    val declaredHosts: List<String> = emptyList(),
    /** 人工复核结论/风险证据/阻断原因。 */
    val evidence: String,
    /** 与已登记候选重复的 id（E1 去重）。 */
    val duplicateOf: String? = null,
) {
    val hasVerifiedHash: Boolean get() = sha256 != null
    val isBlocked: Boolean get() = status.isTerminalBlock
}

/** 聚合项目/订阅集合的子项映射（E4 拆解的台账，本阶段只登记不验证）。 */
data class CommunityBundleRecord(
    val bundleId: String,
    val name: String,
    val sourceUrl: String?,
    val claimedCount: Int?,
    val verifiedCount: Int,
    val status: CommunityAuditStatus,
    val evidence: String,
    val children: List<String> = emptyList(),
)

/**
 * shellsec 主清单里 24 条脚本的取证常量（E1 实际计算）。
 * 键 = 清单 id；值 = 完整 sha256（小写 hex）。
 */
private const val SHELLSEC_BASE = "https://github.com/shellsec/lx-music-source/blob/master/sources"
private const val PDONE_BASE = "https://github.com/pdone/lx-music-source/blob/main"
private const val XZH_BASE = "https://github.com/xzh767/lxmusic-source-all/blob/main"

/**
 * 全量候选登记（E0-E2）。不伪造未获取的 SHA；找不到的明确 UNVERIFIABLE。
 */
object CommunitySourceRegistry {
    val candidates: List<CommunityCandidateRecord> = buildList {
        // ===== A. 此前五个候选（本阶段重新取证） =====
        add(CommunityCandidateRecord(
            candidateId = "changqing", name = "长青SVIP音源(二改修复版)",
            aliases = listOf("长青", "元力菌长青"),
            sourceUrl = "https://mp.weixin.qq.com/ (微信公众号 元力菌 / TZB679)",
            repositoryUrl = "https://github.com/shellsec/lx-music-source",
            downloadUrl = "$SHELLSEC_BASE/changqing/%E9%95%BF%E9%9D%92SVIP%E9%9F%B3%E6%BA%90(%E4%BA%8C%E6%94%B9%E4%BF%AE%E5%A4%8D%E7%89%88).js",
            version = "v1.2.0", sha256 = "48d5fed8b46344e9298d55427382ce573485a264a2ae287ed829e2f2548223e8",
            fileSize = 44876, sourceType = CommunitySourceType.STANDALONE,
            status = CommunityAuditStatus.BLOCKED,
            staticFindings = mapOf("membership-bypass" to 1, "obfuscated" to 2806),
            obfuscationMarkers = 2806,
            declaredHosts = listOf("13413.kstore.vip"),
            evidence = "BLOCK 保持：@name 含 SVIP；@update_url=13413.kstore.vip（历史 BLOCK 域名）；" +
                "@mail=微信公众号元力菌（SVIP 反编译来源）；控制流平坦化 2806 处 _0x 混淆，无法逐行审计，" +
                "无法可靠拆分合规功能。不关闭 membership-bypass 规则，不把 SVIP 包装成音质增强。" +
                "注意：本轮文件 sha 与历史 6a94298… 不同（二改修复版），属同名不同版本，分别登记。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "feichangdao", name = "非常刀", aliases = listOf("非常刀 v5"),
            repositoryUrl = "https://github.com/shellsec/lx-music-source",
            downloadUrl = "$SHELLSEC_BASE/feichangdao/%E9%9D%9E%E5%B8%B8%E5%88%80.js",
            sha256 = "49486cab064e64d5ad63723e670c44ee55eadadcc790ccf493351fbde64284d7",
            fileSize = 17846, sourceType = CommunitySourceType.STANDALONE,
            status = CommunityAuditStatus.QUARANTINED,
            obfuscationMarkers = 0,
            declaredHosts = listOf("175.27.166.236", "api-vehicle.volcengine.com", "api.chksz.top",
                "api.qishui.com", "api.xcvts.cn", "cp.chksz.top", "music-api.gdstudio.xyz",
                "music.haitangw.cc", "music.nxinxz.com", "musicapi.haitangw.net", "oiapi.net", "t.me"),
            evidence = "SHA 与历史记录 49486ca…64284d 完全一致（文件未变）。静态正则无 BLOCK 命中，" +
                "但域名含裸 IP 175.27.166.236、t.me 与多个第三方 API；含 hires/flac24bit/master 音质映射。" +
                "历史 LX 模拟初始化通过（SIMULATED_INIT），但真实音质/授权未验证。QUARANTINE：" +
                "需逐域名确认授权与数据流，禁止通配符白名单，不做商业付费音质真实下载。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "yuningxi", name = "lx-玉宁熙-Pro", aliases = listOf("玉宁熙 v1.2.5", "玉宁熙"),
            repositoryUrl = "https://github.com/shellsec/lx-music-source",
            downloadUrl = "$SHELLSEC_BASE/yuningxi/lx-%E7%8E%89%E5%AE%81%E7%86%99-Pro.js",
            sha256 = "5dd4752c16c17f7bab45dccf87dcd3f3c2a6e251e62bad68bde9554bfd275f79",
            fileSize = 144621, sourceType = CommunitySourceType.STANDALONE,
            status = CommunityAuditStatus.QUARANTINED,
            obfuscationMarkers = 0,
            evidence = "SHA 与历史 5dd4752…d275f79 完全一致。URL 由字符串拼接，静态域名不可见（host_count=0）；" +
                "历史声明 2s/4 次限频。静态 BLOCK 未命中，但域名需运行时专项逆向；" +
                "QUARANTINE：不放宽 SSRF，遵守限频不并发。历史 LX SIMULATED_INIT 通过（非真实）。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "fish-music", name = "fish_music", aliases = listOf("Fish", "Fish v1.9.0"),
            repositoryUrl = "https://github.com/shellsec/lx-music-source",
            downloadUrl = "$SHELLSEC_BASE/fish-music/fish_music.js",
            version = "1.9.0", sha256 = "a711e152c4f28c32fd46b38839a70f476e0b669905773a90e2ce63246136af47",
            fileSize = 639619, sourceType = CommunitySourceType.STANDALONE,
            status = CommunityAuditStatus.OBFUSCATED,
            obfuscationMarkers = 47587,
            evidence = "重度混淆：47587 处 _0x 标记，控制流平坦化，无法静态审计网络与凭据行为。" +
                "历史域名 flower.tempmusics.tk 系运行时执行才暴露。不执行、不安装，保持隔离。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "flower-pdone", name = "野花", aliases = listOf("Flower", "野花音源"),
            sourceUrl = "https://www.winapps.cc/blog/3q27xp7v6f",
            repositoryUrl = PDONE_BASE,
            downloadUrl = "$PDONE_BASE/flower/latest.js",
            version = "1", sha256 = "15c166987edde41cf5917b3160c0ab253ff17e5e6bc6765c9eaa11a822c4aa97",
            fileSize = 10057, sourceType = CommunitySourceType.STANDALONE,
            status = CommunityAuditStatus.QUARANTINED,
            evidence = "pdone 官方 flower/latest.js v1。静态域名 grep 不可见（字符串拆分，host_count=0）；" +
                "历史通过执行发现真实域名 flower.tempmusics.tk，证明静态扫描与运行时域名白名单必须互补。" +
                "QUARANTINE：不执行，需运行时受控环境逆向域名后再评。",
        ))

        // ===== B. 新增独立音源（14 项；能取到真实文件的登记 SHA，否则 UNVERIFIABLE） =====
        add(CommunityCandidateRecord(
            candidateId = "xinghai", name = "星海音乐源",
            repositoryUrl = "https://github.com/shellsec/lx-music-source",
            downloadUrl = "$SHELLSEC_BASE/xinghai/%E6%98%9F%E6%B5%B7%E9%9F%B3%E4%B9%90%E6%BA%90.js",
            sha256 = "1f1aa4759c63496b4580a4e0d7de5a1ad70bd9c41154110f4fa05c363dc2fd15",
            fileSize = 22466, sourceType = CommunitySourceType.STANDALONE,
            status = CommunityAuditStatus.BLOCKED,
            staticFindings = mapOf("card-key" to 1),
            evidence = "静态命中 card-key：注释声明「卡密（可选）：填了就能用 QQ 等仅认证用户音源」——" +
                "依赖卡密访问认证音源，存在访问控制边界不清。BLOCK 待人工确认卡密机制与授权。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "sixyin", name = "六音 (SixYin)",
            repositoryUrl = PDONE_BASE, downloadUrl = "$PDONE_BASE/sixyin/latest.js",
            sha256 = "a407ffe0ab23c29cd32ce249b83e61c8e2b70719895f16034566ad7557282785",
            fileSize = 333015, sourceType = CommunitySourceType.STANDALONE,
            status = CommunityAuditStatus.BLOCKED,
            staticFindings = mapOf("dynamic-function" to 1, "obfuscated" to 16316),
            obfuscationMarkers = 16316,
            evidence = "new Function 动态执行 + 16316 处混淆，历史 LX 验证即因此 REJECT_AUTO_CATALOG。" +
                "无法静态审计，BLOCK 保持。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "yecao", name = "野草", aliases = listOf("野草🌾", "Grass"),
            repositoryUrl = "https://github.com/shellsec/lx-music-source",
            downloadUrl = "$SHELLSEC_BASE/grass/%E9%87%8E%E8%8D%89%F0%9F%8C%BE.js",
            version = "1", sha256 = "d694275f382427d3fef809fc77338ef014dc330af710cca6b3233ece8740510f",
            fileSize = 9176, sourceType = CommunitySourceType.STANDALONE,
            status = CommunityAuditStatus.QUARANTINED,
            evidence = "静态干净（0 域名 grep 可见，0 混淆），但历史执行发现域名 grass.tempmusics.tk。" +
                "QUARANTINE：需运行时受控逆向域名。pdone/grass 与 shellsec/grass SHA 相同（重复，见 duplicateOf）。",
            duplicateOf = "grass-pdone",
        ))
        add(CommunityCandidateRecord(
            candidateId = "nianxin", name = "念心音源",
            repositoryUrl = "https://github.com/shellsec/lx-music-source",
            downloadUrl = "$SHELLSEC_BASE/nianxin/%E5%BF%B5%E5%BF%83%E9%9F%B3%E6%BA%90.js",
            version = "1.0.2", sha256 = "86f6c05f8fef7169fd010a1da362824b5c4b7f5695f89d384fe53564f3fa88d2",
            fileSize = 26672, sourceType = CommunitySourceType.STANDALONE,
            status = CommunityAuditStatus.BLOCKED,
            staticFindings = mapOf("obfuscated" to 1241), obfuscationMarkers = 1241,
            evidence = "1241 处 _0x 混淆（\\x 编码），静态域名不可见（host_count=1 但为声明非请求），" +
                "无法可靠审计数据外传。BLOCK 待去混淆。宣传五平台覆盖未经真实验证。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "qdy", name = "全豆要[聚合音源]",
            repositoryUrl = "https://github.com/shellsec/lx-music-source",
            downloadUrl = "$SHELLSEC_BASE/qdy/%E5%85%A8%E8%B1%86%E8%A6%81%5B%E8%81%9A%E5%90%88%E9%9F%B3%E6%BA%90%5D.js",
            version = "9.3", sha256 = "213f73232562b528826dd97601e74212b517014e06fd614b28d8d8aa0dda400c",
            fileSize = 31090, sourceType = CommunitySourceType.AGGREGATOR,
            status = CommunityAuditStatus.BLOCKED,
            staticFindings = mapOf("membership-bypass" to 1),
            evidence = "静态命中 membership-bypass；聚合接口包装多后端，需拆解后端与会员绕过位置。" +
                "宣传「全 FLAC / 100% 成功率」无复核数据，不进入产品承诺。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "huibq", name = "HUIBQ 音源", aliases = listOf("Huibq"),
            repositoryUrl = PDONE_BASE, downloadUrl = "$PDONE_BASE/huibq/latest.js",
            sha256 = "a9adeb456690b523008038224091cee31977606302d79082eb06adb3c75c9146",
            fileSize = 2727, sourceType = CommunitySourceType.STANDALONE,
            status = CommunityAuditStatus.FETCHED,
            evidence = "pdone 官方 huibq/latest.js（2727B）。与 shellsec/huibi（回避聚合，83639B）" +
                "是不同文件、不同项目，已分别登记去重。静态无 BLOCK；宣传五平台 320k 未真实验证。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "tongyi", name = "统一音乐源",
            sourceUrl = "未定位到可验证的官方仓库/发布页",
            sourceType = CommunitySourceType.STANDALONE, status = CommunityAuditStatus.UNVERIFIABLE,
            evidence = "Web 检索未找到明确名为「统一音乐源」的官方仓库、原始脚本或版本记录；" +
                "与「统一音乐」类通用描述易混淆。不伪造来源/SHA，保持 UNVERIFIABLE。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "molan", name = "墨澜聚合音源", aliases = listOf("墨澜 v2.3.3"),
            repositoryUrl = "https://github.com/shellsec/lx-music-source",
            downloadUrl = "$SHELLSEC_BASE/molan/%E5%A2%A8%E6%BE%9C%E8%81%9A%E5%90%88%E9%9F%B3%E6%BA%90.js",
            version = "2.3.6", sha256 = "b6da06bf9a1730c4e579ebc7593949d3abcff4e0c61d4b32bd140adf347286ff",
            fileSize = 583536, sourceType = CommunitySourceType.AGGREGATOR,
            status = CommunityAuditStatus.BLOCKED,
            staticFindings = mapOf("obfuscated" to 38839), obfuscationMarkers = 38839,
            evidence = "38839 处 _0x 混淆，无法静态审计；聚合多后端（v4.0.0 合并了墨澜+星澜框架）。" +
                "宣传母带音质未验证。BLOCK 待去混淆与逐后端审查。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "yiyin", name = "忆音音源",
            sourceUrl = "CSDN/blog 提及（gitcode 镜像 V260504/第一批次）",
            sourceType = CommunitySourceType.STANDALONE, status = CommunityAuditStatus.UNVERIFIABLE,
            evidence = "仅在第三方博客/镜像描述中出现（HUIBQ/忆音 320k 组合），未定位到原作者仓库与可验证原始文件。" +
                "不把转载描述当原作者声明，不伪造 SHA。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "huanyin", name = "幻音",
            sourceType = CommunitySourceType.STANDALONE, status = CommunityAuditStatus.UNVERIFIABLE,
            evidence = "Web 检索未定位到可验证的「幻音」LX 脚本官方仓库/发布页/版本记录；" +
                "同名音乐 App 众多，无法确认目标音源身份。不伪造来源。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "kulao", name = "裤佬SVIP音源(二改整合版)",
            repositoryUrl = "https://github.com/shellsec/lx-music-source",
            downloadUrl = "$SHELLSEC_BASE/kulao/%F0%9F%91%96%E8%A3%A4%E4%BD%ACSVIP%E9%9F%B3%E6%BA%90(%E4%BA%8C%E6%94%B9%E6%95%B4%E5%90%88%E7%89%88).js",
            sha256 = "58e8cb1403a0eb394d6405dc560c3cd4329fa62aa19c5d753d048a9a6e626d97",
            fileSize = 16108, sourceType = CommunitySourceType.STANDALONE,
            status = CommunityAuditStatus.BLOCKED,
            staticFindings = mapOf("membership-bypass" to 1),
            evidence = "@name 含 SVIP，静态命中 membership-bypass；11 个域名需逐个审授权。" +
                "不得把 SVIP 功能包装成音质增强，BLOCK 保持。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "dujia-v7", name = "独家音源 V7", aliases = listOf("独家音源", "lx"),
            repositoryUrl = "https://github.com/shellsec/lx-music-source",
            downloadUrl = "$SHELLSEC_BASE/lx/%E7%8B%AC%E5%AE%B6%E9%9F%B3%E6%BA%90.js",
            version = "7", sha256 = "79a5c4703e060eaff6f8ffaac05d2fadf5e22b7729f6fe151d1cca2fa3935171",
            fileSize = 64094, sourceType = CommunitySourceType.STANDALONE,
            status = CommunityAuditStatus.BLOCKED,
            staticFindings = mapOf("membership-bypass" to 1),
            evidence = "静态命中 membership-bypass（SVIP 相关）；与 pdone/lx 为同一文件（SHA 相同，已去重）。" +
                "静态域名不可见（host_count=0）。BLOCK 待确认会员绕过具体位置。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "zhizun", name = "至尊源", aliases = listOf("至尊音源"),
            repositoryUrl = XZH_BASE, downloadUrl = "$XZH_BASE/zhizun.js",
            sha256 = "ddc095aca3dc4df5a00ea6df3b84a31d17c1055fad3e1ee09edd6308ccc17210",
            fileSize = 10089, sourceType = CommunitySourceType.STANDALONE,
            status = CommunityAuditStatus.FETCHED,
            evidence = "xzh767/lxmusic-source-all 的 zhizun.js（10089B）。静态无 BLOCK 命中，2 个声明域名。" +
                "历史 LX 模拟通过（列表含至尊），但真实平台覆盖/音质未验证。",
        ))

        // ===== C. 系列与功能型音源 =====
        add(CommunityCandidateRecord(
            candidateId = "ikun-v515", name = "ikun 音源 v515", aliases = listOf("ikun", "ikun 公益版"),
            repositoryUrl = PDONE_BASE, downloadUrl = "$PDONE_BASE/ikun/latest.js",
            sha256 = "8db6c45bc225f1f15fc2e96f7d9ad3bf2e49dd1f2a998cdf23704185ea41fe89",
            fileSize = 4991, sourceType = CommunitySourceType.STANDALONE,
            status = CommunityAuditStatus.FETCHED,
            evidence = "pdone 官方 ikun/latest.js（4991B）。静态无 BLOCK；据第三方描述覆盖五平台、128k~master，" +
                "但宣传音质与成功率未经真实验证。公益版/不同版本号需分别登记（此处仅登记实际获取的 v515 类文件）。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "mygo", name = "MyGO 音源",
            sourceType = CommunitySourceType.STANDALONE, status = CommunityAuditStatus.UNVERIFIABLE,
            evidence = "未定位到可验证的原作者仓库/原始脚本。「全平台母带」为宣传声明，无复核数据，" +
                "不得写入产品能力。不伪造来源/SHA。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "lingchuan", name = "聆川",
            sourceType = CommunitySourceType.STANDALONE, status = CommunityAuditStatus.UNVERIFIABLE,
            evidence = "Web 检索未定位到可验证的官方来源/脚本/版本记录，不伪造。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "miyin", name = "弥音",
            sourceType = CommunitySourceType.STANDALONE, status = CommunityAuditStatus.UNVERIFIABLE,
            evidence = "Web 检索未定位到可验证的官方来源/脚本/版本记录，不伪造。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "chunriying", name = "春日影",
            sourceType = CommunitySourceType.STANDALONE, status = CommunityAuditStatus.UNVERIFIABLE,
            evidence = "Web 检索未定位到可验证的官方来源/脚本/版本记录（与动漫同名易混），不伪造。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "lanyin", name = "澜音",
            sourceUrl = "hebijunge/musicfree-plugins 订阅站提及（CeruMusic 澜音插件）",
            repositoryUrl = "https://github.com/hebijunge/musicfree-plugins",
            sourceType = CommunitySourceType.SUBSCRIPTION_SET, status = CommunityAuditStatus.DISCOVERED,
            evidence = "在 MusicFree 订阅站以「澜音 CeruMusic 插件」出现，需展开具体插件清单并评估 MusicFree→LX 适配。" +
                "不把订阅集合当作单一音源，也不默认其可信。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "xiaoxiongmao", name = "小熊猫",
            sourceUrl = "lxmusic-source-all 合集（README 提及）",
            sourceType = CommunitySourceType.TOKEN_CONFIG, status = CommunityAuditStatus.QUARANTINED,
            evidence = "历史 LX 验证发现从 jsdelivr/GitHub **远程加载第二段 JS** → QUARANTINE_API_REVIEW；" +
                "可能需要用户 Token。不硬编码/不外传 Token，不在 AI 对话索取真实凭据，不上传清单服务器。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "feicai-gongshe", name = "废材公社", aliases = listOf("废材公社 内部源"),
            repositoryUrl = XZH_BASE, downloadUrl = "$XZH_BASE/%E5%BA%9F%E6%9D%90%E5%85%AC%E7%A4%BE%20%E5%86%85%E9%83%A8%E6%BA%90.js",
            sha256 = "176ea2309a6fd17bb38323e6508b2df02da48bfa63fe8779a5378bea0dd80d6d",
            fileSize = 18463, sourceType = CommunitySourceType.TOKEN_CONFIG,
            status = CommunityAuditStatus.QUARANTINED,
            evidence = "xzh767 仓库「废材公社 内部源」（18463B）。静态无 BLOCK，但标为可能需要用户 Token；" +
                "12 个域名需确认数据流。QUARANTINE：仅可设计本地凭据入口，不硬编码/不外传/不索要在 AI 对话。",
        ))

        // ===== D. MusicFree 插件生态（订阅集合，不逐一当作音源） =====
        add(CommunityCandidateRecord(
            candidateId = "musicfree-z-series", name = "Z 系列插件合集",
            sourceUrl = "社区声称 14 个插件（未找到可验证的官方发布页）",
            sourceType = CommunitySourceType.SUBSCRIPTION_SET, status = CommunityAuditStatus.UNVERIFIABLE,
            evidence = "未定位到可验证的「Z 系列 14 插件」官方清单与源文件；订阅站提及的「种子 zz.js」为另一来源。" +
                "不把 14 个插件默认可信，不逐一下载未验证文件。待提供官方清单后展开。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "yuanli-decompiled", name = "元力菌 SVIP 音源反编译版",
            sourceUrl = "元力音乐.json: https://13413.kstore.vip/yuanli/元力音乐.json（第三方聚合引用）",
            sourceType = CommunitySourceType.DECOMPILED, status = CommunityAuditStatus.BLOCKED,
            evidence = "反编译版本 + SVIP 定位 + 依赖 13413.kstore.vip（长青同域，历史 BLOCK）；" +
                "无法确认代码来源/许可证/修改情况；SVIP 不等于可绕过会员。BLOCK，禁止接入。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "yunyun-music", name = "云云音乐",
            sourceType = CommunitySourceType.STANDALONE, status = CommunityAuditStatus.UNVERIFIABLE,
            evidence = "Web 检索未定位到可验证的「云云音乐」LX 官方脚本/仓库（MusicFree 侧有「云云」类描述但协议不同）。" +
                "不伪造来源/SHA。",
        ))
        add(CommunityCandidateRecord(
            candidateId = "musicfree-subscription-hebijunge", name = "MusicFree 插件订阅站(hebijunge 57)",
            repositoryUrl = "https://github.com/hebijunge/musicfree-plugins",
            sourceUrl = "https://hebijunge.github.io/musicfree-plugins/",
            sourceType = CommunitySourceType.SUBSCRIPTION_SET, status = CommunityAuditStatus.DISCOVERED,
            evidence = "订阅站声称 57 个插件（plugins.json，约 643KB），另有澜音 CeruMusic 插件集合。" +
                "插件为 MusicFree API，≠ LX API；需适配层，不可一键批量安装未审插件；" +
                "站点自述逐条 sha256 核对（342 次请求）为其自身声明，未经本仓复核。见 bundles 表。",
        ))

        // ===== E. 聚合/全能型（重点：StellarWave） =====
        add(CommunityCandidateRecord(
            candidateId = "stellarwave", name = "星澜聚合音源 (StellarWave)",
            repositoryUrl = "https://github.com/lick563/Xinglan-aggregate-sound-sources",
            downloadUrl = "$SHELLSEC_BASE/stellarwave/%E6%98%9F%E6%BE%9C%E8%81%9A%E5%90%88%E9%9F%B3%E6%BA%90%20(StellarWave).js",
            sha256 = "d186346cec8f813dc447bebcc4d5a29a5ad41f62c90102edb196d67cc870eaf2",
            fileSize = 74386, sourceType = CommunitySourceType.AGGREGATOR,
            status = CommunityAuditStatus.BLOCKED,
            staticFindings = mapOf("card-key" to 2),
            evidence = "发布页声明 v3.1.1.1 共 87 后端（QQ26/网易19/酷我15/酷狗15/咪咕12），v4.0.0 合并墨澜框架，" +
                "并发 Fallback+LRU 缓存；但静态命中 card-key（注释含具体卡密 6C1F-53W0-GRKI-EVFG，" +
                "及「填卡密解锁仅认证用户音源」），存在访问控制边界问题。" +
                "「99.9%+ 成功率」无复核数据，仅为宣传。BLOCK 待逐后端拆解与卡密机制确认。",
        ))
    }

    /** 聚合/订阅集合拆解台账（E4 的详细展开留给后续阶段，此处先登记可核实的结构）。 */
    val bundles: List<CommunityBundleRecord> = listOf(
        CommunityBundleRecord(
            bundleId = "stellarwave-backends", name = "星澜后端清单",
            sourceUrl = "https://github.com/lick563/Xinglan-aggregate-sound-sources/releases",
            claimedCount = 87, verifiedCount = 0, status = CommunityAuditStatus.DISCOVERED,
            evidence = "发布页 v3.1.1.1 声明 87 后端：QQ26 / 网易19 / 酷我15 / 酷狗15 / 咪咕12。" +
                "含 FFAPI 通用后端、Cookie 配置(@tx_cookie/@wy_cookie)、yuafeng 需 apikey。" +
                "本阶段未逐个验证后端，不写成 87 个已验证音源。",
            children = listOf("FFAPI", "QQ越权", "ygking", "残像", "星海酷我", "yunmge",
                "星海酷狗", "念心", "yuafeng", "星海咪咕"),
        ),
        CommunityBundleRecord(
            bundleId = "musicfree-57", name = "MusicFree 插件订阅(hebijunge)",
            sourceUrl = "https://hebijunge.github.io/musicfree-plugins/plugins.json",
            claimedCount = 57, verifiedCount = 0, status = CommunityAuditStatus.DISCOVERED,
            evidence = "站点自述 57 插件、分类订阅(main/dj/radio/music/all)、约 643KB，多条备用线路。" +
                "插件为 MusicFree 协议，需格式转换/API 适配/QuickJS 兼容评估；本阶段未逐一下载或验证。",
        ),
        CommunityBundleRecord(
            bundleId = "z-series-14", name = "Z 系列插件合集(声称 14)",
            sourceUrl = null, claimedCount = 14, verifiedCount = 0, status = CommunityAuditStatus.UNVERIFIABLE,
            evidence = "未找到可验证的 14 插件官方清单，无法展开，不伪造插件名/SHA。",
        ),
        CommunityBundleRecord(
            bundleId = "yuanli-plugins", name = "元力菌插件/反编译",
            sourceUrl = "https://13413.kstore.vip/yuanli/元力音乐.json",
            claimedCount = null, verifiedCount = 0, status = CommunityAuditStatus.BLOCKED,
            evidence = "依赖历史 BLOCK 域名 13413.kstore.vip，SVIP 定位，反编译来源不明，许可证未知。禁止接入。",
        ),
    )

    /** 按 id 查候选（大小写不敏感）。 */
    fun find(id: String): CommunityCandidateRecord? =
        candidates.firstOrNull { it.candidateId.equals(id, ignoreCase = true) }

    /** 全量候选数。 */
    val totalCount: Int get() = candidates.size

    /** 按状态统计。 */
    fun countByStatus(status: CommunityAuditStatus): Int = candidates.count { it.status == status }

    /**
     * 用 SHA256 去重：同 SHA 的不同 id 视为同一实际文件。
     * 返回 sha -> 该 sha 对应的所有 candidateId（仅 sha 非空者）。
     */
    fun duplicateGroupsBySha(): Map<String, List<String>> =
        candidates.filter { it.sha256 != null }
            .groupBy { it.sha256!! }
            .filterValues { it.size > 1 }
            .mapValues { (_, records) -> records.map { it.candidateId } }
}
