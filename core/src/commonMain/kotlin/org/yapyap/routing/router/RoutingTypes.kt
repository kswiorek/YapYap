package org.yapyap.routing.router

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.LocalIdentity
import org.yapyap.protection.service.EnvelopeProtections
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.UnsignedEnvelope
import org.yapyap.routing.link.PeerLinks
import org.yapyap.routing.store.OutboxTag
import org.yapyap.transport.tor.transport.TorTransport
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal enum class RouterTransport {
    TOR,
    WEBRTC
}

/**
 * Per-account push report: how the fan-out to every device of one target account went.
 *
 * Mechanics only — no GUI vocabulary; display categorization is the caller's job.
 * Push is a latency path: the DAG pull path delivers regardless of these counts,
 * so no count here is a delivery verdict. Relay deposits are a router-internal
 * delivery mechanism, not destinations, and never appear in these counts.
 */
internal data class AccountPushReport(
    /** Peer devices found for the account (excluding the originating device). */
    val devicesTotal: Int,
    /** Envelopes accepted by the outbox (direct or relay-supplemented). */
    val devicesQueued: Int,
    /** Protection deferred (session/identity prerequisites) — payloads leave on
     * their own once the crypto session is ready; render as pending, not failed. */
    val devicesDeferred: Int,
    /** Permanent protection/size failures. */
    val devicesFailed: Int,
)

/** What the router knows about reaching one account, for display only; see [Presence.accountReach]. */
internal data class AccountReach(
    /** When any of the account's devices was last heard from; null when none ever was. */
    val lastSeen: Instant?,
)

/**
 * A received typing indicator, already resolved from a source [PeerId] to the author's
 * [senderAccountId]. Emitted per-account (multiple devices of the same account collapse here);
 * room state management and idle-timeout handling are an orchestrator concern.
 */
internal data class TypingIndicatorEvent(
    val senderAccountId: AccountId,
    val roomId: RoomId,
    val interval: Duration,
    val receivedAt: Instant,
)

/**
 * One inbound ping, resolved to the sender's account.
 *
 * The sender account is the authenticated pinger's account
 * (`getAccountIdForDevice`), or null when the device is unknown. Null senders
 * still drive frontier sync for known rooms (membership candidates suffice);
 * only the unknown-room candidate path needs the sender (docs/room events.md
 * §6), so a null sender there skips row creation and a later ping re-triggers
 * once identity lands.
 *
 * The probing device id itself never leaves the routing layer: the only
 * device-granular pending-sync op (re-opening an attempted device on a ping
 * about the room) runs in `PingProvider` against the repository directly.
 * Everything crossing into the orchestrator is account-level.
 */
internal data class PingFrontiers(
    val senderAccount: AccountId?,
    val roomFrontiers: List<Pair<RoomId, List<Uuid>>>,
)

/**
 * An authenticated bootstrap-family packet received over the wire. The envelope has already passed
 * its kind-specific authentication ([org.yapyap.protection.envelope.BootstrapProtection] —
 * the AEAD intro gate or the account-sig recovery request check); the payload kind selects the
 * handling role: INTRO → the newcomer-side onboarding provider, RECOVERY_REQUEST → the recovery
 * responder (INVITE never travels on the wire — it is an out-of-band QR/CLI artifact).
 */
internal data class BootstrapPacketEvent(
    val payload: BootstrapPayload,
    val receivedAt: Instant,
)

internal sealed interface InboundSideEffect {
    /** A relayed message's forward to its final target, signed by us (an `is_relay` outbox row). */
    data class EnqueueForRelay(val envelope: BinaryEnvelope) : InboundSideEffect

    /**
     * Direct reply to the source (FILE ACK/CANCEL/COMPLETE): ordinary routable packet, enqueued
     * untagged with no relay backup — control packets are politeness, bounded by the TTL.
     */
    data class EnqueueDirect(val envelope: BinaryEnvelope) : InboundSideEffect

    /** The target answered our outbox packet: ACK when [nackReason] is null, else a terminal NACK. */
    data class OutboxDisposition(val packetId: Uuid, val nackReason: PacketNackReason? = null) : InboundSideEffect

    data class PeerHeartbeat(val peerId: PeerId, val ping: SystemPayload.Ping) : InboundSideEffect
    data class PeerOffline(val peerId: PeerId) : InboundSideEffect
}

internal sealed interface InboundHandleResult {
    val sideEffects: List<InboundSideEffect>

    /** Handled: ACKed when the sender asked for a disposition. */
    data class Success(
        override val sideEffects: List<InboundSideEffect> = emptyList(),
    ) : InboundHandleResult

    data class Deferred(override val sideEffects: List<InboundSideEffect> = emptyList()) : InboundHandleResult
    data class Rejected(
        val reason: PacketNackReason,
        override val sideEffects: List<InboundSideEffect> = emptyList(),
    ) : InboundHandleResult
}

/** How a tagged outbox entry left the outbox ([org.yapyap.routing.outbound.OutboxProcessor.outcomes]). */
internal sealed interface OutboxOutcome {
    val tag: OutboxTag
    val target: PeerId

    /** The outbox row that left (chunk rows share their transfer tag; this tells them apart). */
    val packetId: Uuid

    data class Acked(
        override val tag: OutboxTag,
        override val target: PeerId,
        override val packetId: Uuid,
    ) : OutboxOutcome

    data class Nacked(
        override val tag: OutboxTag,
        override val target: PeerId,
        override val packetId: Uuid,
        val reason: PacketNackReason,
    ) : OutboxOutcome

    data class Expired(
        override val tag: OutboxTag,
        override val target: PeerId,
        override val packetId: Uuid,
    ) : OutboxOutcome
}

internal sealed interface PeerSendOutcome {
    /** Direct delivery queued; [relaysDeposited] is how many extra relay copies were enqueued. */
    data class Queued(val relaysDeposited: Int = 0) : PeerSendOutcome

    /**
     * Protection deferred (session/identity prerequisites) — the payload is staged in
     * pending_sends and leaves automatically once the crypto session is ready.
     * Surfaces as [AccountPushReport.devicesDeferred]; the caller maps it to a
     * pending display state, never a failure.
     */
    data object Deferred : PeerSendOutcome

    data object PermanentFailure : PeerSendOutcome
}

/**
 * What the router may know about accounts and devices: which devices an account has and whether
 * they are active. Read-only; the identity tables are written by the orchestrator's folds.
 */
internal interface DeviceDirectory {
    /** Owning account of a device, or null when the device is unknown ("unknown device", not an error). */
    suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId?

    /** The account's non-banned devices. */
    suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId>

    suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId>

    /** Every non-banned device, local included — the candidate list for ping/relay/link fan-out. */
    suspend fun getAllActiveDeviceIds(): List<PeerId>

    /** Device ban state, or null when the device has no row (absence asserts nothing). */
    suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus?

    /** Account status, or null when absent (absence asserts nothing — never read it as "removed"). */
    suspend fun getAccountStatus(accountId: AccountId): IdentityStatus?

    /** The endpoint the device declared (chain AddDevice or intro), or null when the device is unknown. */
    suspend fun getDeclaredTorEndpoint(deviceId: PeerId): TorEndpoint?
}

/**
 * Relay admission (docs/envelope-redesign.md §3.2): a device we know, whose own status and whose
 * account's status are both not BANNED. A relay stores only what such an origin sent it, for such
 * a final target.
 */
internal suspend fun DeviceDirectory.isKnownNonBanned(device: PeerId): Boolean {
    val deviceStatus = getDeviceStatus(device) ?: return false
    if (deviceStatus == IdentityStatus.BANNED) return false
    val account = getAccountIdForDevice(device) ?: return true
    return getAccountStatus(account) != IdentityStatus.BANNED
}

/** Where devices can be reached: the router's own state about peers (it is the only writer). */
internal interface DeviceRouteStore {
    /** The endpoint the router last proved for the device, else the one it declared; null when unknown. */
    suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint?

    /** Records an endpoint proven by an authenticated inbound stream, or our own after Tor starts. */
    suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint)
}

/**
 * The ambient world of a routing component: who we are ([localIdentity], [localDeviceId]), the
 * device world as the orchestrator reports it ([devices]), how we reach peers ([torTransport],
 * [webRtcTransport] and the open [peerLinks]), how we sign envelopes and seal content
 * ([envelopeProtections]), the sizes the transports leave us ([transportLimits], [fileLimits]),
 * time and rules ([clock], [routerConfig]).
 *
 * No stores, engines, or policies: routing-owned state (`routes`, the packet deduplicator, the
 * outbox) and everything routing builds are explicit constructor dependencies of their owners.
 */
internal class RoutingContext(
    val localIdentity: LocalIdentity,
    val localDeviceId: PeerId,
    val devices: DeviceDirectory,
    val torTransport: TorTransport,
    val webRtcTransport: WebRtcTransport,
    val peerLinks: PeerLinks,
    val transportLimits: StateFlow<TransportLimits>,
    val fileLimits: StateFlow<FileLimits>,
    val envelopeProtections: EnvelopeProtections,
    val clock: Clock,
    val routerConfig: StateFlow<RouterConfig>,
) {
    /**
     * A new envelope from this device to [target], signed as its hop (every envelope we send,
     * relay forwards included, is signed by us). [expiresAt] is the content's: a forward copies
     * the one it received and never extends it.
     */
    suspend fun envelope(
        packetType: PacketType,
        target: PeerId,
        routingHeader: ByteArray,
        content: ByteArray = NO_CONTENT,
        expiresAt: Instant,
        dispositionRequested: Boolean,
    ): BinaryEnvelope = envelopeProtections.hop.sign(
        UnsignedEnvelope(
            packetId = Uuid.random(),
            packetType = packetType,
            dispositionRequested = dispositionRequested,
            createdAt = minOf(clock.now(), expiresAt),
            expiresAt = expiresAt,
            source = localDeviceId,
            target = target,
            routingHeader = routingHeader,
            content = content,
        ),
    )

    private companion object {
        val NO_CONTENT = ByteArray(0)
    }
}