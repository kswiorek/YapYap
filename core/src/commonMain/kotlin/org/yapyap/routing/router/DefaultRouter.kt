package org.yapyap.routing.router

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.yapyap.crypto.identity.LocalIdentity
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.directory.DeviceDirectory
import org.yapyap.routing.directory.DeviceRouteStore
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.inbound.InboundEnvelopeProcessor
import org.yapyap.routing.inbound.handlers.BootstrapInboundHandler
import org.yapyap.routing.inbound.handlers.FileInboundHandler
import org.yapyap.routing.inbound.handlers.MessageInboundHandler
import org.yapyap.routing.inbound.handlers.SignalInboundHandler
import org.yapyap.routing.inbound.handlers.SystemInboundHandler
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.link.LinkKeeperConfig
import org.yapyap.routing.outbound.BootstrapSender
import org.yapyap.routing.outbound.OutboundMessenger
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.ProactiveSessionOpener
import org.yapyap.routing.outbound.RelayDepositor
import org.yapyap.routing.outbound.SystemSender
import org.yapyap.routing.outbound.TypingIndicatorDispatcher
import org.yapyap.routing.outbound.WebRtcBootstrapSignaler
import org.yapyap.routing.ping.FrontierSnapshotProvider
import org.yapyap.routing.ping.PingProvider
import org.yapyap.routing.policy.DefaultRelaySelectionPolicy
import org.yapyap.routing.policy.DefaultSyncPeerPolicy
import org.yapyap.routing.policy.LinkPreferencePolicy
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.PacketDeduplicator
import org.yapyap.routing.store.PacketOutbox
import org.yapyap.routing.store.PeerAvailabilityStore
import org.yapyap.routing.store.SyncAttemptStore
import org.yapyap.routing.sync.SyncHandler
import org.yapyap.routing.sync.SyncPayloadProvider
import org.yapyap.routing.sync.SyncRetryProcessor
import org.yapyap.transport.tor.TorSessionState
import org.yapyap.transport.tor.backend.TorHealth
import org.yapyap.transport.tor.transport.TorTransport
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal class DefaultRouter(
    val torTransport: TorTransport,
    val webRtcTransport: WebRtcTransport,
    val localIdentity: LocalIdentity,
    val localDeviceId: PeerId,
    val devices: DeviceDirectory,
    val routes: DeviceRouteStore,
    val packetDeduplicator: PacketDeduplicator,
    val packetOutbox: PacketOutbox,
    val syncAttemptStore: SyncAttemptStore,
    val envelopeProtectionService: EnvelopeProtectionService,
    val clock: Clock = Clock.System,
    val routerConfig: StateFlow<RouterConfig>,
    val transportLimits: StateFlow<TransportLimits>,
    val transportPolicy: OutboundPolicy = LinkPreferencePolicy(routerConfig),
    val syncPayloadProvider: SyncPayloadProvider,
    val frontierSnapshotProvider: FrontierSnapshotProvider,
    val peerAvailabilityStore: PeerAvailabilityStore,
) : Router {


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val retryLoopMaxIdlePollSeconds: StateFlow<Duration> = routerConfig
        .map { it.retryLoopMaxIdlePoll }
        .stateIn(scope, SharingStarted.Eagerly, routerConfig.value.retryLoopMaxIdlePoll)


    private val routingContext = RoutingContext(
        localIdentity = localIdentity,
        localDeviceId = localDeviceId,
        devices = devices,
        routes = routes,
        packetDeduplicator = packetDeduplicator,
        envelopeProtectionService = envelopeProtectionService,
        torTransport = torTransport,
        webRtcTransport = webRtcTransport,
        clock = clock,
        routerConfig = routerConfig,
        transportLimits = transportLimits,
    )
    private val envelopeDispatcher = EnvelopeDispatcher(routingContext)
    private val systemSender = SystemSender(
        routingContext,
        transportPolicy,
        envelopeDispatcher
    )
    private val incomingMessageFlow = MutableSharedFlow<MessagePayload>(replay = 1, extraBufferCapacity = 64)

    // Fed by SystemInboundHandler when a typing indicator system envelope is received.
    private val typingIndicatorFlow = MutableSharedFlow<TypingIndicatorEvent>(extraBufferCapacity = 64)

    // Fed by BootstrapInboundHandler when an authenticated bootstrap-family packet is received.
    private val bootstrapPacketFlow = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)

    private val pingPayloadFlow =
        MutableSharedFlow<PingFrontiers>(extraBufferCapacity = 64, replay = 4)
    private val outboxProcessor: OutboxProcessor = OutboxProcessor(
        ctx = routingContext,
        dispatcher = envelopeDispatcher,
        transportPolicy = transportPolicy,
        packetOutbox = packetOutbox,
        maxIdlePoll = retryLoopMaxIdlePollSeconds,
        isPeerOnline = { peerAvailabilityRegistry.isOnline(it) },
        onNoLink = { peer -> torLinkKeeper.nudge(peer, resetBackoff = false) },
        relayCopies = { envelope -> relayDepositor.deposit(envelope) },
    )

    private val bootstrapSender = BootstrapSender(routingContext, outboxProcessor)

    private val peerAvailabilityRegistry = PeerAvailabilityRegistry(
        clock = clock,
        routerConfig,
        store = peerAvailabilityStore,
    )
    private val proactiveSessionOpener = ProactiveSessionOpener(
        ctx = routingContext,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
    )
    private val relaySelectionPolicy = DefaultRelaySelectionPolicy(
        ctx = routingContext,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
        routerConfig = routerConfig,
    )

    private val relayDepositor: RelayDepositor = RelayDepositor(
        ctx = routingContext,
        relaySelectionPolicy = relaySelectionPolicy,
        enqueue = { envelope, at -> outboxProcessor.enqueueAndWake(envelope, at) },
    )

    private val outboundMessenger = OutboundMessenger(
        ctx = routingContext,
        outboxProcessor = outboxProcessor,
        sessionOpener = proactiveSessionOpener,
    )
    private val webRtcBootstrapSignaler = WebRtcBootstrapSignaler(
        ctx = routingContext,
        dispatcher = envelopeDispatcher,
    )

    private val pingProvider = PingProvider(
        ctx = routingContext,
        config = routerConfig,
        pingPayloadFlow = pingPayloadFlow,
        frontierSnapshotProvider = frontierSnapshotProvider,
        systemSender = systemSender,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
    )

    private val syncHandler = SyncHandler(
        ctx = routingContext,
        outboundMessenger = outboundMessenger,
        syncPayloadProvider = syncPayloadProvider,
        systemSender = systemSender,
    )
    private val typingIndicatorDispatcher = TypingIndicatorDispatcher(
        ctx = routingContext,
        systemSender = systemSender,
        sessionOpener = proactiveSessionOpener,
    )
    private val deliveryFlow = MutableSharedFlow<Delivery>(extraBufferCapacity = 64)

    private val inboundEnvelopeProcessor = InboundEnvelopeProcessor(
        ctx = routingContext,
        systemSender = systemSender,
        handlers = mapOf(
            PacketType.MESSAGE to MessageInboundHandler(routingContext, incomingMessageFlow),
            PacketType.SIGNAL to SignalInboundHandler(routingContext),
            PacketType.FILE to FileInboundHandler(),
            PacketType.SYSTEM to SystemInboundHandler(routingContext, typingIndicatorFlow, syncHandler),
            PacketType.BOOTSTRAP to BootstrapInboundHandler(
                routingContext,
                bootstrapPacketFlow,
            ),
        ),
        outboxProcessor = outboxProcessor,
        syncHandler = syncHandler,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
        pingProvider = pingProvider,
        onSyncDeclined = { syncId, deviceId -> syncProcessor.onDeclined(syncId, deviceId) },
    )

    private val peerPolicy = DefaultSyncPeerPolicy(routingContext)

    /**
     * Keeps a Tor link open to every active peer, so sends, pings and replies ride an open
     * stream instead of dialing. WebRTC sessions stay on-demand ([proactiveSessionOpener]).
     */
    private val torLinkKeeper: LinkKeeper = LinkKeeper(
        name = "Tor",
        sessions = routingContext.peerLinks.tor,
        targets = { devices.getAllActiveDeviceIds().filter { it != routingContext.localDeviceId } },
        ready = torTransport.health.map { it == TorHealth.Ready },
        config = routerConfig
            .map { it.torLinkKeeperConfig() }
            .stateIn(scope, SharingStarted.Eagerly, routerConfig.value.torLinkKeeperConfig()),
        clock = clock,
        onOpened = { peer ->
            // Their app accepted our stream: they're up. The ping carries our frontiers
            // (sync on connect) and lets them adopt this stream as their link to us.
            peerAvailabilityRegistry.markReachable(peer, clock.now())
            pingProvider.pingPeer(peer)
        },
        onFailed = { peer, state ->
            peerAvailabilityRegistry.noteProbeAttempted(peer)
            // Not found, yet the peer is online (it reached us some other way): Tor's cached
            // miss is stale. Drop it (throttled; global) and retry soon instead of backing off.
            val staleMiss = state?.reason == TorSessionState.REASON_ONION_NOT_FOUND &&
                    peerAvailabilityRegistry.isOnline(peer) &&
                    torTransport.refreshOnionLookups()
            if (staleMiss) routerConfig.value.onionRefreshRetryDelay else null
        },
    )

    private val syncProcessor = SyncRetryProcessor(
        ctx = routingContext,
        attempts = syncAttemptStore,
        syncs = syncPayloadProvider,
        systemSender = systemSender,
        outboxProcessor = outboxProcessor,
        peerPolicy = peerPolicy,
        maxIdlePoll = retryLoopMaxIdlePollSeconds,
        onNoLink = { peer -> torLinkKeeper.nudge(peer, resetBackoff = false) },
    )

    private var started = false
    private var torEndpoint: TorEndpoint? = null

    private var torIncomingJob: Job? = null
    private var webRtcIncomingEnvelopeJob: Job? = null
    private var webRtcOutgoingJob: Job? = null
    private var onlinePeerJob: Job? = null
    private var linkUpJob: Job? = null
    private var torLinkKeeperJob: Job? = null
    private var outboxRetryJob: Job? = null
    private var outboxOutcomeJob: Job? = null
    private var syncRetryJob: Job? = null

    override val incomingMessages: Flow<MessagePayload> = incomingMessageFlow.asSharedFlow()

    override val typingIndicators: Flow<TypingIndicatorEvent> = typingIndicatorFlow.asSharedFlow()

    override val bootstrapPackets: Flow<BootstrapPacketEvent> = bootstrapPacketFlow.asSharedFlow()

    override val pingPayloads: Flow<PingFrontiers> = pingPayloadFlow.asSharedFlow()

    // Account-level by design: the resolver maps each online device to its
    // account here, so device-level presence never leaves the router. Unknown
    // devices (no row yet) are dropped — absence asserts nothing. Intra-account
    // device flaps re-emit the same set and are deduped below.
    override val onlineAccounts: Flow<Set<AccountId>> =
        peerAvailabilityRegistry.onlineDevices
            .map { online -> online.mapNotNull { devices.getAccountIdForDevice(it) }.toSet() }
            .distinctUntilChanged()

    override val localLinkStatus: StateFlow<LocalLinkStatus> =
        combine(torTransport.health, torTransport.publishState, torTransport.recovery, ::localLinkStatus)
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                localLinkStatus(
                    torTransport.health.value,
                    torTransport.publishState.value,
                    torTransport.recovery.value
                ),
            )

    override suspend fun start() {
        check(!started) { "Router is already started" }
        try {
            torEndpoint = torTransport.start()
            webRtcTransport.start(localDeviceId)
        } catch (e: Exception) {
            webRtcTransport.stop()
            torTransport.stop()
            throw e
        }
        checkNotNull(torEndpoint) { "Tor endpoint must be initialized after starting transport" }
        routes.upsertPeerTorEndpoint(
            deviceId = localDeviceId,
            torEndpoint = torEndpoint!!,
        )

        inboundEnvelopeProcessor.startDispositions(scope)
        torIncomingJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            torTransport.incoming.collect { inbound ->
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
            webRtcTransport.incomingEnvelopes.collect { inbound ->
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

        webRtcOutgoingJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            webRtcTransport.outgoingBootstrapSignals.collect { signal ->
                runCatching { webRtcBootstrapSignaler.signal(signal) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                    }
            }
        }

        // Any link coming up (either transport) releases the peer's waiting outbox entries.
        linkUpJob = scope.launch {
            routingContext.peerLinks.linkUps.collect { up -> outboxProcessor.onPeerOnline(up.peer) }
        }
        onlinePeerJob = scope.launch {
            peerAvailabilityRegistry.onlineEvents.collect { state ->
                outboxProcessor.onPeerOnline(state)
            }
        }

        // Before the outbox runs: outcomes are not replayed.
        outboxOutcomeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            outboxProcessor.outcomes.collect { outcome ->
                runCatching { onOutboxOutcome(outcome) }.onFailure { e ->
                    if (e is CancellationException) throw e
                    AppLog.error(
                        component = LogComponent.ROUTER,
                        event = LogEvent.OUTBOX_PROCESS_FAILED,
                        message = "Failed to handle outbox outcome",
                        fields = mapOf("tag" to outcome.tag, "error" to e.toString()),
                    )
                }
            }
        }
        outboxRetryJob = outboxProcessor.runIn(scope)

        syncRetryJob = syncProcessor.runIn(scope)

        pingProvider.runIn(scope)

        peerAvailabilityRegistry.start(scope, localDeviceId)

        torLinkKeeperJob = torLinkKeeper.runIn(scope)

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
        inboundEnvelopeProcessor.stopDispositions()

        torLinkKeeper.stop()
        pingProvider.stop()
        peerAvailabilityRegistry.stop()
        // Bounded: logOff only goes to linked peers, but a stalled stream write
        // must still never burn the shutdown budget (and the UI-thread wait above
        // it) and starve the Tor stop below, orphaning the daemon. Peers expire
        // us via sweep regardless.
        runCatching {
            withTimeoutOrNull(8_000) { pingProvider.logOff() }
        }

        // Best-effort per transport: a failing WebRTC stop must never skip the
        // Tor stop and strand a live daemon (its data-dir lock breaks the next
        // launch). Mirrors the teardown discipline in enterResetRequired.
        runCatching { webRtcTransport.stop() }.onFailure { error ->
            AppLog.error(
                component = LogComponent.ROUTER,
                event = LogEvent.SESSION_FAILED,
                message = "WebRTC transport stop failed; continuing with Tor stop",
                throwable = error,
            )
        }
        runCatching { torTransport.stop() }.onFailure { error ->
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
            webRtcOutgoingJob,
            linkUpJob,
            torLinkKeeperJob,
            outboxRetryJob,
            outboxOutcomeJob,
            syncRetryJob,
        ).forEach { it.cancelAndJoin() }

        torIncomingJob = null
        webRtcIncomingEnvelopeJob = null
        webRtcOutgoingJob = null
        linkUpJob = null
        torLinkKeeperJob = null
        outboxRetryJob = null
        outboxOutcomeJob = null
        syncRetryJob = null
        scope.cancel()

        AppLog.info(
            component = LogComponent.ROUTER,
            event = LogEvent.STOPPED,
            message = "Router stopped",
            fields = mapOf("torEndpoint" to torEndpoint.toString()),
        )
        started = false
    }

    override fun isRunning(): Boolean = started

    override suspend fun accountReach(accounts: Collection<AccountId>): Map<AccountId, AccountReach> {
        val lastSeen = peerAvailabilityStore.availabilityForAll()
            .filter { it.lastSeen != Instant.DISTANT_PAST }
            .associate { it.deviceId to it.lastSeen }
        return accounts.associateWith { account ->
            AccountReach(lastSeen = devices.getAllPeerDevicesForAccount(account).mapNotNull { lastSeen[it] }
                .maxOrNull())
        }
    }

    override fun localTorEndpoint(): TorEndpoint =
        checkNotNull(torEndpoint) { "Router not started: local Tor endpoint unknown" }

    override suspend fun announceOnline() {
        check(started) { "Router must be started before announcing online" }
        // Peers without a link get one opened (now, not after their backoff); each successful
        // open pings that peer.
        torLinkKeeper.nudgeAll(resetBackoff = true)
        // Best-effort: a transient send failure to one peer must not propagate out of the announce.
        runCatching { pingProvider.ping() }
    }

    override suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
    ): AccountPushReport {
        check(started) { "Router must be started before sending messages" }
        return outboundMessenger.sendMessage(target, payload)
    }

    override suspend fun sendTypingIndicator(
        targets: Collection<AccountId>,
        roomId: RoomId,
        interval: Duration,
    ) {
        check(started) { "Router must be started before sending typing indicators" }
        typingIndicatorDispatcher.dispatch(targets, roomId, interval)
    }

    override suspend fun sendBootstrap(
        payload: BootstrapPayload,
        target: PeerId,
        targetEndpoint: TorEndpoint?,
        sharedSecret: ByteArray?,
    ): Uuid {
        check(started) { "Router must be started before sending bootstrap intro" }
        return bootstrapSender.sendBootstrap(payload, target, targetEndpoint, sharedSecret)
    }

    override suspend fun startSync(syncId: Uuid, firstAttemptAt: Instant) {
        syncProcessor.start(syncId, firstAttemptAt)
    }

    override suspend fun reopenSync(syncId: Uuid, account: AccountId) {
        syncProcessor.reopen(syncId, account)
    }

    override val deliveries: Flow<Delivery> = deliveryFlow.asSharedFlow()

    /** Routes each tagged outbox outcome to whoever waits on that tag. */
    private suspend fun onOutboxOutcome(outcome: OutboxOutcome) {
        val id = outcome.tag.id
        when (outcome.tag.kind) {
            OutboxTag.Kind.MESSAGE -> if (outcome is OutboxOutcome.Acked) {
                // A device we can't place in an account can't be shown as anyone's delivery.
                val account = devices.getAccountIdForDevice(outcome.target) ?: return
                deliveryFlow.emit(Delivery(DeliverySubject.Message(id), account))
            }

            OutboxTag.Kind.BOOTSTRAP -> if (outcome is OutboxOutcome.Acked) {
                val account = devices.getAccountIdForDevice(outcome.target)
                deliveryFlow.emit(Delivery(DeliverySubject.Bootstrap(id), account))
            }

            OutboxTag.Kind.SYNC -> syncProcessor.onAttemptOutcome(outcome)
        }
    }
}

private fun RouterConfig.torLinkKeeperConfig() = LinkKeeperConfig(
    retryDelay = torLinkRetryDelay,
    maxRetryDelay = torLinkMaxRetryDelay,
    maxConcurrentOpens = torLinkMaxConcurrentOpens,
    openTimeout = torLinkOpenTimeout,
    recheckInterval = linkKeeperRecheckInterval,
    retryJitter = linkKeeperRetryJitter,
    minWait = linkKeeperMinWait,
)
