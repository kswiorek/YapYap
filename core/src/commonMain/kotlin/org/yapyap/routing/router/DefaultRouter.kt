package org.yapyap.routing.router

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PacketType
import org.yapyap.routing.bootstrap.BootstrapService
import org.yapyap.routing.file.FileService
import org.yapyap.routing.file.port.FileCarrier
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.inbound.InboundEnvelopeProcessor
import org.yapyap.routing.inbound.SignalInboundHandler
import org.yapyap.routing.inbound.SystemInboundHandler
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.message.MessageService
import org.yapyap.routing.message.port.FrontierSnapshotProvider
import org.yapyap.routing.message.port.SyncPayloadProvider
import org.yapyap.routing.outbound.EnvelopeDispatcher
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.SystemSender
import org.yapyap.routing.outbound.WebRtcSignaler
import org.yapyap.routing.policy.DefaultRelaySelectionPolicy
import org.yapyap.routing.policy.DefaultSyncPeerPolicy
import org.yapyap.routing.policy.LinkPreferencePolicy
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.presence.PeerAvailabilityRegistry
import org.yapyap.routing.presence.PingProvider
import org.yapyap.routing.presence.PresenceService
import org.yapyap.routing.store.FileIncomingStore
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.FileRequestAttemptStore
import org.yapyap.routing.store.FileSealStore
import org.yapyap.routing.store.FileTransferStore
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.PacketDeduplicator
import org.yapyap.routing.store.PacketOutbox
import org.yapyap.routing.store.PeerAvailabilityStore
import org.yapyap.routing.store.SyncAttemptStore
import org.yapyap.transport.tor.TorSessionState
import org.yapyap.transport.tor.backend.TorHealth
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The routing layer's composition root and lifecycle. Builds the shared kernel (links, outbox,
 * inbound pipeline, policies), the four services, and starts/stops them in dependency order.
 * Everything concern-specific lives in its service; everything here serves at least two.
 *
 * Takes the ambient world ([RoutingContext], built by the composition root) plus the stores
 * routing owns and the ports the orchestrator implements. The assembly stays here, not in
 * wiring: it is routing's own knowledge (which kernel parts each service uses, how outcomes and
 * link reactions are routed). The kernel is built first and the services on top of it; nothing
 * in the kernel refers back to a service.
 */
internal class DefaultRouter(
    /** Who we are, the device world, transports and live links, sealing, time and rules. */
    private val ctx: RoutingContext,
    val transportPolicy: OutboundPolicy = LinkPreferencePolicy(ctx.routerConfig),
    // Routing-owned stores crossing in from the DB.
    private val routes: DeviceRouteStore,
    private val packetDeduplicator: PacketDeduplicator,
    private val packetOutbox: PacketOutbox,
    private val peerAvailabilityStore: PeerAvailabilityStore,
    private val syncAttemptStore: SyncAttemptStore,
    // Content the orchestrator supplies to the message pull path and pings.
    syncPayloadProvider: SyncPayloadProvider,
    private val frontierSnapshotProvider: FrontierSnapshotProvider,
    // File orchestrator ports (content out, content in, request/serve policy).
    fileIngest: FileIngestPort,
    fileRequests: FileRequestSource,
    filePlaintext: FilePlaintextSource,
    // File stores routing owns.
    fileSealStore: FileSealStore,
    fileTransferStore: FileTransferStore,
    fileIncomingStore: FileIncomingStore,
    fileRelayStore: FileRelayStore,
    fileRequestAttemptStore: FileRequestAttemptStore,
) : Router {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycle = RouterLifecycle()

    private val retryLoopMaxIdlePollSeconds: StateFlow<Duration> = ctx.routerConfig
        .map { it.retryLoopMaxIdlePoll }
        .stateIn(scope, SharingStarted.Eagerly, ctx.routerConfig.value.retryLoopMaxIdlePoll)

    // --- kernel -------------------------------------------------------------------------

    private val envelopeDispatcher = EnvelopeDispatcher(ctx, routes)
    private val systemSender = SystemSender(ctx, transportPolicy, envelopeDispatcher, packetDeduplicator)

    private val peerAvailabilityRegistry = PeerAvailabilityRegistry(
        clock = ctx.clock,
        routerConfig = ctx.routerConfig,
        store = peerAvailabilityStore,
    )

    private val pingProvider = PingProvider(
        ctx = ctx,
        config = ctx.routerConfig,
        frontierSnapshotProvider = frontierSnapshotProvider,
        systemSender = systemSender,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
    )

    /**
     * Keeps a Tor link open to every active peer, so sends, pings and replies ride an open
     * stream instead of dialing. Its WebRTC counterpart ([webRtcLinkKeeper]) is the same class
     * with a guarded target set.
     */
    private val torLinkKeeper: LinkKeeper = LinkKeeper(
        name = "Tor",
        sessions = ctx.peerLinks.tor,
        targets = { ctx.devices.getAllActiveDeviceIds().filter { it != ctx.localDeviceId } },
        ready = ctx.torTransport.health.map { it == TorHealth.Ready },
        config = ctx.routerConfig
            .map { it.torLinkKeeperConfig }
            .stateIn(scope, SharingStarted.Eagerly, ctx.routerConfig.value.torLinkKeeperConfig),
        clock = ctx.clock,
        onOpened = { peer ->
            // Their app accepted our stream: they're up. The ping carries our frontiers
            // (sync on connect) and lets them adopt this stream as their link to us.
            peerAvailabilityRegistry.markReachable(peer, ctx.clock.now())
            pingProvider.pingPeer(peer)
        },
        onFailed = { peer, state ->
            peerAvailabilityRegistry.noteProbeAttempted(peer)
            // Not found, yet the peer is online (it reached us some other way): Tor's cached
            // miss is stale. Drop it (throttled; global) and retry soon instead of backing off.
            val staleMiss = state?.reason == TorSessionState.REASON_ONION_NOT_FOUND &&
                    peerAvailabilityRegistry.isOnline(peer) &&
                    ctx.torTransport.refreshOnionLookups()
            if (staleMiss) ctx.routerConfig.value.onionRefreshRetryDelay else null
        },
    )

    /**
     * The WebRTC counterpart of [torLinkKeeper]: same mechanism, different policy. An open is
     * expensive (Tor signaling round-trips plus ICE), so a peer becomes a target only when it is
     * surely online and we expect traffic — it sent us something within the freshness window.
     * Explicit intent (a large fetch, a call) bypasses the gate through [LinkKeeper.await].
     * There is no idle close: each transport owns its sessions' lifetime.
     */
    private val webRtcLinkKeeper: LinkKeeper = LinkKeeper(
        name = "WebRTC",
        sessions = ctx.webRtcTransport,
        targets = {
            val now = ctx.clock.now()
            val freshness = ctx.routerConfig.value.proactiveSessionFreshness
            ctx.devices.getAllActiveDeviceIds().filter { peer ->
                peer != ctx.localDeviceId &&
                        peerAvailabilityRegistry.isOnline(peer) &&
                        peerAvailabilityRegistry.lastSeen(peer)?.let { now - it < freshness } == true
            }
        },
        ready = flowOf(true), //TODO: webrtc health
        config = ctx.routerConfig
            .map { it.webrtcLinkKeeperConfig }
            .stateIn(scope, SharingStarted.Eagerly, ctx.routerConfig.value.webrtcLinkKeeperConfig),
        clock = ctx.clock,
    )
    private val relaySelectionPolicy = DefaultRelaySelectionPolicy(
        ctx = ctx,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
        routerConfig = ctx.routerConfig,
    )
    private val peerPolicy = DefaultSyncPeerPolicy(ctx)

    private val outboxProcessor: OutboxProcessor = OutboxProcessor(
        ctx = ctx,
        dispatcher = envelopeDispatcher,
        transportPolicy = transportPolicy,
        packetOutbox = packetOutbox,
        maxIdlePoll = retryLoopMaxIdlePollSeconds,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
        linkKeeper = torLinkKeeper,
        relaySelection = relaySelectionPolicy,
    )

    private val messageService = MessageService(
        ctx = ctx,
        lifecycle = lifecycle,
        outboxProcessor = outboxProcessor,
        systemSender = systemSender,
        syncAttemptStore = syncAttemptStore,
        syncProvider = syncPayloadProvider,
        peerPolicy = peerPolicy,
        maxIdlePoll = retryLoopMaxIdlePollSeconds,
        torLinkKeeper = torLinkKeeper,
        webRtcLinkKeeper = webRtcLinkKeeper,
        frontiers = pingProvider.frontiers,
    )

    private val bootstrapService = BootstrapService(
        ctx = ctx,
        lifecycle = lifecycle,
        outboxProcessor = outboxProcessor,
    )
    //TODO: consider webrtc link keeper
    private val presenceService = PresenceService(
        ctx = ctx,
        lifecycle = lifecycle,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
        peerAvailabilityStore = peerAvailabilityStore,
        linkKeeper = torLinkKeeper,
        pingProvider = pingProvider,
        scope = scope,
    )

    private val fileService = FileService(
        ctx = ctx,
        lifecycle = lifecycle,
        plaintext = filePlaintext,
        ingest = fileIngest,
        requests = fileRequests,
        sealStore = fileSealStore,
        transferStore = fileTransferStore,
        incomingStore = fileIncomingStore,
        relayStore = fileRelayStore,
        requestAttempts = fileRequestAttemptStore,
        outboxProcessor = outboxProcessor,
        packetOutbox = packetOutbox,
        relaySelection = relaySelectionPolicy,
        peerPolicy = peerPolicy,
        webRtcLinkKeeper = webRtcLinkKeeper,
        torLinkKeeper = torLinkKeeper,
        maxIdlePoll = retryLoopMaxIdlePollSeconds,
    )

    private val webRtcSignaler = WebRtcSignaler(ctx, envelopeDispatcher)

    private val inboundEnvelopeProcessor = InboundEnvelopeProcessor(
        ctx = ctx,
        systemSender = systemSender,
        routes = routes,
        packetDeduplicator = packetDeduplicator,
        handlers = mapOf(
            PacketType.MESSAGE to messageService.inboundHandler,
            PacketType.SIGNAL to SignalInboundHandler(ctx),
            PacketType.FILE to fileService.inboundHandler,
            PacketType.SYSTEM to SystemInboundHandler(messageService),
            PacketType.BOOTSTRAP to bootstrapService.inboundHandler,
        ),
        outboxProcessor = outboxProcessor,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
        pingProvider = pingProvider,
    )

    private var started = false

    private var torIncomingJob: Job? = null
    private var webRtcIncomingEnvelopeJob: Job? = null
    private var webRtcSignalerJob: Job? = null
    private var torLinkKeeperJob: Job? = null
    private var webRtcLinkKeeperJob: Job? = null
    private var outboxRetryJob: Job? = null
    private var outboxOutcomeJob: Job? = null
    private var serviceJobs: List<Job> = emptyList()

    // --- services -----------------------------------------------------------------------

    override val messages: MessageCarrier get() = messageService
    override val bootstrap: BootstrapCarrier get() = bootstrapService
    override val presence: Presence get() = presenceService
    override val files: FileCarrier get() = fileService

    // --- lifecycle ----------------------------------------------------------------------

    override suspend fun start() {
        check(!started) { "Router is already started" }
        val torEndpoint = try {
            val endpoint = ctx.torTransport.start()
            ctx.webRtcTransport.start(ctx.localDeviceId)
            endpoint
        } catch (e: Exception) {
            ctx.webRtcTransport.stop()
            ctx.torTransport.stop()
            throw e
        }
        routes.upsertPeerTorEndpoint(deviceId = ctx.localDeviceId, torEndpoint = torEndpoint)
        lifecycle.markStarted()

        inboundEnvelopeProcessor.startDispositions(scope)
        torIncomingJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ctx.torTransport.incoming.collect { inbound ->
                runCatching { inboundEnvelopeProcessor.handleTorInbound(inbound) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        AppLog.error(
                            component = LogComponent.ROUTER,
                            event = LogEvent.ENVELOPE_HANDLE_FAILED,
                            message = "Failed to handle inbound Tor envelope",
                            fields = mapOf("error" to e.toString()),
                        )
                    }
            }
        }

        webRtcIncomingEnvelopeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ctx.webRtcTransport.incomingEnvelopes.collect { inbound ->
                runCatching { inboundEnvelopeProcessor.handleWebRtcInbound(inbound) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        AppLog.error(
                            component = LogComponent.ROUTER,
                            event = LogEvent.ENVELOPE_HANDLE_FAILED,
                            message = "Failed to handle inbound WebRTC envelope",
                            fields = mapOf("error" to e.toString()),
                        )
                    }
            }
        }

        webRtcSignalerJob = webRtcSignaler.runIn(scope)

        // Before the outbox runs: outcomes are not replayed. (The outbox subscribes to its own
        // link/online inputs in its runIn.)
        outboxOutcomeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            outboxProcessor.outcomes.collect { outcome ->
                runCatching { routeOutcome(outcome) }.onFailure { e ->
                    if (e is CancellationException) throw e
                    AppLog.error(
                        component = LogComponent.ROUTER,
                        event = LogEvent.OUTBOX_PROCESS_FAILED,
                        message = "Failed to route an outbox outcome",
                        fields = mapOf("tag" to outcome.tag, "error" to e.toString()),
                    )
                }
            }
        }
        outboxRetryJob = outboxProcessor.runIn(scope)

        serviceJobs = messageService.runIn(scope) + bootstrapService.runIn(scope) + fileService.runIn(scope)

        pingProvider.runIn(scope)
        peerAvailabilityRegistry.start(scope, ctx.localDeviceId)
        torLinkKeeperJob = torLinkKeeper.runIn(scope)
        webRtcLinkKeeperJob = webRtcLinkKeeper.runIn(scope)

        AppLog.info(
            component = LogComponent.ROUTER,
            event = LogEvent.STARTED,
            message = "Router started",
            fields = mapOf("torEndpoint" to torEndpoint.toString()),
        )
        started = true
    }

    override suspend fun stop() {
        if (!started) return
        val lastEndpoint = ctx.torTransport.localTorEndpoint
        lifecycle.markStopped()
        inboundEnvelopeProcessor.stopDispositions()

        torLinkKeeper.stop()
        webRtcLinkKeeper.stop()
        pingProvider.stop()
        peerAvailabilityRegistry.stop()
        // Bounded: logOff only goes to linked peers, but a stalled stream write
        // must still never burn the shutdown budget (and the UI-thread wait above
        // it) and starve the Tor stop below, orphaning the daemon. Peers expire
        // us via sweep regardless.
        runCatching {
            withTimeoutOrNull(8.seconds) { pingProvider.logOff() }
        }

        // Best-effort per transport: a failing WebRTC stop must never skip the
        // Tor stop and strand a live daemon (its data-dir lock breaks the next
        // launch). Mirrors the teardown discipline in enterResetRequired.
        runCatching { ctx.webRtcTransport.stop() }.onFailure { error ->
            AppLog.error(
                component = LogComponent.ROUTER,
                event = LogEvent.SESSION_FAILED,
                message = "WebRTC transport stop failed; continuing with Tor stop",
                throwable = error,
            )
        }
        runCatching { ctx.torTransport.stop() }.onFailure { error ->
            AppLog.error(
                component = LogComponent.ROUTER,
                event = LogEvent.SESSION_FAILED,
                message = "Tor transport stop failed",
                throwable = error,
            )
        }

        listOfNotNull(
            torIncomingJob,
            webRtcIncomingEnvelopeJob,
            webRtcSignalerJob,
            torLinkKeeperJob,
            webRtcLinkKeeperJob,
            outboxRetryJob,
            outboxOutcomeJob,
        ).plus(serviceJobs).forEach { it.cancelAndJoin() }

        torIncomingJob = null
        webRtcIncomingEnvelopeJob = null
        webRtcSignalerJob = null
        torLinkKeeperJob = null
        webRtcLinkKeeperJob = null
        outboxRetryJob = null
        outboxOutcomeJob = null
        serviceJobs = emptyList()
        scope.cancel()

        AppLog.info(
            component = LogComponent.ROUTER,
            event = LogEvent.STOPPED,
            message = "Router stopped",
            fields = mapOf("torEndpoint" to lastEndpoint.toString()),
        )
        started = false
    }

    override fun isRunning(): Boolean = lifecycle.isRunning

    /**
     * Each tagged outcome goes to the service that owns its kind — a mailbox hand-off, never a
     * blocking switch: the services consume in their own loops.
     */
    private fun routeOutcome(outcome: OutboxOutcome) {
        when (outcome.tag.kind) {
            OutboxTag.Kind.MESSAGE, OutboxTag.Kind.SYNC -> messageService.offerOutcome(outcome)
            OutboxTag.Kind.BOOTSTRAP -> bootstrapService.offerOutcome(outcome)
            OutboxTag.Kind.FILE_TRANSFER, OutboxTag.Kind.FILE_REQUEST -> fileService.offerOutcome(outcome)
        }
    }
}
