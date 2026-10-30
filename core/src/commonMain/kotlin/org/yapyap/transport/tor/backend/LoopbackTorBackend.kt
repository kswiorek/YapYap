package org.yapyap.transport.tor.backend

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.TorEndpoint
import org.yapyap.transport.TransportException
import org.yapyap.transport.tor.TorIncomingFrame
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Loopback [TorBackend] for local development and GUI testing without a Tor daemon.
 *
 * Each instance binds `127.0.0.1` on an OS-assigned free port and advertises a
 * self-describing endpoint of the form `loopback-<port>.onion`. `send` opens a plain
 * TCP connection straight to the target's port using the same transport frame codec
 * as the real backend, so the full inbound path (including peer endpoint
 * reconciliation) is exercised. No files are written, no bootstrap wait: `start()`
 * returns in milliseconds.
 *
 * Loopback is bound to the loopback interface only — traffic never leaves the host —
 * but it provides no transport-layer authenticity or anonymity. Everything above the
 * transport (signatures, E2EE) still verifies. Test/dev only: never selected by default.
 */
internal class LoopbackTorBackend(
    private val config: StateFlow<TorBackendConfig>,
) : TorBackend {

    private val incomingFlow = MutableSharedFlow<TorIncomingFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val incomingFrames: Flow<TorIncomingFrame> = incomingFlow.asSharedFlow()

    // Loopback is dialable the moment the socket binds — no descriptor dance.
    private val _publishState = MutableStateFlow(TorPublishState.PUBLISHING)
    override val publishState: StateFlow<TorPublishState> = _publishState.asStateFlow()

    private val _health = MutableStateFlow<TorHealth>(TorHealth.Stopped)
    override val health: StateFlow<TorHealth> = _health.asStateFlow()

    var publishedLocalEndpoint: TorEndpoint? = null
        private set

    private var acceptSocket: ServerSocket? = null
    private var selectorManager: SelectorManager? = null
    private var scope: CoroutineScope? = null

    override suspend fun start(): TorEndpoint {
        check(acceptSocket == null) { "Loopback Tor backend already started" }

        val selector = SelectorManager(Dispatchers.Default)
        selectorManager = selector

        val listener = aSocket(selector).tcp().bind(LOOPBACK_HOST, 0)
        acceptSocket = listener
        val port = (listener.localAddress as InetSocketAddress).port
        val endpoint = TorEndpoint("loopback-$port.onion", port)
        publishedLocalEndpoint = endpoint
        _publishState.value = TorPublishState.PUBLISHED
        _health.value = TorHealth.Ready

        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = localScope
        localScope.launch {
            acceptInboundConnections(listener)
        }
        AppLog.info(
            component = LogComponent.TOR_BACKEND,
            event = LogEvent.STARTED,
            message = "Loopback Tor backend started",
            fields = mapOf("localEndpoint" to endpoint.onionAddress, "port" to endpoint.port),
        )
        return endpoint
    }

    override suspend fun stop() {
        acceptSocket?.runCatching { close() }
        acceptSocket = null

        selectorManager?.runCatching { close() }
        selectorManager = null

        scope?.cancel()
        scope = null

        publishedLocalEndpoint = null
        _publishState.value = TorPublishState.PUBLISHING
        _health.value = TorHealth.Stopped
        AppLog.info(
            component = LogComponent.TOR_BACKEND,
            event = LogEvent.STOPPED,
            message = "Loopback Tor backend stopped",
        )
    }

    override suspend fun send(target: TorEndpoint, payload: ByteArray) {
        val localSource = publishedLocalEndpoint ?: error("Loopback Tor backend must be started before send")
        val targetPort = loopbackPortFor(target)
        val selector = requireNotNull(selectorManager) { "Loopback selector manager is not initialized" }
        if (payload.size > config.value.maxPayloadBytes) {
            throw TransportException.PayloadTooLarge(
                sizeBytes = payload.size,
                limitBytes = config.value.maxPayloadBytes,
            )
        }

        try {
            // Fail fast by design: a dead peer in dev should surface immediately,
            // not stall like the real backend's multi-minute SOCKS retry loop.
            withTimeout(SEND_TIMEOUT) {
                val socket = aSocket(selector).tcp().connect(LOOPBACK_HOST, targetPort) {
                    socketTimeout = SOCKET_TIMEOUT_MS
                }
                try {
                    val output = socket.openWriteChannel(autoFlush = false)
                    writeTorTransportFrame(output, localSource, payload)
                    output.flush()
                } finally {
                    socket.close()
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw TransportException.LoopbackError("timed out sending to ${target.onionAddress}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TransportException.LoopbackError(
                "failed to send to ${target.onionAddress}: ${e.message}",
            )
        }
    }

    private suspend fun acceptInboundConnections(listener: ServerSocket) {
        while (scope?.isActive == true) {
            val client = runCatching { listener.accept() }.getOrElse { break }
            scope?.launch {
                handleInboundConnection(client)
            }
        }
    }

    /**
     * Same cancellation discipline as the real backend: genuine parse failures surface
     * as [TransportException.TorException.TransportFrameError], while cancellation from
     * backend shutdown is rethrown untouched so an orderly stop never becomes a child failure.
     */
    private suspend fun handleInboundConnection(client: Socket) {
        try {
            val input = client.openReadChannel()
            val frame = try {
                readTorTransportFrame(input, config.value.maxPayloadBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warn(
                    component = LogComponent.TOR_BACKEND,
                    event = LogEvent.ENVELOPE_DECODE_FAILED,
                    message = "Failed to read inbound loopback transport frame",
                )
                throw TransportException.TorException.TransportFrameError(e.message ?: "Unknown error")
            }
            incomingFlow.emit(frame)
        } finally {
            client.close()
        }
    }

    companion object {
        const val LOOPBACK_HOST: String = "127.0.0.1"
        private val SEND_TIMEOUT = 10.seconds
        private const val SOCKET_TIMEOUT_MS: Long = 30_000L

        private val LOOPBACK_ADDRESS = Regex("^loopback-(\\d{1,5})\\.onion$")

        /**
         * The port embedded in the address is authoritative (it is what survives DB
         * round-trips and logs verbatim), not the [TorEndpoint.port] field.
         */
        fun loopbackPortFor(target: TorEndpoint): Int {
            val port = LOOPBACK_ADDRESS.matchEntire(target.onionAddress)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw TransportException.LoopbackError("Not a loopback endpoint: ${target.onionAddress}")
            if (port !in 1..65535) {
                throw TransportException.LoopbackError("Invalid loopback port in ${target.onionAddress}")
            }
            return port
        }
    }
}
