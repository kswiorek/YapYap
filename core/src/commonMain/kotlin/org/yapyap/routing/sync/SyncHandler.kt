package org.yapyap.routing.sync

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import org.yapyap.routing.outbound.OutboundMessenger
import org.yapyap.routing.outbound.SystemSender
import org.yapyap.routing.router.RoutingContext

/**
 * Responder side of sync. What may be served to whom is decided above the router
 * ([SyncPayloadProvider.syncMessages], per requesting account); this resolves the requesting
 * device to its account and carries the reply back to that device.
 */
internal class SyncHandler(
    private val ctx: RoutingContext,
    private val outboundMessenger: OutboundMessenger,
    private val syncPayloadProvider: SyncPayloadProvider,
    private val systemSender: SystemSender,
) {
    /** What to send [sourceDevice] for [request]; empty when the request is declined. */
    suspend fun messagesFor(request: SyncRequest, sourceDevice: PeerId): List<MessagePayload> {
        val requester = ctx.devices.getAccountIdForDevice(sourceDevice) ?: return emptyList()
        return syncPayloadProvider.syncMessages(request, requester)
    }

    /**
     * Sends [messages] back to [sourceDevice]. Empty means declined: requests that asked for a
     * disposition were already NACKed; an older build that didn't ask gets its legacy SyncNack.
     */
    suspend fun reply(request: SyncRequest, sourceDevice: PeerId, messages: List<MessagePayload>) {
        if (messages.isEmpty()) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.SYNC_NO_MESSAGES_FOUND,
                message = "No messages to sync for peer",
                fields = mapOf("peerId" to sourceDevice),
            )
            systemSender.sendSyncNack(
                sourceDevice,
                SystemPayload.SyncNack(request.syncId, reason = "No messages to sync")
            )
            return
        }
        for (msg in messages) {
            // Forwarding in response to a sync request: direct only, no relay deposits needed.
            outboundMessenger.sendMessageToPeer(sourceDevice, msg, supplementRelays = false)
        }
    }
}
