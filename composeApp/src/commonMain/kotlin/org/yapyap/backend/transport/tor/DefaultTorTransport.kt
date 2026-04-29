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
import kotlin.time.Clock
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.time.EpochSecondsProvider
import org.yapyap.backend.time.SystemEpochSecondsProvider

class DefaultTorTransport(
    private val backend: TorBackend,
    private val epochSecondsProvider: EpochSecondsProvider = SystemEpochSecondsProvider,
) : TorTransport {

    private val incomingFlow = MutableSharedFlow<TorInboundEnvelope>(replay = 1, extraBufferCapacity = 64)
    private var scope: CoroutineScope? = null
    private var frameCollectorJob: Job? = null
    private var started = false

    override val incoming: Flow<TorInboundEnvelope> = incomingFlow.asSharedFlow()

    override suspend fun start(): TorEndpoint {
        check(!started) { "Tor transport is already started" }

        val localEndpoint = backend.start()

        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = localScope

        frameCollectorJob = localScope.launch {
            backend.incomingFrames.collect { frame ->
                val envelope = runCatching { BinaryEnvelope.decode(frame.payload) }.getOrNull() ?: return@collect
                incomingFlow.emit(
                    TorInboundEnvelope(
                        envelope = envelope,
                        source = frame.source,
                        receivedAtEpochSeconds = epochSecondsProvider.nowEpochSeconds(),
                    )
                )
            }
        }
        require(frameCollectorJob?.isActive == true)
        started = true
        return localEndpoint
    }

    override suspend fun stop() {
        if (!started) return
        frameCollectorJob?.cancel()
        frameCollectorJob = null
        scope?.cancel()
        runCatching { backend.stop() }
        scope = null
        started = false
    }

    override suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope) {
        check(started) { "Tor transport must be started before send" }
        backend.send(target = target, payload = envelope.encode())
    }
}





