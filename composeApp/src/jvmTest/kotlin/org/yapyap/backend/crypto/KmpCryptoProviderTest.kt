package org.yapyap.backend.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.yapyap.backend.protocol.SignalSecurityScheme

/**
 * Black-box tests for [KmpCryptoProvider] as the reference [CryptoProvider] implementation.
 */
class KmpCryptoProviderTest {

    private val crypto = KmpCryptoProvider()

    @Test
    fun sha256_empty_matchesKnownVector() {
        val expected = hexToBytes(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )
        assertContentEquals(expected, crypto.sha256(byteArrayOf()))
    }

    @Test
    fun sha256_produces32Bytes() {
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
    fun signDetached_roundTrip_verifyDetached() {
        val keys = crypto.generateSigningKeyPair()
        val message = "hello contract".encodeToByteArray()
        val sig = crypto.signDetached(keys.privateKey, message)
        assertTrue(crypto.verifyDetached(keys.publicKey, message, sig))
    }

    @Test
    fun verifyDetached_falseWhenMessageTampered() {
        val keys = crypto.generateSigningKeyPair()
        val message = "payload".encodeToByteArray()
        val sig = crypto.signDetached(keys.privateKey, message)
        val tampered = message + byteArrayOf(0)
        assertFalse(crypto.verifyDetached(keys.publicKey, tampered, sig))
    }

    @Test
    fun verifyDetached_falseWhenSignatureTampered() {
        val keys = crypto.generateSigningKeyPair()
        val message = "payload".encodeToByteArray()
        val sig = crypto.signDetached(keys.privateKey, message).copyOf()
        sig[0] = (sig[0].toInt() xor 0xff).toByte()
        assertFalse(crypto.verifyDetached(keys.publicKey, message, sig))
    }

    @Test
    fun verifyDetached_falseForWrongPublicKey() {
        val keysA = crypto.generateSigningKeyPair()
        val keysB = crypto.generateSigningKeyPair()
        val message = "x".encodeToByteArray()
        val sig = crypto.signDetached(keysA.privateKey, message)
        assertFalse(crypto.verifyDetached(keysB.publicKey, message, sig))
    }

    @Test
    fun accountIdFromPublicKey_stableForSameKey() {
        val keys = crypto.generateSigningKeyPair()
        val a = crypto.accountIdFromPublicKey(keys.publicKey)
        val b = crypto.accountIdFromPublicKey(keys.publicKey)
        assertEquals(a, b)
    }

    @Test
    fun peerIdFromPublicKey_stableForSameKey() {
        val keys = crypto.generateSigningKeyPair()
        val a = crypto.peerIdFromPublicKey(keys.publicKey)
        val b = crypto.peerIdFromPublicKey(keys.publicKey)
        assertEquals(a, b)
    }

    @Test
    fun peerId_differsForDifferentSigningPublicKeys() {
        val keysA = crypto.generateSigningKeyPair()
        val keysB = crypto.generateSigningKeyPair()
        assertNotEquals(
            crypto.peerIdFromPublicKey(keysA.publicKey),
            crypto.peerIdFromPublicKey(keysB.publicKey),
        )
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
