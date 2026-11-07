package org.yapyap.crypto.identity

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.keys.EncryptionPrivateKey
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.crypto.keys.SigningPrivateKey
import org.yapyap.crypto.store.KeyStore
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.identity.SignedPreKeyId

/** This device's private keys. Only crypto and protection (and setup, to sign the device binding) get it. */
internal interface LocalKeys {
    suspend fun localDeviceSigningKey(): SigningPrivateKey

    suspend fun localDeviceEncryptionKey(): EncryptionPrivateKey

    /** Only the account's first device holds the account key; any later device throws here. */
    suspend fun localAccountSigningKey(): SigningPrivateKey

    suspend fun getCurrentLocalSignedPreKey(): LocalSignedPreKey

    /** Resolves a local SPK by wire id (supports archived keys after rotation). */
    suspend fun resolveLocalSignedPreKey(id: SignedPreKeyId): LocalSignedPreKey
}

internal class DefaultLocalKeys(
    private val localIdentity: LocalIdentity,
    private val directory: PeerKeyDirectory,
    private val privateKeyStore: KeyStore,
) : LocalKeys {

    private suspend fun localPrivateKey(slot: KeySlot): ByteArray =
        privateKeyStore.getKey(slot) ?: throw CryptoException.MissingKey(slot.keyringName)

    override suspend fun localDeviceSigningKey(): SigningPrivateKey =
        SigningPrivateKey(localPrivateKey(KeySlot.DeviceSigningPrivate))

    override suspend fun localDeviceEncryptionKey(): EncryptionPrivateKey =
        EncryptionPrivateKey(localPrivateKey(KeySlot.DeviceEncryptionPrivate))

    override suspend fun localAccountSigningKey(): SigningPrivateKey =
        SigningPrivateKey(localPrivateKey(KeySlot.AccountSigningPrivate))

    override suspend fun getCurrentLocalSignedPreKey(): LocalSignedPreKey {
        val device = localIdentity.getLocalDeviceIdentity()
        val activeId = directory.getActiveSignedPreKeyForDevice(device.deviceId)?.id
            ?: device.signedPreKey?.id
            ?: throw keyLookupMiss(device.deviceId, "active signed prekey of local device")
        return resolveLocalSignedPreKey(activeId)
    }

    override suspend fun resolveLocalSignedPreKey(id: SignedPreKeyId): LocalSignedPreKey {
        val device = localIdentity.getLocalDeviceId()
        val stored = directory.getSignedPreKey(id)
            ?: throw keyLookupMiss(device, "signed prekey $id of local device")
        require(stored.deviceId == device) {
            "Signed prekey $id does not belong to local device $device"
        }
        // The DB row never carries private material — attach the keystore key so
        // responder paths (X3DH bootstrap) can actually decrypt with it.
        val privateKey = privateKeyStore.getKey(KeySlot.SignedPreKeyPrivate(id))
            ?: throw keyLookupMiss(device, "private half of signed prekey $id")
        return LocalSignedPreKey(stored, EncryptionPrivateKey(privateKey))
    }

    private fun keyLookupMiss(deviceId: PeerId, what: String): CryptoException.MissingKey =
        CryptoException.MissingKey(what).also {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.KEY_LOOKUP_MISS,
                message = "Key lookup failed: $what",
                fields = mapOf("deviceId" to deviceId),
                throwable = it,
            )
        }
}
