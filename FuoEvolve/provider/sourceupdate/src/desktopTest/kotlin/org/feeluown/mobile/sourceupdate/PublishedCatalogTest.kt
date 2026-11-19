package org.feeluown.mobile.sourceupdate

import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 发布物一致性检查：仓库里那份 `config/source-catalog/source-catalog.json` 必须能用
 * **客户端内置的公钥** 验证通过。
 *
 * 这是整个自动更新链条最容易出错、又最难在线上发现的一环：目录签错了 key、
 * 换 key 忘了同步客户端、签名时改过一行 JSON 忘了重签，任何一项都会让所有设备静默
 * "检查成功但没有更新"。线上表现和"目录本来就是空的"完全一样，所以必须在 CI 里卡住。
 *
 * 用 JDK 自带的 Ed25519 独立验一遍，不复用产品代码的验签实现——避免"自己验自己"。
 */
class PublishedCatalogTest {

    private fun catalogDir(): File {
        // Gradle 测试工作目录是模块目录，但不同运行方式（IDE / CLI）会有差异，
        // 因此向上找直到看见 config/source-catalog。
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "config/source-catalog")
            if (File(candidate, "source-catalog.json").isFile) return candidate
            dir = dir.parentFile
        }
        error("找不到 config/source-catalog，测试工作目录：${File(".").absolutePath}")
    }

    @Test
    fun shippedCatalogVerifiesAgainstEmbeddedTrustRoot() {
        val dir = catalogDir()
        val catalogBytes = File(dir, "source-catalog.json").readBytes()
        val signatureBytes = File(dir, "source-catalog.json.sig").readText().trim()

        val catalog = SourceCatalogParser.parse(catalogBytes.decodeToString())
        val valid = assertIs<CatalogParseResult.Valid>(catalog, "目录结构非法：$catalog")
        assertEquals("fuoevolve-2026-11", valid.catalog.keyId)

        val publicKeyHex = SourceUpdateDefaults.TRUSTED_CATALOG_KEYS.getValue("fuoevolve-2026-11")
        assertTrue(verifyEd25519(catalogBytes, signatureBytes, publicKeyHex), "目录签名与内置公钥不匹配，请重新签名")
    }

    @Test
    fun shippedCatalogKeepsAutoInstallListEmptyUntilScriptsPassReview() {
        // 首版策略：没有任何脚本完成全部审核，自动安装列表必须为空。
        // 这条断言是"防止误把未审核脚本推给家人设备"的最后一道闸。
        val catalog = assertIs<CatalogParseResult.Valid>(
            SourceCatalogParser.parse(File(catalogDir(), "source-catalog.json").readText()),
        )
        assertTrue(
            catalog.catalog.sources.none { it.autoInstall },
            "首版目录不应包含 autoInstall 条目：${catalog.catalog.sources.map { it.id }}",
        )
    }

    /** 用 JDK 内置 Ed25519 验签（不需要 BouncyCastle）。 */
    private fun verifyEd25519(message: ByteArray, signatureBase64: String, publicKeyHex: String): Boolean {
        val keyBytes = publicKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // X.509 SubjectPublicKeyInfo 前缀：固定 12 字节头 + 32 字节裸公钥。
        val prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        val keySpec = X509EncodedKeySpec(prefix + keyBytes)
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(keySpec)
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(message)
        return verifier.verify(java.util.Base64.getDecoder().decode(signatureBase64))
    }
}
