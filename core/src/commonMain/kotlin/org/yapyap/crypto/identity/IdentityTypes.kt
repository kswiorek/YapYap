package org.yapyap.crypto.identity

import org.yapyap.crypto.keys.EncryptionPrivateKey
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.OneTimePreKeyId
import org.yapyap.protocol.identity.SignedPreKey
import org.yapyap.protocol.identity.SignedPreKeyId

/** Our own signed prekey, with the private half from the keystore. */
internal data class LocalSignedPreKey(
    val preKey: SignedPreKey,
    val privateKey: EncryptionPrivateKey,
) {
    val id: SignedPreKeyId get() = preKey.id
    val publicKey: EncryptionPublicKey get() = preKey.publicKey
}

/** One-time prekey allocated locally and offered to a peer for the 4-DH upgrade. */
internal data class LocalOneTimePreKey(
    val id: OneTimePreKeyId,
    val publicKey: EncryptionPublicKey,
    val privateKey: EncryptionPrivateKey,
)

/** A peer's keys needed to start an X3DH handshake with it; the prekey signature is already verified. */
internal data class PeerPreKeyBundle(
    val identityKey: EncryptionPublicKey,
    val signedPreKey: SignedPreKey,
)
