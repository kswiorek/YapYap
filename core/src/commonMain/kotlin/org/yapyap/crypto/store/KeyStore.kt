package org.yapyap.crypto.store

import org.yapyap.crypto.keys.KeySlot

internal interface KeyStore {
    suspend fun putKey(slot: KeySlot, key: ByteArray)

    suspend fun getKey(slot: KeySlot): ByteArray?

    suspend fun deleteKey(slot: KeySlot)

    /**
     * Best-effort removal of the well-known slots ([KeySlot.wellKnown]).
     * OS keyrings offer no enumeration, so prekey slots are enumerated from the DB by the
     * wipe caller instead ([org.yapyap.orchestrator.boot.LocalStoreReset]); entries orphaned
     * from their rows linger — harmless, since fresh provisioning mints new ids and the
     * master-key wipe cryptographically retires the old DB.
     */
    suspend fun deleteAll()
}
