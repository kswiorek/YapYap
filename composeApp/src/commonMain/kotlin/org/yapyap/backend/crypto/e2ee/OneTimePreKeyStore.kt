package org.yapyap.backend.crypto.e2ee

import org.yapyap.backend.crypto.LocalOneTimePreKey

/**
 * Local pool of one-time prekeys (OPKs) consumed during optional 4-DH session upgrade.
 */
interface OneTimePreKeyStore {
    /** Generate and persist a fresh OPK in [org.yapyap.backend.db.OpkStatus.ALLOCATED] state. */
    suspend fun allocate(): LocalOneTimePreKey

    /** Transition [opkId] from ALLOCATED to OFFERED when attached to an outbound [InnerSessionControl.OpkOffer]. */
    suspend fun markOffered(opkId: String)

    /**
     * Load and mark consumed the OPK identified by [opkId].
     * Returns null when the id is unknown or not in OFFERED state.
     */
    suspend fun consume(opkId: String): LocalOneTimePreKey?

    /** Load an offered OPK without consuming it. Returns null when unavailable. */
    suspend fun loadOffered(opkId: String): LocalOneTimePreKey?

    /**
     * Delete OFFERED OPKs offered before [cutoffEpochSeconds].
     * Private key material is removed. Returns pruned opk ids.
     */
    suspend fun pruneExpiredOffers(cutoffEpochSeconds: Long): List<String>
}
