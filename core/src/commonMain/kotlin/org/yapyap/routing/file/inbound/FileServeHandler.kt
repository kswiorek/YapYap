package org.yapyap.routing.file.inbound

import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.file.seal.SealRegistry
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * REQUEST handler (design §9): the serve gate decides, then the seal registry serves with a fresh
 * seal from the requester's held coverage. Refusals are DECLINED so the requester moves to its
 * next candidate at once; the ACK means "serving", and the serve's OFFER follows.
 *
 * The serve's seal is made inline, on the inbound path: the ACK must mean the seal exists and its
 * transfer is queued (ACK means processed), and a seal that can't be made must answer DECLINED
 * now rather than leave the requester waiting out `fileRequestWaitTimeout`. The work is local
 * crypto and two inserts — the same order as opening any inbound message — never a network wait.
 */
internal class FileServeHandler(
    private val ctx: RoutingContext,
    private val requests: FileRequestSource,
    private val seals: SealRegistry,
) {
    suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        val request = try {
            ctx.envelopeProtections.file.openRequest(env.content, env.source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure("Failed to open file REQUEST", env.packetId, env.source, e)
            return inboundResultForProtectionFailure(e)
        }
        val requester = ctx.devices.getAccountIdForDevice(env.source)
            ?: return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        if (!requests.canServe(request.fileId, requester)) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        // Not held, or large (never served by transfer): the next candidate is asked.
        if (!seals.serve(request.fileId, env.source, request.contiguousBytes)) {
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        return InboundHandleResult.Success()
    }
}
