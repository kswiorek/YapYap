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
import org.yapyap.protocol.envelopes.BootstrapHeader
import org.yapyap.protocol.envelopes.BootstrapSecurityScheme
import org.yapyap.protocol.envelopes.Intro
import org.yapyap.protocol.envelopes.RecoveryRequest
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.SignedPreKey
import org.yapyap.protocol.identity.SignedPreKeyId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class BootstrapIntroProtectionTest {

    private val crypto: CryptoProvider = DefaultCryptoProvider()
    private val sponsor = PeerId("sponsor-device")
    private val newcomer = PeerId("newcomer-device")

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
                signingKey = crypto.privateSigningKeyToPublicKey(signing.privateKey),
            ),
            device = DeviceIdentity(
                deviceId = deviceId,
                signingKey = signing.publicKey,
                encryptionKey = encryption.publicKey,
                signedPreKey = SignedPreKey(
                    id = SignedPreKeyId("spk-sponsor"),
                    deviceId = deviceId,
                    publicKey = spk.publicKey,
                    signature = crypto.signDetached(signing.privateKey, spk.publicKey.bytes),
                ),
            ),
            deviceType = DeviceType.DESKTOP,
            torEndpoint = TorEndpoint("sponsorrelay.onion", 443),
            dagHeadTipIds = emptyList(),
        )
    }

    private suspend fun sampleRecoveryRequest(deviceId: PeerId): RecoveryRequest {
        val accountKeys = crypto.generateSigningKeyPair()
        val signing = crypto.generateSigningKeyPair()
        val encryption = crypto.generateEncryptionKeyPair()
        val unsigned = RecoveryRequest(
            version = 1,
            account = AccountIdentity(
                AccountId("recovering-account"),
                "Recovering",
                signingKey = accountKeys.publicKey,
            ),
            device = DeviceIdentity(
                deviceId = deviceId,
                signingKey = signing.publicKey,
                encryptionKey = encryption.publicKey,
            ),
            deviceType = DeviceType.DESKTOP,
            torEndpoint = TorEndpoint("recovering.onion", 80),
            sharedSecret = ByteArray(32) { 7 },
            accountSignature = ByteArray(1),
        )
        return unsigned.copy(
            accountSignature = crypto.signDetached(
                accountKeys.privateKey,
                unsigned.accountSignedDeviceBindingBytes(),
            ),
        )
    }

    @Test
    fun protectIntro_thenOpenIntro_roundTrips() = runTest {
        val secret = ByteArray(32) { 3 }
        val p = BootstrapProtection(crypto, FakeKeySource(secret))
        val payload = samplePayload()

        val protected = p.protect(payload, sponsor, newcomer, secret)
        assertEquals(BootstrapSecurityScheme.SECRET_AEAD, protected.header.scheme)
        val opened = p.open(protected.header, protected.content, sponsor, newcomer)

        assertTrue(payload.encode().contentEquals(opened.encode()))
        assertTrue(p.hasActiveSession())
    }

    @Test
    fun openIntro_withWrongKey_fails() = runTest {
        val p = BootstrapProtection(crypto, FakeKeySource(ByteArray(32) { 3 }))
        val other = BootstrapProtection(crypto, FakeKeySource(ByteArray(32) { 4 }))
        val protected = p.protect(samplePayload(), sponsor, newcomer, ByteArray(32) { 3 })

        assertFailsWith<ProtectionException.AuthenticationFailed> {
            other.open(protected.header, protected.content, sponsor, newcomer)
        }
    }

    @Test
    fun protectIntro_withNoSecret_failsFast() = runTest {
        val p = BootstrapProtection(crypto, FakeKeySource(null))
        // Missing secret is a programming error: the sender must hold the one-time secret.
        assertFailsWith<IllegalArgumentException> {
            p.protect(samplePayload(), sponsor, newcomer, null)
        }
    }

    @Test
    fun openIntro_withNoActiveKey_fails() = runTest {
        val p = BootstrapProtection(crypto, FakeKeySource(null))
        assertTrue(!p.hasActiveSession())
        val header = BootstrapHeader(BootstrapSecurityScheme.SECRET_AEAD, Uuid.random())
        assertFailsWith<ProtectionException> {
            p.open(header, ByteArray(16) { 1 }, sponsor, newcomer)
        }
    }

    @Test
    fun openIntro_tamperedCiphertext_fails() = runTest {
        val secret = ByteArray(32) { 3 }
        val p = BootstrapProtection(crypto, FakeKeySource(secret))
        val protected = p.protect(samplePayload(), sponsor, newcomer, secret)

        val tamperedCipher = protected.content.copyOf().also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte()
        }
        assertFailsWith<ProtectionException.AuthenticationFailed> {
            p.open(protected.header, tamperedCipher, sponsor, newcomer)
        }
    }

    @Test
    fun openIntro_tamperedTarget_AadBound_fails() = runTest {
        val secret = ByteArray(32) { 3 }
        val p = BootstrapProtection(crypto, FakeKeySource(secret))
        val protected = p.protect(samplePayload(), sponsor, newcomer, secret)

        // Re-targeting the packet must invalidate the AEAD (header + hop ends are bound as AAD).
        assertFailsWith<ProtectionException.AuthenticationFailed> {
            p.open(protected.header, protected.content, sponsor, PeerId("some-other-device"))
        }
    }

    @Test
    fun protectRecoveryRequest_thenOpen_roundTrips() = runTest {
        val p = BootstrapProtection(crypto, FakeKeySource(null))
        val payload = sampleRecoveryRequest(sponsor)

        val protected = p.protect(payload, sponsor, newcomer)
        assertEquals(BootstrapSecurityScheme.ACCOUNT_SIGNED, protected.header.scheme)
        val opened = p.open(protected.header, protected.content, sponsor, newcomer)

        assertTrue(payload.encode().contentEquals((opened as RecoveryRequest).encode()))
    }

    @Test
    fun openRecoveryRequest_badSignature_fails() = runTest {
        val p = BootstrapProtection(crypto, FakeKeySource(null))
        val payload = sampleRecoveryRequest(sponsor).copy(accountSignature = ByteArray(64) { 1 })

        val protected = p.protect(payload, sponsor, newcomer)
        assertFailsWith<ProtectionException.AuthenticationFailed> {
            p.open(protected.header, protected.content, sponsor, newcomer)
        }
    }
}
