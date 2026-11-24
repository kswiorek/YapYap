package org.yapyap.protection.envelope


import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.store.BootstrapKeySource
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapEnvelope
import org.yapyap.protocol.envelopes.BootstrapSecurityScheme
import org.yapyap.protocol.envelopes.Intro
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.SignedPreKey
import org.yapyap.protocol.identity.SignedPreKeyId
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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
        return Intro(
            version = 1,
            account = AccountIdentity(
                accountId = AccountId("sponsor-account"),
                displayName = "Sponsor Name",
                signingKey = SigningPublicKey(crypto.privateSigningKeyToPublicKey(signing.privateKey).bytes),
            ),
            device = DeviceIdentity(
                deviceId = deviceId,
                signingKey = signing.publicKey,
                encryptionKey = encryption.publicKey,
                signedPreKey = SignedPreKey(
                    id = SignedPreKeyId("spk-sponsor"),
                    deviceId = deviceId,
                    publicKey = spk.publicKey,
                    signature = crypto.signDetached(signing.privateKey, spk.publicKey.bytes)
                ),
            ),
            deviceType = DeviceType.DESKTOP,
            torEndpoint = TorEndpoint("sponsorrelay.onion", 443),
            dagHeadTipIds = emptyList(),
        )
    }

    private fun ctx(secret: ByteArray?) =
        BootstrapProtectContext(sponsor, newcomer, now, secret)

    @Test
    fun protectIntro_thenOpenIntro_roundTrips() = runTest {
        val secret = ByteArray(32) { 3 }
        val p = BootstrapProtection(crypto, FakeKeySource(secret))
        val payload = samplePayload()

        val envelope = p.protect(payload, ctx(secret))
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
        val envelope = p.protect(samplePayload(), ctx(ByteArray(32) { 3 }))

        assertFailsWith<ProtectionException.AuthenticationFailed> { other.open(envelope) }
    }

    @Test
    fun protectIntro_withNoSecret_failsFast() = runTest {
        val p = BootstrapProtection(crypto, FakeKeySource(null))
        // Missing secret is a programming error; protection maps require-failures to PERMANENT.
        assertFailsWith<ProtectionException.InvalidEnvelope> { p.protect(samplePayload(), ctx(null)) }
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
        val envelope = p.protect(samplePayload(), ctx(secret))

        val tamperedCipher = envelope.payload.copyOf().also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte()
        }
        assertFailsWith<ProtectionException.AuthenticationFailed> { p.open(envelope.copy(payload = tamperedCipher)) }
    }

    @Test
    fun openIntro_tamperedHeader_AadBound_fails() = runTest {
        val secret = ByteArray(32) { 3 }
        val p = BootstrapProtection(crypto, FakeKeySource(secret))
        val envelope = p.protect(samplePayload(), ctx(secret))

        // Re-targeting the header must invalidate the AEAD (header is bound as AAD).
        assertFailsWith<ProtectionException.AuthenticationFailed> {
            p.open(envelope.copy(target = PeerId("some-other-device")))
        }
    }
}
