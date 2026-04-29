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
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.time.EpochSecondsProvider
import org.yapyap.backend.time.SystemEpochSecondsProvider

class DefaultTorTransport(
    private val backend: TorBackend,
    private val epochSecondsProvider: EpochSecondsProvider = SystemEpochSecondsProvider,
    private val logger: AppLogger = NoopAppLogger,
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
                val envelope = runCatching { BinaryEnvelope.decode(frame.payload) }.getOrElse { error ->
                    logger.warn(
                        component = LogComponent.TOR_TRANSPORT,
                        event = LogEvent.ENVELOPE_DECODE_FAILED,
                        message = "Failed to decode inbound Tor envelope",
                        fields = mapOf("source" to frame.source.onionAddress, "sourcePort" to frame.source.port),
                    )
                    logger.error(
                        component = LogComponent.TOR_TRANSPORT,
                        event = LogEvent.ENVELOPE_DECODE_FAILED,
                        message = "Inbound Tor envelope decode error",
                        throwable = error,
                    )
                    return@collect
                }
                incomingFlow.emit(
                    TorInboundEnvelope(
                        envelope = envelope,
                        source = frame.source,
                        receivedAtEpochSeconds = epochSecondsProvider.nowEpochSeconds(),
                    )
                )
                logger.debug(
                    component = LogComponent.TOR_TRANSPORT,
                    event = LogEvent.INBOUND_ENVELOPE_RECEIVED,
                    message = "Received inbound Tor envelope",
                    fields = mapOf("packetType" to envelope.packetType.name, "source" to frame.source.onionAddress),
                )
            }
        }
        require(frameCollectorJob?.isActive == true)
        started = true
        logger.info(
            component = LogComponent.TOR_TRANSPORT,
            event = LogEvent.STARTED,
            message = "Tor transport started",
            fields = mapOf("localEndpoint" to localEndpoint.onionAddress, "port" to localEndpoint.port),
        )
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
        logger.info(
            component = LogComponent.TOR_TRANSPORT,
            event = LogEvent.STOPPED,
            message = "Tor transport stopped",
        )
    }

    override suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope) {
        check(started) { "Tor transport must be started before send" }
        backend.send(target = target, payload = envelope.encode())
        logger.debug(
            component = LogComponent.TOR_TRANSPORT,
            event = LogEvent.SIGNAL_OUTBOUND_EMITTED,
            message = "Sent Tor envelope",
            fields = mapOf("packetType" to envelope.packetType.name, "target" to target.onionAddress, "targetPort" to target.port),
        )
    }
}





