package org.yapyap.persistence.key

import org.yapyap.crypto.identity.IdentityKeyPurpose

internal enum class KeyType {
    PUBLIC,
    PRIVATE,
}

internal data class KeyReference(
    val keyId: String,
    val purpose: IdentityKeyPurpose,
    val type: KeyType,
) {
    init {
        require(keyId.isNotBlank()) { "keyId must not be blank" }
    }
}

internal interface KeyStore {
    suspend fun putKey(ref: KeyReference, key: ByteArray)

    suspend fun getKey(ref: KeyReference): ByteArray?

    suspend fun deleteKey(ref: KeyReference)

    /**
     * Best-effort removal of the well-known identity + master-key refs.
     * OS keyrings offer no enumeration, so dynamic `spk-*`/`opk-*` IDs are
     * enumerated from the DB by the wipe caller instead
     * ([org.yapyap.orchestrator.boot.LocalStoreReset] via `collectKeyRefs`);
     * entries orphaned from their rows linger — harmless for the PoC since fresh
     * provisioning mints new IDs and the master-key wipe cryptographically
     * retires the old DB.
     */
    suspend fun deleteAll()
}
