package org.yapyap.orchestrator.message

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.yapyap.orchestrator.dag.Gap
import org.yapyap.routing.router.SendMessageResult

interface MessagingService {
    /** Outbound: append to local DAG, fan out to room members via router. */
    suspend fun sendTextMessage(roomId: String, text: String): SendMessageResult

    /** Inbound collection — start collecting from router, ingesting into DAG. */
    fun startInboundCollection(scope: CoroutineScope)

    /** Observe ordered messages for a room (for GUI binding). */
    fun messagesInRoom(roomId: String): Flow<List<MessageDisplayItem>>

    /** Observe orphan/gap state for a room (for UI warning display). */
    fun gapsInRoom(roomId: String): Flow<List<Gap>>
}