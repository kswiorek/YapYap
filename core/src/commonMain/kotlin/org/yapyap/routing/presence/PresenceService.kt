package org.yapyap.routing.presence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.router.AccountReach
import org.yapyap.routing.router.LocalLinkStatus
import org.yapyap.routing.router.Presence
import org.yapyap.routing.router.RouterLifecycle
import org.yapyap.routing.router.RouterService
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.localLinkStatus
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
    /** Every transport's keeper; [announceOnline] clears their backoff. */
    private val linkKeepers: List<LinkKeeper>,
    private val pingProvider: PingProvider,
) : Presence, RouterService {

    // Account-level by design: the resolver maps each online device to its account here, so
    // device-level presence never leaves the router. Unknown devices (no row yet) are dropped;
    // absence asserts nothing. Intra-account device flaps re-emit the same set and are deduped.
    override val onlineAccounts: Flow<Set<AccountId>> =
        peerAvailabilityRegistry.onlineDevices
            .map { online -> online.mapNotNull { ctx.devices.getAccountIdForDevice(it) }.toSet() }
            .distinctUntilChanged()

    private val localLinkStatusFlow = MutableStateFlow(
        localLinkStatus(
            ctx.torTransport.health.value,
            ctx.torTransport.publishState.value,
            ctx.torTransport.recovery.value,
        ),
    )

    override val localLinkStatus: StateFlow<LocalLinkStatus> = localLinkStatusFlow.asStateFlow()

    override fun runIn(scope: CoroutineScope): List<Job> = listOf(
        scope.launch {
            combine(
                ctx.torTransport.health,
                ctx.torTransport.publishState,
                ctx.torTransport.recovery,
                ::localLinkStatus,
            ).collect { localLinkStatusFlow.value = it }
        },
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
        // Peers without a link get one opened (now, not after their backoff), on every transport
        // whose keeper targets them; each successful Tor open pings that peer. Backoff resets:
        // coming online is fresh evidence every peer is worth retrying now, unlike the
        // "no link" nudges elsewhere.
        linkKeepers.forEach { it.nudgeAll(resetBackoff = true) }
        // Best-effort: a transient send failure to one peer must not propagate out of the announce.
        runCatching { pingProvider.ping() }
    }
}
