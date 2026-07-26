package org.yapyap.orchestrator.message

import kotlinx.coroutines.flow.Flow
import org.yapyap.routing.router.SendMessageResult

interface MessagingService {

    val incomingMessageEvents: Flow<IncomingMessageEvent>

    /** Outbound: append to local DAG, fan out to room members via router. */
    suspend fun sendTextMessage(roomId: String, text: String): SendMessageResult

    /**
     * Open a room for viewing. Returns a paginated window.
     * Caller must call [RoomMessageWindow.close] when navigating away.
     */
    suspend fun openRoom(roomId: String, initialPageSize: Int = 100): RoomMessageWindow
}
