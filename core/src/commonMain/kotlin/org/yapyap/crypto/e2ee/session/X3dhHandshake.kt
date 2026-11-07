package org.yapyap.crypto.e2ee.session

import org.yapyap.crypto.e2ee.CryptoSessionException
import org.yapyap.crypto.identity.LocalOneTimePreKey
import org.yapyap.crypto.identity.LocalSignedPreKey
import org.yapyap.crypto.identity.PeerPreKeyBundle
import org.yapyap.crypto.keys.EncryptionKeyPair
import org.yapyap.crypto.keys.EncryptionPrivateKey
import org.yapyap.crypto.keys.EncryptionPublicKey
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.DefaultCryptoProvider

private const val SHARED_SECRET_SIZE = DefaultCryptoProvider.AEAD_KEY_SIZE_BYTES
private val X3DH_KDF_INFO = "YapYapX3DH".encodeToByteArray()

public enum class X3dhMode(public val wireValue: Byte) {

    THREE_DH(3),
    FOUR_DH(4);

    internal companion object {
        fun fromWireValue(value: Byte): X3dhMode =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported packet type value: $value")
    }
}

internal data class X3dhWireInfo(
    val ephemeralPublicKey: ByteArray,
    val signedPreKeyId: String,
    val sessionEpoch: Int,
    val sessionGeneration: Int = 1,
    val mode: X3dhMode,
    val oneTimePreKeyId: String? = null,
)

internal data class X3dhInitiatorResult(
    val sharedSecret: ByteArray,
    val ratchetBootstrap: RatchetBootstrap,
    val wire: X3dhWireInfo,
    val ephemeralKeyPair: EncryptionKeyPair,
)

internal data class X3dhResponderResult(
    val sharedSecret: ByteArray,
    val ratchetBootstrap: RatchetBootstrap,
)

/** Output of X3DH used to construct a [DoubleRatchetSession]. */
internal data class RatchetBootstrap(
    val sharedSecret: ByteArray,
    val remoteDhPublicKey: ByteArray? = null,
    val localDhPrivateKey: ByteArray? = null,
    val localDhPublicKey: ByteArray? = null,
)

internal class X3dhHandshake(
    private val crypto: CryptoProvider,
) {
    suspend fun initiatorCompute3Dh(
        localIdentityKey: EncryptionPrivateKey,
        remote: PeerPreKeyBundle,
        ephemeral: EncryptionKeyPair,
        sessionEpoch: Int = 1,
    ): X3dhInitiatorResult {
        require(sessionEpoch > 0) { "sessionEpoch must be positive" }
        val sharedSecret = computeSharedSecretInitiator(
            identityEncryptionPrivateKey = localIdentityKey.bytes,
            ephemeralPrivateKey = ephemeral.privateKey.bytes,
            remoteIdentityEncryptionPublicKey = remote.identityKey.bytes,
            remoteSignedPreKeyPublicKey = remote.signedPreKey.publicKey.bytes,
            oneTimePreKeyPublicKey = null,
        )
        return X3dhInitiatorResult(
            sharedSecret = sharedSecret,
            ratchetBootstrap = RatchetBootstrap(
                sharedSecret = sharedSecret,
                remoteDhPublicKey = remote.signedPreKey.publicKey.bytes.copyOf(),
                localDhPrivateKey = ephemeral.privateKey.bytes.copyOf(),
                localDhPublicKey = ephemeral.publicKey.bytes.copyOf(),
            ),
            wire = X3dhWireInfo(
                ephemeralPublicKey = ephemeral.publicKey.bytes.copyOf(),
                signedPreKeyId = remote.signedPreKey.id.value,
                sessionEpoch = sessionEpoch,
                mode = X3dhMode.THREE_DH,
            ),
            ephemeralKeyPair = EncryptionKeyPair(
                publicKey = EncryptionPublicKey(ephemeral.publicKey.bytes.copyOf()),
                privateKey = EncryptionPrivateKey(ephemeral.privateKey.bytes.copyOf()),
            ),
        )
    }

    suspend fun initiatorCompute4Dh(
        localIdentityKey: EncryptionPrivateKey,
        remote: PeerPreKeyBundle,
        ephemeral: EncryptionKeyPair,
        oneTimePreKeyPublicKey: EncryptionPublicKey,
        oneTimePreKeyId: String,
        sessionEpoch: Int = 2,
    ): X3dhInitiatorResult {
        require(sessionEpoch > 0) { "sessionEpoch must be positive" }
        require(oneTimePreKeyId.isNotBlank()) { "oneTimePreKeyId must not be blank" }
        val sharedSecret = computeSharedSecretInitiator(
            identityEncryptionPrivateKey = localIdentityKey.bytes,
            ephemeralPrivateKey = ephemeral.privateKey.bytes,
            remoteIdentityEncryptionPublicKey = remote.identityKey.bytes,
            remoteSignedPreKeyPublicKey = remote.signedPreKey.publicKey.bytes,
            oneTimePreKeyPublicKey = oneTimePreKeyPublicKey.bytes,
        )
        return X3dhInitiatorResult(
            sharedSecret = sharedSecret,
            ratchetBootstrap = RatchetBootstrap(
                sharedSecret = sharedSecret,
                remoteDhPublicKey = remote.signedPreKey.publicKey.bytes.copyOf(),
                localDhPrivateKey = ephemeral.privateKey.bytes.copyOf(),
                localDhPublicKey = ephemeral.publicKey.bytes.copyOf(),
            ),
            wire = X3dhWireInfo(
                ephemeralPublicKey = ephemeral.publicKey.bytes.copyOf(),
                signedPreKeyId = remote.signedPreKey.id.value,
                sessionEpoch = sessionEpoch,
                mode = X3dhMode.FOUR_DH,
                oneTimePreKeyId = oneTimePreKeyId,
            ),
            ephemeralKeyPair = EncryptionKeyPair(
                publicKey = EncryptionPublicKey(ephemeral.publicKey.bytes.copyOf()),
                privateKey = EncryptionPrivateKey(ephemeral.privateKey.bytes.copyOf()),
            ),
        )
    }

    suspend fun responderCompute3Dh(
        localIdentityKey: EncryptionPrivateKey,
        localSignedPreKey: LocalSignedPreKey,
        remoteIdentityKey: EncryptionPublicKey,
        wire: X3dhWireInfo,
    ): X3dhResponderResult {
        if (wire.mode != X3dhMode.THREE_DH) {
            throw CryptoSessionException.HandshakeMismatch("expected THREE_DH wire mode but got ${wire.mode}")
        }
        if (wire.signedPreKeyId != localSignedPreKey.id.value) {
            throw CryptoSessionException.HandshakeMismatch(
                "signed prekey id mismatch: wire=${wire.signedPreKeyId}, local=${localSignedPreKey.id}",
            )
        }
        val sharedSecret = computeSharedSecretResponder(
            identityEncryptionPrivateKey = localIdentityKey.bytes,
            signedPreKeyPrivateKey = localSignedPreKey.privateKey.bytes,
            remoteIdentityEncryptionPublicKey = remoteIdentityKey.bytes,
            remoteEphemeralPublicKey = wire.ephemeralPublicKey,
            oneTimePreKeyPrivateKey = null,
        )
        return X3dhResponderResult(
            sharedSecret = sharedSecret,
            ratchetBootstrap = RatchetBootstrap(
                sharedSecret = sharedSecret,
                localDhPrivateKey = localSignedPreKey.privateKey.bytes.copyOf(),
                localDhPublicKey = localSignedPreKey.publicKey.bytes.copyOf(),
            ),
        )
    }

    suspend fun responderCompute4Dh(
        localIdentityKey: EncryptionPrivateKey,
        localSignedPreKey: LocalSignedPreKey,
        oneTimePreKey: LocalOneTimePreKey,
        remoteIdentityKey: EncryptionPublicKey,
        wire: X3dhWireInfo,
    ): X3dhResponderResult {
        if (wire.mode != X3dhMode.FOUR_DH) {
            throw CryptoSessionException.HandshakeMismatch("expected FOUR_DH wire mode but got ${wire.mode}")
        }
        if (wire.signedPreKeyId != localSignedPreKey.id.value) {
            throw CryptoSessionException.HandshakeMismatch(
                "signed prekey id mismatch: wire=${wire.signedPreKeyId}, local=${localSignedPreKey.id}",
            )
        }
        if (wire.oneTimePreKeyId != oneTimePreKey.id.value) {
            throw CryptoSessionException.HandshakeMismatch(
                "one-time prekey id mismatch: wire=${wire.oneTimePreKeyId}, local=${oneTimePreKey.id}",
            )
        }
        val sharedSecret = computeSharedSecretResponder(
            identityEncryptionPrivateKey = localIdentityKey.bytes,
            signedPreKeyPrivateKey = localSignedPreKey.privateKey.bytes,
            remoteIdentityEncryptionPublicKey = remoteIdentityKey.bytes,
            remoteEphemeralPublicKey = wire.ephemeralPublicKey,
            oneTimePreKeyPrivateKey = oneTimePreKey.privateKey.bytes,
        )
        return X3dhResponderResult(
            sharedSecret = sharedSecret,
            ratchetBootstrap = RatchetBootstrap(
                sharedSecret = sharedSecret,
                localDhPrivateKey = localSignedPreKey.privateKey.bytes.copyOf(),
                localDhPublicKey = localSignedPreKey.publicKey.bytes.copyOf(),
            ),
        )
    }

    private suspend fun computeSharedSecretInitiator(
        identityEncryptionPrivateKey: ByteArray,
        ephemeralPrivateKey: ByteArray,
        remoteIdentityEncryptionPublicKey: ByteArray,
        remoteSignedPreKeyPublicKey: ByteArray,
        oneTimePreKeyPublicKey: ByteArray?,
    ): ByteArray {
        val dh1 = crypto.deriveSharedSecret(identityEncryptionPrivateKey, remoteSignedPreKeyPublicKey)
        val dh2 = crypto.deriveSharedSecret(ephemeralPrivateKey, remoteIdentityEncryptionPublicKey)
        val dh3 = crypto.deriveSharedSecret(ephemeralPrivateKey, remoteSignedPreKeyPublicKey)
        val dh4 = oneTimePreKeyPublicKey?.let { crypto.deriveSharedSecret(ephemeralPrivateKey, it) }
        return combineAndKdf(dh1, dh2, dh3, dh4)
    }

    private suspend fun computeSharedSecretResponder(
        identityEncryptionPrivateKey: ByteArray,
        signedPreKeyPrivateKey: ByteArray,
        remoteIdentityEncryptionPublicKey: ByteArray,
        remoteEphemeralPublicKey: ByteArray,
        oneTimePreKeyPrivateKey: ByteArray?,
    ): ByteArray {
        val dh1 = crypto.deriveSharedSecret(signedPreKeyPrivateKey, remoteIdentityEncryptionPublicKey)
        val dh2 = crypto.deriveSharedSecret(identityEncryptionPrivateKey, remoteEphemeralPublicKey)
        val dh3 = crypto.deriveSharedSecret(signedPreKeyPrivateKey, remoteEphemeralPublicKey)
        val dh4 = oneTimePreKeyPrivateKey?.let { crypto.deriveSharedSecret(it, remoteEphemeralPublicKey) }
        return combineAndKdf(dh1, dh2, dh3, dh4)
    }

    private suspend fun combineAndKdf(
        dh1: ByteArray,
        dh2: ByteArray,
        dh3: ByteArray,
        dh4: ByteArray?,
    ): ByteArray {
        val ikm = if (dh4 == null) {
            dh1 + dh2 + dh3
        } else {
            dh1 + dh2 + dh3 + dh4
        }
        return kdfSharedSecret(ikm)
    }

    private suspend fun kdfSharedSecret(ikm: ByteArray): ByteArray =
        crypto.hkdf(
            ikm = ikm,
            salt = null,
            info = X3DH_KDF_INFO,
            outputLength = SHARED_SECRET_SIZE,
        )
}
