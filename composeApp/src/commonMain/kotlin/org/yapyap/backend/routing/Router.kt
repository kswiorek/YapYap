package org.yapyap.backend.routing

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.crypto.AccountId
import org.yapyap.backend.protocol.MessagePayload

interface Router {
    val incomingMessages: Flow<MessagePayload>

    suspend fun start()
    suspend fun stop()
    fun isRunning(): Boolean

    suspend fun sendMessage(target: AccountId, payload: MessagePayload, forceTransport: RouterTransport = RouterTransport.AUTO)


}

enum class RouterTransport {
    TOR,
    WEBRTC,
    AUTO,
}