package org.yapyap.routing.inbound.handlers

import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.router.InboundHandleResult

internal class FileInboundHandler : InboundEnvelopeHandler {
    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        // TODO Sprint 5
        return InboundHandleResult.Success
    }
}
