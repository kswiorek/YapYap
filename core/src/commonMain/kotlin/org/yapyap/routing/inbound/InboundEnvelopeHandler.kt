package org.yapyap.routing.inbound

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionDisposition
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.router.InboundHandleResult
import kotlin.uuid.Uuid

internal fun interface InboundEnvelopeHandler {
    suspend fun handle(env: BinaryEnvelope): InboundHandleResult
}

internal fun logInboundProtectionFailure(
    message: String,
    packetId: Uuid,
    source: PeerId,
    exception: ProtectionException,
) {
    AppLog.warn(
        component = LogComponent.ROUTER,
        event = LogEvent.ENVELOPE_PROTECTION_FAILED,
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
        ProtectionDisposition.PERMANENT -> InboundHandleResult.Rejected(
            if (ex is ProtectionException.InvalidEnvelope) {
                PacketNackReason.DECODE_FAILED
            } else {
                PacketNackReason.PERMANENT_PROTECTION_FAILED
            },
        )

        // Silent by design: no NACK is sent and dedup is cleared (InboundEnvelopeProcessor)
        // so the sender's resend is reprocessed fresh once the prerequisite lands. A NACK here
        // would be replayed verbatim for later duplicates (sendDispositionForDuplicate) and
        // strand the packet behind a stale verdict.
        ProtectionDisposition.DEFER -> InboundHandleResult.Deferred()
    }
