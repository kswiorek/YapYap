package org.yapyap.backend.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.yapyap.backend.protocol.SignalSecurityScheme

/**
 * Black-box tests for [KmpCryptoProvider] as the reference [CryptoProvider] implementation.
 */
class KmpCryptoProviderTest {

    private val crypto = KmpCryptoProvider()

    @Test
    fun sha256_empty_matchesKnownVector() = runTest {
        val expected = hexToBytes(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )
        assertContentEquals(expected, crypto.sha256(byteArrayOf()))
    }

    @Test
    fun sha256_produces32Bytes() = runTest {
        assertEquals(32, crypto.sha256(byteArrayOf(1, 2, 3)).size)
    }

    @Test
    fun randomBytes_lengthMatches() {
        assertEquals(17, crypto.randomBytes(17).size)
    }

    @Test
    fun randomBytes_rejectsNonPositiveSize() {
        assertFailsWith<IllegalArgumentException> { crypto.randomBytes(0) }
        assertFailsWith<IllegalArgumentException> { crypto.randomBytes(-1) }
    }

    @Test
    fun randomBytes_twoDrawsAreVeryLikelyDistinct() {
        val a = crypto.randomBytes(32)
        val b = crypto.randomBytes(32)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun generateNonce_lengthMatchesSchemeNonceSize() {
        for (scheme in SignalSecurityScheme.entries) {
            assertEquals(scheme.nonceSize, crypto.generateNonce(scheme).size)
        }
    }

    @Test
    fun signDetached_roundTrip_verifyDetached() = runTest {
        val keys = crypto.generateSigningKeyPair()
        val message = "hello contract".encodeToByteArray()
        val sig = crypto.signDetached(keys.privateKey, message)
        assertTrue(crypto.verifyDetached(keys.publicKey, message, sig))
    }

    @Test
    fun verifyDetached_falseWhenMessageTampered() = runTest {
        val keys = crypto.generateSigningKeyPair()
        val message = "payload".encodeToByteArray()
        val sig = crypto.signDetached(keys.privateKey, message)
        val tampered = message + byteArrayOf(0)
        assertFalse(crypto.verifyDetached(keys.publicKey, tampered, sig))
    }

    @Test
    fun verifyDetached_falseWhenSignatureTampered() = runTest {
        val keys = crypto.generateSigningKeyPair()
        val message = "payload".encodeToByteArray()
        val sig = crypto.signDetached(keys.privateKey, message).copyOf()
        sig[0] = (sig[0].toInt() xor 0xff).toByte()
        assertFalse(crypto.verifyDetached(keys.publicKey, message, sig))
    }

    @Test
    fun verifyDetached_falseForWrongPublicKey() = runTest {
        val keysA = crypto.generateSigningKeyPair()
        val keysB = crypto.generateSigningKeyPair()
        val message = "x".encodeToByteArray()
        val sig = crypto.signDetached(keysA.privateKey, message)
        assertFalse(crypto.verifyDetached(keysB.publicKey, message, sig))
    }

    @Test
    fun accountIdFromPublicKey_stableForSameKey() = runTest {
        val keys = crypto.generateSigningKeyPair()
        val a = crypto.accountIdFromPublicKey(keys.publicKey)
        val b = crypto.accountIdFromPublicKey(keys.publicKey)
        assertEquals(a, b)
    }

    @Test
    fun peerIdFromPublicKey_stableForSameKey() = runTest {
        val keys = crypto.generateSigningKeyPair()
        val a = crypto.peerIdFromPublicKey(keys.publicKey)
        val b = crypto.peerIdFromPublicKey(keys.publicKey)
        assertEquals(a, b)
    }

    @Test
    fun peerId_differsForDifferentSigningPublicKeys() = runTest {
        val keysA = crypto.generateSigningKeyPair()
        val keysB = crypto.generateSigningKeyPair()
        assertNotEquals(
            crypto.peerIdFromPublicKey(keysA.publicKey),
            crypto.peerIdFromPublicKey(keysB.publicKey),
        )
    }

    @Test
    fun deriveSharedSecret_symmetricForDerX25519KeyPairs() = runTest {
        val alice = crypto.generateEncryptionKeyPair()
        val bob = crypto.generateEncryptionKeyPair()

        val aliceView = crypto.deriveSharedSecret(alice.privateKey, bob.publicKey)
        val bobView = crypto.deriveSharedSecret(bob.privateKey, alice.publicKey)

        assertEquals(32, aliceView.size)
        assertContentEquals(aliceView, bobView)
    }

    @Test
    fun hkdf_deterministicForSameInputs() = runTest {
        val ikm = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(9, 8, 7)
        val info = "yapyap-test".encodeToByteArray()

        val first = crypto.hkdf(ikm, salt, info, outputLength = 32)
        val second = crypto.hkdf(ikm, salt, info, outputLength = 32)

        assertEquals(32, first.size)
        assertContentEquals(first, second)
    }

    @Test
    fun hkdf_differsWhenInfoChanges() = runTest {
        val ikm = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(9, 8, 7)

        val a = crypto.hkdf(ikm, salt, info = "a".encodeToByteArray(), outputLength = 32)
        val b = crypto.hkdf(ikm, salt, info = "b".encodeToByteArray(), outputLength = 32)

        assertFalse(a.contentEquals(b))
    }

    @Test
    fun hkdf_supportsVariableOutputLength() = runTest {
        val derived = crypto.hkdf(
            ikm = byteArrayOf(5, 6, 7),
            salt = null,
            info = byteArrayOf(),
            outputLength = 64,
        )
        assertEquals(64, derived.size)
    }

    @Test
    fun encryptAead_roundTrip() = runTest {
        val key = crypto.hkdf(
            ikm = byteArrayOf(1, 2, 3),
            salt = null,
            info = "message-key".encodeToByteArray(),
            outputLength = KmpCryptoProvider.AEAD_KEY_SIZE_BYTES,
        )
        val plaintext = "secret payload".encodeToByteArray()

        val ciphertext = crypto.encryptAead(key, plaintext,)
        assertFalse(ciphertext.contentEquals(plaintext))
        assertTrue(ciphertext.size > plaintext.size)

        val opened = crypto.decryptAead(key, ciphertext,)
        assertContentEquals(plaintext, opened)
    }

    @Test
    fun decryptAead_failsWhenCiphertextTampered() = runTest {
        val key = crypto.randomBytes(KmpCryptoProvider.AEAD_KEY_SIZE_BYTES)
        val ciphertext = crypto.encryptAead(key, byteArrayOf(42),).copyOf()
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 0xff).toByte()

        assertFailsWith<Exception> {
            crypto.decryptAead(key, ciphertext,)
        }
    }

    @Test
    fun encryptAead_rejectsInvalidKeySize() = runTest {
        assertFailsWith<IllegalArgumentException> {
            crypto.encryptAead(byteArrayOf(1), byteArrayOf(2),)
        }
    }

    @Test
    fun encryptAead_roundTrip_withAssociatedData() = runTest {
        val key = ByteArray(32) { it.toByte() }
        val plaintext = "secret".encodeToByteArray()
        val aad = byteArrayOf(1, 2, 3)

        val ciphertext = crypto.encryptAead(key, plaintext, aad)
        assertContentEquals(plaintext, crypto.decryptAead(key, ciphertext, aad))
    }

    @Test
    fun decryptAead_failsWhenAssociatedDataDiffers() = runTest {
        val key = ByteArray(32) { it.toByte() }
        val ciphertext = crypto.encryptAead(key, byteArrayOf(42), byteArrayOf(1))

        assertFailsWith<Exception> {
            crypto.decryptAead(key, ciphertext, byteArrayOf(2))
        }
    }

    private companion object {
        fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0)
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
