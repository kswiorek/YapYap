package org.yapyap.transport.tor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.transport.tor.backend.TorBackend
import org.yapyap.transport.tor.transport.TorTransport

/** Recording fake — no real Tor mesh. */
class RecordingTorTransport(
    val advertisedEndpoint: TorEndpoint = TorEndpoint(onionAddress = "fake.onion", port = 80),
) : TorTransport {

    private val incomingMutable = MutableSharedFlow<TorIncomingEnvelope>(extraBufferCapacity = 64)
    override val incoming: Flow<TorIncomingEnvelope> = incomingMutable.asSharedFlow()

    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    val sends = mutableListOf<Pair<TorEndpoint, BinaryEnvelope>>()
    var failNextSend: Boolean = false

    override suspend fun start(): TorEndpoint {
        startCalls++
        return advertisedEndpoint
    }

    override suspend fun stop() {
        stopCalls++
    }

    override suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope) {
        if (failNextSend) {
            failNextSend = false
            error("simulated Tor send failure")
        }
        sends.add(target to envelope)
    }

    fun tryEmitIncoming(envelope: TorIncomingEnvelope): Boolean = incomingMutable.tryEmit(envelope)
}

/** Recording lower-level Tor backend (byte payloads). */
class RecordingTorBackend : TorBackend {

    private val framesMutable = MutableSharedFlow<TorIncomingFrame>(extraBufferCapacity = 64)
    override val incomingFrames = framesMutable.asSharedFlow()

    val startCalls = mutableListOf<Int?>()
    val stopCalls = mutableListOf<Unit>()
    val sends = mutableListOf<Pair<TorEndpoint, ByteArray>>()
    var nextEndpoint: TorEndpoint = TorEndpoint("backend.onion", 9050)

    override suspend fun start(localPort: Int?): TorEndpoint {
        startCalls.add(localPort)
        return nextEndpoint
    }

    override suspend fun stop() {
        stopCalls.add(Unit)
    }

    override suspend fun send(target: TorEndpoint, payload: ByteArray) {
        sends.add(target to payload.copyOf())
    }

    fun tryEmitFrame(frame: TorIncomingFrame): Boolean = framesMutable.tryEmit(frame)
}
