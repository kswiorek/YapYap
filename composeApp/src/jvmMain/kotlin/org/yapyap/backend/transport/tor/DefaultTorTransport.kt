package org.yapyap.backend.transport.tor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.TorEndpoint

class DefaultTorTransport(
    private val backend: TorBackend,
    private val clockEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : TorTransport {

    private val incomingFlow = MutableSharedFlow<TorInboundEnvelope>(replay = 1, extraBufferCapacity = 64)
    private var scope: CoroutineScope? = null
    private var frameCollectorJob: Job? = null
    private var started = false

    override val incoming: Flow<TorInboundEnvelope> = incomingFlow.asSharedFlow()

    override suspend fun start(localPeer: PeerDescriptor) {
        check(!started) { "Tor transport is already started" }
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = localScope

        frameCollectorJob = localScope.launch {
            backend.incomingFrames.collect { frame ->
                val envelope = runCatching { BinaryEnvelope.decode(frame.payload) }.getOrNull() ?: return@collect
                incomingFlow.emit(
                    TorInboundEnvelope(
                        envelope = envelope,
                        source = frame.source,
                        receivedAtEpochSeconds = clockEpochSeconds(),
                    )
                )
            }
        }

        try {
            backend.start(localPeer.torEndpoint.port)
            started = true
        } catch (error: Throwable) {
            frameCollectorJob?.cancel()
            frameCollectorJob = null
            scope?.cancel()
            scope = null
            throw error
        }
    }

    override suspend fun stop() {
        if (!started) return

        frameCollectorJob?.cancel()
        frameCollectorJob = null
        scope?.cancel()
        scope = null
        backend.stop()
        started = false
    }

    override suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope) {
        check(started) { "Tor transport must be started before send" }
        backend.send(target = target, payload = envelope.encode())
    }
}





