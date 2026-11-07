package org.yapyap.crypto.identity

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.keys.EncryptionPrivateKey
import org.yapyap.crypto.keys.EncryptionPublicKey
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.crypto.keys.SignedPreKeyId
import org.yapyap.crypto.keys.SigningPrivateKey
import org.yapyap.crypto.keys.SigningPublicKey
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.crypto.store.KeyStore
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.AccountRole
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import kotlin.time.Instant

/** Read-only view of peers' public identity material, declared by crypto for the identity store to implement. */
internal interface PeerKeyDirectory {
    suspend fun getDeviceIdentity(deviceId: PeerId): DeviceIdentity?

    suspend fun getSignedPreKey(id: SignedPreKeyId): SignedPreKey?

    suspend fun getActiveSignedPreKeyForDevice(deviceId: PeerId): SignedPreKey?

    /** Owning account of a device, or null when unknown. */
    suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId?
}

/** Peers' public keys, resolved strictly: a missing record throws [CryptoException]. */
internal interface PeerKeys {
    suspend fun resolvePeerIdentity(deviceId: PeerId): DeviceIdentity

    /**
     * The peer's keys for an X3DH handshake, with its prekey signature verified. [signedPreKeyId]
     * pins a specific prekey (epoch-2 upgrade reuses epoch 1's); null takes the active one.
     */
    suspend fun resolvePeerPreKeyBundle(
        deviceId: PeerId,
        signedPreKeyId: SignedPreKeyId? = null,
    ): PeerPreKeyBundle

    /** Owning account of a device, or null when unknown. */
    suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId?
}

internal class DefaultPeerKeys(
    private val cryptoProvider: CryptoProvider,
    private val directory: PeerKeyDirectory,
) : PeerKeys {

    override suspend fun resolvePeerIdentity(deviceId: PeerId): DeviceIdentity {
        val device = directory.getDeviceIdentity(deviceId)
        if (device == null) {
            val e = CryptoException.MissingDeviceRecord(deviceId.id)
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_DEVICE_RECORD_MISSING,
                message = "Missing peer record",
                fields = mapOf("deviceId" to deviceId),
                throwable = e,
            )
            throw e
        }
        // No key re-verification here: chain rows are authenticated by the global fold at
        // commit time (account binding signature over both keys), and provisional intro rows
        // arrive over AEAD under the one-time onboarding secret. The device self-signature
        // this check used to verify is gone (it shared its column with the account signature
        // and could never verify for chain-folded rows).
        return device
    }

    override suspend fun resolvePeerPreKeyBundle(
        deviceId: PeerId,
        signedPreKeyId: SignedPreKeyId?,
    ): PeerPreKeyBundle {
        val device = resolvePeerIdentity(deviceId)
        val signedPreKey = if (signedPreKeyId != null) {
            val stored = directory.getSignedPreKey(signedPreKeyId)
                ?: throw keyLookupMiss(deviceId, "signed prekey $signedPreKeyId of device $deviceId")
            require(stored.deviceId == deviceId) {
                "Signed prekey $signedPreKeyId does not belong to deviceId=$deviceId"
            }
            stored
        } else {
            device.signedPreKey ?: throw keyLookupMiss(deviceId, "active signed prekey of device $deviceId")
        }
        if (!cryptoProvider.verifyDetached(device.signingKey, signedPreKey.publicKey.bytes, signedPreKey.signature)) {
            val e =
                CryptoException.IncompleteRecord("Device ${deviceId.id} keySignature verification failed, cannot verify device identity.")
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_DEVICE_RECORD_MISSING,
                message = "Incomplete peer record, signedPreKey verification failed",
                fields = mapOf("deviceId" to deviceId),
                throwable = e,
            )
            throw e
        }
        return PeerPreKeyBundle(identityKey = device.encryptionKey, signedPreKey = signedPreKey)
    }

    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? =
        directory.getAccountIdForDevice(deviceId)

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
