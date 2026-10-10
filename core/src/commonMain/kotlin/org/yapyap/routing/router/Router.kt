package org.yapyap.routing.router

import kotlinx.coroutines.flow.Flow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.MessagePayload
import kotlin.time.Duration

interface Router {
    val incomingMessages: Flow<MessagePayload>

    /**
     * Hot stream of typing indicators received from peers, resolved to the author's account.
     * Room state aggregation and idle-timeout are handled upstream (orchestrator).
     */
    val typingIndicators: Flow<TypingIndicatorEvent>

    /**
     * Hot stream of inbound pings, resolved to the sender's account (null when
     * the device is unknown). One element per received ping — probes and
     * replies alike, since both carry the sender's latest frontiers.
     */
    val pingPayloads: Flow<PingFrontiers>

    /**
     * Hot stream of authenticated bootstrap-family packets (one flow per packet type — payload
     * variants dispatch consumer-side, like [incomingMessages]). Each event has passed its kind's
     * authentication (AEAD intro gate / account-signature recovery check); consuming is an
     * orchestrator concern: INTRO → newcomer onboarding provider, RECOVERY_REQUEST → recovery responder.
     */
    val bootstrapPackets: Flow<BootstrapPacketEvent>

    /**
     * Live set of accounts with at least one currently-online device.
     * Account-level by design: device-level presence (PeerId-keyed) stays
     * inside the router — consumers never learn which device carries an
     * account. The local account is not a member (self-presence is not peer
     * presence); callers that display the local account treat it as online.
     */
    val onlineAccounts: Flow<Set<AccountId>>

    suspend fun start()
    suspend fun stop()
    fun isRunning(): Boolean

    /**
     * Immediately pings every known peer, advertising our presence and exchanging
     * frontier snapshots so listeners can trigger frontier syncs.
     *
     * The orchestrator calls this once, after the subsystems that consume [pingPayloads] (e.g. the
     * sync coordinator) are running, rather than having it fire inside [start]. Idempotent and
     * best-effort: failures to individual peers are swallowed. Returns once the pings are handed to
     * the transport.
     */
    suspend fun announceOnline()

    /**
     * Pushes [payload] to every device of [target]. Transport selection is owned by the
     * outbox retry loop, per attempt — there is no per-send override.
     *
     * The returned report is a latency snapshot, not a delivery verdict: the message
     * is already durable in the sender's DAG, and the peer's pull path (ping frontiers
     * + sync) delivers even when every count is zero. The local device is never a
     * target (own-account sends reach the account's other devices only); an
     * own-account send with no other devices is a normal, quiet outcome.
     */
    suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
    ): AccountPushReport

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
     * Send a bootstrap-family packet: protect inside the router, queue through the outbox with
     * a short lifetime, cleared on the peer's ACK.
     *
     * @param targetEndpoint out-of-band endpoint override for targets with no local devices row.
     * @param sharedSecret sender's in-memory one-time secret, required for INTRO (used once,
     *   never persisted). Null for RECOVERY_REQUEST.
     */
    suspend fun sendBootstrap(
        payload: BootstrapPayload,
        target: PeerId,
        targetEndpoint: TorEndpoint? = null,
        sharedSecret: ByteArray? = null,
    )

}