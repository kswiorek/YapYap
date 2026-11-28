package org.yapyap.routing.router

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.MessageId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.SyncId
import org.yapyap.protocol.envelopes.DagPayload
import kotlin.time.Duration
import kotlin.time.Instant

/** A push-path ACK reported by [MessageCarrier.deliveries], resolved to the acknowledging account. */
internal data class MessageAck(val messageId: MessageId, val account: AccountId)

/**
 * Messages in both directions: push (send and its receipts) and pull (asking for missing
 * history and serving it). Routing fans out per device; the orchestrator owns what is missing
 * and who may serve it.
 */
internal interface MessageCarrier {
    /**
     * Hot stream of DAG nodes received from peers, after authentication and decryption.
     * Never pull-path control: only [DagPayload] can reach it.
     */
    val incomingMessages: Flow<DagPayload>

    /**
     * Pushes [payload] to every device of [target]. Transport selection is owned by the
     * outbox retry loop, per attempt; there is no per-send override.
     *
     * The returned report is a latency snapshot, not a delivery verdict: the message
     * is already durable in the sender's DAG, and the peer's pull path (ping frontiers
     * and sync) delivers even when every count is zero. The local device is never a
     * target (own-account sends reach the account's other devices only); an
     * own-account send with no other devices is a normal, quiet outcome.
     */
    suspend fun sendMessage(target: AccountId, payload: DagPayload): AccountPushReport

    /**
     * Push-path ACKs, resolved to the acknowledging account: a message pushed by [sendMessage]
     * (or served in a sync reply) was processed by one of that account's devices.
     *
     * Hot and best-effort: only an ACK is reported, and the absence of one proves nothing:
     * messages also arrive through relays and the pull path, which never ACK to the sender.
     * One event per acknowledging device, so an account may be reported more than once.
     */
    val deliveries: Flow<MessageAck>

    /**
     * Hot stream of typing indicators received from peers, resolved to the author's account.
     * Room state aggregation and idle-timeout are handled upstream (orchestrator).
     */
    val typingIndicators: Flow<TypingIndicatorEvent>

    /**
     * Signal that the local user is typing in [roomId] to [targets] (room members).
     * [interval] is the send cadence the caller will keep announcing with; it is stamped
     * into the payload so receivers can idle-timeout at ~2x. Fire-and-forget: indicators are
     * only delivered to peers with an open WebRTC session and are never queued or persisted.
     * Session opening for recently-reachable peers is a side effect of this call.
     */
    suspend fun sendTypingIndicator(targets: Collection<AccountId>, roomId: RoomId, interval: Duration)

    /**
     * Hot stream of inbound pings' frontiers, resolved to the sender's account (null when
     * the device is unknown). One element per received ping, probes and replies alike, since
     * both carry the sender's latest frontiers. What they mean (new syncs, reopening declined
     * ones, re-pushing a removal) is decided upstream.
     */
    val frontiers: Flow<PingFrontiers>

    /**
     * Starts asking candidate devices for pending sync [syncId] (idempotent), first at
     * [firstAttemptAt]. What to ask for and which accounts may answer come from the
     * [org.yapyap.routing.message.port.SyncPayloadProvider]; the router asks until that
     * reports the sync satisfied or gone.
     */
    suspend fun startSync(syncId: SyncId, firstAttemptAt: Instant)

    /**
     * [account] asserts it can serve [syncId] after all (e.g. its ping advertises the sync's
     * room): if every candidate device has declined, that account's devices are asked again.
     */
    suspend fun reopenSync(syncId: SyncId, account: AccountId)
}
