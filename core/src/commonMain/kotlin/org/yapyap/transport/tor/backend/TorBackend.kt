package org.yapyap.transport.tor.backend

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.TorEndpoint
import org.yapyap.transport.tor.TorIncomingFrame

interface TorBackend {
    val incomingFrames: Flow<TorIncomingFrame>

    suspend fun start(localPort: Int? = null): TorEndpoint

    suspend fun stop()

    suspend fun send(target: TorEndpoint, payload: ByteArray)
}

