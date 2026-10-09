package org.yapyap.orchestrator.runtime.account

import org.yapyap.orchestrator.runtime.globalevent.GlobalEventOutcome
import org.yapyap.protocol.PeerId

/**
 * Self-service control plane: the local user managing their own devices and
 * account. Needs no admin flag — self-removal is a valid authorization branch
 * on its own. [org.yapyap.orchestrator.runtime.identity.IdentityService] stays
 * read-only; this is where self writes live.
 *
 * Every mutator refuses while newcomer onboarding is in flight (the fold has not
 * anchored the local device yet) and before any write — refusals are values
 * ([GlobalEventOutcome.Refused]); infrastructure failures still throw.
 */
interface AccountService {
    /** Remove another of the local account's devices (e.g. a lost phone). */
    suspend fun removeOwnDevice(deviceId: PeerId): GlobalEventOutcome

    /**
     * Remove this device, keeping the account. After Published this node is
     * chain-dead — the fold commit flips `OrchestratorState` to `ResetRequired`
     * (same machinery as the self-ban path); the GUI observes
     * `Orchestrator.state` until `resetApp()`.
     */
    suspend fun removeThisDevice(): GlobalEventOutcome

    /**
     * Remove the local account entirely (leave the network). Same teardown
     * post-condition as [removeThisDevice].
     */
    suspend fun removeOwnAccount(): GlobalEventOutcome

    /**
     * Still-active devices whose branch-1 `AddDevice` was authored by [deviceId],
     * per the last committed fold — the cascade-ban source set for the removal UI
     * (ban-first-then-query: the set is frozen once the ban lands, docs/global
     * events.md §3). A removal survivor dies only to its own `RemoveDevice`,
     * never to a second removal of its author — offer [removeOwnDevice] per
     * survivor. Read-only: no onboarding guard.
     */
    suspend fun devicesAddedBy(deviceId: PeerId): List<PeerId>
}
