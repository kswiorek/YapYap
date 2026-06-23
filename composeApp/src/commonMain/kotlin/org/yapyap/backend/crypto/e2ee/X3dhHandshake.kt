package org.yapyap.backend.crypto.e2ee

import org.yapyap.backend.crypto.CryptoProvider
import org.yapyap.backend.crypto.EncryptionKeyPair
import org.yapyap.backend.crypto.KmpCryptoProvider

private const val SHARED_SECRET_SIZE = KmpCryptoProvider.AEAD_KEY_SIZE_BYTES
private val X3DH_KDF_INFO = "YapYapX3DH".encodeToByteArray()

enum class X3dhMode(val wireValue: Byte) {

    THREE_DH(3),
    FOUR_DH(4);

    companion object {
        fun fromWireValue(value: Byte): X3dhMode =
            X3dhMode.entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported packet type value: $value")
    }
}

data class X3dhRemotePeerKeys(
    val identityEncryptionPublicKey: ByteArray,
    val signedPreKeyPublicKey: ByteArray,
    val signedPreKeyId: String,
)

data class X3dhLocalInitiatorKeys(
    val identityEncryptionPrivateKey: ByteArray,
    val identityEncryptionPublicKey: ByteArray,
)

data class X3dhLocalResponderKeys(
    val identityEncryptionPrivateKey: ByteArray,
    val identityEncryptionPublicKey: ByteArray,
    val signedPreKeyPrivateKey: ByteArray,
    val signedPreKeyPublicKey: ByteArray,
    val signedPreKeyId: String,
)

data class X3dhWireInfo(
    val ephemeralPublicKey: ByteArray,
    val signedPreKeyId: String,
    val sessionEpoch: Int,
    val mode: X3dhMode,
    val oneTimePreKeyId: String? = null,
)

data class X3dhInitiatorResult(
    val sharedSecret: ByteArray,
    val ratchetBootstrap: RatchetBootstrap,
    val wire: X3dhWireInfo,
    val ephemeralKeyPair: EncryptionKeyPair,
)

data class X3dhResponderResult(
    val sharedSecret: ByteArray,
    val ratchetBootstrap: RatchetBootstrap,
)

/** Output of X3DH used to construct a [DoubleRatchetSession]. */
data class RatchetBootstrap(
    val sharedSecret: ByteArray,
    val remoteDhPublicKey: ByteArray? = null,
    val localDhPrivateKey: ByteArray? = null,
    val localDhPublicKey: ByteArray? = null,
)

class X3dhHandshake(
    private val crypto: CryptoProvider,
) {
    suspend fun initiatorCompute3Dh(
        local: X3dhLocalInitiatorKeys,
        remote: X3dhRemotePeerKeys,
        ephemeral: EncryptionKeyPair,
        sessionEpoch: Int = 1,
    ): X3dhInitiatorResult {
        require(sessionEpoch > 0) { "sessionEpoch must be positive" }
        val sharedSecret = computeSharedSecretInitiator(
            identityEncryptionPrivateKey = local.identityEncryptionPrivateKey,
            ephemeralPrivateKey = ephemeral.privateKey,
            remoteIdentityEncryptionPublicKey = remote.identityEncryptionPublicKey,
            remoteSignedPreKeyPublicKey = remote.signedPreKeyPublicKey,
            oneTimePreKeyPublicKey = null,
        )
        return X3dhInitiatorResult(
            sharedSecret = sharedSecret,
            ratchetBootstrap = RatchetBootstrap(
                sharedSecret = sharedSecret,
                remoteDhPublicKey = remote.signedPreKeyPublicKey.copyOf(),
            ),
            wire = X3dhWireInfo(
                ephemeralPublicKey = ephemeral.publicKey.copyOf(),
                signedPreKeyId = remote.signedPreKeyId,
                sessionEpoch = sessionEpoch,
                mode = X3dhMode.THREE_DH,
            ),
            ephemeralKeyPair = EncryptionKeyPair(
                publicKey = ephemeral.publicKey.copyOf(),
                privateKey = ephemeral.privateKey.copyOf(),
            ),
        )
    }

    suspend fun initiatorCompute4Dh(
        local: X3dhLocalInitiatorKeys,
        remote: X3dhRemotePeerKeys,
        ephemeral: EncryptionKeyPair,
        oneTimePreKeyPublicKey: ByteArray,
        oneTimePreKeyId: String,
        sessionEpoch: Int = 2,
    ): X3dhInitiatorResult {
        require(sessionEpoch > 0) { "sessionEpoch must be positive" }
        require(oneTimePreKeyId.isNotBlank()) { "oneTimePreKeyId must not be blank" }
        val sharedSecret = computeSharedSecretInitiator(
            identityEncryptionPrivateKey = local.identityEncryptionPrivateKey,
            ephemeralPrivateKey = ephemeral.privateKey,
            remoteIdentityEncryptionPublicKey = remote.identityEncryptionPublicKey,
            remoteSignedPreKeyPublicKey = remote.signedPreKeyPublicKey,
            oneTimePreKeyPublicKey = oneTimePreKeyPublicKey,
        )
        return X3dhInitiatorResult(
            sharedSecret = sharedSecret,
            ratchetBootstrap = RatchetBootstrap(
                sharedSecret = sharedSecret,
                remoteDhPublicKey = remote.signedPreKeyPublicKey.copyOf(),
            ),
            wire = X3dhWireInfo(
                ephemeralPublicKey = ephemeral.publicKey.copyOf(),
                signedPreKeyId = remote.signedPreKeyId,
                sessionEpoch = sessionEpoch,
                mode = X3dhMode.FOUR_DH,
                oneTimePreKeyId = oneTimePreKeyId,
            ),
            ephemeralKeyPair = EncryptionKeyPair(
                publicKey = ephemeral.publicKey.copyOf(),
                privateKey = ephemeral.privateKey.copyOf(),
            ),
        )
    }

    suspend fun responderCompute3Dh(
        local: X3dhLocalResponderKeys,
        remoteIdentityEncryptionPublicKey: ByteArray,
        wire: X3dhWireInfo,
    ): X3dhResponderResult {
        require(wire.mode == X3dhMode.THREE_DH) { "expected THREE_DH wire mode but got ${wire.mode}" }
        require(wire.signedPreKeyId == local.signedPreKeyId) {
            "signed prekey id mismatch: wire=${wire.signedPreKeyId}, local=${local.signedPreKeyId}"
        }
        val sharedSecret = computeSharedSecretResponder(
            identityEncryptionPrivateKey = local.identityEncryptionPrivateKey,
            signedPreKeyPrivateKey = local.signedPreKeyPrivateKey,
            remoteIdentityEncryptionPublicKey = remoteIdentityEncryptionPublicKey,
            remoteEphemeralPublicKey = wire.ephemeralPublicKey,
            oneTimePreKeyPrivateKey = null,
        )
        return X3dhResponderResult(
            sharedSecret = sharedSecret,
            ratchetBootstrap = RatchetBootstrap(
                sharedSecret = sharedSecret,
                localDhPrivateKey = local.signedPreKeyPrivateKey.copyOf(),
                localDhPublicKey = local.signedPreKeyPublicKey.copyOf(),
            ),
        )
    }

    suspend fun responderCompute4Dh(
        local: X3dhLocalResponderKeys,
        oneTimePreKeyPrivateKey: ByteArray,
        oneTimePreKeyId: String,
        remoteIdentityEncryptionPublicKey: ByteArray,
        wire: X3dhWireInfo,
    ): X3dhResponderResult {
        require(wire.mode == X3dhMode.FOUR_DH) { "expected FOUR_DH wire mode but got ${wire.mode}" }
        require(wire.signedPreKeyId == local.signedPreKeyId) {
            "signed prekey id mismatch: wire=${wire.signedPreKeyId}, local=${local.signedPreKeyId}"
        }
        require(wire.oneTimePreKeyId == oneTimePreKeyId) {
            "one-time prekey id mismatch: wire=${wire.oneTimePreKeyId}, local=$oneTimePreKeyId"
        }
        val sharedSecret = computeSharedSecretResponder(
            identityEncryptionPrivateKey = local.identityEncryptionPrivateKey,
            signedPreKeyPrivateKey = local.signedPreKeyPrivateKey,
            remoteIdentityEncryptionPublicKey = remoteIdentityEncryptionPublicKey,
            remoteEphemeralPublicKey = wire.ephemeralPublicKey,
            oneTimePreKeyPrivateKey = oneTimePreKeyPrivateKey,
        )
        return X3dhResponderResult(
            sharedSecret = sharedSecret,
            ratchetBootstrap = RatchetBootstrap(
                sharedSecret = sharedSecret,
                localDhPrivateKey = local.signedPreKeyPrivateKey.copyOf(),
                localDhPublicKey = local.signedPreKeyPublicKey.copyOf(),
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
        val ikm = if (oneTimePreKeyPublicKey == null) {
            dh1 + dh2 + dh3
        } else {
            val dh4 = crypto.deriveSharedSecret(ephemeralPrivateKey, oneTimePreKeyPublicKey)
            dh1 + dh2 + dh3 + dh4
        }
        return kdfSharedSecret(ikm)
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
        val ikm = if (oneTimePreKeyPrivateKey == null) {
            dh1 + dh2 + dh3
        } else {
            val dh4 = crypto.deriveSharedSecret(oneTimePreKeyPrivateKey, remoteEphemeralPublicKey)
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
