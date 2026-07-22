package org.yapyap.persistence.key

import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.primitives.CryptoProvider

/**
 * Provides the SQLCipher / DB master key, persisted via [KeyStore] (typically OS keyring).
 */
interface MasterKeyProvider {
    /** Returns the existing DB master key, or creates and stores one. */
    suspend fun getOrCreate(): ByteArray
}

class DefaultMasterKeyProvider(
    private val keyStore: KeyStore,
    private val crypto: CryptoProvider,
    private val keyId: String = DEFAULT_KEY_ID,
    private val keySizeBytes: Int = DEFAULT_KEY_SIZE_BYTES,
) : MasterKeyProvider {
    init {
        require(keyId.isNotBlank()) { "keyId must not be blank" }
        require(keySizeBytes > 0) { "keySizeBytes must be > 0" }
    }

    private val ref = KeyReference(
        keyId = keyId,
        purpose = IdentityKeyPurpose.ENCRYPTION,
        type = KeyType.PRIVATE,
    )

    override suspend fun getOrCreate(): ByteArray {
        keyStore.getKey(ref)?.let { return it }
        val generated = crypto.randomBytes(keySizeBytes)
        keyStore.putKey(ref, generated)
        return generated
    }

    companion object {
        const val DEFAULT_KEY_ID: String = "db-master-key"
        const val DEFAULT_KEY_SIZE_BYTES: Int = 32
    }
}
