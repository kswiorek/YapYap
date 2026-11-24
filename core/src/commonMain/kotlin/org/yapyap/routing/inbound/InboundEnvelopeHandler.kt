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
    val fields = mapOf(
        "packetId" to packetId,
        "sourceDeviceId" to source,
        "disposition" to exception.disposition.name,
        "reason" to exception.reason.name,
        "error" to exception.message,
    )
    // A duplicate is expected traffic (the same content by two routes), not a failure.
    if (exception is ProtectionException.Duplicate) {
        AppLog.debug(LogComponent.ROUTER, LogEvent.ENVELOPE_PROTECTION_FAILED, message, fields)
    } else {
        AppLog.warn(LogComponent.ROUTER, LogEvent.ENVELOPE_PROTECTION_FAILED, message, fields)
    }
}

internal fun inboundResultForProtectionFailure(ex: ProtectionException): InboundHandleResult =
// Content opened before (a direct copy and a relay copy, docs/envelope-redesign.md §3.3): it
    // was processed then, so it is answered like a processed packet — an ACK.
    if (ex is ProtectionException.Duplicate) InboundHandleResult.Success() else when (ex.disposition) {
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
