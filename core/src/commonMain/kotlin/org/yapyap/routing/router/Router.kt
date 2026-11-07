package org.yapyap.routing.router

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.transport.tor.backend.TorHealth
import org.yapyap.transport.tor.backend.TorPublishState
import org.yapyap.transport.tor.backend.TorRecoveryStep
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal interface Router {
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

    /**
     * Our own onion publish readiness, straight from the Tor transport.
     * Device-local (no peer involved), so the orchestrator can safely lift it
     * to the public transport-state flow.
     */
    val localPublishState: StateFlow<TorPublishState>

    /**
     * Our own Tor daemon health (bootstrap progress and stall reason, device
     * offline, network liveness), straight from the Tor transport. Device-local
     * like [localPublishState]; the orchestrator lifts it to the public status.
     */
    val localTransportHealth: StateFlow<TorHealth>

    /** The Tor backend's current recovery step (null when healthy), straight from the transport. */
    val localTransportRecovery: StateFlow<TorRecoveryStep?>

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
     * Our own onion endpoint, as Tor published it on [start]. The orchestrator puts it in invites,
     * intros and its own AddDevice — the one place it handles an address (bootstrap). Throws before [start].
     */
    fun localTorEndpoint(): TorEndpoint

    /**
     * When each device was last heard from, for display only (the roster's "last seen").
     * Devices never heard from are absent. Routing decisions stay inside the router.
     */
    suspend fun deviceLastSeen(): Map<PeerId, Instant>

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
     * @return the queued packet's id, as later reported in [deliveries] ([DeliverySubject.Bootstrap]).
     */
    suspend fun sendBootstrap(
        payload: BootstrapPayload,
        target: PeerId,
        targetEndpoint: TorEndpoint? = null,
        sharedSecret: ByteArray? = null,
    ): Uuid

    /**
     * Starts asking candidate devices for pending sync [syncId] (idempotent), first at
     * [firstAttemptAt]. What to ask for and which accounts may answer come from the
     * [org.yapyap.routing.sync.SyncPayloadProvider]; the router asks until that reports the
     * sync satisfied or gone.
     */
    suspend fun startSync(syncId: Uuid, firstAttemptAt: Instant)

    /**
     * [account] asserts it can serve [syncId] after all (e.g. its ping advertises the sync's
     * room): if every candidate device has declined, that account's devices are asked again.
     */
    suspend fun reopenSync(syncId: Uuid, account: AccountId)

    /**
     * Push-path ACKs, resolved to the acknowledging account: a message pushed by [sendMessage]
     * (or served in a sync reply) was processed by one of that account's devices, or a
     * bootstrap packet from [sendBootstrap] reached its target (account null: the target may
     * have no account here yet, as with a recovery request's target before its intro).
     *
     * Hot and best-effort: only an ACK is reported, and the absence of one proves nothing —
     * messages also arrive through relays and the pull path, which never ACK to the sender.
     * One event per acknowledging device, so an account may be reported more than once.
     */
    val deliveries: Flow<Delivery>

}
