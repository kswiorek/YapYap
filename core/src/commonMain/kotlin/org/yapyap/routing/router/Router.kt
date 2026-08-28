package org.yapyap.routing.router

import kotlinx.coroutines.flow.Flow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.protocol.envelopes.MessagePayload

interface Router {
    val incomingMessages: Flow<MessagePayload>

    /**
     * Hot stream of typing indicators received from peers, resolved to the author's account.
     * Room state aggregation and idle-timeout are handled upstream (orchestrator).
     */
    val typingIndicators: Flow<TypingIndicatorEvent>

    suspend fun start()
    suspend fun stop()
    fun isRunning(): Boolean

    suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
        forceTransport: RouterTransport? = null,
    ): SendMessageResult

    /**
     * Signal that the local user is typing in [roomId] to [targets] (room members).
     * Fire-and-forget: indicators are only delivered to peers with an open WebRTC session and
     * are never queued or persisted. Session opening for recently-reachable peers is a side
     * effect of this call.
     */
    suspend fun sendTypingIndicator(
        targets: Collection<AccountId>,
        roomId: String,
    )

}