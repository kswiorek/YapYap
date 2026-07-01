package org.yapyap.crypto.identity

import org.yapyap.crypto.primitives.CryptoProvider

/**
 * Builds a [DeviceIdentityRecord] with a valid encryption-key attestation and signed prekey,
 * using the same signing payload layout as [DefaultIdentityProvisioning] / [DefaultIdentityResolver].
 */
internal suspend fun buildAttestedDeviceIdentity(
    crypto: CryptoProvider,
    label: String,
): DeviceIdentityRecord {
    val signing = crypto.generateSigningKeyPair()
    val encryption = crypto.generateEncryptionKeyPair()
    val spk = crypto.generateEncryptionKeyPair()
    val deviceId = crypto.peerIdFromPublicKey(signing.publicKey)
    val encryptionKeyId = "encryption-$label"
    val spkId = "spk-$label"
    val keySignature = crypto.signDetached(
        signing.privateKey,
        encryption.publicKey + encryptionKeyId.encodeToByteArray(),
    )
    val spkSignature = crypto.signDetached(signing.privateKey, spk.publicKey)
    return DeviceIdentityRecord(
        deviceId = deviceId,
        signing = IdentityPublicKeyRecord(
            keyId = "signing-$label",
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
            keyId = spkId,
            publicKey = spk.publicKey,
            signature = spkSignature,
            privateKey = null,
        ),
        keySignature = keySignature,
    )
}
