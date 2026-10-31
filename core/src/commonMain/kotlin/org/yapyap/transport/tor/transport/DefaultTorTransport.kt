package org.yapyap.transport.tor.transport

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.transport.TransportException
import org.yapyap.transport.session.SessionPhase
import org.yapyap.transport.tor.TorIncomingEnvelope
import org.yapyap.transport.tor.TorLinkId
import org.yapyap.transport.tor.TorSessionState
import org.yapyap.transport.tor.backend.*
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Tor transport over a [TorBackend]: one-shot frames plus sessions (long-lived streams with
 * the record protocol in [TorStreamRecordType]).
 *
 * Every open stream is a *link*. A link we dialed becomes the session to its target once the
 * far end sends ACCEPT; a link someone dialed us on only carries replies until
 * [adoptInbound] makes it the session to an authenticated endpoint. Each link sends
 * keepalives when idle and closes once nothing has arrived for `sessionIdleTimeout`, which is
 * how a vanished peer is noticed.
 *
 * When both sides dial each other, both links stay usable; the side whose onion sorts higher
 * closes its own dialed link once it has adopted the other one, so the pair converges on the
 * link dialed by the lower onion without either side ever being left without a session.
 */
@OptIn(InternalCoroutinesApi::class)
internal class DefaultTorTransport(
    private val backend: TorBackend,
    private val config: StateFlow<TorBackendConfig> = MutableStateFlow(TorBackendConfig()),
) : TorTransport {

    private val incomingFlow = MutableSharedFlow<TorIncomingEnvelope>(replay = 1, extraBufferCapacity = 64)
    private var scope: CoroutineScope? = null
    private var frameCollectorJob: Job? = null

    @Volatile
    private var started = false
    private var localEndpoint: TorEndpoint? = null

    override val incoming: Flow<TorIncomingEnvelope> = incomingFlow.asSharedFlow()

    override val publishState: StateFlow<TorPublishState>
        get() = backend.publishState

    override val health: StateFlow<TorHealth>
        get() = backend.health

    override val recovery: StateFlow<TorRecoveryStep?>
        get() = backend.recovery

    private val lock = SynchronizedObject()
    private val links = mutableMapOf<TorLinkId, Link>()
    private val dialing = mutableMapOf<TorEndpoint, Job>()
    private var nextLinkId = 1L

    private val _sessionStates = MutableStateFlow<Map<TorEndpoint, TorSessionState>>(emptyMap())
    override val sessionStates: StateFlow<Map<TorEndpoint, TorSessionState>> = _sessionStates.asStateFlow()

    private inner class Link(
        val id: TorLinkId,
        /** Where its packets come from: the dialed target, or the dialer's claim. */
        val remote: TorEndpoint,
        val outbound: Boolean,
        val duplex: TorDuplex,
    ) {
        val writeLock = Mutex()
        val accepted = CompletableDeferred<Unit>()

        @Volatile
        var lastReceived: TimeMark = TimeSource.Monotonic.markNow()

        @Volatile
        var lastSent: TimeMark = TimeSource.Monotonic.markNow()

        @Volatile
        var closed = false

        /** The endpoint this link is a session to; null while it only carries replies. */
        @Volatile
        var sessionKey: TorEndpoint? = null

        var job: Job? = null

        /** Whether it was dialed by the lower onion of the pair (the one both sides keep). */
        val dialedByLower: Boolean
            get() {
                val local = localEndpoint?.onionAddress?.lowercase() ?: return outbound
                val far = remote.onionAddress.lowercase()
                return if (outbound) local < far else far < local
            }
    }

    override suspend fun start(): TorEndpoint {
        check(!started) { "Tor transport is already started" }

        val localEndpoint = backend.start()
        this.localEndpoint = localEndpoint

        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = localScope

        frameCollectorJob = localScope.launch {
            backend.incomingFrames.collect { frame ->
                // Self-dial reachability probes are backend business, not envelopes:
                // swallow silently so they never warn like corrupt traffic.
                if (probeNonceOrNull(frame.payload) != null) {
                    AppLog.debug(
                        component = LogComponent.TOR_TRANSPORT,
                        event = LogEvent.INBOUND_ENVELOPE_RECEIVED,
                        message = "Skipped self-dial reachability probe frame",
                        fields = mapOf("source" to frame.source.onionAddress),
                    )
                    return@collect
                }
                emitEnvelope(frame.source, frame.payload, link = null)
            }
        }
        localScope.launch {
            backend.incomingStreams.collect { stream -> acceptInbound(stream) }
        }
        require(frameCollectorJob?.isActive == true)
        started = true
        AppLog.info(
            component = LogComponent.TOR_TRANSPORT,
            event = LogEvent.STARTED,
            message = "Tor transport started",
            fields = mapOf("localEndpoint" to localEndpoint.onionAddress, "port" to localEndpoint.port),
        )
        return localEndpoint
    }

    override suspend fun stop() {
        if (!started) return
        started = false
        val open = synchronized(lock) {
            dialing.values.forEach { it.cancel() }
            dialing.clear()
            links.values.toList()
        }
        open.forEach { closeLink(it, "transport stopped") }
        frameCollectorJob?.cancel()
        frameCollectorJob = null
        scope?.cancel()
        runCatching { backend.stop() }
        scope = null
        _sessionStates.value = emptyMap()
        AppLog.info(
            component = LogComponent.TOR_TRANSPORT,
            event = LogEvent.STOPPED,
            message = "Tor transport stopped",
        )
    }

    override suspend fun refreshOnionLookups(): Boolean = backend.refreshOnionLookups()

    override suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope, budget: Duration?, via: TorLinkId?) {
        check(started) { "Tor transport must be started before send" }
        val payload = envelope.encode()
        val path = when {
            via != null && sendOnLink(linkOrNull(via), payload) -> "reply-link"
            sendOnSession(target, payload) -> "session"
            else -> {
                backend.send(target = target, payload = payload, budget = budget)
                "one-shot"
            }
        }
        AppLog.debug(
            component = LogComponent.TOR_TRANSPORT,
            event = LogEvent.SIGNAL_OUTBOUND_EMITTED,
            message = "Sent Tor envelope",
            fields = mapOf(
                "packetType" to envelope.packetType.name,
                "target" to target.onionAddress,
                "targetPort" to target.port,
                "path" to path,
            ),
        )
    }

    override fun hasSession(key: TorEndpoint): Boolean = synchronized(lock) { sessionLinkLocked(key) != null }

    override suspend fun openSession(key: TorEndpoint) {
        check(started) { "Tor transport must be started before opening a session" }
        val localScope = scope ?: return
        val job = synchronized(lock) {
            if (sessionLinkLocked(key) != null || key in dialing) return
            setStateLocked(key, SessionPhase.NEGOTIATING, null)
            localScope.launch(start = CoroutineStart.LAZY) { dial(key) }.also { dialing[key] = it }
        }
        job.start()
    }

    override suspend fun closeSession(key: TorEndpoint) {
        val toClose = synchronized(lock) {
            dialing.remove(key)?.cancel()
            links.values.filter { it.sessionKey == key }
        }
        toClose.forEach { closeLink(it, "closed locally") }
        synchronized(lock) {
            if (sessionLinkLocked(key) == null && _sessionStates.value[key]?.phase == SessionPhase.NEGOTIATING) {
                setStateLocked(key, SessionPhase.CLOSED, "closed locally")
            }
        }
    }

    override fun adoptInbound(link: TorLinkId, endpoint: TorEndpoint): Boolean {
        val superseded = mutableListOf<Link>()
        val adopted = synchronized(lock) {
            val candidate = links[link] ?: return false
            if (candidate.closed) return false
            if (candidate.outbound) return candidate.sessionKey == endpoint
            if (!onionMatches(
                    candidate.remote.onionAddress,
                    endpoint.onionAddress
                ) || candidate.remote.port != endpoint.port
            ) {
                return false
            }
            if (candidate.sessionKey == endpoint) return true
            // The far end dialed again: its earlier stream is dead or about to be.
            superseded += links.values.filter { !it.outbound && it.sessionKey == endpoint }
            candidate.sessionKey = endpoint
            superseded += duplicatesToDropLocked(endpoint)
            refreshStateLocked(endpoint, null)
            candidate
        }
        superseded.forEach { closeLink(it, "superseded") }
        AppLog.info(
            component = LogComponent.TOR_TRANSPORT,
            event = LogEvent.SESSION_STATE_CHANGED,
            message = "Adopted inbound Tor stream as session",
            fields = mapOf("endpoint" to endpoint.onionAddress, "link" to adopted.id.value),
        )
        return true
    }

    // ---- links ---------------------------------------------------------------------------

    private suspend fun dial(target: TorEndpoint) {
        val configSnapshot = config.value
        var link: Link? = null
        try {
            val duplex = backend.openStream(target, configSnapshot.sessionDialBudget)
            link = register(duplex, remote = target, outbound = true)
            if (link == null) {
                duplex.close()
                return
            }
            link.sessionKey = target
            startLink(link)
            val accepted = withTimeoutOrNull(configSnapshot.sessionAcceptTimeout) { link.accepted.await() }
            if (accepted == null) {
                closeLink(link, "not accepted")
                markDialFailed(target, "far end did not accept the stream")
            }
        } catch (e: CancellationException) {
            link?.let { closeLink(it, "dial cancelled") }
            throw e
        } catch (e: Exception) {
            link?.let { closeLink(it, "dial failed") }
            val reason = when (e) {
                is TransportException.TorException.OnionNotFound -> TorSessionState.REASON_ONION_NOT_FOUND
                else -> e.message ?: e::class.simpleName ?: "dial failed"
            }
            markDialFailed(target, reason)
        } finally {
            val self = currentCoroutineContext()[Job]
            synchronized(lock) { if (dialing[target] === self) dialing.remove(target) }
        }
    }

    private fun acceptInbound(stream: TorInboundStream) {
        val link = register(stream.duplex, remote = stream.source, outbound = false)
        if (link == null) {
            stream.duplex.close()
            AppLog.warn(
                component = LogComponent.TOR_TRANSPORT,
                event = LogEvent.SESSION_FAILED,
                message = "Refused inbound Tor stream",
                fields = mapOf("source" to stream.source.onionAddress),
            )
            return
        }
        startLink(link)
    }

    /** Null when stopped, or (inbound) when `maxInboundSessions` streams are already open. */
    private fun register(duplex: TorDuplex, remote: TorEndpoint, outbound: Boolean): Link? = synchronized(lock) {
        if (!started) return null
        if (!outbound && links.values.count { !it.outbound } >= config.value.maxInboundSessions) return null
        val link = Link(TorLinkId(nextLinkId++), remote, outbound, duplex)
        links[link.id] = link
        link
    }

    private fun startLink(link: Link) {
        val localScope = scope ?: return closeLink(link, "transport stopped")
        link.job = localScope.launch {
            var reason = "closed by far end"
            try {
                if (!link.outbound) writeRecord(link, TorStreamRecordType.ACCEPT)
                coroutineScope {
                    val keepalive = launch { keepaliveLoop(link) }
                    readLoop(link)
                    keepalive.cancel()
                }
            } catch (e: CancellationException) {
                reason = "cancelled"
                throw e
            } catch (e: Exception) {
                reason = e.message ?: e::class.simpleName ?: "stream error"
            } finally {
                closeLink(link, reason)
            }
        }
    }

    private suspend fun readLoop(link: Link) {
        val maxPayloadBytes = config.value.maxPayloadBytes
        while (true) {
            val record = readTorStreamRecord(link.duplex.input, maxPayloadBytes)
            link.lastReceived = TimeSource.Monotonic.markNow()
            when (record.type) {
                TorStreamRecordType.ACCEPT -> if (link.outbound && !link.accepted.isCompleted) onAccepted(link)
                TorStreamRecordType.DATA -> emitEnvelope(link.remote, record.payload, link.id)
                TorStreamRecordType.KEEPALIVE, null -> Unit
            }
        }
    }

    private suspend fun keepaliveLoop(link: Link) {
        while (true) {
            val configSnapshot = config.value
            delay(configSnapshot.sessionKeepaliveInterval / KEEPALIVE_CHECKS_PER_INTERVAL)
            if (link.lastReceived.elapsedNow() >= configSnapshot.sessionIdleTimeout) {
                throw TransportException.TorException.SessionClosed("nothing received for ${configSnapshot.sessionIdleTimeout}")
            }
            if (link.lastSent.elapsedNow() >= configSnapshot.sessionKeepaliveInterval) {
                writeRecord(link, TorStreamRecordType.KEEPALIVE)
            }
        }
    }

    private fun onAccepted(link: Link) {
        val endpoint = link.remote
        val dropped = synchronized(lock) {
            if (link.closed) return
            link.accepted.complete(Unit)
            val dropped = duplicatesToDropLocked(endpoint)
            refreshStateLocked(endpoint, null)
            dropped
        }
        dropped.forEach { closeLink(it, "superseded") }
        AppLog.info(
            component = LogComponent.TOR_TRANSPORT,
            event = LogEvent.SESSION_STATE_CHANGED,
            message = "Tor session connected",
            fields = mapOf("endpoint" to endpoint.onionAddress, "link" to link.id.value),
        )
    }

    /**
     * Our own dialed links to [endpoint] that should go now that the far end's link is usable
     * too: only on the side whose onion sorts higher (the other side keeps its dialed link,
     * which is the one we adopted). Never leaves [endpoint] without a usable link.
     */
    private fun duplicatesToDropLocked(endpoint: TorEndpoint): List<Link> {
        val usable = links.values.filter { it.isUsableFor(endpoint) }
        val ours = usable.filter { it.outbound && !it.dialedByLower }
        val theirs = usable.filter { !it.outbound && it.dialedByLower }
        return if (theirs.isNotEmpty()) ours else emptyList()
    }

    private fun Link.isUsableFor(endpoint: TorEndpoint): Boolean =
        !closed && sessionKey == endpoint && (!outbound || accepted.isCompleted)

    private fun sessionLinkLocked(endpoint: TorEndpoint): Link? =
        links.values.filter { it.isUsableFor(endpoint) }
            .sortedWith(compareByDescending<Link> { it.dialedByLower }.thenByDescending { it.id.value })
            .firstOrNull()

    private fun linkOrNull(id: TorLinkId): Link? = synchronized(lock) { links[id]?.takeIf { !it.closed } }

    /** Writes [payload] on a usable session link to [target]; false when there is none (or it just died). */
    private suspend fun sendOnSession(target: TorEndpoint, payload: ByteArray): Boolean {
        repeat(SESSION_SEND_ATTEMPTS) {
            val link = synchronized(lock) { sessionLinkLocked(target) } ?: return false
            if (sendOnLink(link, payload)) return true
        }
        throw TransportException.TorException.SessionClosed("Tor session to ${target.onionAddress} closed mid-send")
    }

    private suspend fun sendOnLink(link: Link?, payload: ByteArray): Boolean {
        if (link == null || link.closed) return false
        val limit = config.value.maxPayloadBytes
        if (payload.size > limit) throw TransportException.PayloadTooLarge(sizeBytes = payload.size, limitBytes = limit)
        return try {
            writeRecord(link, TorStreamRecordType.DATA, payload)
            true
        } catch (e: TransportException.TorException.SessionClosed) {
            false
        }
    }

    /** Writes one record; a failed or stalled write closes the link and throws [TransportException.TorException.SessionClosed]. */
    private suspend fun writeRecord(link: Link, type: TorStreamRecordType, payload: ByteArray = ByteArray(0)) {
        val timeout = config.value.sessionWriteTimeout
        val written = try {
            link.writeLock.withLock {
                if (link.closed) return@withLock false
                withTimeoutOrNull(timeout) {
                    writeTorStreamRecord(link.duplex.output, type, payload)
                    link.duplex.output.flush()
                    true
                } ?: false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!written) {
            closeLink(link, "write failed")
            throw TransportException.TorException.SessionClosed("Tor stream write failed")
        }
        link.lastSent = TimeSource.Monotonic.markNow()
    }

    private fun closeLink(link: Link, reason: String) {
        val endpoint = synchronized(lock) {
            if (link.closed) return
            link.closed = true
            links.remove(link.id)
            link.sessionKey
        }
        link.accepted.completeExceptionally(TransportException.TorException.SessionClosed(reason))
        link.duplex.close()
        link.job?.cancel()
        if (endpoint != null) synchronized(lock) { refreshStateLocked(endpoint, reason) }
        AppLog.debug(
            component = LogComponent.TOR_TRANSPORT,
            event = LogEvent.SESSION_STATE_CHANGED,
            message = "Tor stream closed",
            fields = mapOf(
                "remote" to link.remote.onionAddress,
                "outbound" to link.outbound,
                "link" to link.id.value,
                "reason" to reason,
            ),
        )
    }

    // ---- states --------------------------------------------------------------------------

    /** CONNECTED while a usable link exists; otherwise a CONNECTED state becomes CLOSED. */
    private fun refreshStateLocked(endpoint: TorEndpoint, reasonIfDown: String?) {
        if (!started) return
        if (sessionLinkLocked(endpoint) != null) {
            setStateLocked(endpoint, SessionPhase.CONNECTED, null)
        } else if (_sessionStates.value[endpoint]?.phase == SessionPhase.CONNECTED) {
            setStateLocked(endpoint, SessionPhase.CLOSED, reasonIfDown)
        }
    }

    private fun markDialFailed(target: TorEndpoint, reason: String) {
        synchronized(lock) {
            if (sessionLinkLocked(target) == null) setStateLocked(target, SessionPhase.FAILED, reason)
        }
        AppLog.debug(
            component = LogComponent.TOR_TRANSPORT,
            event = LogEvent.SESSION_FAILED,
            message = "Tor session dial failed",
            fields = mapOf("target" to target.onionAddress, "reason" to reason),
        )
    }

    private fun setStateLocked(endpoint: TorEndpoint, phase: SessionPhase, reason: String?) {
        if (!started) return
        val current = _sessionStates.value[endpoint]
        if (current?.phase == phase && current.reason == reason) return
        _sessionStates.value += endpoint to TorSessionState(endpoint, phase, reason)
    }

    private suspend fun emitEnvelope(source: TorEndpoint, payload: ByteArray, link: TorLinkId?) {
        val envelope = runCatching { BinaryEnvelope.decode(payload) }.getOrElse { error ->
            AppLog.warn(
                component = LogComponent.TOR_TRANSPORT,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode inbound Tor envelope",
                fields = mapOf(
                    "source" to source.onionAddress,
                    "sourcePort" to source.port,
                    "error" to error.toString(),
                ),
            )
            return
        }
        incomingFlow.emit(TorIncomingEnvelope(source = source, envelope = envelope, link = link))
        AppLog.debug(
            component = LogComponent.TOR_TRANSPORT,
            event = LogEvent.INBOUND_ENVELOPE_RECEIVED,
            message = "Received inbound Tor envelope",
            fields = mapOf(
                "packetType" to envelope.packetType.name,
                "source" to source.onionAddress,
                "link" to link?.value,
            ),
        )
    }

    private companion object {
        /** Liveness is checked this many times per keepalive interval. */
        const val KEEPALIVE_CHECKS_PER_INTERVAL: Int = 4

        /** A session send tries this many usable links before giving up (each failure closes one). */
        const val SESSION_SEND_ATTEMPTS: Int = 2
    }
}
