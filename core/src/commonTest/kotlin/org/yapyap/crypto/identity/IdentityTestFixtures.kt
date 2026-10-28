package org.yapyap.crypto.identity

import org.yapyap.crypto.primitives.CryptoProvider

/**
 * Builds a [DeviceIdentityRecord] with a signed prekey, using real generated keys.
 */
internal suspend fun buildAttestedDeviceIdentity(
    crypto: CryptoProvider,
    label: String,
): DeviceIdentityRecord {
    val signing = crypto.generateSigningKeyPair()
    val encryption = crypto.generateEncryptionKeyPair()
    val spk = crypto.generateEncryptionKeyPair()
    val deviceId = crypto.peerIdFromPublicKey(signing.publicKey)
    val spkId = "spk-$label"
    val spkSignature = crypto.signDetached(signing.privateKey, spk.publicKey)
    return DeviceIdentityRecord(
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
            keyId = spkId,
            publicKey = spk.publicKey,
            signature = spkSignature,
            privateKey = null,
        ),
    )
}
