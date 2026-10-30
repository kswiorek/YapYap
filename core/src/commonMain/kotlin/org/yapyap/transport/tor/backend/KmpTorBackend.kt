package org.yapyap.transport.tor.backend

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.matthewnelson.kmp.file.File
import io.matthewnelson.kmp.file.resolve
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.TorEndpoint
import org.yapyap.transport.TransportException
import org.yapyap.transport.network.NetworkMonitor
import org.yapyap.transport.network.NetworkState
import org.yapyap.transport.tor.TorIncomingFrame
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.ComparableTimeMark
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
    /**
     * Device network observation. Null means none (kmp-tor's no-op observer:
     * always "connected", no reaction to switches or sleep).
     */
    private val networkMonitor: NetworkMonitor? = null,
) : TorBackend, TorRecoveryControl {

    private val inboundFlow = MutableSharedFlow<TorIncomingFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val incomingFrames: Flow<TorIncomingFrame> = inboundFlow.asSharedFlow()

    private val _publishState = MutableStateFlow(TorPublishState.PUBLISHING)
    override val publishState: StateFlow<TorPublishState> = _publishState.asStateFlow()

    private val _health = MutableStateFlow<TorHealth>(TorHealth.Stopped)
    override val health: StateFlow<TorHealth> = _health.asStateFlow()

    private val _recovery = MutableStateFlow<TorRecoveryStep?>(null)
    override val recovery: StateFlow<TorRecoveryStep?> = _recovery.asStateFlow()

    /**
     * Serializes everything that reconfigures or restarts the daemon (recovery
     * steps, network-change reactions): interleaving a DisableNetwork cycle with
     * a restart would leave Tor in whichever state wrote last.
     */
    private val daemonMutex = Mutex()

    /** Unanswered descriptor fetches, per onion (ours for the self-dial probe, peers' for sends). */
    private val descriptorFetches = DescriptorFetchTracker()

    /** When Tor's onion lookups were last dropped (NEWNYM), for throttling [refreshOnionLookups]. */
    private var lastLookupRefresh: TimeSource.Monotonic.ValueTimeMark? = null

    private var socksPort: Int? = null

    var publishedLocalEndpoint: TorEndpoint? = null
        private set

    private var torRuntime: TorRuntime? = null
    private var acceptSocket: ServerSocket? = null
    private var selectorManager: SelectorManager? = null
    private var scope: CoroutineScope? = null
    private var verifierJob: Job? = null
    private var publishTimersJob: Job? = null
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
        _health.value = TorHealth.Starting
        runtime.subscribe(*instanceObservers)

        val localScope = CoroutineScope(SupervisorJob() + selectorContext)
        scope = localScope

        try {
            val onionReused = bringUpDaemon(runtime, listener)
            val resolvedEndpoint = checkNotNull(publishedLocalEndpoint)
            val onionAddress = resolvedEndpoint.onionAddress

            networkMonitor?.let { monitor -> watchNetwork(monitor, localScope) }
            startSupervisor(localScope)

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

    /**
     * Starts the daemon (or starts it again after a stop) up to "onion added":
     * waits only for the SOCKS listener, never for bootstrap. Returns whether
     * the persisted onion key was reused.
     */
    private suspend fun bringUpDaemon(runtime: TorRuntime, listener: ServerSocket): Boolean {
        runtime.startDaemonAsync()
        waitForSocksListener(runtime)
        seedBootstrapStatus(runtime)
        if (networkMonitor?.state?.value?.online == false) updateHealth { TorHealth.NoNetwork }

        // Stable onion identity: the whole sponsor flow assumes the onion in a
        // newcomer's QR still resolves when the sponsor dials minutes later.
        // ADD_ONION with a fresh key is ephemeral (new onion every restart),
        // so persist the private key under torStateRoot and re-add it.
        val (onionEntry, onionReused) = addStableOnion(runtime, listener)
        val onionAddress = onionEntry.publicKey.address().toString().let { raw ->
            if (raw.endsWith(".onion", ignoreCase = true)) raw else "$raw.onion"
        }
        publishedLocalEndpoint = TorEndpoint(onionAddress = onionAddress, port = config.value.defaultTorPort)
        restartPublishVerification()
        return onionReused
    }

    private fun startSupervisor(owner: CoroutineScope) {
        val online: StateFlow<Boolean> = networkMonitor
            ?.state
            ?.map { it.online }
            ?.stateIn(owner, SharingStarted.Eagerly, networkMonitor.state.value.online)
            ?: MutableStateFlow(true)
        val supervisor = TorSupervisor(
            control = this,
            networkOnline = online,
            config = config,
            guardResets = FileGuardResetRecord(Path(torStateRootPath, GUARD_RESET_FILE)),
        )
        owner.launch { supervisor.recovery.collect { _recovery.value = it } }
        owner.launch { supervisor.run() }
    }

    override suspend fun perform(step: TorRecoveryStep) {
        val runtime = torRuntime ?: return
        daemonMutex.withLock {
            when (step) {
                TorRecoveryStep.CYCLE_NETWORK -> {
                    if (networkMonitor?.state?.value?.online == false) return
                    withContext(NonCancellable) { cycleNetwork(runtime) }
                    signalNewNym()
                    restartPublishVerification(probeWhenReady = true)
                }

                TorRecoveryStep.RESTART_DAEMON -> restartDaemon(runtime, step) {}
                TorRecoveryStep.PURGE_DIRECTORY_CACHE -> restartDaemon(runtime, step) {
                    deleteTorFiles(Path(torStateRootPath, CACHE_DIR)) { it.startsWith("cached-") }
                }

                TorRecoveryStep.RESET_GUARD_STATE -> restartDaemon(runtime, step) {
                    deleteTorFiles(Path(torStateRootPath, DATA_DIR)) { it == GUARD_STATE_FILE }
                }
            }
        }
    }

    /**
     * Stops the daemon, runs [whileStopped] (file cleanup), and brings it back
     * up with the same onion. On failure health stays [TorHealth.Starting]
     * without progress, so the supervisor escalates again after its stall
     * timeout instead of giving up.
     */
    private suspend fun restartDaemon(runtime: TorRuntime, step: TorRecoveryStep, whileStopped: () -> Unit) {
        val listener = acceptSocket ?: return
        withContext(NonCancellable) { runCatching { runtime.stopDaemonAsync() } }
        socksPort = null
        _health.value = TorHealth.Starting
        runCatching { whileStopped() }.onFailure { error ->
            AppLog.warn(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.TOR_DAEMON_WARNING,
                message = "Tor recovery cleanup failed; restarting anyway",
                fields = mapOf("step" to step, "error" to error.toString()),
            )
        }
        runCatching { bringUpDaemon(runtime, listener) }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLog.error(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.SESSION_FAILED,
                message = "Tor restart during recovery failed",
                throwable = error,
                fields = mapOf("step" to step),
            )
        }
    }

    /** Deletes regular files directly in [dir] whose name matches. Missing dir is fine. */
    private fun deleteTorFiles(dir: Path, nameMatches: (String) -> Boolean) {
        if (!SystemFileSystem.exists(dir)) return
        SystemFileSystem.list(dir)
            .filter { nameMatches(it.name) && SystemFileSystem.metadataOrNull(it)?.isRegularFile == true }
            .forEach { SystemFileSystem.delete(it, mustExist = false) }
    }

    override suspend fun recheckReachability() {
        if (_publishState.value != TorPublishState.PUBLISHED) return
        repeat(RECHECK_ATTEMPTS) { attempt ->
            when (checkSelfReachability()) {
                ProbeOutcome.REACHABLE -> return
                ProbeOutcome.NOT_FOUND, ProbeOutcome.FETCH_STALLED, ProbeOutcome.FAILED -> signalNewNym()
                ProbeOutcome.TIMED_OUT -> if (attempt + 1 >= TIMEOUTS_BEFORE_REFRESH) signalNewNym()
            }
            if (attempt < RECHECK_ATTEMPTS - 1) delay(config.value.probeRefreshRetryDelay)
        }
        // Still claiming PUBLISHED with nobody able to dial us is the worst state:
        // say so, and let the supervisor act on it.
        if (_publishState.value == TorPublishState.PUBLISHED) {
            _publishState.value = TorPublishState.DEGRADED
            AppLog.warn(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.SESSION_FAILED,
                message = "Periodic self-dial failed; our onion no longer looks reachable",
                fields = mapOf("onionAddress" to publishedLocalEndpoint?.onionAddress),
            )
        }
    }

    /**
     * (Re)starts proving our onion is dialable: PUBLISHING until a self-dial
     * succeeds. Every boot re-uploads the descriptor, and so does Tor after a
     * network switch (new intro circuits), so both re-warm through here.
     * PUBLISHED only latches on a successful self-dial (see the verifier); the
     * watchdog flips to DEGRADED past the budget. Both timers count from
     * bootstrap completion, not from the call: a slow bootstrap must not burn
     * the publish budget before Tor can even upload. Everything dies with the
     * backend scope on stop().
     */
    private fun restartPublishVerification(probeWhenReady: Boolean = false) {
        val owner = scope ?: return
        val onionAddress = publishedLocalEndpoint?.onionAddress ?: return
        publishTimersJob?.cancel()
        verifierJob?.cancel()
        _publishState.value = TorPublishState.PUBLISHING
        publishTimersJob = owner.launch {
            launch {
                health.first { it == TorHealth.Ready }
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
            // After a network change Tor often keeps its still-valid descriptor
            // and uploads nothing, so there is no UPLOADED to wait for: probe as
            // soon as Tor is usable again instead of after the grace period.
            if (probeWhenReady) {
                launch {
                    health.first { it == TorHealth.Ready }
                    maybeStartVerifier()
                }
            }
            // Backstop for a missed/late UPLOADED event (or a daemon that never
            // emits one): verify reachability directly after a grace period even
            // if no upload was ever observed. The probe is ground truth; the
            // event is only the fast path.
            launch {
                health.first { it == TorHealth.Ready }
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
        }
    }

    /**
     * Reacts to network switches and wake-ups. Going offline/online is handled
     * by kmp-tor through [TorNetworkObserver]; a switch or a wake-up keeps the
     * device "online", so nothing would tell Tor its connections (opened from
     * the old address, or silently dropped while asleep) are dead. Tor would
     * find out only through timeouts and guard backoff — the "stuck at 5%"
     * after moving networks.
     */
    private fun watchNetwork(monitor: NetworkMonitor, owner: CoroutineScope) {
        val startGeneration = monitor.state.value.generation
        owner.launch {
            monitor.state
                .filter { it.generation > startGeneration }
                .distinctUntilChangedBy { it.generation }
                .collectLatest { state -> onNetworkChanged(monitor, state) }
        }
    }

    private suspend fun onNetworkChanged(monitor: NetworkMonitor, state: NetworkState) {
        val runtime = torRuntime ?: return
        AppLog.info(
            component = LogComponent.TOR_BACKEND,
            event = LogEvent.NETWORK_CHANGED,
            message = "Network changed; refreshing Tor",
            fields = mapOf("change" to state.change, "online" to state.online, "generation" to state.generation),
        )
        // Offline: kmp-tor already disables Tor's network; there is nothing to refresh yet.
        if (!state.online) {
            updateHealth { TorHealth.NoNetwork }
            return
        }
        daemonMutex.withLock {
            // NonCancellable: a newer change cancels this handler (collectLatest),
            // and stopping between the two writes would leave Tor's network disabled.
            withContext(NonCancellable) { cycleNetwork(runtime, monitor) }
        }
        // Cached descriptor lookups and our intro circuits belong to the old network.
        signalNewNym()
        restartPublishVerification(probeWhenReady = true)
    }

    /**
     * Cycles DisableNetwork: drops every connection (made from an old address,
     * or dead after sleep) and makes Tor retry its guards now instead of after
     * backoff. Then follows the SOCKS listener to its new port and lets Tor's own
     * events drive health again.
     */
    private suspend fun cycleNetwork(runtime: TorRuntime, monitor: NetworkMonitor? = networkMonitor) {
        setNetworkDisabled(runtime, true)
        // Re-check: if the device went offline meanwhile, leave it disabled
        // (kmp-tor's own Disconnected may have landed before our write).
        if (monitor != null && !monitor.state.value.online) return
        setNetworkDisabled(runtime, false)
        // Re-enabling reopens the SOCKS listener on a new auto port.
        withTimeoutOrNull(SOCKS_REOPEN_TIMEOUT) {
            while (true) {
                querySocksPort(runtime)?.let { socksPort = it; return@withTimeoutOrNull }
                delay(150.milliseconds)
            }
        }
        // Back from NoNetwork: Tor's own events take over again, starting
        // from wherever its bootstrap actually is (often still 100%).
        updateHealth { if (it == TorHealth.NoNetwork) TorHealth.Starting else it }
        seedBootstrapStatus(runtime)
    }

    private suspend fun setNetworkDisabled(runtime: TorRuntime, disabled: Boolean) {
        runCatching {
            runtime.executeAsync(TorCmd.Config.Set(TorOption.DisableNetwork.asSetting(disabled)))
        }.onFailure { error ->
            AppLog.warn(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.TOR_DAEMON_WARNING,
                message = "Failed to set DisableNetwork",
                fields = mapOf("disabled" to disabled, "error" to error.toString()),
            )
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
            runtime.unsubscribe(*instanceObservers)
            // NonCancellable: stop() also runs from start()'s failure path, where
            // the caller is often already cancelled (a cancelled boot). There a
            // plain suspend call throws at once and leaves the daemon running
            // inside our process, holding the data directory.
            withContext(NonCancellable) {
                runCatching { runtime.stopDaemonAsync() }
            }
        }
        torRuntime = null

        publishedLocalEndpoint = null
        socksPort = null
        started = false
        _health.value = TorHealth.Stopped
        _recovery.value = null
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
        var lastSocksCode: Int? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            // Enforce the budget per attempt, not just between them: a single
            // SOCKS dial may legitimately take up to socksSocketTimeout (Tor
            // answers only once rendezvous is built or definitively failed),
            // and without this cap one stalled read would sail past the budget.
            // External cancellation still propagates as CancellationException;
            // only our own timeout surfaces as budget exhaustion below.
            val remaining = deadline - TimeSource.Monotonic.markNow()
            if (remaining <= Duration.ZERO) throw budgetExhausted(target, lastSocksCode)
            try {
                withTimeout(remaining) {
                    sendWatchingFetch(target, payload, configSnapshot)
                }
                return
            } catch (_: TimeoutCancellationException) {
                throw budgetExhausted(target, lastSocksCode)
            } catch (error: SocksConnectException) {
                attempts++
                lastSocksCode = error.code
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
                    if (error.code == NOT_PUBLISHED_SOCKS_CODE) {
                        throw TransportException.TorException.OnionNotFound(target.onionAddress)
                    }
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
            } catch (stall: FetchStalledException) {
                // A directory relay is hanging on the target's descriptor. Waiting
                // would hold this send for Tor's full two minutes; drop the lookups
                // (throttled: it's global) so the retry asks other relays.
                attempts++
                refreshAfterStalledFetch(target, stall.hsDir)
                retryTransientOrThrow(stall, "fetch-stalled", attempts, deadline, configSnapshot, target)
            } catch (moved: SocksPortMovedException) {
                // Not a Tor failure: retry on the new port at once (still within the budget).
                attempts++
                AppLog.info(
                    component = LogComponent.TOR_BACKEND,
                    event = LogEvent.TOR_DAEMON_NOTICE,
                    message = "Tor SOCKS listener moved; retrying on the new port",
                    fields = mapOf("from" to moved.from, "to" to moved.to),
                )
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
        val socket = try {
            aSocket(selector).tcp().connect("127.0.0.1", localSocksPort) {
                socketTimeout = configSnapshot.socksSocketTimeout.inWholeMilliseconds
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tor closes its SOCKS listener whenever DisableNetwork is set (going
            // offline, or our own cycle on a network switch) and reopens it on a
            // new auto port. Without this every later dial hits the dead port.
            val current = torRuntime?.let { querySocksPort(it) }
            if (current != null && current != localSocksPort) {
                socksPort = current
                throw SocksPortMovedException(localSocksPort, current)
            }
            throw e
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
            // Online/offline → DisableNetwork. Process-wide adapter: this
            // builder block only runs the first time per data directory.
            networkMonitor?.let { networkObserver = TorNetworkObserver.of(it) }
            config { _ ->
                TorOption.__SocksPort.configure { auto() }
                // Short keepalive so middleboxes/NATs with aggressive idle
                // timeouts don't silently kill our long-lived intro circuits
                // (a dead intro set looks exactly like "published but nobody
                // can dial us"). Default is 5 minutes.
                TorOption.KeepalivePeriod.configure(60, IntervalUnit.SECONDS)
            }
            // required() is what puts an event into the control connection's
            // SETEVENTS — an observer alone only registers the callback, and
            // without the event enabled nothing ever arrives (publishState
            // would sit at PUBLISHING forever). Callbacks must not throw.
            //
            // Observers registered here must be stateless (logging only):
            // kmp-tor returns this same runtime for the same directory for the
            // life of the process, so a builder observer bound to `this` would
            // keep feeding the first backend instance after an in-process
            // restart. Stateful ones live in [instanceObservers].
            required(TorEvent.HS_DESC)
            // kmp-tor's own runtime log: daemon launch, process exit and control
            // connection failures surface here, never as Tor control events.
            observer(RuntimeEvent.ERROR.observer(HEALTH_OBSERVER_TAG, null) { error ->
                runCatching {
                    AppLog.error(
                        component = LogComponent.TOR_BACKEND,
                        event = LogEvent.TOR_DAEMON_WARNING,
                        message = "Tor runtime error",
                        throwable = error,
                    )
                }
            })
            observer(RuntimeEvent.LOG.WARN.observer(HEALTH_OBSERVER_TAG, null) { line ->
                runCatching {
                    AppLog.warn(
                        component = LogComponent.TOR_BACKEND,
                        event = LogEvent.TOR_DAEMON_WARNING,
                        message = "Tor runtime warning",
                        fields = mapOf("line" to line),
                    )
                }
            })
            observer(RuntimeEvent.LOG.INFO.observer(HEALTH_OBSERVER_TAG, null) { line ->
                runCatching {
                    AppLog.debug(
                        component = LogComponent.TOR_BACKEND,
                        event = LogEvent.TOR_DAEMON_NOTICE,
                        message = "Tor runtime info",
                        fields = mapOf("line" to line),
                    )
                }
            })
            // Diagnostics: STATUS_CLIENT carries bootstrap progress and, when
            // stuck, Tor's reason (REASON/COUNT/HOSTADDR); STATUS_GENERAL clock
            // skew; NETWORK_LIVENESS whether Tor thinks the network is up; WARN/ERR
            // the daemon's own complaints. Without these a stuck bootstrap is a
            // silent "Bootstrapped 5%" and nothing else.
            required(TorEvent.STATUS_CLIENT)
            required(TorEvent.STATUS_GENERAL)
            observer(TorEvent.STATUS_GENERAL.observer(HEALTH_OBSERVER_TAG, null) { data ->
                runCatching { onStatusGeneralEvent(data) }
            })
            required(TorEvent.NETWORK_LIVENESS)
            required(TorEvent.WARN)
            observer(TorEvent.WARN.observer(HEALTH_OBSERVER_TAG, null) { data ->
                runCatching {
                    AppLog.warn(
                        component = LogComponent.TOR_BACKEND,
                        event = LogEvent.TOR_DAEMON_WARNING,
                        message = "Tor daemon warning",
                        fields = mapOf("line" to data),
                    )
                }
            })
            required(TorEvent.ERR)
            observer(TorEvent.ERR.observer(HEALTH_OBSERVER_TAG, null) { data ->
                runCatching {
                    AppLog.error(
                        component = LogComponent.TOR_BACKEND,
                        event = LogEvent.TOR_DAEMON_WARNING,
                        message = "Tor daemon error",
                        fields = mapOf("line" to data),
                    )
                }
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

    /**
     * Observers bound to this instance's state, subscribed in start() and
     * removed in stop() (see the builder note in [createTorRuntime]).
     */
    private val instanceObservers: Array<TorEvent.Observer> = arrayOf(
        TorEvent.HS_DESC.observer(PUBLISH_OBSERVER_TAG, null) { data ->
            runCatching { onHsDescEvent(data) }
        },
        TorEvent.STATUS_CLIENT.observer(HEALTH_OBSERVER_TAG, null) { data ->
            runCatching { onStatusClientEvent(data) }
        },
        TorEvent.NETWORK_LIVENESS.observer(HEALTH_OBSERVER_TAG, null) { data ->
            runCatching { onNetworkLivenessEvent(data) }
        },
    )

    private fun onStatusClientEvent(data: String) {
        AppLog.debug(
            component = LogComponent.TOR_BACKEND,
            event = LogEvent.TOR_DAEMON_NOTICE,
            message = "Tor client status event",
            fields = mapOf("line" to data),
        )
        val event = parseTorStatusEvent(data) ?: return
        updateHealth { it.afterBootstrapEvent(event) }
    }

    private fun onStatusGeneralEvent(data: String) {
        val event = parseTorStatusEvent(data)
        // CLOCK_SKEW and friends: a wrong clock makes Tor reject every consensus,
        // which looks exactly like a network that won't bootstrap.
        if (event != null && (event.severity == "WARN" || event.severity == "ERR")) {
            AppLog.warn(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.TOR_DAEMON_WARNING,
                message = "Tor general status warning",
                fields = mapOf("action" to event.action, "line" to data),
            )
        } else {
            AppLog.debug(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.TOR_DAEMON_NOTICE,
                message = "Tor general status event",
                fields = mapOf("line" to data),
            )
        }
    }

    private fun onNetworkLivenessEvent(data: String) {
        val up = parseNetworkLiveness(data) ?: return
        AppLog.info(
            component = LogComponent.TOR_BACKEND,
            event = LogEvent.TOR_DAEMON_NOTICE,
            message = "Tor network liveness changed",
            fields = mapOf("live" to up),
        )
        updateHealth { it.afterLiveness(up) }
    }

    /**
     * Applies [transform] unless stopped (late events after stop() must not
     * resurrect a dead backend) and logs real transitions. Bootstrap progress
     * steps are debug; a new stall reason is a warning; everything else info.
     */
    private fun updateHealth(transform: (TorHealth) -> TorHealth) {
        var previous: TorHealth = TorHealth.Stopped
        val next = _health.updateAndGet { current ->
            previous = current
            if (current == TorHealth.Stopped) current else transform(current)
        }
        if (next == previous) return
        val problem = (next as? TorHealth.Bootstrapping)?.problem
        val previousProblem = (previous as? TorHealth.Bootstrapping)?.problem
        val fields = buildMap<String, Any?> {
            put("health", next::class.simpleName)
            if (next is TorHealth.Bootstrapping) put("progress", next.progress)
            if (problem != null) {
                put("reason", problem.reason)
                put("warning", problem.warning)
                put("count", problem.count)
                put("hostAddr", problem.hostAddr)
            }
        }
        when {
            problem != null && problem != previousProblem -> AppLog.warn(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.TOR_HEALTH_CHANGED,
                message = "Tor bootstrap is stuck",
                fields = fields,
            )

            next is TorHealth.Bootstrapping && previous is TorHealth.Bootstrapping -> AppLog.debug(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.TOR_HEALTH_CHANGED,
                message = "Tor bootstrap progressed",
                fields = fields,
            )

            else -> AppLog.info(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.TOR_HEALTH_CHANGED,
                message = "Tor health changed",
                fields = fields,
            )
        }
    }

    /**
     * Seeds [health] from `GETINFO status/bootstrap-phase` in case bootstrap
     * events fired before our observers were attached (a warm restart can reach
     * 100% almost immediately). Only applies while still [TorHealth.Starting],
     * so it never rolls back state already advanced by a live event.
     */
    private suspend fun seedBootstrapStatus(runtime: TorRuntime) {
        val phase = runCatching {
            runtime.executeAsync(TorCmd.Info.Get(BOOTSTRAP_PHASE_KEY))[BOOTSTRAP_PHASE_KEY]
        }.getOrNull() ?: return
        val event = parseTorStatusEvent(phase) ?: return
        updateHealth { if (it == TorHealth.Starting) it.afterBootstrapEvent(event) else it }
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
        parseHsDescEvent(data)?.let { descriptorFetches.onEvent(it) }
        val local = publishedLocalEndpoint?.onionAddress ?: return
        val uploadedOnion = hsDescUploadedOnion(data) ?: return
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
            var consecutiveTimeouts = 0
            while (isActive) {
                val outcome = checkSelfReachability()
                if (outcome == ProbeOutcome.REACHABLE) {
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
                when (outcome) {
                    // NOT_FOUND: our lookup hit HSDirs that did not have the fresh
                    // descriptor yet; Tor caches that miss and won't re-ask them for
                    // minutes. FETCH_STALLED: a relay never answered our fetch. FAILED (Tor refused the stream): usually a cached
                    // descriptor whose intro points died — after a restart or a
                    // network cycle the service has new ones. Either way the next
                    // probe would fail the same way: drop Tor's cached lookups first
                    // (NEWNYM; Tor rate-limits it to one per 10 s).
                    ProbeOutcome.NOT_FOUND, ProbeOutcome.FETCH_STALLED, ProbeOutcome.FAILED -> {
                        consecutiveTimeouts = 0
                        signalNewNym()
                        delay(config.value.probeRefreshRetryDelay)
                    }
                    // A first timeout: Tor is likely still working on it (slow
                    // HSDir fetch or rendezvous right after bootstrap or a network
                    // change). Keep what it has fetched so far — a refresh would
                    // throw away a descriptor that may have just arrived.
                    ProbeOutcome.TIMED_OUT -> {
                        // The probe shares this Tor's client state, and a second
                        // timeout in a row means that state is stale (intro points
                        // remembered as failed while our service was rebuilding its
                        // circuits): refresh then.
                        if (++consecutiveTimeouts >= TIMEOUTS_BEFORE_REFRESH) {
                            consecutiveTimeouts = 0
                            signalNewNym()
                        }
                        delay(config.value.probeRefreshRetryDelay)
                    }

                    ProbeOutcome.REACHABLE -> Unit
                }
            }
        }
    }

    /**
     * Throttled [signalNewNym] for callers outside the backend's own recovery
     * (peer sends, the router): NEWNYM is global — every onion's cached lookups
     * go, and the next dial to each pays a fresh fetch — so at most once per
     * [TorBackendConfig.onionLookupRefreshMinInterval]. True when it refreshed now.
     */
    override suspend fun refreshOnionLookups(): Boolean {
        val last = lastLookupRefresh
        if (last != null && last.elapsedNow() < config.value.onionLookupRefreshMinInterval) return false
        return signalNewNym()
    }

    /** `SIGNAL NEWNYM`: drops Tor's cached onion descriptor lookups (and failed-lookup state). */
    private suspend fun signalNewNym(): Boolean {
        val runtime = torRuntime ?: return false
        return runCatching { runtime.executeAsync(TorCmd.Signal.NewNym) }
            .onSuccess {
                lastLookupRefresh = TimeSource.Monotonic.markNow()
                descriptorFetches.clear()
                AppLog.debug(
                    component = LogComponent.TOR_BACKEND,
                    event = LogEvent.TOR_DAEMON_NOTICE,
                    message = "Refreshed Tor onion lookups",
                )
            }
            .isSuccess
    }

    private enum class ProbeOutcome { REACHABLE, NOT_FOUND, FETCH_STALLED, TIMED_OUT, FAILED }

    /** The send + loop-back half of a probe; see [checkSelfReachability]. */
    private suspend fun selfDialOnce(
        local: TorEndpoint,
        nonce: ByteArray,
        payload: ByteArray,
        configSnapshot: TorBackendConfig,
    ): ProbeOutcome = coroutineScope {
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
            val socksCode = (sendError as? SocksConnectException)?.code
            val outcome =
                if (socksCode == NOT_PUBLISHED_SOCKS_CODE) ProbeOutcome.NOT_FOUND else ProbeOutcome.FAILED
            AppLog.debug(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.SESSION_FAILED,
                message = "Self-dial probe failed",
                fields = mapOf(
                    "outcome" to outcome,
                    "socksCode" to socksCode,
                    "error" to sendError.toString()
                ),
            )
            return@coroutineScope outcome
        }
        wait.await()
        ProbeOutcome.REACHABLE
    }

    /**
     * Returns (relay, waited) once a descriptor fetch of [onion] has gone
     * unanswered for [timeout], counting from no earlier than [since].
     */
    private suspend fun awaitFetchStall(
        onion: String,
        since: ComparableTimeMark,
        timeout: Duration,
    ): Pair<String, Duration> {
        while (true) {
            val stalled = descriptorFetches.oldestPending(onion, since)
            if (stalled != null && stalled.second >= timeout) {
                AppLog.debug(
                    component = LogComponent.TOR_BACKEND,
                    event = LogEvent.SESSION_FAILED,
                    message = "Descriptor fetch unanswered by a directory relay",
                    fields = mapOf("onion" to onion, "hsDir" to stalled.first, "waited" to stalled.second),
                )
                return stalled
            }
            delay(FETCH_STALL_POLL)
        }
    }

    /**
     * One send attempt that gives up early when Tor's descriptor fetch for the
     * target hangs (see [awaitFetchStall]); otherwise exactly [sendFrameOnce].
     */
    private suspend fun sendWatchingFetch(
        target: TorEndpoint,
        payload: ByteArray,
        configSnapshot: TorBackendConfig,
    ): Unit = coroutineScope {
        val since = descriptorFetches.now()
        val attempt = async {
            sendFrameOnce(target, payload, configSnapshot)
            null
        }
        val stalled = async { awaitFetchStall(target.onionAddress, since, configSnapshot.descriptorFetchStallTimeout) }
        val stall = select {
            attempt.onAwait { it }
            stalled.onAwait { it }
        }
        attempt.cancel()
        stalled.cancel()
        if (stall != null) throw FetchStalledException(stall.first)
    }

    /**
     * One self-dial round trip to our own onion: raw probe frame out through
     * SOCKS, matched looped-back frame in via [inboundFlow]. Single shot, no
     * inner retry (the verifier loop owns the cadence). Anything but
     * [ProbeOutcome.REACHABLE] only means "not proven this time", never
     * "unreachable"; the outcome tells the loop how to retry.
     */
    private suspend fun checkSelfReachability(): ProbeOutcome {
        val local = publishedLocalEndpoint ?: return ProbeOutcome.FAILED
        val configSnapshot = config.value
        val nonce = Random.nextBytes(TOR_PROBE_NONCE_SIZE)
        val payload = buildProbePayload(nonce)
        return try {
            withTimeout(configSnapshot.probeTimeout) {
                coroutineScope {
                    val since = descriptorFetches.now()
                    val attempt = async { selfDialOnce(local, nonce, payload, configSnapshot) }
                    // A directory relay that never answers our descriptor fetch holds the
                    // probe's stream until Tor's own two-minute timeout; end the probe
                    // early instead, so the caller can refresh lookups and try other relays.
                    val stalled = async {
                        awaitFetchStall(local.onionAddress, since, configSnapshot.descriptorFetchStallTimeout)
                        ProbeOutcome.FETCH_STALLED
                    }
                    val outcome = select {
                        attempt.onAwait { it }
                        stalled.onAwait { it }
                    }
                    attempt.cancel()
                    stalled.cancel()
                    outcome
                }
            }
        } catch (_: TimeoutCancellationException) {
            AppLog.debug(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.SESSION_FAILED,
                message = "Self-dial probe timed out",
                fields = mapOf("timeout" to configSnapshot.probeTimeout),
            )
            ProbeOutcome.TIMED_OUT
        } catch (e: CancellationException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            ProbeOutcome.FAILED
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

    /**
     * Waits only for the daemon's SOCKS listener (seconds), not for bootstrap:
     * bootstrap can legitimately take minutes on a new or hostile network, and
     * blocking start() on it turned every slow bootstrap into a failed app start.
     *
     * Asks Tor directly (`GETINFO net/listeners/socks`): kmp-tor's
     * `runtime.listeners()` stays empty until bootstrap reaches 100%, so polling
     * it was a hidden wait for full bootstrap under [TorBackendConfig.startupTimeout].
     */
    private suspend fun waitForSocksListener(runtime: TorRuntime) {
        val completed = withTimeoutOrNull(config.value.startupTimeout) {
            while (true) {
                socksPort = querySocksPort(runtime) ?: socksPort

                if (socksPort != null) return@withTimeoutOrNull

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

    /** Tor's current SOCKS port, or null when it has none open (e.g. DisableNetwork is set). */
    private suspend fun querySocksPort(runtime: TorRuntime): Int? = runCatching {
        socksPortFromListeners(runtime.executeAsync(TorCmd.Info.Get(SOCKS_LISTENERS_KEY))[SOCKS_LISTENERS_KEY])
    }.getOrNull()

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

    private class SocksPortMovedException(val from: Int, val to: Int) : RuntimeException()

    private class FetchStalledException(val hsDir: String) : RuntimeException("Descriptor fetch unanswered by $hsDir")

    private fun budgetExhausted(target: TorEndpoint, lastSocksCode: Int?): TransportException.TorException =
        if (lastSocksCode == NOT_PUBLISHED_SOCKS_CODE) {
            TransportException.TorException.OnionNotFound(target.onionAddress)
        } else {
            TransportException.TorException.SocksError("Tor send budget exhausted for ${target.onionAddress}")
        }

    private suspend fun refreshAfterStalledFetch(target: TorEndpoint, hsDir: String) {
        val refreshed = refreshOnionLookups()
        AppLog.info(
            component = LogComponent.TOR_BACKEND,
            event = LogEvent.TOR_DAEMON_NOTICE,
            message = if (refreshed) "Descriptor fetch stuck; refreshed Tor onion lookups" else "Descriptor fetch stuck; lookups refreshed recently, retrying",
            fields = mapOf("targetHost" to target.onionAddress, "hsDir" to hsDir),
        )
    }

    private fun Path.toKmpFile(): File = File(toString())

    companion object {
        /** SOCKS host-unreachable: the target onion has no published descriptor (yet). */
        const val NOT_PUBLISHED_SOCKS_CODE: Int = 4

        /** Raw 64-byte ed25519 private key for the stable hidden service, under torStateRoot. */
        const val ONION_KEY_FILE: String = "onion_v3.key"

        /** Tag grouping our HS_DESC control observer (non-static: removable). */
        const val PUBLISH_OBSERVER_TAG: String = "yapyap-publish"

        /** Tag grouping our health/diagnostics control observers. */
        const val HEALTH_OBSERVER_TAG: String = "yapyap-health"

        private const val BOOTSTRAP_PHASE_KEY: String = "status/bootstrap-phase"

        private const val SOCKS_LISTENERS_KEY: String = "net/listeners/socks"

        private val SOCKS_REOPEN_TIMEOUT: Duration = 10_000L.milliseconds

        /** How often a running probe checks for an unanswered descriptor fetch. */
        private val FETCH_STALL_POLL: Duration = 1_000L.milliseconds

        /** Consecutive self-dial timeouts after which Tor's cached onion lookups are dropped. */
        private const val TIMEOUTS_BEFORE_REFRESH: Int = 2

        /** Probes per periodic reachability re-check before declaring DEGRADED. */
        private const val RECHECK_ATTEMPTS: Int = 3

        // Tor's files under torStateRoot (kmp-tor layout: work/data is Tor's
        // DataDirectory, cache its CacheDirectory). The onion key lives beside
        // them, so no recovery step can change our address.
        private const val CACHE_DIR: String = "cache"
        private const val DATA_DIR: String = "work/data"
        private const val GUARD_STATE_FILE: String = "state"
        private const val GUARD_RESET_FILE: String = "guard_reset_at"
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

/**
 * First port from a `GETINFO net/listeners/socks` value: space-separated,
 * optionally quoted `host:port` entries (`"127.0.0.1:45651"`, `"[::1]:9050"`).
 * Null when Tor has no SOCKS listener yet.
 */
internal fun socksPortFromListeners(value: String?): Int? =
    value?.split(' ')
        ?.map { it.trim().trim('"') }
        ?.firstNotNullOfOrNull { it.substringAfterLast(':', "").toIntOrNull()?.takeIf { port -> port in 1..65535 } }

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
