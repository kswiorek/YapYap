package org.yapyap.routing.ping

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.identity.AccountId
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.PeerId
import org.yapyap.routing.outbound.OutboundMessenger
import org.yapyap.routing.router.PeerSendOutcome
import kotlin.time.Clock

/**
 * Ping-contradiction recovery (docs/room events.md §6): a ping advertising a
 * room our fold says the sender's account was removed from proves the sender
 * never saw their removal — re-deliver the removal node to that device so
 * their orphan machinery mints sync rows and the bounded gate serves the gap.
 *
 * Device-granular by construction (the re-push targets the pinging device), so
 * it lives in the routing layer next to the §6 re-open hook. Backoff-bounded
 * and dedup-safe; self-extinguishing — a converged device stops advertising
 * the room (shared-room ping filter), so the contradiction stops firing.
 */
internal class RemovalRePusher(
    private val roomRepository: RoomRepository,
    private val messageRepository: MessageRepository,
    private val outboundMessenger: OutboundMessenger,
    private val clock: Clock = Clock.System,
) {
    private val lastPushAt = HashMap<Pair<PeerId, RoomId>, Long>()
    private val backoffMutex = Mutex()

    /**
     * Re-delivers the room's removal node for [senderAccount] to [deviceId].
     * Returns false when suppressed (no contradiction, node missing, backoff) —
     * the next ping re-triggers.
     */
    suspend fun rePushRemoval(deviceId: PeerId, roomId: RoomId, senderAccount: AccountId): Boolean {
        val row = roomRepository.memberRowOf(roomId, senderAccount) ?: return false
        val nodeId = row.removalNodeId ?: return false
        val now = clock.now().epochSeconds
        val key = deviceId to roomId
        if (backoffMutex.withLock { lastPushAt[key] }?.let { now - it < REPUSH_BACKOFF_SECONDS } == true) return false
        val node = messageRepository.findById(nodeId)?.payload ?: return false
        // Store-and-forward carries this past the target's offline window
        // (relay deposits); beyond relay retention the next ping re-triggers.
        val outcome = outboundMessenger.sendMessageToPeer(deviceId, node, forceTransport = null)
        if (outcome !is PeerSendOutcome.Queued) return false
        backoffMutex.withLock { lastPushAt[key] = now }
        AppLog.debug(
            component = LogComponent.ROUTER,
            event = LogEvent.PING_HANDLED,
            message = "Re-pushed removal node to stale device",
            fields = mapOf("deviceId" to deviceId, "roomId" to roomId, "removalNode" to nodeId),
        )
        return true
    }

    companion object {
        /** Matches the sync retry cap: the ping is the proof, the backoff is the rate governor. */
        const val REPUSH_BACKOFF_SECONDS = 3600L
    }
}
