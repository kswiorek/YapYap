package org.yapyap.crypto.identity


import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.SignedPreKey
import org.yapyap.protocol.identity.SignedPreKeyId

/**
 * Builds a [DeviceIdentity] with a signed prekey, using real generated keys.
 */
internal suspend fun buildAttestedDeviceIdentity(
    crypto: CryptoProvider,
    label: String,
): DeviceIdentity {
    val signing = crypto.generateSigningKeyPair()
    val encryption = crypto.generateEncryptionKeyPair()
    val spk = crypto.generateEncryptionKeyPair()
    val deviceId = crypto.peerIdFromPublicKey(signing.publicKey)
    val spkId = "spk-$label"
    val spkSignature = crypto.signDetached(signing.privateKey, spk.publicKey.bytes)
    return DeviceIdentity(
        deviceId = deviceId,
        signingKey = signing.publicKey,
        encryptionKey = encryption.publicKey,
        signedPreKey = SignedPreKey(
            id = SignedPreKeyId(spkId),
            deviceId = deviceId,
            publicKey = spk.publicKey,
            signature = spkSignature
        ),
    )
}
