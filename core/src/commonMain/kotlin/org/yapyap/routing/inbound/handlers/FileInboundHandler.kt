package org.yapyap.routing.inbound.handlers

import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.router.InboundHandleResult

internal class FileInboundHandler : InboundEnvelopeHandler {
    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        // TODO [Sprint 5]: when real authentication lands here, set
        // sourceAuthenticated = true on Success — until then the processor must
        // not heal endpoint mappings from file traffic (fail-closed default).
        return InboundHandleResult.Success()
    }
}
