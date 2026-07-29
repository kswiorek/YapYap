package org.yapyap.routing.router

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LoggingTypes
import org.yapyap.persistence.packet.PacketDeduplicator
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.inbound.AckResponder
import org.yapyap.routing.inbound.InboundEnvelopeProcessor
import org.yapyap.routing.inbound.handlers.FileInboundHandler
import org.yapyap.routing.inbound.handlers.MessageInboundHandler
import org.yapyap.routing.inbound.handlers.SignalInboundHandler
import org.yapyap.routing.inbound.handlers.SystemInboundHandler
import org.yapyap.routing.outbound.OutboundMessenger
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.WebRtcBootstrapSignaler
import org.yapyap.routing.policy.OutboundPolicy
import org.yapyap.routing.policy.SessionOrTorPolicy
import org.yapyap.time.EpochSecondsProvider
import org.yapyap.time.SystemEpochSecondsProvider
import org.yapyap.transport.tor.transport.TorTransport
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcSessionPhase
import kotlin.coroutines.cancellation.CancellationException

class DefaultRouter(
    val torTransport: TorTransport,
    val webRtcTransport: WebRtcTransport,
    val identityResolver: IdentityResolver,
    val packetDeduplicator: PacketDeduplicator,
    val packetOutbox: PacketOutbox,
    val envelopeProtectionService: EnvelopeProtectionService,
    val timeProvider: EpochSecondsProvider = SystemEpochSecondsProvider,
    val routerConfig: RouterConfig,
    val transportPolicy: OutboundPolicy = SessionOrTorPolicy(routerConfig),
): Router {
    private val routingContext = RoutingContext(
        identityResolver = identityResolver,
        packetDeduplicator = packetDeduplicator,
        envelopeProtectionService = envelopeProtectionService,
        torTransport = torTransport,
        webRtcTransport = webRtcTransport,
        timeProvider = timeProvider,
        routerConfig = routerConfig,
    )
    private val envelopeDispatcher = EnvelopeDispatcher(routingContext)
    private val ackResponder = AckResponder(routingContext, envelopeDispatcher)
    private val incomingMessageFlow = MutableSharedFlow<MessagePayload>(replay = 1, extraBufferCapacity = 64)
    private val outboxProcessor = OutboxProcessor(
        ctx = routingContext,
        dispatcher = envelopeDispatcher,
        transportPolicy = transportPolicy,
        packetOutbox = packetOutbox,
        maxIdlePollSeconds = routerConfig.outboxMaxIdlePollSeconds,
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
    private val inboundEnvelopeProcessor = InboundEnvelopeProcessor(
        ctx = routingContext,
        ackResponder = ackResponder,
        handlers = mapOf(
            PacketType.MESSAGE to MessageInboundHandler(routingContext, incomingMessageFlow),
            PacketType.SIGNAL to SignalInboundHandler(routingContext),
            PacketType.FILE to FileInboundHandler(),
        ),
        systemHandler = SystemInboundHandler(ctx = routingContext),
        outboxProcessor = outboxProcessor,
    )

    private var started = false
    private var torEndpoint: TorEndpoint? = null
    private var localDeviceIdentity: DeviceIdentityRecord? = null

    private var scope: CoroutineScope? = null
    private var torIncomingJob: Job? = null
    private var webRtcIncomingEnvelopeJob: Job? = null
    private var webRtcOutgoingJob: Job? = null
    private var webRtcSessionJob: Job? = null
    private var outboxRetryJob: Job? = null

    override val incomingMessages: Flow<MessagePayload> = incomingMessageFlow.asSharedFlow()

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

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        torIncomingJob = s.launch(start = CoroutineStart.UNDISPATCHED) {
            torTransport.incoming.collect { inbound ->
                runCatching { inboundEnvelopeProcessor.handleTorInbound(inbound) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        AppLog.error(
                            component = LogComponent.ROUTER,
                            event = LoggingTypes.ENVELOPE_HANDLE_FAILED,
                            message = "Failed to handle inbound Tor envelope",
                            fields = mapOf("error" to e.toString()),
                        )
                    }
            }
        }

        webRtcIncomingEnvelopeJob = s.launch(start = CoroutineStart.UNDISPATCHED) {
            webRtcTransport.incomingEnvelopes.collect { inbound ->
                runCatching { inboundEnvelopeProcessor.handleWebRtcInbound(inbound) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        AppLog.error(
                            component = LogComponent.ROUTER,
                            event = LoggingTypes.ENVELOPE_HANDLE_FAILED,
                            message = "Failed to handle inbound WebRTC envelope",
                            fields = mapOf("error" to e.toString()),
                        )
                    }
            }
        }

        webRtcOutgoingJob = s.launch(start = CoroutineStart.UNDISPATCHED) {
            webRtcTransport.outgoingBootstrapSignals.collect { signal ->
                runCatching { webRtcBootstrapSignaler.signal(signal) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                    }
            }
        }

        webRtcSessionJob = s.launch {
            webRtcTransport.sessionStates.collect { state ->
                if (state.phase == WebRtcSessionPhase.CONNECTED) {
                    outboxProcessor.onWebRtcSessionConnected(state.peerId)
                }
            }
        }

        outboxRetryJob = outboxProcessor.runIn(s)
        outboxProcessor.pruneRelayOverCapacityOnBoot()

        AppLog.info(
            component = LogComponent.ROUTER,
            event = LoggingTypes.STARTED,
            message = "Router started",
            fields = mapOf("torEndpoint" to torEndpoint.toString()),
        )
        started = true
    }

    override suspend fun stop() {
        if (!started) return

        webRtcTransport.stop()
        torTransport.stop()
        torIncomingJob?.cancel()
        torIncomingJob = null

        webRtcOutgoingJob?.cancel()
        webRtcOutgoingJob = null
        webRtcIncomingEnvelopeJob?.cancel()
        webRtcIncomingEnvelopeJob = null
        webRtcSessionJob?.cancel()
        webRtcSessionJob = null
        outboxRetryJob?.cancel()
        outboxRetryJob = null
        scope?.cancel()
        scope = null

        AppLog.info(
            component = LogComponent.ROUTER,
            event = LoggingTypes.STOPPED,
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

    suspend fun testOpenWebRtcSession(target: PeerId) {
        webRtcTransport.openSession(target)
    }

    suspend fun testCloseWebRtcSession(target: PeerId) {
        webRtcTransport.closeSession(target)
    }
}
