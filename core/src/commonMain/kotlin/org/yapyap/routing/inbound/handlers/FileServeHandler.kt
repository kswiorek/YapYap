package org.yapyap.routing.inbound.handlers

import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.FilePushOps
import org.yapyap.routing.file.port.FileServeGate
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RoutingContext

/**
 * REQUEST handler (design §9): the serve gate decides, then the push engine serves with
 * a fresh transfer floor-aligned at the requester's held coverage. Refusals are DECLINED
 * so the requester moves to its next candidate at once. Unexpected store, identity or
 * serve failures propagate: the inbound processor clears dedup and the sender retries.
 */
internal class FileServeHandler(
    private val ctx: RoutingContext,
    private val serveGate: FileServeGate,
    private val pusher: FilePushOps,
) {
    suspend fun handleRequest(fileEnvelope: FileEnvelope, payload: FilePayload.Request): InboundHandleResult {
        // Serve-on-REQUEST (design §9): the gate decides, then the pusher serves
        // with a fresh transfer floor-aligned at the requester's held coverage. Refusals
        // are DECLINED so the requester moves to its next candidate at once.
        val requester = ctx.devices.getAccountIdForDevice(fileEnvelope.source)
            ?: return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        if (!serveGate.canServe(payload.fileId, requester)) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        val served = pusher.serveFile(payload.fileId, fileEnvelope.source, payload.contiguousBytes)
        if (!served) {
            // Not held (or large, which is never served by transfer): the next candidate is asked.
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        return InboundHandleResult.Success(sourceAuthenticated = true)
    }
}
