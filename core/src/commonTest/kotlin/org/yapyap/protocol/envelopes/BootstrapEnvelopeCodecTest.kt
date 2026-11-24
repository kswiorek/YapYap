package org.yapyap.protocol.envelopes


import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.SignedPreKey
import org.yapyap.protocol.identity.SignedPreKeyId
import org.yapyap.protocol.identity.SigningPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class BootstrapEnvelopeCodecTest {

    private val crypto: CryptoProvider = DefaultCryptoProvider()

    private suspend fun sampleIntroPayload(): Intro {
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
            dagHeadTipIds = listOf(Uuid.random()),
        )
    }

    @Test
    fun packetType_bootstrap_wireValueRoundTrip() {
        assertEquals(PacketType.BOOTSTRAP, PacketType.fromWireValue(5))
        assertEquals((5).toByte(), PacketType.BOOTSTRAP.wireValue)
    }

    @Test
    fun bootstrapHeader_encodeDecode_roundTrip() {
        val original = BootstrapHeader(
            scheme = BootstrapSecurityScheme.SECRET_AEAD,
            bootstrapEnvelopeId = Uuid.random(),
        )
        val decoded = BootstrapHeader.decode(original.encode())
        assertEquals(original, decoded)
        assertTrue(original.encode().contentEquals(decoded.encode()))
    }

    @Test
    fun bootstrapHeader_accountSigned_roundTrip() {
        val original = BootstrapHeader(
            scheme = BootstrapSecurityScheme.ACCOUNT_SIGNED,
            bootstrapEnvelopeId = Uuid.random(),
        )
        val decoded = BootstrapHeader.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun bootstrapSecurityScheme_wireValuesDistinct() {
        val wires = BootstrapSecurityScheme.entries.map { it.wireValue }.toSet()
        assertEquals(BootstrapSecurityScheme.entries.size, wires.size)
    }

    @Test
    fun bootstrapIntro_encodeDecode_roundTrip() = runTest {
        val original = sampleIntroPayload()
        val decoded = BootstrapPayload.decode(original.encode())
        // Re-encoding after decode must be byte-identical (stronger than field equality for
        // records whose ByteArray fields compare by reference).
        assertTrue(original.encode().contentEquals(decoded.encode()))
        assertTrue(original.device.signedPreKey!!.signature.contentEquals((decoded as Intro).device.signedPreKey!!.signature))
    }

    @Test
    fun bootstrapIntro_encodeDoesNotSerializePrivatePreKey() = runTest {
        val payload = sampleIntroPayload()
        val encoded = payload.encode()
        // Private key material never leaves the device: the wire type [SignedPreKey] has no private
        // half (that lives only in LocalSignedPreKey), so the decoded prekey is exactly the public one.
        val decoded = BootstrapPayload.decode(encoded)
        assertEquals(payload.device.signedPreKey, (decoded as Intro).device.signedPreKey)
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
        val device = DeviceIdentity(
            deviceId = deviceId,
            signingKey = signing.publicKey,
            encryptionKey = encryption.publicKey,
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
        val account = AccountIdentity(
            accountId = accountId,
            displayName = "Newcomer",
            signingKey = accountPublic,
        )
        val binding = accountSignedDeviceBindingBytes(
            accountId = accountId,
            deviceId = deviceId,
            signingPublicKey = signing.publicKey.bytes,
            encryptionPublicKey = encryption.publicKey.bytes,
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
