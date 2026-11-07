package org.yapyap.protocol.identity

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId

/**
 * What a device presents about itself: its id, public identity keys and, when known, its
 * current signed prekey. Carried in bootstrap payloads and stored in `devices`.
 */
internal data class DeviceIdentity(
    val deviceId: PeerId,
    val signingKey: SigningPublicKey,
    val encryptionKey: EncryptionPublicKey,
    val signedPreKey: SignedPreKey? = null,
)

/**
 * What an account presents about itself. [signingKey] is null for the key-less stand-in a
 * newly added device starts on, until the fold confirms its real account.
 */
internal data class AccountIdentity(
    val accountId: AccountId,
    val displayName: String,
    val signingKey: SigningPublicKey? = null,
)

/** The public half of a device's signed prekey, as peers see it. */
internal class SignedPreKey(
    val id: SignedPreKeyId,
    val deviceId: PeerId,
    val publicKey: EncryptionPublicKey,
    /** The device's signing key over [publicKey]. */
    val signature: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is SignedPreKey && id == other.id && deviceId == other.deviceId &&
                publicKey == other.publicKey && signature.contentEquals(other.signature))

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }

    override fun toString(): String = "SignedPreKey(id=$id, deviceId=$deviceId)"
}
