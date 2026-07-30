package org.yapyap.routing.router

import kotlinx.coroutines.flow.Flow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.SystemPayload

interface Router {
    val incomingMessages: Flow<MessagePayload>

    suspend fun start()
    suspend fun stop()
    fun isRunning(): Boolean

    suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
        forceTransport: RouterTransport? = null,
    ): SendMessageResult

    suspend fun requestSync(syncRequest: SystemPayload.SyncRequest, candidateAccounts: List<AccountId>)

}