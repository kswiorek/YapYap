package org.yapyap.transport.tor

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.transport.TransportException
import org.yapyap.transport.session.SessionPhase
import org.yapyap.transport.tor.backend.*
import org.yapyap.transport.tor.transport.TorTransport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Recording fake — no real Tor mesh. */
internal class RecordingTorTransport(
    val advertisedEndpoint: TorEndpoint = TorEndpoint(onionAddress = "fake.onion", port = 80),
) : TorTransport {

    private val incomingMutable = MutableSharedFlow<TorIncomingEnvelope>(extraBufferCapacity = 64)
    override val incoming: Flow<TorIncomingEnvelope> = incomingMutable.asSharedFlow()

    override val publishState: StateFlow<TorPublishState> =
        MutableStateFlow(TorPublishState.PUBLISHING).asStateFlow()

    override val health: StateFlow<TorHealth> =
        MutableStateFlow<TorHealth>(TorHealth.Ready).asStateFlow()

    override val recovery: StateFlow<TorRecoveryStep?> =
        MutableStateFlow<TorRecoveryStep?>(null).asStateFlow()

    var refreshOnionLookupsCalls = 0
        private set
    var refreshOnionLookupsResult = true

    override suspend fun refreshOnionLookups(): Boolean {
        refreshOnionLookupsCalls++
        return refreshOnionLookupsResult
    }

    // Sessions: every endpoint counts as linked unless listed in [unlinkedEndpoints], so router
    // tests that predate sessions see the "always reachable" mesh they assume.
    private val sessionStatesMutable = MutableStateFlow<Map<TorEndpoint, TorSessionState>>(emptyMap())
    override val sessionStates: StateFlow<Map<TorEndpoint, TorSessionState>> = sessionStatesMutable.asStateFlow()

    /** Endpoints that report no session (and so make the router park or one-shot instead). */
    val unlinkedEndpoints = mutableSetOf<TorEndpoint>()
    val openSessionCalls = mutableListOf<TorEndpoint>()
    val adoptCalls = mutableListOf<Pair<TorLinkId, TorEndpoint>>()

    /** The `via` link passed with each send, in order (null when none). */
    val sendVias = mutableListOf<TorLinkId?>()

    override fun hasSession(key: TorEndpoint): Boolean = key !in unlinkedEndpoints

    override suspend fun openSession(key: TorEndpoint) {
        openSessionCalls.add(key)
    }

    override suspend fun closeSession(key: TorEndpoint) {
        unlinkedEndpoints.add(key)
    }

    override fun adoptInbound(link: TorLinkId, endpoint: TorEndpoint): Boolean {
        adoptCalls.add(link to endpoint)
        return true
    }

    /** Publishes a session state (e.g. CONNECTED) for [endpoint], updating [hasSession] to match. */
    fun setSessionState(endpoint: TorEndpoint, phase: SessionPhase, reason: String? = null) {
        if (phase == SessionPhase.CONNECTED) unlinkedEndpoints.remove(endpoint) else unlinkedEndpoints.add(endpoint)
        sessionStatesMutable.value += endpoint to TorSessionState(endpoint, phase, reason)
    }

    var startCalls = 0
        private set
    var stopCalls = 0
        private set

    private val sendsMutex = Mutex()
    val sends = mutableListOf<Pair<TorEndpoint, BinaryEnvelope>>()
    var failNextSend: Boolean = false

    /** One-shot: the next non-heartbeat send throws this. */
    var nextSendError: Throwable? = null

    /** Runs at the start of every send (e.g. to hold one target's send open). */
    var beforeSend: (suspend (TorEndpoint, BinaryEnvelope) -> Unit)? = null

    /** The budget passed with each non-heartbeat send, in order. */
    val sendBudgets = mutableListOf<Duration?>()

    override suspend fun start(): TorEndpoint {
        startCalls++
        return advertisedEndpoint
    }

    override suspend fun stop() {
        stopCalls++
    }

    override suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope, budget: Duration?, via: TorLinkId?) {
        sendsMutex.withLock { sendVias += via }
        if (failNextSend && !envelope.isHeartbeat()) {
            failNextSend = false
            error("simulated Tor send failure")
        }
        beforeSend?.invoke(target, envelope)
        if (!envelope.isHeartbeat()) sendsMutex.withLock { sendBudgets += budget }
        nextSendError?.takeIf { !envelope.isHeartbeat() }?.let {
            nextSendError = null
            throw it
        }
        sendsMutex.withLock { sends.add(target to envelope) }
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
        while (true) {
            if (sendsMutex.withLock { sendsExcludingHeartbeat().size } >= count) return
            yield()
        }
    }

    fun tryEmitIncoming(envelope: TorIncomingEnvelope): Boolean = incomingMutable.tryEmit(envelope)
}

/**
 * [TorTransport] fake that delays each [send] and tracks overlapping dispatches.
 * Used to verify outbox retry dispatches run concurrently.
 */
internal class ConcurrencyTrackingTorTransport(
    val advertisedEndpoint: TorEndpoint = TorEndpoint(onionAddress = "fake.onion", port = 80),
    private val sendDelayMillis: Long = 200,
) : TorTransport {

    private val incomingMutable = MutableSharedFlow<TorIncomingEnvelope>(extraBufferCapacity = 64)
    override val incoming: Flow<TorIncomingEnvelope> = incomingMutable.asSharedFlow()

    override val publishState: StateFlow<TorPublishState> =
        MutableStateFlow(TorPublishState.PUBLISHING).asStateFlow()

    override val health: StateFlow<TorHealth> =
        MutableStateFlow<TorHealth>(TorHealth.Ready).asStateFlow()

    override val recovery: StateFlow<TorRecoveryStep?> =
        MutableStateFlow<TorRecoveryStep?>(null).asStateFlow()

    var refreshOnionLookupsCalls = 0
        private set
    var refreshOnionLookupsResult = true

    override suspend fun refreshOnionLookups(): Boolean {
        refreshOnionLookupsCalls++
        return refreshOnionLookupsResult
    }

    override val sessionStates: StateFlow<Map<TorEndpoint, TorSessionState>> =
        MutableStateFlow<Map<TorEndpoint, TorSessionState>>(emptyMap()).asStateFlow()

    override fun hasSession(key: TorEndpoint): Boolean = true
    override suspend fun openSession(key: TorEndpoint) = Unit
    override suspend fun closeSession(key: TorEndpoint) = Unit
    override fun adoptInbound(link: TorLinkId, endpoint: TorEndpoint): Boolean = true

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

    override suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope, budget: Duration?, via: TorLinkId?) {
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
internal class RecordingTorBackend : TorBackend {

    private val framesMutable = MutableSharedFlow<TorIncomingFrame>(extraBufferCapacity = 64)
    override val incomingFrames = framesMutable.asSharedFlow()

    override val incomingStreams: Flow<TorInboundStream> = emptyFlow()

    val openStreamCalls = mutableListOf<TorEndpoint>()

    override suspend fun openStream(target: TorEndpoint, budget: Duration?): TorDuplex {
        openStreamCalls.add(target)
        throw TransportException.TorException.SocksError("recording backend opens no streams")
    }

    override val publishState: StateFlow<TorPublishState> =
        MutableStateFlow(TorPublishState.PUBLISHING).asStateFlow()

    override val health: StateFlow<TorHealth> =
        MutableStateFlow<TorHealth>(TorHealth.Ready).asStateFlow()

    override val recovery: StateFlow<TorRecoveryStep?> =
        MutableStateFlow<TorRecoveryStep?>(null).asStateFlow()

    var refreshOnionLookupsCalls = 0
        private set
    var refreshOnionLookupsResult = true

    override suspend fun refreshOnionLookups(): Boolean {
        refreshOnionLookupsCalls++
        return refreshOnionLookupsResult
    }

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

    override suspend fun send(target: TorEndpoint, payload: ByteArray, budget: Duration?) {
        sends.add(target to payload.copyOf())
    }

    fun tryEmitFrame(frame: TorIncomingFrame): Boolean = framesMutable.tryEmit(frame)
}
