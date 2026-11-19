package org.feeluown.mobile.sourceupdate

import org.feeluown.mobile.sourceupdate.crypto.Ed25519
import org.feeluown.mobile.sourceupdate.crypto.Sha256
import org.feeluown.mobile.sourceupdate.crypto.hexToByteArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

sealed interface ArtifactVerification {
    data class Accepted(val sha256: String, val sizeBytes: Int) : ArtifactVerification
    data class Rejected(val reason: String) : ArtifactVerification
}

/**
 * 脚本产物校验：体积 → SHA-256。签名在目录层面校验，见 [CatalogSignatureVerifier]。
 */
class SourceArtifactVerifier(
    private val maxBytes: Int = SourceStaticPolicy.DEFAULT_MAX_SCRIPT_BYTES,
) {

    fun verify(entry: SourceCatalogEntry, bytes: ByteArray): ArtifactVerification {
        if (bytes.isEmpty()) return ArtifactVerification.Rejected("脚本内容为空")
        val limit = entry.sizeBytes?.coerceAtMost(maxBytes.toLong())?.toInt() ?: maxBytes
        if (bytes.size > limit) {
            return ArtifactVerification.Rejected("脚本 ${bytes.size} 字节，超过允许的 $limit 字节")
        }
        val actual = Sha256.hex(bytes)
        if (!actual.equals(entry.sha256.trim(), ignoreCase = true)) {
            return ArtifactVerification.Rejected("SHA-256 不匹配：期望 ${entry.sha256}，实际 $actual")
        }
        return ArtifactVerification.Accepted(actual, bytes.size)
    }
}

sealed interface SignatureVerification {
    data object NotRequired : SignatureVerification
    data object Accepted : SignatureVerification
    data class Rejected(val reason: String) : SignatureVerification
}

/**
 * 目录签名校验。
 *
 * 只支持 Ed25519；签名为**独立文件**（`<catalog>.sig`，Base64 原文签名），而不是塞在 JSON 里。
 * 原因：把签名放进 JSON 就必须定义"去掉签名字段后的规范化序列化"，各语言实现稍有差异就会
 * 验签失败；独立文件对签名者与验证者都只有一个字节流，最不容易出错。
 *
 * 没有配置任何公钥时：
 * - [requireSignature] = true → 一律拒绝（默认，防止有人用未签名的目录冒充官方）；
 * - [requireSignature] = false → 放行并只信任 SHA-256（用于自建调试）。
 */
class CatalogSignatureVerifier(
    trustedKeys: Map<String, ByteArray> = emptyMap(),
    private val requireSignature: Boolean = true,
) {
    private val keys: Map<String, ByteArray> = trustedKeys.mapKeys { it.key.lowercase() }

    fun verify(
        rawCatalog: ByteArray,
        signatureBase64: String?,
        algorithm: String?,
        keyId: String?,
    ): SignatureVerification {
        // 没有配置任何公钥时无法验证任何签名，只剩两种自洽的选择：
        // 严格模式一律拒绝；调试模式只信任 SHA-256。
        if (keys.isEmpty() && !requireSignature) return SignatureVerification.NotRequired

        val declaredAlgorithm = algorithm?.trim().orEmpty()
        if (declaredAlgorithm.isEmpty()) {
            return if (requireSignature) {
                SignatureVerification.Rejected("目录未声明 signatureAlgorithm，按策略拒绝")
            } else {
                SignatureVerification.NotRequired
            }
        }
        if (!declaredAlgorithm.equals("Ed25519", ignoreCase = true)) {
            return SignatureVerification.Rejected("不支持的签名算法「$declaredAlgorithm」")
        }
        if (signatureBase64.isNullOrBlank()) {
            return SignatureVerification.Rejected("目录声明了 Ed25519 签名但缺少 .sig 文件")
        }
        if (keys.isEmpty()) {
            return SignatureVerification.Rejected("客户端内置公钥为空，无法验证签名")
        }
        val signature = decodeSignature(signatureBase64)
            ?: return SignatureVerification.Rejected("签名不是合法的 Base64")
        if (signature.size != 64) {
            return SignatureVerification.Rejected("Ed25519 签名应为 64 字节，实际 ${signature.size}")
        }

        val candidates = if (keyId.isNullOrBlank()) keys.values.toList() else {
            keys[keyId.lowercase()]?.let { listOf(it) }
                ?: return SignatureVerification.Rejected("未知的签名 keyId「$keyId」")
        }
        for (key in candidates) {
            if (Ed25519.verify(key, rawCatalog, signature)) return SignatureVerification.Accepted
        }
        return SignatureVerification.Rejected("签名校验失败：目录内容与签名不匹配")
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeSignature(value: String): ByteArray? {
        val normalized = value.filterNot { it.isWhitespace() }
        return runCatching { Base64.decode(normalized) }.getOrNull()
    }

    companion object {
        /** 把十六进制公钥转成 [CatalogSignatureVerifier] 需要的字节数组，便于配置与测试。 */
        fun publicKey(hex: String): ByteArray = hex.hexToByteArray()

        fun signatureToBase64(signature: ByteArray): String = Base64.encode(signature)

        fun sha256Hex(bytes: ByteArray): String = Sha256.hex(bytes)
    }
}
