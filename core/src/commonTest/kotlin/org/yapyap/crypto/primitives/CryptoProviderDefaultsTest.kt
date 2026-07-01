package org.yapyap.crypto.primitives

import kotlinx.coroutines.test.runTest
import org.yapyap.protocol.SignalSecurityScheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract tests for [CryptoProvider] default methods only ([CryptoProvider.toHex],
 * [CryptoProvider.accountIdFromPublicKey], [CryptoProvider.peerIdFromPublicKey]).
 * Uses a stub so these tests run on every Kotlin Multiplatform target that executes commonTest.
 */
class CryptoProviderDefaultsTest {

    @Test
    fun toHex_lowerCaseHex_noSeparator() {
        val crypto = throwingStub()
        assertEquals("00", crypto.toHex(byteArrayOf(0)))
        assertEquals("0a", crypto.toHex(byteArrayOf(10)))
        assertEquals("ff", crypto.toHex(byteArrayOf(-1)))
        assertEquals("000aff", crypto.toHex(byteArrayOf(0, 10, -1)))
    }

    @Test
    fun accountIdFromPublicKey_isHexOfSha256OfPublicKey() = runTest {
        val digest = ByteArray(32) { 9 }
        val crypto = sha256FixedStub(digest)
        assertEquals(crypto.toHex(digest), crypto.accountIdFromPublicKey(byteArrayOf(1, 2, 3)).id)
    }

    @Test
    fun peerIdFromPublicKey_isHexOfSha256OfPublicKey() = runTest {
        val digest = ByteArray(32) { (it + 3).toByte() }
        val crypto = sha256FixedStub(digest)
        assertEquals(crypto.toHex(digest), crypto.peerIdFromPublicKey(byteArrayOf(7)).id)
    }

    @Test
    fun accountIdAndPeerId_useSameFormulaForSameKeyMaterial() = runTest {
        val digest = ByteArray(32) { 1 }
        val crypto = sha256FixedStub(digest)
        val key = byteArrayOf(11)
        assertEquals(
            crypto.accountIdFromPublicKey(key).id,
            crypto.peerIdFromPublicKey(key).id,
        )
    }

    private fun sha256FixedStub(fixedDigest: ByteArray): CryptoProvider =
        object : ThrowingCryptoStub() {
            override suspend fun sha256(bytes: ByteArray): ByteArray = fixedDigest.copyOf()
        }

    private fun throwingStub(): CryptoProvider = ThrowingCryptoStub()

    /** Base type so overrides only touch methods under test. */
    private open class ThrowingCryptoStub : CryptoProvider {
        override suspend fun sha256(bytes: ByteArray): ByteArray = error("not stubbed: sha256")
        override fun randomBytes(size: Int): ByteArray = error("not stubbed: randomBytes")
        override suspend fun generateSigningKeyPair(): SigningKeyPair = error("not stubbed: generateSigningKeyPair")
        override suspend fun generateEncryptionKeyPair(): EncryptionKeyPair = error("not stubbed: generateEncryptionKeyPair")
        override suspend fun signDetached(privateSigningKey: ByteArray, message: ByteArray): ByteArray =
            error("not stubbed: signDetached")

        override suspend fun verifyDetached(publicSigningKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
            error("not stubbed: verifyDetached")

        override fun generateNonce(scheme: SignalSecurityScheme): ByteArray =
            error("not stubbed: generateNonce")

        override suspend fun deriveSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
            error("not stubbed: deriveSharedSecret")

        override suspend fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, outputLength: Int): ByteArray =
            error("not stubbed: hkdf")

        override suspend fun encryptAead(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray?): ByteArray =
            error("not stubbed: encryptAead")

        override suspend fun decryptAead(key: ByteArray, ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
            error("not stubbed: decryptAead")

        override suspend fun privateSigningKeyToPublicKey(privateKey: ByteArray): ByteArray {
            error("not stubbed: privateSigningKeyToPublicKey")
        }

        override suspend fun privateEncryptionKeyToPublicKey(privateKey: ByteArray): ByteArray {
            error("not stubbed: privateEncryptionKeyToPublicKey")
        }
    }
}