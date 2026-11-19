package org.feeluown.mobile.sourceupdate

/**
 * 测试夹具：目录、脚本、签名都由仓库外的 Node 工具（Ed25519）真实生成，
 * 避免"自己签自己验"变成自证。
 *
 * 公钥 `d75a9801...` 对应 RFC 8032 TEST 1 的私钥，只用于测试。
 */
internal object SourceFixtures {

    const val SCRIPT: String =
        "globalThis.lx.on('inited', function(){ globalThis.lx.send('inited', {status: true, " +
            "sources: {kw: {type: 'music', actions: ['musicUrl'], qualitys: ['128k']}}}) });"

    const val SCRIPT_SHA256 = "3122822d125357bef59e707fcad41570f0da16999b6f9cdfe5061f97deff53b4"

    const val PUBLIC_KEY_HEX = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"

    const val CATALOG_URL = "https://catalog.example.com/source-catalog.json"
    const val SCRIPT_URL = "https://catalog.example.com/scripts/fixture.js"

    /** 测试用镜像 allowlist。fail-closed 后必须显式给出，不能依赖空名单放行。 */
    val TEST_ALLOWLIST: Set<String> = setOf("catalog.example.com", "mirror.example.com")

    const val CATALOG_JSON: String =
        """{"schemaVersion":1,"catalogVersion":42,"signatureAlgorithm":"Ed25519","keyId":"test-key","sources":[{"id":"fixture-source","name":"Fixture Source","version":"1.0.0","scriptUrl":"https://catalog.example.com/scripts/fixture.js","sha256":"3122822d125357bef59e707fcad41570f0da16999b6f9cdfe5061f97deff53b4","reviewStatus":"APPROVED","license":"MIT","autoInstall":true,"autoEnable":true,"allowedHosts":["api.example.com"],"platforms":["kw"],"capabilities":["musicUrl"]}]}"""

    /** 对 [CATALOG_JSON] 原文的 Ed25519 签名（Base64）。 */
    const val CATALOG_SIGNATURE = "vnvrx7T1K0lSXChaqTyl3OKd+8ReqY+4htg48/UQVZT7r12zO6phWqoZ/rnwAeU+x1Q/YIGicNmdhjGZgoOHAA=="

    fun catalogEntry(
        id: String = "fixture-source",
        version: String = "1.0.0",
        sha256: String = SCRIPT_SHA256,
        autoInstall: Boolean = true,
        autoEnable: Boolean = true,
        reviewStatus: String = "APPROVED",
        allowedHosts: List<String> = listOf("api.example.com"),
    ): SourceCatalogEntry = SourceCatalogEntry(
        id = id,
        name = "Fixture Source",
        version = version,
        scriptUrl = SCRIPT_URL,
        sha256 = sha256,
        reviewStatus = reviewStatus,
        license = "MIT",
        autoInstall = autoInstall,
        autoEnable = autoEnable,
        allowedHosts = allowedHosts,
        platforms = listOf("kw"),
        capabilities = listOf("musicUrl"),
    )

    fun signedVerifier(): CatalogSignatureVerifier = CatalogSignatureVerifier(
        trustedKeys = mapOf("test-key" to CatalogSignatureVerifier.publicKey(PUBLIC_KEY_HEX)),
        requireSignature = true,
    )
}

/** 用固定映射模拟网络：缺省 URL 返回 HTTP 404，便于断言"尝试过哪些地址"。 */
internal class MapCatalogFetcher(
    private val bodies: Map<String, String>,
    private val etags: Map<String, String> = emptyMap(),
    private val failures: Map<String, CatalogFetchError> = emptyMap(),
) : CatalogFetcher {

    val requested = mutableListOf<String>()

    override suspend fun fetch(url: String, etag: String?, maxBytes: Int?): CatalogFetchResult {
        requested += url
        failures[url]?.let { error ->
            return CatalogFetchResult(url, error = error, detail = "模拟失败")
        }
        val body = bodies[url]
            ?: return CatalogFetchResult(url, error = CatalogFetchError.HTTP_STATUS, detail = "HTTP 404")
        val currentEtag = etags[url]
        if (etag != null && currentEtag != null && etag == currentEtag) {
            return CatalogFetchResult(url, notModified = true, etag = currentEtag)
        }
        return CatalogFetchResult(url, body = body, etag = currentEtag)
    }
}
