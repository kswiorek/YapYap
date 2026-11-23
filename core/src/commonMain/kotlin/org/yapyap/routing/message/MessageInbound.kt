package org.yapyap.routing.message

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.envelopes.SyncPayload
import org.yapyap.routing.router.InboundHandleResult

/**
 * The message service's inbound face: what the kernel's inbound pipeline delegates to it. The
 * pipeline keeps the wire obligations (dedup, protection verdicts, ACK/NACK); the service owns
 * what the packets mean.
 */
internal interface MessageInbound {
    /** A typing indicator from [source]. Account resolution and idle-timeout are upstream. */
    suspend fun onTyping(source: PeerId, roomId: RoomId, intervalMillis: Int)

    /**
     * A pull-path request from [sourceDevice]: the orchestrator is consulted synchronously and
     * its answer is the disposition — served → ACK plus the reply, nothing → DECLINED (design §8:
     * a sync request is processed when its reply is served).
     */
    suspend fun onSyncRequested(
        request: SyncPayload.Request,
        sourceDevice: PeerId,
    ): InboundHandleResult
}
