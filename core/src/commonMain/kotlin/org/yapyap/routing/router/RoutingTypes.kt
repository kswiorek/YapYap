package org.yapyap.routing.router

import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.logging.AppLogger
import org.yapyap.persistence.packet.PacketDeduplicator
import org.yapyap.persistence.packet.PacketIdAllocator
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.packet.PacketId
import org.yapyap.time.EpochSecondsProvider
import org.yapyap.transport.tor.transport.TorTransport
import org.yapyap.transport.webrtc.transport.WebRtcTransport

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
    MIXED,
}

data class SendMessageResult(
    val status: SendMessageStatus,
    val peersTotal: Int,
    val peersQueued: Int,
    val failureKind: SendFailureKind?,
)

internal sealed interface InboundHandleResult {
    data object Success : InboundHandleResult
    data object Deferred : InboundHandleResult
    data class Rejected(val reason: PacketNackReason) : InboundHandleResult
}

internal sealed interface SystemInboundResult {
    data object Ignored : SystemInboundResult
    data class RemoveFromOutbox(val packetId: PacketId) : SystemInboundResult
}

internal sealed interface PeerSendOutcome {
    data object Queued : PeerSendOutcome
    data object NotReady : PeerSendOutcome
    data object PermanentFailure : PeerSendOutcome
}

internal class RoutingContext(
    val identityResolver: IdentityResolver,
    val packetIdAllocator: PacketIdAllocator,
    val packetDeduplicator: PacketDeduplicator,
    val envelopeProtectionService: EnvelopeProtectionService,
    val torTransport: TorTransport,
    val webRtcTransport: WebRtcTransport,
    val timeProvider: EpochSecondsProvider,
    val logger: AppLogger,
    val routerConfig: RouterConfig,
) {
    lateinit var localDeviceIdentity: DeviceIdentityRecord

    val localDeviceId: PeerId
        get() = localDeviceIdentity.deviceId
}
