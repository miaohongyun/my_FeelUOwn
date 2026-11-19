package org.feeluown.mobile.sourceupdate

/**
 * 音源目录的默认配置。
 *
 * 所有可被篡改的东西（目录内容、脚本文件）都不在这里，这里只有**信任根**：
 * 目录地址、允许的下载通道、Ed25519 公钥。
 *
 * 换目录地址时注意：`SourceMirrorPolicy` 只允许内置 allowlist 里的域名，
 * 不接受目录自己声明新的镜像，否则一次目录劫持就能把下载通道换到攻击者主机。
 */
object SourceUpdateDefaults {

    /** 受控目录（本仓库 `config/source-catalog/`，随仓库发布）。 */
    const val CATALOG_URL =
        "https://raw.githubusercontent.com/miaohongyun/my_FeelUOwn/master/FuoEvolve/config/source-catalog/source-catalog.json"

    /**
     * 国内可达性兜底。公共代理只当下载通道，不作为信任根：
     * 内容仍要过目录签名与脚本 SHA-256，代理篡改会被验签挡住。
     */
    val MIRROR_URLS: List<String> = listOf(
        "https://ghproxy.net/$CATALOG_URL",
        "https://gh-proxy.com/$CATALOG_URL",
    )

    /** 允许的下载通道域名（含主地址与镜像）。 */
    val MIRROR_ALLOWLIST: Set<String> = setOf(
        "raw.githubusercontent.com",
        "ghproxy.net",
        "gh-proxy.com",
    )

    /** 目录签名公钥。私钥不入库，只在发布目录时由 `tools/sign-catalog.mjs` 使用。 */
    val TRUSTED_CATALOG_KEYS: Map<String, String> = mapOf(
        "fuoevolve-2026-11" to "ac38e1f21af2fe5cc4d1683cffc824b16b41ad54d33577f92ae55263efa9cb05",
    )

    /** 生产环境必须为 true：没有有效签名的目录一律不接受。 */
    const val REQUIRE_SIGNATURE = true

    /** 默认检查间隔（小时）。 */
    const val UPDATE_INTERVAL_HOURS = 24

    /** 单脚本体积上限：1 MiB。 */
    const val MAX_SCRIPT_BYTES = 1024 * 1024

    fun signatureVerifier(): CatalogSignatureVerifier = CatalogSignatureVerifier(
        trustedKeys = TRUSTED_CATALOG_KEYS.mapValues { CatalogSignatureVerifier.publicKey(it.value) },
        requireSignature = REQUIRE_SIGNATURE,
    )

    fun mirrorPolicy(): SourceMirrorPolicy = SourceMirrorPolicy(
        mirrors = MIRROR_URLS,
        allowlist = MIRROR_ALLOWLIST,
    )
}
