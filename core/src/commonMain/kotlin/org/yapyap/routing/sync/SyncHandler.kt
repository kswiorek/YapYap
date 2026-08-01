package org.yapyap.routing.sync

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.sync.PendingSyncRepository
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.SystemPayload.SyncRequest
import org.yapyap.routing.outbound.OutboundMessenger
import org.yapyap.routing.outbound.SystemSender
import kotlin.uuid.Uuid


internal class SyncHandler(
    private val outboundMessenger: OutboundMessenger,
    private val syncPayloadProvider: SyncPayloadProvider,
    private val pendingSyncRepository: PendingSyncRepository,
    private val systemSender: SystemSender
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
            val syncNack = SystemPayload.SyncNack(
                payload.syncId,
                reason = "No messages to sync"
            )
            systemSender.sendSyncNack(sourceDevice, syncNack)
        }
        for (msg in messages) {
            outboundMessenger.sendMessageToPeer(sourceDevice, msg, forceTransport = null)
        }
    }

    suspend fun onMarkPeerAttempted(syncId: Uuid, peerId: PeerId) {
        pendingSyncRepository.addAttemptedPeer(syncId, peerId)
    }
}