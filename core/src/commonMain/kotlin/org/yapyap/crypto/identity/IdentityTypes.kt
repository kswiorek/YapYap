package org.yapyap.crypto.identity

import org.yapyap.crypto.keys.EncryptionPrivateKey
import org.yapyap.crypto.keys.EncryptionPublicKey
import org.yapyap.crypto.keys.OneTimePreKeyId
import org.yapyap.crypto.keys.SignedPreKeyId
import org.yapyap.crypto.keys.SigningPublicKey
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.AccountRole
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import kotlin.time.Instant

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

/** A stored `accounts` row. */
internal data class Account(
    val identity: AccountIdentity,
    val role: AccountRole,
    val status: IdentityStatus,
    val isLocal: Boolean,
) {
    val accountId: AccountId get() = identity.accountId
    val displayName: String get() = identity.displayName
}

/** A stored `devices` row. */
internal data class Device(
    val identity: DeviceIdentity,
    val accountId: AccountId,
    val deviceType: DeviceType,
    val torEndpoint: TorEndpoint,
    val status: IdentityStatus,
    val isLocal: Boolean,
    val provisional: Boolean,
) {
    val deviceId: PeerId get() = identity.deviceId
}

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
