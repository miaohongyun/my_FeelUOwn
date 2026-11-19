package org.feeluown.mobile.sourceupdate.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoTest {

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".encodeToByteArray()),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex(
                ("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq").encodeToByteArray(),
            ),
        )
    }

    @Test
    fun sha512MatchesKnownVectors() {
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
                "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            Sha512.digest(ByteArray(0)).toHex(),
        )
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            Sha512.digest("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun sha512HandlesMultiBlockInput() {
        // 200 字节跨两个 128 字节分块，用于覆盖长度字段与分块循环。
        val input = ByteArray(200) { (it % 251).toByte() }
        val digest = Sha512.digest(input)
        assertEquals(64, digest.size)
        val again = Sha512.digest(input.copyOf())
        assertTrue(digest.contentEquals(again))
    }

    @Test
    fun ed25519VerifiesRfc8032TestOne() {
        val publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToByteArray()
        val signature = (
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
            ).hexToByteArray()
        assertTrue(Ed25519.verify(publicKey, ByteArray(0), signature))
    }

    @Test
    fun ed25519VerifiesRfc8032TestTwoAndThree() {
        val publicKeyTwo = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c".hexToByteArray()
        val signatureTwo = (
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
            ).hexToByteArray()
        assertTrue(Ed25519.verify(publicKeyTwo, "72".hexToByteArray(), signatureTwo))

        val publicKeyThree = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025".hexToByteArray()
        val signatureThree = (
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
                "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"
            ).hexToByteArray()
        assertTrue(Ed25519.verify(publicKeyThree, "af82".hexToByteArray(), signatureThree))
    }

    @Test
    fun ed25519VerifiesLongMultiBlockMessage() {
        // 200 字节消息：覆盖 SHA-512 跨分块与归约标量的常规路径（前面的空消息用例盖不到）。
        val publicKey = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025".hexToByteArray()
        val message = (
            "030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3" +
                "cad1d8dfe6edf4fb020910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4dbe2e9f0f7fe050c131a21282f363d444b525960676e757c838a9198" +
                "9fa6adb4bbc2c9d0d7dee5ecf3fa01080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3dae1e8eff6fd040b121920272e353c434a51585f666d74"
            ).hexToByteArray()
        val signature = (
            "5cae99b947d47217d78560e6616757734841e290350a871918cd1574fc7c1151" +
                "11bd678de4025105833654f72cab0fa19c29fb68506b2519d4b4e6473ecf050c"
            ).hexToByteArray()
        assertTrue(Ed25519.verify(publicKey, message, signature))
        assertFalse(Ed25519.verify(publicKey, message.copyOf(message.size - 1), signature))
    }

    @Test
    fun ed25519RejectsTamperedPayloadAndSignature() {
        val publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToByteArray()
        val payload = """{"schemaVersion":1,"catalogVersion":7,"sources":[]}""".encodeToByteArray()
        val signature = (
            "c3d1773eafd0d0d34c3bb2e4f93ed960adfce5a4ece8b6c4ba6c3247cdff3f69" +
                "1f873d072200153513c4c39bf87be37d381591425f17d6f5ab26e6acb7a97200"
            ).hexToByteArray()

        assertTrue(Ed25519.verify(publicKey, payload, signature))
        assertFalse(Ed25519.verify(publicKey, payload + " ".encodeToByteArray(), signature))

        val tamperedSignature = signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(Ed25519.verify(publicKey, payload, tamperedSignature))

        val tamperedKey = publicKey.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(Ed25519.verify(tamperedKey, payload, signature))

        assertFalse(Ed25519.verify(ByteArray(31), payload, signature))
        assertFalse(Ed25519.verify(publicKey, payload, ByteArray(63)))
    }
}
