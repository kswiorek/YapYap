package org.yapyap.transport.tor.backend

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.matthewnelson.kmp.file.File
import io.matthewnelson.kmp.file.resolve
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.TorEvent
import io.matthewnelson.kmp.tor.runtime.core.config.IntervalUnit
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3.PrivateKey.Companion.toED25519_V3PrivateKey
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Companion.toPort
import io.matthewnelson.kmp.tor.runtime.core.util.executeAsync
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.TorEndpoint
import org.yapyap.transport.TransportException
import org.yapyap.transport.tor.TorIncomingFrame
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Tor backend powered by kmp-tor runtime using noexec resources.
 */
internal class KmpTorBackend(
    private val torStateRootPath: Path,
    private val coroutineContext: CoroutineContext = EmptyCoroutineContext,
    private val config: StateFlow<TorBackendConfig>,
) : TorBackend {

    private val inboundFlow = MutableSharedFlow<TorIncomingFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val incomingFrames: Flow<TorIncomingFrame> = inboundFlow.asSharedFlow()

    private val _publishState = MutableStateFlow(TorPublishState.PUBLISHING)
    override val publishState: StateFlow<TorPublishState> = _publishState.asStateFlow()

    private var socksPort: Int? = null

    var publishedLocalEndpoint: TorEndpoint? = null
        private set

    private var torRuntime: TorRuntime? = null
    private var acceptSocket: ServerSocket? = null
    private var selectorManager: SelectorManager? = null
    private var scope: CoroutineScope? = null
    private var verifierJob: Job? = null
    private var started = false

    override suspend fun start(): TorEndpoint {
        check(torRuntime == null) { "Tor backend already started" }

        val selectorContext = if (coroutineContext == EmptyCoroutineContext) {
            Dispatchers.Default
        } else {
            coroutineContext
        }
        val selector = SelectorManager(selectorContext)
        selectorManager = selector

        val listener = aSocket(selector).tcp().bind("127.0.0.1", 0)
        acceptSocket = listener

        val runtime = createTorRuntime(torStateRootPath.toKmpFile())
        torRuntime = runtime

        val localScope = CoroutineScope(SupervisorJob() + selectorContext)
        scope = localScope

        try {
            runtime.startDaemonAsync()
            waitUntilReady(runtime)

            // Stable onion identity: the whole sponsor flow assumes the onion in a
            // newcomer's QR still resolves when the sponsor dials minutes later.
            // ADD_ONION with a fresh key is ephemeral (new onion every restart),
            // so persist the private key under torStateRoot and re-add it.
            val (onionEntry, onionReused) = addStableOnion(runtime, listener)
            val onionAddress = onionEntry.publicKey.address().toString().let { raw ->
                if (raw.endsWith(".onion", ignoreCase = true)) raw else "$raw.onion"
            }
            val resolvedEndpoint = TorEndpoint(onionAddress = onionAddress, port = config.value.defaultTorPort)
            publishedLocalEndpoint = resolvedEndpoint

            // Every boot re-uploads the descriptor, so every boot re-warms.
            // PUBLISHED only latches on a successful self-dial (see the
            // verifier); the watchdog below flips to DEGRADED past the
            // budget. All three die with localScope on stop().
            _publishState.value = TorPublishState.PUBLISHING
            localScope.launch {
                delay(config.value.publishTimeout)
                if (_publishState.value == TorPublishState.PUBLISHING && torRuntime != null) {
                    _publishState.value = TorPublishState.DEGRADED
                    AppLog.warn(
                        component = LogComponent.TOR_BACKEND,
                        event = LogEvent.SESSION_FAILED,
                        message = "No HS_DESC UPLOADED for our onion within budget; still retrying underneath",
                        fields = mapOf("onionAddress" to onionAddress),
                    )
                }
            }
            // Backstop for a missed/late UPLOADED event (or a daemon that never
            // emits one): verify reachability directly after a grace period even
            // if no upload was ever observed. The probe is ground truth; the
            // event is only the fast path.
            localScope.launch {
                delay(config.value.uploadGracePeriod)
                if (_publishState.value == TorPublishState.PUBLISHING) {
                    AppLog.info(
                        component = LogComponent.TOR_BACKEND,
                        event = LogEvent.STARTED,
                        message = "No descriptor upload observed yet; verifying reachability via self-dial anyway",
                        fields = mapOf("onionAddress" to onionAddress),
                    )
                    maybeStartVerifier()
                }
            }

            localScope.launch {
                acceptInboundConnections(listener)
            }
            started = true
            AppLog.info(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.STARTED,
                message = "KMP Tor backend started",
                fields = mapOf(
                    "onionAddress" to onionAddress,
                    "port" to config.value.defaultTorPort,
                    "onionReused" to onionReused,
                ),
            )
            return resolvedEndpoint
        } catch (error: Throwable) {
            AppLog.error(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.SESSION_FAILED,
                message = "Failed to start KMP Tor backend",
                throwable = error,
            )
            stop()
            throw error
        }
    }

    override suspend fun stop() {
        acceptSocket?.safeClose()
        acceptSocket = null

        selectorManager?.safeClose()
        selectorManager = null

        scope?.cancel()
        scope = null

        torRuntime?.let { runtime ->
            runCatching { runtime.stopDaemonAsync() }
        }
        torRuntime = null

        publishedLocalEndpoint = null
        socksPort = null
        started = false
        AppLog.info(
            component = LogComponent.TOR_BACKEND,
            event = LogEvent.STOPPED,
            message = "KMP Tor backend stopped",
        )
    }

    override suspend fun send(target: TorEndpoint, payload: ByteArray) {
        val configSnapshot = config.value
        if (payload.size > configSnapshot.maxPayloadBytes) {
            throw TransportException.PayloadTooLarge(
                sizeBytes = payload.size,
                limitBytes = configSnapshot.maxPayloadBytes,
            )
        }

        val deadline = TimeSource.Monotonic.markNow() + configSnapshot.socksRetryTimeout
        var attempts = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            // Enforce the budget per attempt, not just between them: a single
            // SOCKS dial may legitimately take up to socksSocketTimeout (Tor
            // answers only once rendezvous is built or definitively failed),
            // and without this cap one stalled read would sail past the budget.
            // External cancellation still propagates as CancellationException;
            // only our own timeout surfaces as budget exhaustion below.
            val remaining = deadline - TimeSource.Monotonic.markNow()
            if (remaining <= Duration.ZERO) {
                throw TransportException.TorException.SocksError(
                    "Tor send budget exhausted for ${target.onionAddress}"
                )
            }
            try {
                withTimeout(remaining) {
                    sendFrameOnce(target, payload, configSnapshot)
                }
                return
            } catch (_: TimeoutCancellationException) {
                throw TransportException.TorException.SocksError(
                    "Tor send budget exhausted for ${target.onionAddress}"
                )
            } catch (error: SocksConnectException) {
                attempts++
                val shouldRetry =
                    error.code in configSnapshot.socksTransientFailureCodes &&
                            TimeSource.Monotonic.markNow() < deadline
                if (!shouldRetry) {
                    AppLog.error(
                        component = LogComponent.TOR_BACKEND,
                        event = LogEvent.SESSION_FAILED,
                        message = "SOCKS connect failed for Tor send",
                        throwable = error,
                        fields = mapOf(
                            "targetHost" to target.onionAddress,
                            "targetPort" to target.port,
                            "code" to error.code,
                            "attempts" to attempts,
                        ),
                    )
                    throw TransportException.TorException.SocksError("SOCKS connect failed with code ${error.code}, error: ${error.message}")
                }
                // Host-unreachable (code 4) is the normal state of a fresh onion
                // whose descriptor hasn't propagated yet — back off slowly.
                // Other transient codes (relay overload etc.) retry on the base cadence.
                // Both get ±50% jitter so a fleet of sponsors doesn't hammer HSDirs in lockstep.
                val baseDelay = if (error.code == NOT_PUBLISHED_SOCKS_CODE) {
                    configSnapshot.socksNotPublishedRetryDelay
                } else {
                    configSnapshot.socksRetryDelay
                }
                val jittered = baseDelay + Random.nextLong(0L, baseDelay.inWholeMilliseconds / 2).milliseconds
                AppLog.debug(
                    component = LogComponent.TOR_BACKEND,
                    event = LogEvent.SESSION_FAILED,
                    message = "SOCKS connect transient failure, retrying Tor send",
                    fields = mapOf(
                        "targetHost" to target.onionAddress,
                        "targetPort" to target.port,
                        "code" to error.code,
                        "attempts" to attempts,
                        "retryDelayMs" to jittered.inWholeMilliseconds,
                    ),
                )
                delay(jittered)
            } catch (error: ClosedByteChannelException) {
                // Tor closed the SOCKS connection without a reply code — happens
                // when a rendezvous attempt collapses mid-handshake (e.g. the
                // far end's intro circuits are dead but its descriptor lingers).
                // Same treatment as a transient SOCKS failure: bounded inner retry.
                attempts++
                retryTransientOrThrow(error, "closed-channel", attempts, deadline, configSnapshot, target)
            } catch (error: SocketTimeoutException) {
                // Local SOCKS I/O stalled (slow Tor, not dead Tor): same deal.
                attempts++
                retryTransientOrThrow(error, "io-timeout", attempts, deadline, configSnapshot, target)
            }
        }
    }

    /**
     * Single SOCKS dial + frame write, no retry. Throws [SocksConnectException]
     * on a Tor error reply, [ClosedByteChannelException]/[SocketTimeoutException]
     * when the local Tor side drops or stalls the connection.
     */
    private suspend fun sendFrameOnce(
        target: TorEndpoint,
        payload: ByteArray,
        configSnapshot: TorBackendConfig,
    ) {
        val localSource = publishedLocalEndpoint ?: error("Tor backend must be started before send")
        val localSocksPort = requireNotNull(socksPort) { "Tor socks port is not ready" }
        val selector = requireNotNull(selectorManager) { "Tor selector manager is not initialized" }
        val socket = aSocket(selector).tcp().connect("127.0.0.1", localSocksPort) {
            socketTimeout = configSnapshot.socksSocketTimeout.inWholeMilliseconds
        }
        try {
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = false)

            performSocks5Handshake(target, input, output)
            writeTorTransportFrame(output, localSource, payload)
            output.flush()
        } finally {
            socket.safeClose()
        }
    }

    private suspend fun retryTransientOrThrow(
        error: Throwable,
        kind: String,
        attempts: Int,
        deadline: TimeSource.Monotonic.ValueTimeMark,
        configSnapshot: TorBackendConfig,
        target: TorEndpoint,
    ) {
        if (torRuntime == null || TimeSource.Monotonic.markNow() >= deadline) {
            AppLog.error(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.SESSION_FAILED,
                message = "Tor send failed for Tor send",
                throwable = error,
                fields = mapOf(
                    "targetHost" to target.onionAddress,
                    "targetPort" to target.port,
                    "kind" to kind,
                    "attempts" to attempts,
                ),
            )
            throw TransportException.TorException.SocksError("Tor send failed ($kind), error: ${error.message}")
        }
        val jittered = configSnapshot.socksRetryDelay +
                Random.nextLong(0L, configSnapshot.socksRetryDelay.inWholeMilliseconds / 2).milliseconds
        AppLog.debug(
            component = LogComponent.TOR_BACKEND,
            event = LogEvent.SESSION_FAILED,
            message = "Tor send transient failure, retrying Tor send",
            fields = mapOf(
                "targetHost" to target.onionAddress,
                "targetPort" to target.port,
                "kind" to kind,
                "attempts" to attempts,
                "retryDelayMs" to jittered.inWholeMilliseconds,
            ),
        )
        delay(jittered)
    }

    private fun createTorRuntime(localStateDir: File): TorRuntime {
        val workDirectory = localStateDir.resolve("work")
        val cacheDirectory = localStateDir.resolve("cache")

        val environment = TorRuntime.Environment.Builder(
            workDirectory = workDirectory,
            cacheDirectory = cacheDirectory,
            loader = { resourceDir ->
                createResourceLoader(resourceDir)
            },
        )

        return TorRuntime.Builder(environment) {
            config { _ ->
                TorOption.__SocksPort.configure { auto() }
                // Short keepalive so middleboxes/NATs with aggressive idle
                // timeouts don't silently kill our long-lived intro circuits
                // (a dead intro set looks exactly like "published but nobody
                // can dial us"). Default is 5 minutes.
                TorOption.KeepalivePeriod.configure(60, IntervalUnit.SECONDS)
            }
            // required() is what puts HS_DESC into the control connection's
            // SETEVENTS — observer() alone only registers the callback, and
            // without the event enabled nothing ever arrives (publishState
            // would sit at PUBLISHING forever). Callbacks must not throw.
            required(TorEvent.HS_DESC)
            observer(TorEvent.HS_DESC.observer(PUBLISH_OBSERVER_TAG, null) { data ->
                runCatching { onHsDescEvent(data) }
            })
            // NOTICE carries daemon-side context (bootstrap, circuit rebuilds,
            // rendezvous complaints) we can't see otherwise. Debug only —
            // chatty during bootstrap, sparse afterwards.
            required(TorEvent.NOTICE)
            observer(TorEvent.NOTICE.observer(PUBLISH_OBSERVER_TAG, null) { data ->
                runCatching {
                    AppLog.debug(
                        component = LogComponent.TOR_BACKEND,
                        event = LogEvent.TOR_DAEMON_NOTICE,
                        message = "Tor daemon notice",
                        fields = mapOf("line" to data),
                    )
                }
            })
        }
    }

    private fun onHsDescEvent(data: String) {
        // Log every HS_DESC line: UPLOADED starts verification, but UPLOAD /
        // UPLOAD_FAILED / CREATED tell us what the daemon actually did when the
        // latch never comes.
        AppLog.debug(
            component = LogComponent.TOR_BACKEND,
            event = LogEvent.TOR_DAEMON_NOTICE,
            message = "Tor hidden-service descriptor event",
            fields = mapOf("line" to data),
        )
        val uploadedOnion = hsDescUploadedOnion(data) ?: return
        val local = publishedLocalEndpoint?.onionAddress ?: return
        if (!onionMatches(uploadedOnion, local)) return
        AppLog.info(
            component = LogComponent.TOR_BACKEND,
            event = LogEvent.STARTED,
            message = "Local onion descriptor uploaded; verifying reachability via self-dial",
            fields = mapOf("onionAddress" to local),
        )
        maybeStartVerifier()
    }

    /**
     * Bounded self-dial loop: dials our own onion end-to-end (full rendezvous)
     * to prove it is actually dialable, not just published. First success
     * latches PUBLISHED; exhausting [TorBackendConfig.probeBudget] without one
     * means something eats our inbound (see the keepalive note above) and we
     * report DEGRADED so the shell stops claiming readiness. One loop per boot
     * at most; dies with the backend scope on stop(). Idempotent: the UPLOADED
     * fast path and the no-event grace timer share it.
     */
    private fun maybeStartVerifier() {
        if (_publishState.value != TorPublishState.PUBLISHING) return
        val owner = scope ?: return
        if (verifierJob?.isActive == true) return
        verifierJob = owner.launch {
            val startedAt = TimeSource.Monotonic.markNow()
            while (isActive) {
                if (checkSelfReachability()) {
                    if (_publishState.value != TorPublishState.PUBLISHED) {
                        _publishState.value = TorPublishState.PUBLISHED
                        AppLog.info(
                            component = LogComponent.TOR_BACKEND,
                            event = LogEvent.STARTED,
                            message = "Local onion reachable via rendezvous; address is dialable",
                            fields = mapOf(
                                "onionAddress" to publishedLocalEndpoint?.onionAddress,
                            ),
                        )
                    }
                    return@launch
                }
                if (startedAt + config.value.probeBudget < TimeSource.Monotonic.markNow()) {
                    _publishState.value = TorPublishState.DEGRADED
                    AppLog.warn(
                        component = LogComponent.TOR_BACKEND,
                        event = LogEvent.SESSION_FAILED,
                        message = "Descriptor uploaded but self-dial never succeeded; inbound rendezvous looks broken",
                        fields = mapOf("onionAddress" to publishedLocalEndpoint?.onionAddress),
                    )
                    return@launch
                }
                delay(config.value.probeInterval)
            }
        }
    }

    /**
     * One self-dial round trip to our own onion: raw probe frame out through
     * SOCKS, matched looped-back frame in via [inboundFlow]. Single shot, no
     * inner retry (the verifier loop owns the cadence). False only means
     * "not proven this time", never "unreachable".
     */
    private suspend fun checkSelfReachability(): Boolean {
        val local = publishedLocalEndpoint ?: return false
        val configSnapshot = config.value
        val nonce = Random.nextBytes(TOR_PROBE_NONCE_SIZE)
        val payload = buildProbePayload(nonce)
        return try {
            withTimeout(configSnapshot.probeTimeout) {
                coroutineScope {
                    val wait = async {
                        inboundFlow.first { frame ->
                            probeNonceOrNull(frame.payload)?.contentEquals(nonce) == true
                        }
                    }
                    // Let the collector subscribe before the frame can loop back.
                    yield()
                    try {
                        sendFrameOnce(local, payload, configSnapshot)
                    } catch (sendError: Exception) {
                        if (sendError is CancellationException) throw sendError
                        if (sendError is kotlinx.coroutines.CancellationException) throw sendError
                        wait.cancel()
                        return@coroutineScope false
                    }
                    wait.await()
                    true
                }
            }
        } catch (_: TimeoutCancellationException) {
            // This attempt's budget lapsed — not a verdict, the loop retries.
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Adds our hidden service with a stable key: loads the persisted
     * `onion_v3.key` (raw 64-byte ed25519 private key) or generates and stores
     * one on first boot. Returns the entry plus whether the key was reused.
     *
     * The key file lives beside Tor's own `work/`/`cache/` dirs inside
     * `torStateRoot` — deliberately *not* in the app KeyStore, so the transport
     * layer stays self-contained and never depends upward on crypto/persistence.
     */
    private suspend fun addStableOnion(
        runtime: TorRuntime,
        listener: ServerSocket,
    ): Pair<io.matthewnelson.kmp.tor.runtime.core.ctrl.HiddenServiceEntry, Boolean> {
        val existing = loadOnionKey()
        return if (existing != null) {
            val entry = runtime.executeAsync(
                TorCmd.Onion.Add.existing(existing) {
                    port(config.value.defaultTorPort.toPort()) {
                        target(listener.port.toPort())
                    }
                }
            )
            // `existing` mode destroys the in-memory key on completion by default;
            // the file copy remains the source of truth.
            entry to true
        } else {
            val entry = runtime.executeAsync(
                TorCmd.Onion.Add.new(ED25519_V3) {
                    port(config.value.defaultTorPort.toPort()) {
                        target(listener.port.toPort())
                    }
                }
            )
            val privateKey = entry.privateKey
            if (privateKey != null) {
                runCatching { storeOnionKey(privateKey.encoded()) }.onFailure { error ->
                    AppLog.warn(
                        component = LogComponent.TOR_BACKEND,
                        event = LogEvent.SESSION_FAILED,
                        message = "Failed to persist onion key; onion will rotate on next restart",
                        fields = mapOf("error" to error.toString()),
                    )
                }
            } else {
                AppLog.warn(
                    component = LogComponent.TOR_BACKEND,
                    event = LogEvent.SESSION_FAILED,
                    message = "Tor did not return an onion private key; onion will rotate on next restart",
                )
            }
            entry to false
        }
    }

    private fun onionKeyFile(): Path = Path(torStateRootPath, ONION_KEY_FILE)

    private fun loadOnionKey(): ED25519_V3.PrivateKey? {
        val file = onionKeyFile()
        if (!SystemFileSystem.exists(file)) return null
        return try {
            val bytes = SystemFileSystem.source(file).use { source ->
                val buffer = kotlinx.io.Buffer()
                while (source.readAtMostTo(buffer, Long.MAX_VALUE) != -1L) {
                    // drain
                }
                val out = ByteArray(buffer.size.toInt())
                var offset = 0
                while (offset < out.size) {
                    val read = buffer.readAtMostTo(out, offset, out.size)
                    if (read <= 0) break
                    offset += read
                }
                out
            }
            bytes.toED25519_V3PrivateKey()
        } catch (error: Exception) {
            AppLog.warn(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.SESSION_FAILED,
                message = "Corrupt onion key file; generating a fresh onion (old invites break)",
                fields = mapOf("file" to file.toString(), "error" to error.toString()),
            )
            try {
                SystemFileSystem.delete(file)
            } catch (_: Exception) {
                // best effort; a fresh key will overwrite below on next boot
            }
            null
        }
    }

    private fun storeOnionKey(raw: ByteArray) {
        val file = onionKeyFile()
        SystemFileSystem.sink(file).use { sink ->
            val buffer = kotlinx.io.Buffer()
            buffer.write(raw, 0, raw.size)
            sink.write(buffer, buffer.size)
            sink.flush()
        }
    }

    private suspend fun waitUntilReady(runtime: TorRuntime) {
        val completed = withTimeoutOrNull(config.value.startupTimeout) {
            while (true) {
                socksPort = runtime.listeners().socks.lastOrNull()?.port?.value ?: socksPort
                val ready = socksPort != null && runtime.isReady()

                if (ready) return@withTimeoutOrNull

                if (torRuntime == null) {
                    throw TransportException.TorException.TorRuntimeError("Tor runtime exitted before ready")
                }
                delay(150.milliseconds)
            }
        }
        if (completed == null) {
            throw TransportException.TorException.SocksConnectionTimeout()
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
     * Reads one transport frame from an accepted connection and emits it. Genuine parse
     * failures surface as [TransportException.TorException.TransportFrameError].
     * Cancellation is rethrown untouched — both cancellation hierarchies (the
     * `kotlin.coroutines` one and the kotlinx one carrying [JobCancellationException] from
     * structured-concurrency teardown). Wrapping either — e.g. when in-flight reads are
     * cancelled at backend shutdown — would turn an orderly stop into a spurious child
     * failure and an uncaught exception.
     */
    internal suspend fun handleInboundConnection(client: Socket) {
        try {
            val input = client.openReadChannel()
            val frame = try {
                readTorTransportFrame(input, config.value.maxPayloadBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (error: Exception) {
                AppLog.warn(
                    component = LogComponent.TOR_BACKEND,
                    event = LogEvent.ENVELOPE_DECODE_FAILED,
                    message = "Failed to read inbound Tor transport frame",
                )
                AppLog.error(
                    component = LogComponent.TOR_BACKEND,
                    event = LogEvent.ENVELOPE_DECODE_FAILED,
                    message = "Inbound Tor transport frame parse error",
                    throwable = error,
                )
                throw TransportException.TorException.TransportFrameError(error.message ?: "Unknown error")
            }
            inboundFlow.emit(frame)
        } finally {
            client.safeClose()
        }
    }

    private suspend fun performSocks5Handshake(
        target: TorEndpoint,
        input: ByteReadChannel,
        output: ByteWriteChannel,
    ) {
        output.writeByte(0x05)
        output.writeByte(0x01)
        output.writeByte(0x00)
        output.flush()

        val methodResponse = input.readByteArray(2)
        require(methodResponse[0].toInt() == 0x05) { "Invalid SOCKS version in method response" }
        require(methodResponse[1].toInt() == 0x00) { "SOCKS server rejected no-auth method" }

        val hostBytes = target.onionAddress.encodeToByteArray()
        require(hostBytes.size <= 255) { "Target onion host is too long" }

        output.writeByte(0x05)
        output.writeByte(0x01)
        output.writeByte(0x00)
        output.writeByte(0x03)
        output.writeByte(hostBytes.size.toByte())
        output.writeFully(hostBytes)
        output.writeByte(((target.port ushr 8) and 0xff).toByte())
        output.writeByte((target.port and 0xff).toByte())
        output.flush()

        val header = input.readByteArray(4)
        require(header[0].toInt() == 0x05) { "Invalid SOCKS version in connect response" }
        if (header[1].toInt() != 0x00) {
            throw SocksConnectException(header[1].toInt())
        }

        val addressLength = when (header[3].toInt()) {
            0x01 -> 4
            0x03 -> input.readUnsignedByte()
            0x04 -> 16
            else -> error("Unsupported SOCKS bind address type ${header[3].toInt()}")
        }

        input.readExact(addressLength + 2)
    }

    private fun ASocket.safeClose() {
        runCatching { close() }
    }

    private fun SelectorManager.safeClose() {
        runCatching { close() }
    }

    private class SocksConnectException(val code: Int) : RuntimeException()

    private fun Path.toKmpFile(): File = File(toString())

    companion object {
        /** SOCKS host-unreachable: the target onion has no published descriptor (yet). */
        const val NOT_PUBLISHED_SOCKS_CODE: Int = 4

        /** Raw 64-byte ed25519 private key for the stable hidden service, under torStateRoot. */
        const val ONION_KEY_FILE: String = "onion_v3.key"

        /** Tag grouping our HS_DESC control observer (non-static: removable). */
        const val PUBLISH_OBSERVER_TAG: String = "yapyap-publish"
    }
}

/**
 * Returns the onion address from an `HS_DESC UPLOADED <onion> …` control line,
 * or null for anything else (other actions, malformed lines). Accepts the line
 * with or without the leading `650 HS_DESC` prefix.
 */
internal fun hsDescUploadedOnion(data: String): String? {
    val tokens = data.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
    val i = tokens.indexOf("HS_DESC")
    val action: String
    val onion: String
    if (i >= 0) {
        if (i + 2 >= tokens.size) return null
        action = tokens[i + 1]
        onion = tokens[i + 2]
    } else {
        if (tokens.size < 2) return null
        action = tokens[0]
        onion = tokens[1]
    }
    if (!action.equals("UPLOADED", ignoreCase = true)) return null
    return onion
}

/** Onion equality ignoring case and a present/absent `.onion` suffix. */
internal fun onionMatches(a: String, b: String): Boolean {
    fun norm(s: String): String {
        var v = s.trim().lowercase()
        if (v.endsWith(".onion")) v = v.dropLast(".onion".length)
        return v
    }

    val na = norm(a)
    return na.isNotEmpty() && na == norm(b)
}
