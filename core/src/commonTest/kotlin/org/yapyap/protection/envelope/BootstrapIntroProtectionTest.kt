package org.yapyap.protection.envelope

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.key.BootstrapKeySource
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapEnvelope
import org.yapyap.protocol.envelopes.BootstrapSecurityScheme
import org.yapyap.protocol.envelopes.Intro
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.*
import kotlin.uuid.Uuid

class BootstrapIntroProtectionTest {

    private val crypto: CryptoProvider = DefaultCryptoProvider()
    private val sponsor = PeerId("sponsor-device")
    private val newcomer = PeerId("newcomer-device")
    private val now = epochSeconds(1_700_000_000L)

    private class FakeKeySource(var secret: ByteArray?) : BootstrapKeySource {
        override suspend fun introKey(): ByteArray? = secret
    }

    private suspend fun samplePayload(): Intro {
        val signing = crypto.generateSigningKeyPair()
        val encryption = crypto.generateEncryptionKeyPair()
        val spk = crypto.generateEncryptionKeyPair()
        val deviceId = crypto.peerIdFromPublicKey(signing.publicKey)
        val encryptionKeyId = "device-encryption"
        return Intro(
            version = 1,
            account = AccountIdentityRecord(
                accountId = AccountId("sponsor-account"),
                displayName = "Sponsor Name",
                key = IdentityPublicKeyRecord(
                    keyId = "account-signing",
                    keyVersion = 0,
                    purpose = IdentityKeyPurpose.SIGNING,
                    publicKey = crypto.privateSigningKeyToPublicKey(signing.privateKey),
                ),
            ),
            device = DeviceIdentityRecord(
                deviceId = deviceId,
                signing = IdentityPublicKeyRecord(
                    keyId = "device-signing",
                    keyVersion = 0,
                    purpose = IdentityKeyPurpose.SIGNING,
                    publicKey = signing.publicKey,
                ),
                encryption = IdentityPublicKeyRecord(
                    keyId = encryptionKeyId,
                    keyVersion = 0,
                    purpose = IdentityKeyPurpose.ENCRYPTION,
                    publicKey = encryption.publicKey,
                ),
                signedPreKey = SignedPreKeyRecord(
                    deviceId = deviceId,
                    keyId = "spk-sponsor",
                    publicKey = spk.publicKey,
                    signature = crypto.signDetached(signing.privateKey, spk.publicKey),
                    privateKey = null,
                    isActive = true,
                    createdAt = now,
                ),
                keySignature = crypto.signDetached(
                    signing.privateKey,
                    encryption.publicKey + encryptionKeyId.encodeToByteArray(),
                ),
            ),
            deviceType = DeviceType.DESKTOP,
            torEndpoint = TorEndpoint("sponsorrelay.onion", 443),
            dagHeadLamport = 12L,
        )
    }

    @Test
    fun deriveIntroKey_isDeterministic_domainSeparated_and32Bytes() = runTest {
        val secret = ByteArray(32) { 7 }
        val p = BootstrapProtection(crypto, FakeKeySource(null))
        val k1 = p.deriveIntroKey(secret)
        val k2 = p.deriveIntroKey(secret)
        assertContentEquals(k1, k2)
        assertEquals(32, k1.size)
        // A different inbound key material must derive a different AEAD key.
        val altered = secret.copyOf().also { it[0] = 9 }
        assertFalse(k1.contentEquals(p.deriveIntroKey(altered)))
    }

    @Test
    fun protectIntro_thenOpenIntro_roundTrips() = runTest {
        val secret = ByteArray(32) { 3 }
        val p = BootstrapProtection(crypto, FakeKeySource(secret))
        val payload = samplePayload()

        val envelope = p.protect(payload, sponsor, newcomer, now, secret)
        val opened = p.open(envelope)

        assertTrue(payload.encode().contentEquals(opened.encode()))
        assertEquals(sponsor, envelope.source)
        assertEquals(newcomer, envelope.target)
        assertEquals(now, envelope.createdAt)
    }

    @Test
    fun openIntro_withWrongKey_fails() = runTest {
        val p = BootstrapProtection(crypto, FakeKeySource(ByteArray(32) { 3 }))
        val other = BootstrapProtection(crypto, FakeKeySource(ByteArray(32) { 4 }))
        val envelope = p.protect(samplePayload(), sponsor, newcomer, now, ByteArray(32) { 3 })

        assertFailsWith<ProtectionException.AuthenticationFailed> { other.open(envelope) }
    }

    @Test
    fun protectIntro_withNoSecret_failsFast() = runTest {
        val p = BootstrapProtection(crypto, FakeKeySource(null))
        // The protect path takes the sender's in-memory secret explicitly now; a missing one is
        // a programming error, not a protection failure.
        assertFailsWith<IllegalArgumentException> { p.protect(samplePayload(), sponsor, newcomer, now, null) }
    }

    @Test
    fun openIntro_withNoActiveKey_fails() = runTest {
        val p = BootstrapProtection(crypto, FakeKeySource(null))
        val envelope = BootstrapEnvelope(
            scheme = BootstrapSecurityScheme.SECRET_AEAD,
            bootstrapEnvelopeId = Uuid.random(),
            source = sponsor,
            target = newcomer,
            createdAt = now,
            payload = ByteArray(16) { 1 },
        )
        assertFailsWith<ProtectionException> { p.open(envelope) }
    }

    @Test
    fun openIntro_tamperedCiphertext_fails() = runTest {
        val secret = ByteArray(32) { 3 }
        val p = BootstrapProtection(crypto, FakeKeySource(secret))
        val envelope = p.protect(samplePayload(), sponsor, newcomer, now, secret)

        val tamperedCipher = envelope.payload.copyOf().also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte()
        }
        assertFailsWith<ProtectionException.AuthenticationFailed> { p.open(envelope.copy(payload = tamperedCipher)) }
    }

    @Test
    fun openIntro_tamperedHeader_AadBound_fails() = runTest {
        val secret = ByteArray(32) { 3 }
        val p = BootstrapProtection(crypto, FakeKeySource(secret))
        val envelope = p.protect(samplePayload(), sponsor, newcomer, now, secret)

        // Re-targeting the header must invalidate the AEAD (header is bound as AAD).
        assertFailsWith<ProtectionException.AuthenticationFailed> {
            p.open(envelope.copy(target = PeerId("some-other-device")))
        }
    }
}
