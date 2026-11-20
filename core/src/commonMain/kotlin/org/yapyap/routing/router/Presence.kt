package org.yapyap.routing.router

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.TorEndpoint

/** Who is reachable, and this device's own connectivity and address. */
internal interface Presence {
    /**
     * Live set of accounts with at least one currently-online device.
     * Account-level by design: device-level presence (PeerId-keyed) stays
     * inside the router; consumers never learn which device carries an
     * account. The local account is not a member (self-presence is not peer
     * presence); callers that display the local account treat it as online.
     */
    val onlineAccounts: Flow<Set<AccountId>>

    /**
     * What the router knows about reaching each of [accounts], for display only (the roster's
     * "last seen"). Reported per account, never per device; routing decisions stay inside the router.
     */
    suspend fun accountReach(accounts: Collection<AccountId>): Map<AccountId, AccountReach>

    /**
     * This device's own connectivity, folded from the transports' health (network, Tor bootstrap,
     * onion publication, automatic recovery). Device-local; the orchestrator lifts it to the public
     * transport status. Transport types stop here.
     */
    val localLinkStatus: StateFlow<LocalLinkStatus>

    /**
     * Our own onion endpoint, as Tor published it on [Router.start]. The orchestrator puts it in
     * invites, intros and its own AddDevice, the one place it handles an address (bootstrap).
     * Throws before [Router.start].
     */
    fun localTorEndpoint(): TorEndpoint
}
