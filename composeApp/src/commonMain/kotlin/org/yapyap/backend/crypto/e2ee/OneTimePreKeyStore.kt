package org.yapyap.backend.crypto.e2ee

import org.yapyap.backend.crypto.LocalOneTimePreKey

/**
 * Local pool of one-time prekeys (OPKs) consumed during optional 4-DH session upgrade.
 */
interface OneTimePreKeyStore {
    /** Generate and persist a fresh OPK ready to be offered to a peer. */
    suspend fun allocate(): LocalOneTimePreKey

    /**
     * Load and mark consumed the OPK identified by [opkId].
     * Returns null when the id is unknown or was already consumed.
     */
    suspend fun consume(opkId: String): LocalOneTimePreKey?
}
