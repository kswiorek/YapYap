package org.yapyap.routing.presence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.router.*
import org.yapyap.routing.store.PeerAvailabilityStore
import kotlin.time.Instant

/**
 * Presence, both directions: what other accounts look like to us ([onlineAccounts],
 * [accountReach], [localLinkStatus]) and us telling them what we look like ([announceOnline]).
 * The device-level registry stays internal; the orchestrator only ever sees accounts.
 */
internal class PresenceService(
    private val ctx: RoutingContext,
    private val lifecycle: RouterLifecycle,
    private val peerAvailabilityRegistry: PeerAvailabilityRegistry,
    private val peerAvailabilityStore: PeerAvailabilityStore,
    private val linkKeeper: LinkKeeper,
    private val pingProvider: PingProvider,
    scope: CoroutineScope,
) : Presence {

    // Account-level by design: the resolver maps each online device to its account here, so
    // device-level presence never leaves the router. Unknown devices (no row yet) are dropped;
    // absence asserts nothing. Intra-account device flaps re-emit the same set and are deduped.
    override val onlineAccounts: Flow<Set<AccountId>> =
        peerAvailabilityRegistry.onlineDevices
            .map { online -> online.mapNotNull { ctx.devices.getAccountIdForDevice(it) }.toSet() }
            .distinctUntilChanged()

    override val localLinkStatus: StateFlow<LocalLinkStatus> =
        combine(
            ctx.torTransport.health,
            ctx.torTransport.publishState,
            ctx.torTransport.recovery,
            ::localLinkStatus,
        ).stateIn(
            scope,
            SharingStarted.Eagerly,
            localLinkStatus(
                ctx.torTransport.health.value,
                ctx.torTransport.publishState.value,
                ctx.torTransport.recovery.value,
            ),
        )

    override suspend fun accountReach(accounts: Collection<AccountId>): Map<AccountId, AccountReach> {
        val lastSeen = peerAvailabilityStore.availabilityForAll()
            .filter { it.lastSeen != Instant.DISTANT_PAST }
            .associate { it.deviceId to it.lastSeen }
        return accounts.associateWith { account ->
            AccountReach(
                lastSeen = ctx.devices.getAllPeerDevicesForAccount(account).mapNotNull { lastSeen[it] }.maxOrNull(),
            )
        }
    }

    override fun localTorEndpoint(): TorEndpoint =
        checkNotNull(ctx.torTransport.localTorEndpoint) { "Router not started: local Tor endpoint unknown" }

    override suspend fun announceOnline() {
        lifecycle.requireStarted("announcing online")
        // Peers without a link get one opened (now, not after their backoff); each successful
        // open pings that peer. The only resetBackoff = true caller: coming online is fresh
        // evidence every peer is worth retrying now, unlike the "no link" nudges elsewhere.
        linkKeeper.nudgeAll(resetBackoff = true)
        // Best-effort: a transient send failure to one peer must not propagate out of the announce.
        runCatching { pingProvider.ping() }
    }
}
