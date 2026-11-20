package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileFetchAbortReason
import org.yapyap.routing.file.fetch.FileFetchManager
import org.yapyap.routing.file.port.FileCarrier
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileReceiveEnds
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.file.port.FileServeGate
import org.yapyap.routing.file.relay.FileRelayForwarder
import org.yapyap.routing.inbound.handlers.FileInboundHandler
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.ProactiveSessionOpener
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileIncomingTransferStore
import org.yapyap.routing.store.FilePushStore
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.FileRequestAttemptStore
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.PacketOutbox
import org.yapyap.routing.store.RelayBackup
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.uuid.Uuid

/** The file stores and the orchestrator-side ports routing's file machinery runs against. */
internal class FileRoutingPorts(
    val ingest: FileIngestPort,
    val requests: FileRequestSource,
    val serveGate: FileServeGate,
    val plaintext: FilePlaintextSource,
    val receiveEnds: FileReceiveEnds,
    val incoming: FileIncomingTransferStore,
    val pushes: FilePushStore,
    val relay: FileRelayStore,
    val requestAttempts: FileRequestAttemptStore,
    /** Plaintext bytes per small-path chunk. */
    val chunkSizeBytes: StateFlow<Int>,
    /** File bytes per large-path DATA frame. */
    val fetchFrameBytes: StateFlow<Int>,
)

/**
 * Routing's file machinery, assembled in one place (design §6, §7, §9): the pusher, the relay
 * forwarder, the large-path fetch, the request processor, the inbound FILE handler and the
 * [FileCarrier] the orchestrator sees. The router builds it from its shared pieces and only
 * routes to it: FILE packets to [inboundHandler], FILE and FILE_REQUEST outcomes to
 * [onOutboxOutcome], OFFER-marker relay copies to [depositByMarkerPacket].
 */
internal class FileRouting(
    ctx: RoutingContext,
    ports: FileRoutingPorts,
    private val outboxProcessor: OutboxProcessor,
    packetOutbox: PacketOutbox,
    relaySelection: RelaySelectionPolicy,
    peerPolicy: SyncPeerPolicy,
    sessionOpener: ProactiveSessionOpener,
    maxIdlePoll: StateFlow<Duration>,
    onNoLink: suspend (PeerId) -> Unit,
) {
    private val receiveEnds = ports.receiveEnds
    private val events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)

    private val outbox: FileOutbox = object : FileOutbox {
        override suspend fun enqueue(envelope: BinaryEnvelope, tag: OutboxTag, relayBackup: RelayBackup) =
            outboxProcessor.enqueueAndWake(envelope, ctx.clock.now(), relayBackup = relayBackup, tag = tag)

        override suspend fun enqueueDirect(envelope: BinaryEnvelope) =
            outboxProcessor.enqueueAndWake(envelope, ctx.clock.now())

        override suspend fun deleteByTag(tag: OutboxTag) = packetOutbox.deleteByTag(tag)

        override suspend fun hasQueued(tag: OutboxTag) = packetOutbox.hasTagged(tag)

        override suspend fun isPacketQueued(packetId: Uuid) = packetOutbox.getByPacketId(packetId) != null
    }

    private val pusher = FilePusher(
        ctx = ctx,
        plaintext = ports.plaintext,
        pushes = ports.pushes,
        outbox = outbox,
        events = events,
        chunkSizeBytes = ports.chunkSizeBytes,
        relaySelection = relaySelection,
    )

    // Relay-side forward driver (design §7): shares the file outbox with the authoring pusher;
    // FILE outcomes for unknown push rows are its forwards.
    private val relayForwarder = FileRelayForwarder(ctx = ctx, relay = ports.relay, outbox = outbox)

    // Large-path fetch (design §6.6): the fetch protocol on dedicated file channels; no envelopes,
    // no outbox.
    private val fetchManager = FileFetchManager(
        localDeviceId = ctx.localDeviceId,
        devices = ctx.devices,
        transport = ctx.webRtcTransport,
        plaintext = ports.plaintext,
        ingest = ports.ingest,
        serveGate = ports.serveGate,
        routerConfig = ctx.routerConfig,
        frameBytes = ports.fetchFrameBytes,
        events = events,
    )

    private val requestProcessor = FileRequestProcessor(
        ctx = ctx,
        attempts = ports.requestAttempts,
        requests = ports.requests,
        outboxProcessor = outboxProcessor,
        peerPolicy = peerPolicy,
        maxIdlePoll = maxIdlePoll,
        events = events,
        onNoLink = onNoLink,
        sessionOpener = sessionOpener,
        fetchManager = fetchManager,
    )

    private val carrier = DefaultFileCarrier(
        localDeviceId = ctx.localDeviceId,
        protection = ctx.envelopeProtectionService,
        incoming = ports.incoming,
        enqueue = { envelope -> outboxProcessor.enqueueAndWake(envelope, ctx.clock.now()) },
        clock = ctx.clock,
        routerConfig = ctx.routerConfig,
        pushOps = pusher,
        events = events,
        startRequest = { fileId, firstAttemptAt -> requestProcessor.start(fileId, firstAttemptAt) },
        abortFetch = { fileId -> fetchManager.abortFetch(fileId, FileFetchAbortReason.CANCELLED) },
    )

    val fileCarrier: FileCarrier get() = carrier

    val inboundHandler = FileInboundHandler(
        ctx = ctx,
        incoming = ports.incoming,
        ingest = ports.ingest,
        serveGate = ports.serveGate,
        pusher = pusher,
        relay = ports.relay,
        onCoverageAck = { transferId, contiguous, missing -> pusher.onCoverageAck(transferId, contiguous, missing) },
        onTransferComplete = { transferId -> pusher.onTransferComplete(transferId) },
        onTransferCancelled = { transferId, reason -> pusher.onTransferCancelled(transferId, reason) },
        onOfferReceived = { fileId -> requestProcessor.onOfferReceived(fileId) },
        onRelayStored = { relayForwarder.wake() },
    )

    /**
     * Starts the file loops in [scope]: push reconciliation and the deposit sweep, requests, relay
     * forwards, fetch channels, and answering the receive engine's ended receives. The router's
     * outbox loop dispatches what they queue.
     */
    fun runIn(scope: CoroutineScope): List<Job> = listOf(
        pusher.runIn(scope),
        requestProcessor.runIn(scope),
        relayForwarder.runIn(scope),
        fetchManager.runIn(scope),
        scope.launch {
            receiveEnds.receiveEnds.collect { end ->
                try {
                    carrier.onReceiveEnd(end)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Politeness: the authors' transfers end on their TTL without it.
                    AppLog.error(
                        component = LogComponent.ROUTER,
                        event = LogEvent.OUTBOX_PROCESS_FAILED,
                        message = "Failed to answer an ended receive",
                        throwable = e,
                        fields = mapOf("fileId" to end.fileId),
                    )
                }
            }
        },
    )

    /** An OFFER marker's relay copies (design §5): a one-round deposit of its authoring transfer. */
    suspend fun depositByMarkerPacket(packetId: Uuid): Int = pusher.depositByMarkerPacket(packetId)

    /** Routes a FILE or FILE_REQUEST outcome to whoever waits on it. */
    suspend fun onOutboxOutcome(outcome: OutboxOutcome) {
        when (outcome.tag.kind) {
            OutboxTag.Kind.FILE_REQUEST -> requestProcessor.onAttemptOutcome(outcome)
            // Pusher-owned: OFFER/chunk ACKs advance the window, DECLINED and other terminals end
            // the transfer, expiries re-arm live transfers. Relay forwards share the FILE tag: the
            // pusher ignores unknown transfer ids and the forwarder drains its own.
            OutboxTag.Kind.FILE -> {
                pusher.onOutboxOutcome(outcome)
                relayForwarder.onOutboxOutcome(outcome)
            }

            else -> error("Not a file outcome: ${outcome.tag.kind}")
        }
    }

}
