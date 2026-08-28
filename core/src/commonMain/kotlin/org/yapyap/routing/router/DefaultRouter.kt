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
import org.yapyap.persistence.packet.PacketDeduplicator
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.persistence.sync.PendingSyncRepository
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.inbound.InboundEnvelopeProcessor
import org.yapyap.routing.inbound.handlers.FileInboundHandler
import org.yapyap.routing.inbound.handlers.MessageInboundHandler
import org.yapyap.routing.inbound.handlers.SignalInboundHandler
import org.yapyap.routing.inbound.handlers.SystemInboundHandler
import org.yapyap.routing.outbound.*
import org.yapyap.routing.policy.DefaultSyncPeerPolicy
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.policy.SessionOrTorPolicy
import org.yapyap.routing.sync.SyncHandler
import org.yapyap.routing.sync.SyncPayloadProvider
import org.yapyap.routing.sync.SyncRetryProcessor
import org.yapyap.time.EpochProvider
import org.yapyap.time.SystemEpochProvider
import org.yapyap.transport.tor.transport.TorTransport
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcSessionPhase
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

class DefaultRouter(
    val torTransport: TorTransport,
    val webRtcTransport: WebRtcTransport,
    val identityResolver: IdentityResolver,
    val packetDeduplicator: PacketDeduplicator,
    val packetOutbox: PacketOutbox,
    val syncRepository: PendingSyncRepository,
    val envelopeProtectionService: EnvelopeProtectionService,
    val timeProvider: EpochProvider = SystemEpochProvider,
    val routerConfig: StateFlow<RouterConfig>,
    val transportLimits: StateFlow<TransportLimits>,
    val transportPolicy: OutboundPolicy = SessionOrTorPolicy(routerConfig),
    val syncPayloadProvider: SyncPayloadProvider,
): Router {


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val retryLoopMaxIdlePollSeconds: StateFlow<Long> = routerConfig
        .map { it.retryLoopMaxIdlePollSeconds }
        .stateIn(scope, SharingStarted.Eagerly, routerConfig.value.retryLoopMaxIdlePollSeconds)


    private val routingContext = RoutingContext(
        identityResolver = identityResolver,
        packetDeduplicator = packetDeduplicator,
        envelopeProtectionService = envelopeProtectionService,
        torTransport = torTransport,
        webRtcTransport = webRtcTransport,
        timeProvider = timeProvider,
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
    private val outboxProcessor = OutboxProcessor(
        ctx = routingContext,
        dispatcher = envelopeDispatcher,
        transportPolicy = transportPolicy,
        packetOutbox = packetOutbox,
        maxIdlePollSeconds = retryLoopMaxIdlePollSeconds,
    )

    private val outboundMessenger = OutboundMessenger(
        ctx = routingContext,
        dispatcher = envelopeDispatcher,
        transportPolicy = transportPolicy,
        outboxProcessor = outboxProcessor,
    )
    private val webRtcBootstrapSignaler = WebRtcBootstrapSignaler(
        ctx = routingContext,
        dispatcher = envelopeDispatcher,
    )
    private val syncHandler = SyncHandler(
        outboundMessenger,
        syncPayloadProvider,
        syncRepository,
        systemSender)
    private val peerAvailabilityRegistry = PeerAvailabilityRegistry(timeProvider = timeProvider, routerConfig)
    private val proactiveSessionOpener = ProactiveSessionOpener(
        ctx = routingContext,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
    )
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
        ),
        outboxProcessor = outboxProcessor,
        syncHandler = syncHandler,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
    )

    private val peerPolicy = DefaultSyncPeerPolicy(routingContext, peerAvailabilityRegistry)

    private val syncProcessor = SyncRetryProcessor(
        ctx = routingContext,
        pendingSyncs = syncRepository,
        systemSender = systemSender,
        peerPolicy = peerPolicy,
        peerAvailabilityRegistry = peerAvailabilityRegistry,
        maxIdlePollSeconds = retryLoopMaxIdlePollSeconds,
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
        roomId: String,
        interval: Duration,
    ) {
        check(started) { "Router must be started before sending typing indicators" }
        typingIndicatorDispatcher.dispatch(targets, roomId, interval)
    }
}
