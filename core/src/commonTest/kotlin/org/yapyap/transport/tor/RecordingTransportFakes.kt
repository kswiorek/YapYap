package org.yapyap.transport.tor

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.packet.PacketType
import org.yapyap.transport.tor.backend.TorBackend
import org.yapyap.transport.tor.transport.TorTransport
import kotlin.time.Duration.Companion.milliseconds

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
        if (failNextSend && !envelope.isHeartbeat()) {
            failNextSend = false
            error("simulated Tor send failure")
        }
        sends.add(target to envelope)
    }

    /** [sends] excluding heartbeat envelopes (ping / log-off), for assertions on message traffic. */
    fun sendsExcludingHeartbeat(): List<Pair<TorEndpoint, BinaryEnvelope>> =
        sends.filterNot { (_, envelope) -> envelope.isHeartbeat() }

    private fun BinaryEnvelope.isHeartbeat(): Boolean {
        if (packetType != PacketType.SYSTEM) return false
        val payload = runCatching { SystemEnvelope.decode(payload).decodePayload() }.getOrNull()
            ?: return false
        return payload is SystemPayload.Ping || payload is SystemPayload.LogOff
    }

    suspend fun awaitSendCount(count: Int) {
        while (sends.size < count) {
            yield()
        }
    }

    /** Waits until there are at least [count] non-heartbeat sends. */
    suspend fun awaitMessageSendCount(count: Int) {
        while (sendsExcludingHeartbeat().size < count) {
            yield()
        }
    }

    fun tryEmitIncoming(envelope: TorIncomingEnvelope): Boolean = incomingMutable.tryEmit(envelope)
}

/**
 * [TorTransport] fake that delays each [send] and tracks overlapping dispatches.
 * Used to verify outbox retry dispatches run concurrently.
 */
class ConcurrencyTrackingTorTransport(
    val advertisedEndpoint: TorEndpoint = TorEndpoint(onionAddress = "fake.onion", port = 80),
    private val sendDelayMillis: Long = 200,
) : TorTransport {

    private val incomingMutable = MutableSharedFlow<TorIncomingEnvelope>(extraBufferCapacity = 64)
    override val incoming: Flow<TorIncomingEnvelope> = incomingMutable.asSharedFlow()

    private val sendStatsMutex = Mutex()
    private var activeSends = 0
    var maxConcurrentSends = 0
        private set

    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    val sends = mutableListOf<Pair<TorEndpoint, BinaryEnvelope>>()

    override suspend fun start(): TorEndpoint {
        startCalls++
        return advertisedEndpoint
    }

    override suspend fun stop() {
        stopCalls++
    }

    override suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope) {
        sendStatsMutex.withLock {
            activeSends++
            if (activeSends > maxConcurrentSends) {
                maxConcurrentSends = activeSends
            }
        }
        try {
            delay(sendDelayMillis.milliseconds)
            sends.add(target to envelope)
        } finally {
            sendStatsMutex.withLock {
                activeSends--
            }
        }
    }

    suspend fun awaitSendCount(count: Int) {
        while (sends.size < count) {
            yield()
        }
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

    override suspend fun start(): TorEndpoint {
        startCalls.add(80)
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
