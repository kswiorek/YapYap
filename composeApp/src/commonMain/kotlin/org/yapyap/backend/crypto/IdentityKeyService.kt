package org.yapyap.backend.crypto

import org.yapyap.backend.protocol.DeviceAddress

enum class IdentityKeyPurpose {
    SIGNING,
    ENCRYPTION,
}

data class IdentityPublicKeyRecord(
    val keyId: String,
    val keyVersion: Long,
    val purpose: IdentityKeyPurpose,
    val publicKey: ByteArray,
)

data class LocalIdentityRecord(
    val address: DeviceAddress,
    val signing: IdentityPublicKeyRecord,
    val encryption: IdentityPublicKeyRecord,
)

interface IdentityPublicKeyRepository {
    fun upsertLocalIdentity(identity: LocalIdentityRecord)

    fun resolveDeviceKey(deviceId: String, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord?
}

interface IdentityKeyService {
    /**
     * Loads existing local identity metadata or creates an initial identity keyset.
     */
    fun getOrCreateLocalIdentity(address: DeviceAddress): LocalIdentityRecord

    /**
     * Resolves the currently active local signing key id for detached signatures.
     */
    fun resolveLocalSigningKeyId(address: DeviceAddress): String

    /**
     * Loads local private key bytes for the requested key id and purpose.
     */
    fun loadLocalPrivateKey(keyId: String, purpose: IdentityKeyPurpose): ByteArray

    /**
     * Resolves a peer public key for verification / key agreement.
     */
    fun resolvePeerPublicKey(source: DeviceAddress, purpose: IdentityKeyPurpose): ByteArray?
}
