package org.yapyap.routing.router

import kotlinx.coroutines.flow.Flow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.protocol.envelopes.MessagePayload
import kotlin.time.Duration

interface Router {
    val incomingMessages: Flow<MessagePayload>

    /**
     * Hot stream of typing indicators received from peers, resolved to the author's account.
     * Room state aggregation and idle-timeout are handled upstream (orchestrator).
     */
    val typingIndicators: Flow<TypingIndicatorEvent>

    val pingPayloads: Flow<List<Pair<RoomId, Long>>>

    suspend fun start()
    suspend fun stop()
    fun isRunning(): Boolean

    /**
     * Immediately pings every known peer, advertising our presence and exchanging lamport clock
     * snapshots so listeners can trigger range syncs.
     *
     * The orchestrator calls this once, after the subsystems that consume [pingPayloads] (e.g. the
     * sync coordinator) are running, rather than having it fire inside [start]. Idempotent and
     * best-effort: failures to individual peers are swallowed. Returns once the pings are handed to
     * the transport.
     */
    suspend fun announceOnline()

    suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
        forceTransport: RouterTransport? = null,
    ): SendMessageResult

    /**
     * Signal that the local user is typing in [roomId] to [targets] (room members).
     * [interval] is the send cadence the caller will keep announcing with; it is stamped
     * into the payload so receivers can idle-timeout at ~2x. Fire-and-forget: indicators are
     * only delivered to peers with an open WebRTC session and are never queued or persisted.
     * Session opening for recently-reachable peers is a side effect of this call.
     */
    suspend fun sendTypingIndicator(
        targets: Collection<AccountId>,
        roomId: RoomId,
        interval: Duration,
    )

}