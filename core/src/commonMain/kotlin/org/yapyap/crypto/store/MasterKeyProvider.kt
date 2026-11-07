package org.yapyap.crypto.store

import org.yapyap.crypto.keys.KeySlot
import org.yapyap.crypto.primitives.CryptoProvider

/**
 * Provides the SQLCipher / DB master key, persisted via [KeyStore] (typically OS keyring).
 */
internal interface MasterKeyProvider {
    /** Returns the existing DB master key, or creates and stores one. */
    suspend fun getOrCreate(): ByteArray
}

internal class DefaultMasterKeyProvider(
    private val keyStore: KeyStore,
    private val crypto: CryptoProvider,
    private val slot: KeySlot = KeySlot.DatabaseMasterKey,
    private val keySizeBytes: Int = DEFAULT_KEY_SIZE_BYTES,
) : MasterKeyProvider {
    init {
        require(keySizeBytes > 0) { "keySizeBytes must be > 0" }
    }

    override suspend fun getOrCreate(): ByteArray {
        keyStore.getKey(slot)?.let { return it }
        val generated = crypto.randomBytes(keySizeBytes)
        keyStore.putKey(slot, generated)
        return generated
    }

    companion object {
        const val DEFAULT_KEY_SIZE_BYTES: Int = 32
    }
}
