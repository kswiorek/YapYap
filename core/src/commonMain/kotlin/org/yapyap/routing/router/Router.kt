package org.yapyap.routing.router

import kotlinx.coroutines.flow.Flow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.protocol.envelopes.MessagePayload

interface Router {
    val incomingMessages: Flow<MessagePayload>

    suspend fun start()
    suspend fun stop()
    fun isRunning(): Boolean

    suspend fun sendMessage(target: AccountId, payload: MessagePayload, forceTransport: RouterTransport? = null)


}

enum class RouterTransport {
    TOR,
    WEBRTC
}