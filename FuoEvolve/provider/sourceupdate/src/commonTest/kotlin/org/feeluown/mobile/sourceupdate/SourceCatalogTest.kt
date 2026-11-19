package org.feeluown.mobile.sourceupdate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SourceCatalogParserTest {

    @Test
    fun parsesSignedFixture() {
        val result = SourceCatalogParser.parse(SourceFixtures.CATALOG_JSON)
        val valid = assertIs<CatalogParseResult.Valid>(result)
        assertEquals(42, valid.catalog.catalogVersion.toInt())
        assertEquals(1, valid.catalog.sources.size)
        val entry = valid.catalog.sources.first()
        assertEquals("fixture-source", entry.id)
        assertEquals(SourceReviewStatus.APPROVED, entry.review)
        assertTrue(entry.autoInstall)
        assertEquals(listOf("api.example.com"), entry.allowedHosts)
    }

    @Test
    fun acceptsEmptyCatalog() {
        val result = SourceCatalogParser.parse("""{"schemaVersion":1,"catalogVersion":3,"sources":[]}""")
        val valid = assertIs<CatalogParseResult.Valid>(result)
        assertTrue(valid.catalog.sources.isEmpty())
    }

    @Test
    fun rejectsUnsupportedSchema() {
        val result = SourceCatalogParser.parse("""{"schemaVersion":9,"catalogVersion":1,"sources":[]}""")
        val invalid = assertIs<CatalogParseResult.Invalid>(result)
        assertTrue(invalid.reason.contains("schemaVersion"))
    }

    @Test
    fun rejectsDuplicateIds() {
        val raw = """
            {"schemaVersion":1,"catalogVersion":1,"sources":[
              {"id":"a","name":"A","version":"1","scriptUrl":"https://x.example.com/a.js","sha256":"${SourceFixtures.SCRIPT_SHA256}","reviewStatus":"REVIEW_CANDIDATE"},
              {"id":"a","name":"A2","version":"1","scriptUrl":"https://x.example.com/b.js","sha256":"${SourceFixtures.SCRIPT_SHA256}","reviewStatus":"REVIEW_CANDIDATE"}
            ]}
        """.trimIndent()
        val invalid = assertIs<CatalogParseResult.Invalid>(SourceCatalogParser.parse(raw))
        assertTrue(invalid.reason.contains("重复"))
    }

    @Test
    fun rejectsInsecureOrPrivateScriptUrl() {
        val http = SourceCatalogParser.parse(
            """{"schemaVersion":1,"catalogVersion":1,"sources":[
                 {"id":"a","name":"A","version":"1","scriptUrl":"http://x.example.com/a.js","sha256":"${SourceFixtures.SCRIPT_SHA256}"}
               ]}""",
        )
        assertIs<CatalogParseResult.Invalid>(http)

        val lan = SourceCatalogParser.parse(
            """{"schemaVersion":1,"catalogVersion":1,"sources":[
                 {"id":"a","name":"A","version":"1","scriptUrl":"https://192.168.1.10/a.js","sha256":"${SourceFixtures.SCRIPT_SHA256}"}
               ]}""",
        )
        val invalid = assertIs<CatalogParseResult.Invalid>(lan)
        assertTrue(invalid.reason.contains("私网"))
    }

    @Test
    fun rejectsAutoInstallWithoutApproval() {
        val raw = """
            {"schemaVersion":1,"catalogVersion":1,"sources":[
              {"id":"a","name":"A","version":"1","scriptUrl":"https://x.example.com/a.js",
               "sha256":"${SourceFixtures.SCRIPT_SHA256}","reviewStatus":"QUARANTINE_OBFUSCATED","autoInstall":true}
            ]}
        """.trimIndent()
        val invalid = assertIs<CatalogParseResult.Invalid>(SourceCatalogParser.parse(raw))
        assertTrue(invalid.reason.contains("autoInstall"))
    }

    @Test
    fun rejectsAutoInstallWithoutAllowedHosts() {
        // 运行时 LxNetworkPolicy 对空白名单拒绝一切网络，因此这类脚本无法工作，
        // 不允许它进入目录（两道防线必须同向）。
        val raw = """
            {"schemaVersion":1,"catalogVersion":1,"sources":[
              {"id":"a","name":"A","version":"1","scriptUrl":"https://x.example.com/a.js",
               "sha256":"${SourceFixtures.SCRIPT_SHA256}","reviewStatus":"APPROVED",
               "license":"MIT","autoInstall":true,"allowedHosts":[]}
            ]}
        """.trimIndent()
        val invalid = assertIs<CatalogParseResult.Invalid>(SourceCatalogParser.parse(raw))
        assertTrue(invalid.reason.contains("allowedHosts"))
    }

    @Test
    fun rejectsUnknownReviewStatusByTreatingItAsCandidate() {
        val raw = """
            {"schemaVersion":1,"catalogVersion":1,"sources":[
              {"id":"a","name":"A","version":"1","scriptUrl":"https://x.example.com/a.js",
               "sha256":"${SourceFixtures.SCRIPT_SHA256}","reviewStatus":"SOMETHING_NEW","autoInstall":true}
            ]}
        """.trimIndent()
        val invalid = assertIs<CatalogParseResult.Invalid>(SourceCatalogParser.parse(raw))
        assertTrue(invalid.reason.contains("autoInstall"))
    }

    @Test
    fun warnsOnUnknownLicenseAndMissingHosts() {
        val raw = """
            {"schemaVersion":1,"catalogVersion":1,"sources":[
              {"id":"a","name":"A","version":"1","scriptUrl":"https://x.example.com/a.js",
               "sha256":"${SourceFixtures.SCRIPT_SHA256}","reviewStatus":"APPROVED"}
            ]}
        """.trimIndent()
        val valid = assertIs<CatalogParseResult.Valid>(SourceCatalogParser.parse(raw))
        assertTrue(valid.warnings.any { it.contains("许可证") })
        assertTrue(valid.warnings.any { it.contains("allowedHosts") })
    }

    @Test
    fun rejectsMalformedSha256() {
        val raw = """
            {"schemaVersion":1,"catalogVersion":1,"sources":[
              {"id":"a","name":"A","version":"1","scriptUrl":"https://x.example.com/a.js","sha256":"deadbeef"}
            ]}
        """.trimIndent()
        assertIs<CatalogParseResult.Invalid>(SourceCatalogParser.parse(raw))
    }
}

class SourceStaticPolicyTest {

    @Test
    fun cleanScriptPasses() {
        val report = SourceStaticPolicy.scan(SourceFixtures.SCRIPT, listOf("example.com"))
        assertEquals(StaticVerdict.CLEAN, report.verdict, report.findings.joinToString { it.message })
        assertFalse(report.blocked)
    }

    @Test
    fun blocksDynamicExecution() {
        val report = SourceStaticPolicy.scan("eval('1+1');", emptyList())
        assertEquals(StaticVerdict.BLOCKED, report.verdict)
        assertTrue(report.findings.any { it.ruleId == "dynamic-eval" })

        val function = SourceStaticPolicy.scan("var f = new Function('return 1');", emptyList())
        assertEquals(StaticVerdict.BLOCKED, function.verdict)
    }

    @Test
    fun blocksUndeclaredHostsButAllowsDeclaredOnes() {
        val script = "lx.request('https://api.example.com/v1/url', {}, function(){})"
        assertEquals(StaticVerdict.CLEAN, SourceStaticPolicy.scan(script, listOf("api.example.com")).verdict)

        val blocked = SourceStaticPolicy.scan(script, listOf("other.example.com"))
        assertEquals(StaticVerdict.BLOCKED, blocked.verdict)
        assertTrue(blocked.undeclaredHosts.contains("api.example.com"))
    }

    @Test
    fun supportsWildcardHosts() {
        val script = "lx.request('https://cdn.api.example.com/x', {}, function(){})"
        val report = SourceStaticPolicy.scan(script, listOf("*.example.com"))
        assertFalse(report.blocked, report.findings.joinToString { "${it.ruleId}/${it.message}" })

        // 裸星号不是合法通配写法：宁可拒绝，也不要把「*example.com」误当成「任意 example.com 域名」
        val sloppy = SourceStaticPolicy.scan(script, listOf("*example.com"))
        assertTrue(sloppy.blocked)
    }

    @Test
    fun blocksObfuscatedAndCardKeyScripts() {
        val obfuscated = buildString {
            repeat(25) { append("var _0x${it}abc = 1;") }
        }
        assertTrue(SourceStaticPolicy.scan(obfuscated, emptyList()).blocked)

        assertTrue(SourceStaticPolicy.scan("var CARD_KEY = 'abc';", emptyList()).blocked)
        assertTrue(SourceStaticPolicy.scan("// 卡密登录", emptyList()).blocked)
        assertTrue(SourceStaticPolicy.scan("// SVIP 音源", emptyList()).blocked)
    }

    @Test
    fun blocksOversizedScript() {
        val big = "a".repeat(SourceStaticPolicy.DEFAULT_MAX_SCRIPT_BYTES + 1)
        assertTrue(SourceStaticPolicy.scan(big, emptyList()).blocked)
    }

    @Test
    fun warnsOnBrowserOnlyApis() {
        val report = SourceStaticPolicy.scan("document.cookie = 'a=1'; localStorage.x", emptyList())
        assertEquals(StaticVerdict.NEEDS_REVIEW, report.verdict)
        assertTrue(report.findings.all { it.severity == StaticSeverity.WARN })
    }

    @Test
    fun extractsHttpAndHttpsHosts() {
        val hosts = SourceStaticPolicy.extractHosts(
            "a('http://kw.example.com/x'); b('https://API.Example.com/y'); c('wss://ws.example.com')",
        )
        assertTrue(hosts.containsAll(listOf("kw.example.com", "api.example.com", "ws.example.com")))
    }
}

class SourceArtifactVerifierTest {

    private val verifier = SourceArtifactVerifier()

    @Test
    fun acceptsMatchingSha256() {
        val entry = SourceFixtures.catalogEntry()
        val result = verifier.verify(entry, SourceFixtures.SCRIPT.encodeToByteArray())
        assertIs<ArtifactVerification.Accepted>(result)
        assertEquals(SourceFixtures.SCRIPT_SHA256, result.sha256)
    }

    @Test
    fun rejectsMismatchedSha256() {
        val entry = SourceFixtures.catalogEntry(sha256 = "0".repeat(64))
        val rejected = assertIs<ArtifactVerification.Rejected>(
            verifier.verify(entry, SourceFixtures.SCRIPT.encodeToByteArray()),
        )
        assertTrue(rejected.reason.contains("SHA-256"))
    }

    @Test
    fun rejectsOversizedPayload() {
        val entry = SourceFixtures.catalogEntry().copy(sizeBytes = 10)
        assertIs<ArtifactVerification.Rejected>(verifier.verify(entry, SourceFixtures.SCRIPT.encodeToByteArray()))
    }

    @Test
    fun rejectsEmptyPayload() {
        assertIs<ArtifactVerification.Rejected>(verifier.verify(SourceFixtures.catalogEntry(), ByteArray(0)))
    }
}

class CatalogSignatureVerifierTest {

    @Test
    fun acceptsRealSignature() {
        val verdict = SourceFixtures.signedVerifier().verify(
            rawCatalog = SourceFixtures.CATALOG_JSON.encodeToByteArray(),
            signatureBase64 = SourceFixtures.CATALOG_SIGNATURE,
            algorithm = "Ed25519",
            keyId = "test-key",
        )
        assertEquals(SignatureVerification.Accepted, verdict)
    }

    @Test
    fun rejectsTamperedCatalog() {
        val tampered = SourceFixtures.CATALOG_JSON.replace("\"catalogVersion\":42", "\"catalogVersion\":43")
        val verdict = SourceFixtures.signedVerifier().verify(
            rawCatalog = tampered.encodeToByteArray(),
            signatureBase64 = SourceFixtures.CATALOG_SIGNATURE,
            algorithm = "Ed25519",
            keyId = "test-key",
        )
        assertIs<SignatureVerification.Rejected>(verdict)
    }

    @Test
    fun rejectsMissingSignatureWhenRequired() {
        val verdict = SourceFixtures.signedVerifier().verify(
            rawCatalog = SourceFixtures.CATALOG_JSON.encodeToByteArray(),
            signatureBase64 = null,
            algorithm = "Ed25519",
            keyId = "test-key",
        )
        assertIs<SignatureVerification.Rejected>(verdict)
    }

    @Test
    fun rejectsUnsignedCatalogWhenRequired() {
        val verdict = SourceFixtures.signedVerifier().verify(
            rawCatalog = "{}".encodeToByteArray(),
            signatureBase64 = null,
            algorithm = null,
            keyId = null,
        )
        assertIs<SignatureVerification.Rejected>(verdict)
    }

    @Test
    fun allowsUnsignedCatalogWhenNotRequired() {
        val permissive = CatalogSignatureVerifier(requireSignature = false)
        assertEquals(
            SignatureVerification.NotRequired,
            permissive.verify("{}".encodeToByteArray(), null, null, null),
        )
    }

    @Test
    fun rejectsUnknownKeyIdAndAlgorithm() {
        val unknownKey = SourceFixtures.signedVerifier().verify(
            rawCatalog = SourceFixtures.CATALOG_JSON.encodeToByteArray(),
            signatureBase64 = SourceFixtures.CATALOG_SIGNATURE,
            algorithm = "Ed25519",
            keyId = "someone-else",
        )
        assertIs<SignatureVerification.Rejected>(unknownKey)

        val rsa = SourceFixtures.signedVerifier().verify(
            rawCatalog = SourceFixtures.CATALOG_JSON.encodeToByteArray(),
            signatureBase64 = SourceFixtures.CATALOG_SIGNATURE,
            algorithm = "RSA-SHA256",
            keyId = "test-key",
        )
        assertIs<SignatureVerification.Rejected>(rsa)
    }

    @Test
    fun rejectsMalformedBase64() {
        val verdict = SourceFixtures.signedVerifier().verify(
            rawCatalog = SourceFixtures.CATALOG_JSON.encodeToByteArray(),
            signatureBase64 = "not base64!!",
            algorithm = "Ed25519",
            keyId = "test-key",
        )
        assertIs<SignatureVerification.Rejected>(verdict)
    }
}
