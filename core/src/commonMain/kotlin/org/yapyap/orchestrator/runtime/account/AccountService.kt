package org.yapyap.orchestrator.runtime.account

import org.yapyap.orchestrator.runtime.globalevent.GlobalEventOutcome
import org.yapyap.protocol.PeerId

/**
 * Self-service control plane: the local user managing their own devices and
 * account. Needs no admin flag — self-removal is a valid authorization branch
 * on its own. [org.yapyap.orchestrator.runtime.identity.IdentityService] stays
 * read-only; this is where self writes live.
 */
interface AccountService {
    /** Remove another of the local account's devices (e.g. a lost phone). */
    suspend fun removeOwnDevice(deviceId: PeerId): GlobalEventOutcome

    /**
     * Remove this device, keeping the account. After Published this node is
     * chain-dead — the caller must drive teardown (stop + local wipe).
     */
    suspend fun removeThisDevice(): GlobalEventOutcome

    /**
     * Remove the local account entirely (leave the network). Same teardown
     * post-condition as [removeThisDevice].
     */
    suspend fun removeOwnAccount(): GlobalEventOutcome
}
