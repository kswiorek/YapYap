package org.yapyap.backend.transport.tor

import kotlinx.coroutines.flow.Flow
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.DeviceAddress
import org.yapyap.backend.protocol.TorEndpoint

data class TorInboundEnvelope(
    val envelope: BinaryEnvelope,
    val source: TorEndpoint,
    val receivedAtEpochSeconds: Long,
)

interface TorTransport {
    val incoming: Flow<TorInboundEnvelope>

    suspend fun start(localDevice: DeviceAddress, localPort: Int)

    suspend fun stop()

    suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope)
}

