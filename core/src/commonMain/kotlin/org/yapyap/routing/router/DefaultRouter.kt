package org.yapyap.routing.router

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.yapyap.config.TransportLimits
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.availability.PeerAvailabilityStore
import org.yapyap.persistence.packet.PacketDeduplicator
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.persistence.sync.PendingSyncRepository
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapIntroPayload
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.inbound.InboundEnvelopeProcessor
import org.yapyap.routing.inbound.handlers.*
import org.yapyap.routing.outbound.*
import org.yapyap.routing.ping.LamportSnapshotProvider
import org.yapyap.routing.ping.PingProvider
import org.yapyap.routing.policy.DefaultRelaySelectionPolicy
import org.yapyap.routing.policy.DefaultSyncPeerPolicy
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.policy.SessionOrTorPolicy
import org.yapyap.routing.sync.SyncHandler
import org.yapyap.routing.sync.SyncPayloadProvider
import org.yapyap.routing.sync.SyncRetryProcessor
import org.yapyap.transport.tor.transport.TorTransport
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcSessionPhase
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration

class DefaultRouter(
    val torTransport: TorTransport,
    val webRtcTransport: WebRtcTransport,
    val identityResolver: IdentityResolver,
    val packetDeduplicator: PacketDeduplicator,
    val packetOutbox: PacketOutbox,
    val syncRepository: PendingSyncRepository,
    val envelopeProtectionService: EnvelopeProtectionService,
    val clock: Clock = Clock.System,
    val routerConfig: StateFlow<RouterConfig>,
    val transportLimits: StateFlow<TransportLimits>,
    val transportPolicy: OutboundPolicy = SessionOrTorPolicy(routerConfig),
    val syncPayloadProvider: SyncPayloadProvider,
    val lamportSnapshotProvider: LamportSnapshotProvider,
    val peerAvailabilityStore: PeerAvailabilityStore,
): Router {


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val retryLoopMaxIdlePollSeconds: StateFlow<Duration> = routerConfig
        .map { it.retryLoopMaxIdlePoll }
        .stateIn(scope, SharingStarted.Eagerly, routerConfig.value.retryLoopMaxIdlePoll)


    private val routingContext = RoutingContext(
        identityResolver = identityResolver,
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
        envelopeDispatcher)
    private val incomingMessageFlow = MutableSharedFlow<MessagePayload>(replay = 1, extraBufferCapacity = 64)
    // Fed by SystemInboundHandler when a typing indicator system envelope is received.
    private val typingIndicatorFlow = MutableSharedFlow<TypingIndicatorEvent>(extraBufferCapacity = 64)

    // Fed by BootstrapInboundHandler when an authenticated bootstrap intro is received.
    private val bootstrapIntroFlow = MutableSharedFlow<BootstrapIntroEvent>(extraBufferCapacity = 64)

    private val pingPayloadFlow = MutableSharedFlow<List<Pair<RoomId, Long>>>(extraBufferCapacity = 64, replay = 4)
    private val outboxProcessor = OutboxProcessor(
        ctx = routingContext,
        dispatcher = envelopeDispatcher,
        transportPolicy = transportPolicy,
        packetOutbox = packetOutbox,
        maxIdlePoll = retryLoopMaxIdlePollSeconds,
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

    private val outboundMessenger = OutboundMessenger(
        ctx = routingContext,
        dispatcher = envelopeDispatcher,
        transportPolicy = transportPolicy,
        outboxProcessor = outboxProcessor,
        sessionOpener = proactiveSessionOpener,
        relaySelectionPolicy = relaySelectionPolicy,
    )
    private val webRtcBootstrapSignaler = WebRtcBootstrapSignaler(
        ctx = routingContext,
        dispatcher = envelopeDispatcher,
    )

    private val pingProvider = PingProvider(
        ctx = routingContext,
        config = routerConfig,
        pingPayloadFlow = pingPayloadFlow,
        lamportSnapshotProvider = lamportSnapshotProvider,
        systemSender = systemSender,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
    )

    private val syncHandler = SyncHandler(
        outboundMessenger,
        syncPayloadProvider,
        syncRepository,
        systemSender)
    private val typingIndicatorDispatcher = TypingIndicatorDispatcher(
        ctx = routingContext,
        systemSender = systemSender,
        sessionOpener = proactiveSessionOpener,
    )
    private val inboundEnvelopeProcessor = InboundEnvelopeProcessor(
        ctx = routingContext,
        systemSender = systemSender,
        handlers = mapOf(
            PacketType.MESSAGE to MessageInboundHandler(routingContext, incomingMessageFlow),
            PacketType.SIGNAL to SignalInboundHandler(routingContext),
            PacketType.FILE to FileInboundHandler(),
            PacketType.SYSTEM to SystemInboundHandler(routingContext, typingIndicatorFlow),
            PacketType.BOOTSTRAP to BootstrapInboundHandler(routingContext, bootstrapIntroFlow),
        ),
        outboxProcessor = outboxProcessor,
        syncHandler = syncHandler,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
        pingProvider = pingProvider,
    )

    private val peerPolicy = DefaultSyncPeerPolicy(routingContext, peerAvailabilityRegistry)

    private val syncProcessor = SyncRetryProcessor(
        ctx = routingContext,
        pendingSyncs = syncRepository,
        systemSender = systemSender,
        peerPolicy = peerPolicy,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
        maxIdlePoll = retryLoopMaxIdlePollSeconds,
    )

    private var started = false
    private var torEndpoint: TorEndpoint? = null
    private var localDeviceIdentity: DeviceIdentityRecord? = null

    private var torIncomingJob: Job? = null
    private var webRtcIncomingEnvelopeJob: Job? = null
    private var webRtcOutgoingJob: Job? = null
    private var webRtcSessionJob: Job? = null
    private var outboxRetryJob: Job? = null
    private var syncRetryJob: Job? = null

    override val incomingMessages: Flow<MessagePayload> = incomingMessageFlow.asSharedFlow()

    override val typingIndicators: Flow<TypingIndicatorEvent> = typingIndicatorFlow.asSharedFlow()

    override val bootstrapIntros: Flow<BootstrapIntroEvent> = bootstrapIntroFlow.asSharedFlow()

    override val pingPayloads: Flow<List<Pair<RoomId, Long>>> = pingPayloadFlow.asSharedFlow()

    override suspend fun start() {
        check(!started) { "Router is already started" }
        localDeviceIdentity = identityResolver.getLocalDeviceIdentityRecord()
        routingContext.localDeviceIdentity = localDeviceIdentity!!

        try {
            torEndpoint = torTransport.start()
            webRtcTransport.start(localDeviceIdentity!!.deviceId)
        }
        catch (e: Exception) {
            webRtcTransport.stop()
            torTransport.stop()
            throw e
        }
        checkNotNull(torEndpoint) { "Tor endpoint must be initialized after starting transport" }
        identityResolver.updatePeerTorEndpoint(
            deviceId = localDeviceIdentity!!.deviceId,
            torEndpoint = torEndpoint!!,
        )

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

        webRtcSessionJob = scope.launch {
            webRtcTransport.sessionStates.collect { state ->
                if (state.phase == WebRtcSessionPhase.CONNECTED) {
                    outboxProcessor.onWebRtcSessionConnected(state.peerId)
                }
            }
        }

        outboxRetryJob = outboxProcessor.runIn(scope)

        syncRetryJob = syncProcessor.runIn(scope)

        pingProvider.runIn(scope)

        peerAvailabilityRegistry.start(scope, localDeviceIdentity!!.deviceId)

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

        pingProvider.stop()
        peerAvailabilityRegistry.stop()
        pingProvider.logOff()

        webRtcTransport.stop()
        torTransport.stop()

        listOfNotNull(
            torIncomingJob,
            webRtcIncomingEnvelopeJob,
            webRtcOutgoingJob,
            webRtcSessionJob,
            outboxRetryJob,
            syncRetryJob,
        ).forEach { it.cancelAndJoin() }

        torIncomingJob = null
        webRtcIncomingEnvelopeJob = null
        webRtcOutgoingJob = null
        webRtcSessionJob = null
        outboxRetryJob = null
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

    override suspend fun announceOnline() {
        check(started) { "Router must be started before announcing online" }
        // Best-effort: a transient send failure to one peer must not propagate out of the announce.
        runCatching { pingProvider.ping() }
    }

    override suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
        forceTransport: RouterTransport?,
    ): SendMessageResult {
        check(started) { "Router must be started before sending messages" }
        return outboundMessenger.sendMessage(target, payload, forceTransport)
    }

    override suspend fun sendTypingIndicator(
        targets: Collection<AccountId>,
        roomId: RoomId,
        interval: Duration,
    ) {
        check(started) { "Router must be started before sending typing indicators" }
        typingIndicatorDispatcher.dispatch(targets, roomId, interval)
    }

    override suspend fun sendBootstrapIntro(payload: BootstrapIntroPayload) {
        check(started) { "Router must be started before sending bootstrap intro" }
        bootstrapSender.sendBootstrapIntro(payload)
    }
}
