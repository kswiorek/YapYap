package org.yapyap.routing.router

import kotlinx.coroutines.flow.Flow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.protocol.envelopes.BootstrapIntroPayload
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

    /**
     * Hot stream of authenticated bootstrap intros received from sponsors. Each event has passed
     * the preshared-key AEAD gate; persisting the sponsor's rows and triggering the global-room
     * range sync is an orchestrator concern.
     */
    val bootstrapIntros: Flow<BootstrapIntroEvent>

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

    /**
     * Send the onboarding bootstrap intro to a QR-scanned newcomer. Protection happens inside the
     * router (the preshared-key AEAD resolved from the active onboarding session); the result is
     * queued through the outbox with a short lifetime and cleared on the newcomer's ACK.
     */
    suspend fun sendBootstrapIntro(payload: BootstrapIntroPayload)

}