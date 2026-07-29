package org.yapyap.routing.inbound

import org.yapyap.logging.LogComponent
import org.yapyap.logging.LoggingTypes
import org.yapyap.protection.ProtectionDisposition
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RoutingContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal fun interface InboundEnvelopeHandler {
    suspend fun handle(env: BinaryEnvelope): InboundHandleResult
}

@OptIn(ExperimentalUuidApi::class)
internal fun RoutingContext.logInboundProtectionFailure(
    message: String,
    packetId: Uuid,
    source: PeerId,
    exception: ProtectionException,
) {
    logger.warn(
        component = LogComponent.ROUTER,
        event = LoggingTypes.ENVELOPE_PROTECTION_FAILED,
        message = message,
        fields = mapOf(
            "packetId" to packetId,
            "sourceDeviceId" to source,
            "disposition" to exception.disposition.name,
            "reason" to exception.reason.name,
            "error" to exception.message,
        ),
    )
}

internal fun inboundResultForProtectionFailure(ex: ProtectionException): InboundHandleResult =
    when (ex.disposition) {
        ProtectionDisposition.DEFER -> InboundHandleResult.Deferred
        ProtectionDisposition.PERMANENT -> InboundHandleResult.Rejected(
            if (ex is ProtectionException.InvalidEnvelope) {
                PacketNackReason.DECODE_FAILED
            } else {
                PacketNackReason.PROTECTION_FAILED
            },
        )
        ProtectionDisposition.RETRYABLE -> InboundHandleResult.Rejected(PacketNackReason.PROTECTION_FAILED)
    }
