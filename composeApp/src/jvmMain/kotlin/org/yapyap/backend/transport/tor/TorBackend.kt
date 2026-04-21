package org.yapyap.backend.transport.tor

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.protocol.TorEndpoint

data class TorIncomingFrame(
    val source: TorEndpoint,
    val payload: ByteArray,
)

interface TorBackend {
    val incomingFrames: Flow<TorIncomingFrame>

    suspend fun start(localPort: Int = 80): TorEndpoint

    suspend fun stop()

    suspend fun send(target: TorEndpoint, payload: ByteArray)
}

