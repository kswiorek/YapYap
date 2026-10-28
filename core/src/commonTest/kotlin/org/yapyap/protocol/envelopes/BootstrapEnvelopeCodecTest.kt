package org.yapyap.protocol.envelopes

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protocol.*
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class BootstrapEnvelopeCodecTest {

    private val sponsorDevice = PeerId("sponsor-device")
    private val newcomerDevice = PeerId("newcomer-device")
    private val payloadBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)

    private val crypto: CryptoProvider = DefaultCryptoProvider()
    private val now = epochSeconds(1_700_000_000L)

    private suspend fun sampleIntroPayload(): Intro {
        val signing = crypto.generateSigningKeyPair()
        val encryption = crypto.generateEncryptionKeyPair()
        val spk = crypto.generateEncryptionKeyPair()
        val deviceId = crypto.peerIdFromPublicKey(signing.publicKey)
        return Intro(
            version = 1,
            account = AccountIdentityRecord(
                accountId = AccountId("sponsor-account"),
                displayName = "Sponsor Name",
                key = IdentityPublicKeyRecord(
                    purpose = IdentityKeyPurpose.SIGNING,
                    publicKey = crypto.privateSigningKeyToPublicKey(signing.privateKey),
                ),
            ),
            device = DeviceIdentityRecord(
                deviceId = deviceId,
                signing = IdentityPublicKeyRecord(
                    purpose = IdentityKeyPurpose.SIGNING,
                    publicKey = signing.publicKey,
                ),
                encryption = IdentityPublicKeyRecord(
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
            ),
            deviceType = DeviceType.DESKTOP,
            torEndpoint = TorEndpoint("sponsorrelay.onion", 443),
            dagHeadTipIds = listOf(Uuid.random()),
        )
    }

    private fun sampleEnvelope(payload: ByteArray = payloadBytes): BootstrapEnvelope =
        BootstrapEnvelope(
            scheme = BootstrapSecurityScheme.SECRET_AEAD,
            bootstrapEnvelopeId = Uuid.random(),
            source = sponsorDevice,
            target = newcomerDevice,
            createdAt = now,
            payload = payload,
        )

    @Test
    fun packetType_bootstrap_wireValueRoundTrip() {
        assertEquals(PacketType.BOOTSTRAP, PacketType.fromWireValue(5))
        assertEquals((5).toByte(), PacketType.BOOTSTRAP.wireValue)
    }

    @Test
    fun bootstrapEnvelope_encodeDecode_roundTrip() {
        val original = sampleEnvelope()
        val decoded = BootstrapEnvelope.decode(original.encode())
        assertEquals(original, decoded)
        assertTrue(original.payload.contentEquals(decoded.payload))
    }

    @Test
    fun bootstrapEnvelope_aadBytes_isEncodedEnvelopeWithoutPayload() {
        // encoded = aadBytes || int32(payload.size) || payload
        val env = sampleEnvelope()
        val encoded = env.encode()
        val aad = env.aadBytes()
        assertEquals(encoded.size - (4 + env.payload.size), aad.size)
    }

    @Test
    fun bootstrapEnvelope_decode_rejectsBadMagic() {
        val encoded = sampleEnvelope().encode()
        val corrupted = encoded.copyOf().also { it[0] = 0x00 }
        assertFailsWith<IllegalArgumentException> { BootstrapEnvelope.decode(corrupted) }
    }

    @Test
    fun bootstrapEnvelope_decode_rejectsTrailingBytes() {
        val encoded = sampleEnvelope().encode()
        val padded = encoded + byteArrayOf(0x00)
        assertFailsWith<IllegalArgumentException> { BootstrapEnvelope.decode(padded) }
    }

    @Test
    fun bootstrapIntro_encodeDecode_roundTrip() = runTest {
        val original = sampleIntroPayload()
        val decoded = BootstrapPayload.decode(original.encode())
        // Re-encoding after decode must be byte-identical (stronger than field equality for
        // records whose ByteArray fields compare by reference).
        assertTrue(original.encode().contentEquals(decoded.encode()))
        assertTrue(original.device.signedPreKey!!.signature.contentEquals(decoded.device.signedPreKey!!.signature))
    }

    @Test
    fun bootstrapIntro_encodeDoesNotSerializePrivatePreKey() = runTest {
        val payload = sampleIntroPayload()
        val encoded = payload.encode()
        // privateKey must always decode to null — private key material never leaves the device.
        val decoded = BootstrapPayload.decode(encoded)
        assertEquals(null, (decoded as Intro).device.signedPreKey!!.privateKey)
    }

    @Test
    fun bootstrapIntro_decode_rejectsUnsupportedVersion() = runTest {
        val original = sampleIntroPayload()
        val bytes = original.encode()
        // Flip the payload version byte right after the kind byte (offset 1).
        val tampered = bytes.copyOf().also { it[1] = 0x02 }
        assertFailsWith<IllegalArgumentException> { BootstrapPayload.decode(tampered) }
    }

    private suspend fun sampleInvitePayload(newAccount: Boolean): Invite {
        val signing = crypto.generateSigningKeyPair()
        val encryption = crypto.generateEncryptionKeyPair()
        val deviceId = crypto.peerIdFromPublicKey(signing.publicKey)
        val device = DeviceIdentityRecord(
            deviceId = deviceId,
            signing = IdentityPublicKeyRecord(
                purpose = IdentityKeyPurpose.SIGNING,
                publicKey = signing.publicKey,
            ),
            encryption = IdentityPublicKeyRecord(
                purpose = IdentityKeyPurpose.ENCRYPTION,
                publicKey = encryption.publicKey,
            ),
            signedPreKey = null,
        )
        val torEndpoint = TorEndpoint("newcomer.onion", 80)
        val deviceType = DeviceType.DESKTOP
        if (!newAccount) {
            return Invite(
                account = null,
                device = device,
                deviceType = deviceType,
                torEndpoint = torEndpoint,
                sharedSecret = byteArrayOf(0x01, 0x02, 0x03),
                accountKeySignature = null,
            )
        }
        val accountSigning = crypto.generateSigningKeyPair()
        val accountPublic = crypto.privateSigningKeyToPublicKey(accountSigning.privateKey)
        val accountId = crypto.accountIdFromPublicKey(accountPublic)
        val account = AccountIdentityRecord(
            accountId = accountId,
            displayName = "Newcomer",
            key = IdentityPublicKeyRecord(
                purpose = IdentityKeyPurpose.SIGNING,
                publicKey = accountPublic,
            ),
        )
        val binding = accountSignedDeviceBindingBytes(
            accountId = accountId,
            deviceId = deviceId,
            signingPublicKey = signing.publicKey,
            encryptionPublicKey = encryption.publicKey,
            torEndpoint = torEndpoint,
            deviceType = deviceType,
        )
        return Invite(
            account = account,
            device = device,
            deviceType = deviceType,
            torEndpoint = torEndpoint,
            sharedSecret = byteArrayOf(0x01, 0x02, 0x03),
            accountKeySignature = crypto.signDetached(accountSigning.privateKey, binding),
        )
    }

    @Test
    fun bootstrapInvite_directDecode_roundTrip_newAccount() = runTest {
        val original = sampleInvitePayload(newAccount = true)
        // Exact sponsor path: DefaultOnboardingService.sponsorNewcomer calls Invite.decode(bytes).
        val decoded = Invite.decode(original.encode())
        assertTrue(original.encode().contentEquals(decoded.encode()))
    }

    @Test
    fun bootstrapInvite_directDecode_roundTrip_addDevice() = runTest {
        val original = sampleInvitePayload(newAccount = false)
        // Exact sponsor path: DefaultOnboardingService.sponsorNewcomer calls Invite.decode(bytes).
        val decoded = Invite.decode(original.encode())
        assertTrue(original.encode().contentEquals(decoded.encode()))
    }

    @Test
    fun bootstrapInvite_payloadDecode_roundTrip() = runTest {
        for (newAccount in listOf(false, true)) {
            val original = sampleInvitePayload(newAccount = newAccount)
            val decoded = BootstrapPayload.decode(original.encode())
            assertTrue(decoded is Invite)
            assertTrue(original.encode().contentEquals(decoded.encode()))
        }
    }
}