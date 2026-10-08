package org.yapyap.routing.router

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.config.TransportLimits
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.persistence.packet.PacketDeduplicator
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import org.yapyap.transport.tor.transport.TorTransport
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

enum class RouterTransport {
    TOR,
    WEBRTC
}

enum class SendMessageStatus {
    SUCCESS,
    PARTIAL,
    FAILURE,
}

enum class SendFailureKind {
    NO_PEERS,
    DEFERRED,
    PERMANENT,
    TOO_LARGE,
    HISTORY_INCOMPLETE,
    /** Local account holds a committed REMOVED row for the room — refused before any write. */
    NOT_A_MEMBER,
    MIXED,
}

data class SendMessageResult(
    val status: SendMessageStatus,
    val peersTotal: Int,
    val peersQueued: Int,
    val failureKind: SendFailureKind?,
)

/**
 * A received typing indicator, already resolved from a source [PeerId] to the author's
 * [senderAccountId]. Emitted per-account (multiple devices of the same account collapse here);
 * room state management and idle-timeout handling are an orchestrator concern.
 */
data class TypingIndicatorEvent(
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
data class PingFrontiers(
    val senderAccount: AccountId?,
    val roomFrontiers: List<Pair<RoomId, List<Uuid>>>,
)

/**
 * An authenticated bootstrap-family packet received over the wire. The envelope has already passed
 * its kind-specific authentication ([org.yapyap.protection.service.EnvelopeProtectionService.openBootstrap] —
 * the AEAD intro gate or the account-sig recovery request check); the payload kind selects the
 * handling role: INTRO → the newcomer-side onboarding provider, RECOVERY_REQUEST → the recovery
 * responder (INVITE never travels on the wire — it is an out-of-band QR/CLI artifact).
 */
data class BootstrapPacketEvent(
    val payload: BootstrapPayload,
    val receivedAt: Instant,
)

internal sealed interface InboundSideEffect {
    data class EnqueueForRelay(val envelope: BinaryEnvelope) : InboundSideEffect
    data class RemoveFromOutbox(val packetId: Uuid) : InboundSideEffect
    data class SyncRequested(val peerId: PeerId, val sync: SyncRequest) : InboundSideEffect
    data class MarkPeerAttempted(val peerId: PeerId, val syncId: Uuid) : InboundSideEffect
    data class PeerHeartbeat(val peerId: PeerId, val ping: SystemPayload.Ping) : InboundSideEffect
    data class PeerOffline(val peerId: PeerId) : InboundSideEffect
}

internal sealed interface InboundHandleResult {
    val sideEffects: List<InboundSideEffect>

    data class Success(override val sideEffects: List<InboundSideEffect> = emptyList()) : InboundHandleResult
    data class Deferred(override val sideEffects: List<InboundSideEffect> = emptyList()) : InboundHandleResult
    data class Rejected(
        val reason: PacketNackReason,
        override val sideEffects: List<InboundSideEffect> = emptyList(),
    ) : InboundHandleResult
}

internal sealed interface PeerSendOutcome {
    /** Direct delivery queued; [relaysDeposited] is how many extra relay copies were enqueued. */
    data class Queued(val relaysDeposited: Int = 0) : PeerSendOutcome

    /**
     * Protection deferred (session/identity prerequisites) — the payload is staged in
     * pending_sends and leaves automatically once the crypto session is ready.
     * GUI-facing: render as pending, not failed.
     */
    data object Deferred : PeerSendOutcome

    data object PermanentFailure : PeerSendOutcome
}

internal class RoutingContext(
    val identityResolver: IdentityResolver,
    val packetDeduplicator: PacketDeduplicator,
    val envelopeProtectionService: EnvelopeProtectionService,
    val torTransport: TorTransport,
    val webRtcTransport: WebRtcTransport,
    val clock: Clock,
    val routerConfig: StateFlow<RouterConfig>,
    val transportLimits: StateFlow<TransportLimits>,
) {
    lateinit var localDeviceIdentity: DeviceIdentityRecord

    val localDeviceId: PeerId
        get() = localDeviceIdentity.deviceId
}