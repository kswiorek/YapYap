package org.yapyap.routing.router

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.envelopes.MessagePayload
import kotlin.time.Duration

/** Messages and typing indicators, account-level: routing fans out per device. */
internal interface MessageCarrier {
    /** Hot stream of messages received from peers, after authentication and decryption. */
    val incomingMessages: Flow<MessagePayload>

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
    suspend fun sendMessage(target: AccountId, payload: MessagePayload): AccountPushReport

    /**
     * Push-path ACKs, resolved to the acknowledging account: a message pushed by [sendMessage]
     * (or served in a sync reply) was processed by one of that account's devices, or a
     * bootstrap packet from [BootstrapCarrier.sendBootstrap] reached its target (account null:
     * the target may have no account here yet, as with a recovery request's target before its
     * intro).
     *
     * Hot and best-effort: only an ACK is reported, and the absence of one proves nothing:
     * messages also arrive through relays and the pull path, which never ACK to the sender.
     * One event per acknowledging device, so an account may be reported more than once.
     */
    val deliveries: Flow<Delivery>

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
}
