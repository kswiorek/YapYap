package org.yapyap.routing.sync

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.identity.AccountId
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.protocol.PeerId
import org.yapyap.routing.outbound.OutboundMessenger
import org.yapyap.routing.router.PeerSendOutcome
import org.yapyap.routing.router.RouterConfig
import kotlin.time.Clock

/**
 * Ping-contradiction recovery (docs/room events.md §6): a ping advertising a
 * room our fold says the sender's account was removed from proves the sender
 * never saw their removal — re-deliver the removal node to that device so
 * their orphan machinery mints sync rows and the bounded gate serves the gap.
 *
 * Sync-content push: the content decision (which removal node, if any) lives
 * in [SyncPayloadProvider]; this only rate-governs and sends. Device-granular
 * by construction (the re-push targets the pinging device). Backoff-bounded
 * and dedup-safe; self-extinguishing — a converged device stops advertising
 * the room (shared-room ping filter), so the contradiction stops firing.
 */
internal class RemovalRePusher(
    private val syncPayloadProvider: SyncPayloadProvider,
    private val outboundMessenger: OutboundMessenger,
    private val routerConfig: StateFlow<RouterConfig>,
    private val clock: Clock = Clock.System,
) {
    private val lastPushAt = HashMap<Pair<PeerId, RoomId>, Long>()
    private val backoffMutex = Mutex()

    /**
     * Re-delivers the room's removal node for [senderAccount] to [deviceId].
     * No-op when suppressed (no contradiction, node missing, backoff) —
     * the next ping re-triggers.
     */
    suspend fun rePushRemoval(deviceId: PeerId, roomId: RoomId, senderAccount: AccountId) {
        val key = deviceId to roomId
        val now = clock.now().epochSeconds
        val backoffSeconds = routerConfig.value.removalRePushBackoff.inWholeSeconds
        if (backoffMutex.withLock { lastPushAt[key] }?.let { now - it < backoffSeconds } == true) return
        // Store-and-forward carries this past the target's offline window
        // (relay deposits); beyond relay retention the next ping re-triggers.
        val node = syncPayloadProvider.removalNodeFor(roomId, senderAccount) ?: return
        val outcome = outboundMessenger.sendMessageToPeer(deviceId, node, forceTransport = null)
        if (outcome !is PeerSendOutcome.Queued) return
        backoffMutex.withLock { lastPushAt[key] = now }
        AppLog.debug(
            component = LogComponent.ROUTER,
            event = LogEvent.PING_HANDLED,
            message = "Re-pushed removal node to stale device",
            fields = mapOf("deviceId" to deviceId, "roomId" to roomId, "removalNode" to node.messageId),
        )
        return
    }
}
