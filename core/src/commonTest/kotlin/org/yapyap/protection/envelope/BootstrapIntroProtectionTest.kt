package org.yapyap.protection.envelope

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.persistence.db.DeviceType
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapEnvelope
import org.yapyap.protocol.envelopes.BootstrapIntroPayload
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

    private suspend fun samplePayload(): BootstrapIntroPayload {
        val signing = crypto.generateSigningKeyPair()
        val encryption = crypto.generateEncryptionKeyPair()
        val spk = crypto.generateEncryptionKeyPair()
        val deviceId = crypto.peerIdFromPublicKey(signing.publicKey)
        val encryptionKeyId = "device-encryption"
        return BootstrapIntroPayload(
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
            dagHeadMessageId = Uuid.random(),
            dagHeadLamport = 12L,
        )
    }

    @Test
    fun deriveIntroKey_isDeterministic_domainSeparated_and32Bytes() = runTest {
        val secret = ByteArray(32) { 7 }
        val p = BootstrapIntroProtection(crypto, FakeKeySource(null))
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
        val p = BootstrapIntroProtection(crypto, FakeKeySource(secret))
        val payload = samplePayload()

        val envelope = p.protectIntro(payload, sponsor, newcomer, now)
        val opened = p.openIntro(envelope)

        assertTrue(payload.encode().contentEquals(opened.encode()))
        assertEquals(sponsor, envelope.source)
        assertEquals(newcomer, envelope.target)
        assertEquals(now, envelope.createdAt)
    }

    @Test
    fun openIntro_withWrongKey_fails() = runTest {
        val p = BootstrapIntroProtection(crypto, FakeKeySource(ByteArray(32) { 3 }))
        val other = BootstrapIntroProtection(crypto, FakeKeySource(ByteArray(32) { 4 }))
        val envelope = p.protectIntro(samplePayload(), sponsor, newcomer, now)

        assertFailsWith<ProtectionException.AuthenticationFailed> { other.openIntro(envelope) }
    }

    @Test
    fun protectIntro_withNoActiveKey_fails() = runTest {
        val p = BootstrapIntroProtection(crypto, FakeKeySource(null))
        assertFailsWith<ProtectionException> { p.protectIntro(samplePayload(), sponsor, newcomer, now) }
    }

    @Test
    fun openIntro_withNoActiveKey_fails() = runTest {
        val p = BootstrapIntroProtection(crypto, FakeKeySource(null))
        val envelope = BootstrapEnvelope(Uuid.random(), sponsor, newcomer, now, ByteArray(16) { 1 })
        assertFailsWith<ProtectionException> { p.openIntro(envelope) }
    }

    @Test
    fun openIntro_tamperedCiphertext_fails() = runTest {
        val secret = ByteArray(32) { 3 }
        val p = BootstrapIntroProtection(crypto, FakeKeySource(secret))
        val envelope = p.protectIntro(samplePayload(), sponsor, newcomer, now)

        val tamperedCipher = envelope.payload.copyOf().also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte()
        }
        assertFailsWith<ProtectionException.AuthenticationFailed> { p.openIntro(envelope.copy(payload = tamperedCipher)) }
    }

    @Test
    fun openIntro_tamperedHeader_AadBound_fails() = runTest {
        val secret = ByteArray(32) { 3 }
        val p = BootstrapIntroProtection(crypto, FakeKeySource(secret))
        val envelope = p.protectIntro(samplePayload(), sponsor, newcomer, now)

        // Re-targeting the header must invalidate the AEAD (header is bound as AAD).
        assertFailsWith<ProtectionException.AuthenticationFailed> {
            p.openIntro(envelope.copy(target = PeerId("some-other-device")))
        }
    }
}