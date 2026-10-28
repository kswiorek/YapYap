package org.yapyap.protocol.envelopes

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.TorEndpoint
import kotlin.test.*

class GlobalEventPayloadCodecTest {

    private val crypto = DefaultCryptoProvider()

    private suspend fun sampleAddDevice(withSpk: Boolean): GlobalEventPayload.AddDevice {
        val signing = crypto.generateSigningKeyPair()
        val encryption = crypto.generateEncryptionKeyPair()
        val spk = crypto.generateEncryptionKeyPair()
        val deviceId = crypto.peerIdFromPublicKey(signing.publicKey)
        val accountKeys = crypto.generateSigningKeyPair()
        val accountId = crypto.accountIdFromPublicKey(accountKeys.publicKey)
        val tor = TorEndpoint("peer.onion", 443)
        val deviceType = DeviceType.DESKTOP
        val binding = accountSignedDeviceBindingBytes(
            accountId = accountId,
            deviceId = deviceId,
            signingPublicKey = signing.publicKey,
            encryptionPublicKey = encryption.publicKey,
            torEndpoint = tor,
            deviceType = deviceType,
        )
        return GlobalEventPayload.AddDevice(
            accountId = accountId,
            deviceId = deviceId,
            signingPublicKey = signing.publicKey,
            encryptionPublicKey = encryption.publicKey,
            torEndpoint = tor,
            deviceType = deviceType,
            keySignature = crypto.signDetached(accountKeys.privateKey, binding),
            signedPreKeyId = if (withSpk) "spk-test" else null,
            signedPreKeyPublicKey = if (withSpk) spk.publicKey else null,
            signedPreKeySignature = if (withSpk) {
                crypto.signDetached(signing.privateKey, spk.publicKey)
            } else {
                null
            },
        )
    }

    @Test
    fun addDevice_withSpk_encodeDecode_roundTrip() = runTest {
        val original = sampleAddDevice(withSpk = true)
        val decoded = GlobalEventPayload.AddDevice.decode(original.encode())
        assertTrue(original.encode().contentEquals(decoded.encode()))
        assertEquals(original, decoded)
    }

    @Test
    fun addDevice_withoutSpk_encodeDecode_roundTrip() = runTest {
        val original = sampleAddDevice(withSpk = false)
        val decoded = GlobalEventPayload.AddDevice.decode(original.encode())
        assertTrue(original.encode().contentEquals(decoded.encode()))
        assertEquals(original, decoded)
    }

    @Test
    fun addDevice_init_rejects_partial_spk() = runTest {
        val full = sampleAddDevice(withSpk = true)
        assertFailsWith<IllegalArgumentException> {
            full.copy(signedPreKeySignature = null)
        }
        assertFailsWith<IllegalArgumentException> {
            full.copy(signedPreKeyId = null)
        }
        assertFailsWith<IllegalArgumentException> {
            full.copy(signedPreKeyPublicKey = null)
        }
    }

    @Test
    fun addDevice_bindingBytes_ignores_spk() = runTest {
        // The account binding must cover exactly the pre-SPK fields: the SPK rides
        // alongside the authorization, it is not part of it.
        val signing = crypto.generateSigningKeyPair()
        val encryption = crypto.generateEncryptionKeyPair()
        val spk = crypto.generateEncryptionKeyPair()
        val deviceId = crypto.peerIdFromPublicKey(signing.publicKey)
        val accountKeys = crypto.generateSigningKeyPair()
        val accountId = crypto.accountIdFromPublicKey(accountKeys.publicKey)
        val tor = TorEndpoint("peer.onion", 443)
        val binding = accountSignedDeviceBindingBytes(
            accountId = accountId,
            deviceId = deviceId,
            signingPublicKey = signing.publicKey,
            encryptionPublicKey = encryption.publicKey,
            torEndpoint = tor,
            deviceType = DeviceType.DESKTOP,
        )
        val keySignature = crypto.signDetached(accountKeys.privateKey, binding)
        suspend fun event(withSpk: Boolean): GlobalEventPayload.AddDevice = GlobalEventPayload.AddDevice(
            accountId = accountId,
            deviceId = deviceId,
            signingPublicKey = signing.publicKey,
            encryptionPublicKey = encryption.publicKey,
            torEndpoint = tor,
            deviceType = DeviceType.DESKTOP,
            keySignature = keySignature,
            signedPreKeyId = if (withSpk) "spk-test" else null,
            signedPreKeyPublicKey = if (withSpk) spk.publicKey else null,
            signedPreKeySignature = if (withSpk) {
                crypto.signDetached(signing.privateKey, spk.publicKey)
            } else {
                null
            },
        )
        assertContentEquals(event(false).bindingBytes(), event(true).bindingBytes())
    }

    @Test
    fun addDevice_spk_signature_verifies_under_device_key() = runTest {
        val event = sampleAddDevice(withSpk = true)
        assertTrue(
            crypto.verifyDetached(
                event.signingPublicKey,
                event.signedPreKeyPublicKey!!,
                event.signedPreKeySignature!!,
            ),
        )
    }
}
