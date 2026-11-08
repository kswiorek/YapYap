package org.yapyap.crypto.store

import org.yapyap.crypto.identity.LocalOneTimePreKey
import org.yapyap.protocol.identity.OneTimePreKeyId
import kotlin.time.Instant

/**
 * Local pool of one-time prekeys (OPKs) consumed during optional 4-DH session upgrade.
 */
internal interface OpkRepository {
    /** Generate and persist a fresh OPK in [org.yapyap.crypto.store.OpkStatus.ALLOCATED] state. */
    suspend fun allocate(): LocalOneTimePreKey

    /** Transition [id] from ALLOCATED to OFFERED when attached to an outbound [org.yapyap.crypto.e2ee.session.InnerSessionControl.OpkOffer]. */
    suspend fun markOffered(id: OneTimePreKeyId)

    /**
     * Load and mark consumed the OPK identified by [id].
     * Returns null when the id is unknown or not in OFFERED state.
     */
    suspend fun consume(id: OneTimePreKeyId): LocalOneTimePreKey?

    /** Load an offered OPK without consuming it. Returns null when unavailable. */
    suspend fun loadOffered(id: OneTimePreKeyId): LocalOneTimePreKey?

    /**
     * Delete OFFERED OPKs offered before [cutoff].
     * Private key material is removed. Returns pruned opk ids.
     */
    suspend fun pruneExpiredOffers(cutoff: Instant): List<OneTimePreKeyId>

    /** All OPK IDs held for the local device (keystore cleanup at wipe). */
    suspend fun opkIds(): List<OneTimePreKeyId>
}

/** Lifecycle of a local one-time prekey. */
public enum class OpkStatus {
    ALLOCATED,
    OFFERED,
    CONSUMED,
}
