package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.envelope.FileEnvelopeProtection
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.file.fetch.FileFetchService
import org.yapyap.routing.file.port.FileCarrier
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileReceiveEnds
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.file.port.FileServeGate
import org.yapyap.routing.file.push.FileOutbox
import org.yapyap.routing.file.push.FilePushEngine
import org.yapyap.routing.file.push.FileSender
import org.yapyap.routing.inbound.handlers.FileInboundHandler
import org.yapyap.routing.inbound.handlers.FileRelayDoor
import org.yapyap.routing.inbound.handlers.FileServeHandler
import org.yapyap.routing.inbound.handlers.FileTargetHandler
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
import org.yapyap.routing.store.RetryClass
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
    /** File envelope protection: handed to file routing only, never to the shared routing context. */
    val protection: FileEnvelopeProtection,
    /** Plaintext bytes per small-path chunk. */
    val chunkSizeBytes: StateFlow<Int>,
    /** File bytes per large-path DATA frame. */
    val fetchFrameBytes: StateFlow<Int>,
)

/**
 * Routing's file machinery, assembled in one place (design §6, §7, §9): the push engine, the relay
 * forwarder, the large-path fetch, the request processor, the inbound FILE handler and the
 * [FileCarrier] the orchestrator sees. The router builds it from its shared pieces and only
 * routes to it: FILE packets to [inboundHandler], FILE and FILE_REQUEST outcomes to
 * [onOutboxOutcome], OFFER-marker relay copies to [depositByMarkerPacket].
 */
internal class FileRouting(
    ctx: RoutingContext,
    ports: FileRoutingPorts,
    private val outboxProcessor: OutboxProcessor,
    private val packetOutbox: PacketOutbox,
    relaySelection: RelaySelectionPolicy,
    peerPolicy: SyncPeerPolicy,
    sessionOpener: ProactiveSessionOpener,
    maxIdlePoll: StateFlow<Duration>,
    onNoLink: suspend (PeerId) -> Unit,
) {
    private val receiveEnds = ports.receiveEnds
    private val events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
    private val outcomeChannel = Channel<OutboxOutcome>(Channel.UNLIMITED)

    private val outbox: FileOutbox = object : FileOutbox {
        override suspend fun enqueue(
            envelope: BinaryEnvelope,
            tag: OutboxTag,
            relayBackup: RelayBackup,
            retryClass: RetryClass,
        ) = outboxProcessor.enqueueAndWake(
            envelope,
            ctx.clock.now(),
            relayBackup = relayBackup,
            tag = tag,
            retryClass = retryClass,
        )

        override suspend fun enqueueDirect(envelope: BinaryEnvelope) =
            outboxProcessor.enqueueAndWake(envelope, ctx.clock.now())

        override suspend fun deleteByTag(tag: OutboxTag) = packetOutbox.deleteByTag(tag)

        override suspend fun packetIdsByTag(tag: OutboxTag) = packetOutbox.packetIdsByTag(tag)
    }

    private val incomingTransfers = FileIncomingTransfers(ports.incoming, ctx.clock)

    private val pushEngine = FilePushEngine(
        ctx = ctx,
        protection = ports.protection,
        plaintext = ports.plaintext,
        pushes = ports.pushes,
        outbox = outbox,
        events = events,
        relaySelection = relaySelection,
        relay = ports.relay,
        incoming = incomingTransfers,
    )

    private val sender = FileSender(
        ctx = ctx,
        protection = ports.protection,
        plaintext = ports.plaintext,
        pushes = ports.pushes,
        engine = pushEngine,
        events = events,
        chunkSizeBytes = ports.chunkSizeBytes,
    )

    // Large-path fetch (design §6.6): the fetch protocol on dedicated file channels; no envelopes,
    // no outbox. One actor per channel on each end; state dies with the channel.
    private val fetchService = FileFetchService(
        devices = ctx.devices,
        transport = ctx.webRtcTransport,
        plaintext = ports.plaintext,
        ingest = ports.ingest,
        serveGate = ports.serveGate,
        routerConfig = ctx.routerConfig,
        frameBytes = ports.fetchFrameBytes,
        events = events,
        clock = ctx.clock,
    )

    private val requestProcessor = FileRequestProcessor(
        ctx = ctx,
        protection = ports.protection,
        attempts = ports.requestAttempts,
        requests = ports.requests,
        outboxProcessor = outboxProcessor,
        peerPolicy = peerPolicy,
        maxIdlePoll = maxIdlePoll,
        events = events,
        onNoLink = onNoLink,
        sessionOpener = sessionOpener,
        fetchService = fetchService,
    )

    private val carrier = DefaultFileCarrier(
        localDeviceId = ctx.localDeviceId,
        protection = ports.protection,
        incoming = incomingTransfers,
        enqueue = { envelope -> outboxProcessor.enqueueAndWake(envelope, ctx.clock.now()) },
        clock = ctx.clock,
        routerConfig = ctx.routerConfig,
        pushOps = sender,
        events = events,
        startRequest = { fileId, firstAttemptAt -> requestProcessor.start(fileId, firstAttemptAt) },
        abortFetch = { fileId, reason -> fetchService.abortFetch(fileId, reason) },
    )

    val fileCarrier: FileCarrier get() = carrier

    private val targetHandler = FileTargetHandler(
        ctx = ctx,
        protection = ports.protection,
        incoming = incomingTransfers,
        ingest = ports.ingest,
        signals = pushEngine,
        onOfferReceived = { fileId -> requestProcessor.onOfferReceived(fileId) },
    )

    private val relayDoor = FileRelayDoor(
        ctx = ctx,
        protection = ports.protection,
        relay = ports.relay,
        packetOutbox = packetOutbox,
        onRelayWhole = { transferId -> pushEngine.onRelayWhole(transferId) },
    )

    private val serveHandler = FileServeHandler(
        ctx = ctx,
        serveGate = ports.serveGate,
        pusher = sender,
    )

    val inboundHandler = FileInboundHandler(
        ctx = ctx,
        protection = ports.protection,
        target = targetHandler,
        door = relayDoor,
        serve = serveHandler,
    )

    /**
     * Starts the file loops in [scope]: push reconciliation and the deposit sweep, requests, relay
     * forwards, fetch channels, the outcome consumer, and answering the receive engine's ended
     * receives. The router's outbox loop dispatches what they queue.
     */
    fun runIn(scope: CoroutineScope): List<Job> = listOf(
        pushEngine.runIn(scope),
        requestProcessor.runIn(scope),
        fetchService.runIn(scope),
        scope.launch {
            for (outcome in outcomeChannel) {
                try {
                    routeOutcome(outcome)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(
                        component = LogComponent.FILE,
                        event = LogEvent.FILE_TASK_FAILED,
                        message = "Failed to handle file outbox outcome",
                        throwable = e,
                        fields = mapOf("tag" to outcome.tag),
                    )
                }
            }
        },
        scope.launch {
            receiveEnds.receiveEnds.collect { end ->
                try {
                    carrier.onReceiveEnd(end)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Politeness: the authors' transfers end on their TTL without it.
                    AppLog.error(
                        component = LogComponent.FILE,
                        event = LogEvent.FILE_TASK_FAILED,
                        message = "Failed to answer an ended receive",
                        throwable = e,
                        fields = mapOf("fileId" to end.fileId),
                    )
                }
            }
        },
    )

    /** An OFFER marker's relay copies (design §5): a one-round deposit of its authoring transfer. */
    suspend fun depositByMarkerPacket(packetId: Uuid): Int {
        // Resolve through the outbox tag, not the push row's marker id: the actor re-arms
        // an unacked marker with a fresh packet, so by the time the backup fires the row
        // points at a newer marker than the packet that triggered this backup.
        val tag = packetOutbox.getByPacketId(packetId)?.tag ?: return 0
        if (tag.kind != OutboxTag.Kind.FILE_PUSH) return 0
        return pushEngine.depositRemainder(TransferId(tag.id))
    }

    /** Routes a FILE_PUSH / FILE_FORWARD / FILE_REQUEST outcome to whoever waits on it. */
    fun onOutboxOutcome(outcome: OutboxOutcome) {
        outcomeChannel.trySend(outcome)
    }

    private suspend fun routeOutcome(outcome: OutboxOutcome) {
        when (outcome.tag.kind) {
            OutboxTag.Kind.FILE_REQUEST -> requestProcessor.onAttemptOutcome(outcome)
            OutboxTag.Kind.FILE_PUSH -> pushEngine.onOutboxOutcome(outcome)
            OutboxTag.Kind.FILE_FORWARD -> pushEngine.onForwardOutcome(outcome)

            else -> error("Not a file outcome: ${outcome.tag.kind}")
        }
    }
}
