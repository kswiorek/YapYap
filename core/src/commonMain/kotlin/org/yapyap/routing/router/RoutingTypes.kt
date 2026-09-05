package org.yapyap.routing.router

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.config.TransportLimits
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.packet.PacketDeduplicator
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapIntroPayload
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
    NOT_READY,
    PERMANENT,
    TOO_LARGE,
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
 * An authenticated bootstrap intro received from a sponsor. The packet has already passed the
 * preshared-key AEAD gate ([org.yapyap.protection.service.EnvelopeProtectionService.openBootstrap]);
 * persisting the sponsor's provisional identity rows and triggering the global-room range sync are
 * orchestrator concerns (see [org.yapyap.orchestrator.runtime.onboarding.OnboardingService]).
 */
data class BootstrapIntroEvent(
    val payload: BootstrapIntroPayload,
    val receivedAt: Instant,
)

internal sealed interface InboundSideEffect {
    data class EnqueueForRelay(val envelope: BinaryEnvelope) : InboundSideEffect
    data class RemoveFromOutbox(val packetId: Uuid) : InboundSideEffect
    data class SyncRequested(val peerId: PeerId, val sync: SyncRequest) : InboundSideEffect
    data class MarkPeerAttempted(val peerId: PeerId, val syncId: Uuid) : InboundSideEffect
    data class PeerHeartbeat(val peerId: PeerId, val ping: SystemPayload.Ping) : InboundSideEffect
    data class PeerOffline(val peerId: PeerId) : InboundSideEffect
    // TODO Sprint 4: data class PeerHeartbeat(...) : InboundSideEffect
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
    data object NotReady : PeerSendOutcome
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