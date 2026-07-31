package org.yapyap.routing.sync

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import org.yapyap.routing.outbound.OutboundMessenger


internal class SyncHandler(
    private val outboundMessenger: OutboundMessenger,
    private val syncPayloadProvider: SyncPayloadProvider,
) {
    suspend fun onSyncRequested(payload: SyncRequest, sourceDevice: PeerId) {
        val messages = syncPayloadProvider.getMessages(payload)
        if (messages.isEmpty()) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_NO_MESSAGES_FOUND,
                message = "No messages to sync for peer",
                fields = mapOf("peerId" to sourceDevice),
            )
        }
        for (msg in messages) {
            outboundMessenger.sendMessageToPeer(sourceDevice, msg, forceTransport = null)
        }
    }

    suspend fun sendSyncRequest(target: PeerId, request: SyncRequest) { //Possibly returns outcome
        // mirror AckResponder.sendSystemEnvelope: protectSystem -> BinaryEnvelope(SYSTEM) -> dispatch
        // securityScheme = SIGNED (sync requests are routing-level, like ACKs)
    }
}