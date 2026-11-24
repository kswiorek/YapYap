package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.logging.LogComponent
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileFetchAbortReason
import org.yapyap.protocol.FileId
import org.yapyap.routing.file.fetch.FileFetchService
import org.yapyap.routing.file.inbound.FileInboundHandler
import org.yapyap.routing.file.inbound.FileServeHandler
import org.yapyap.routing.file.inbound.TargetSide
import org.yapyap.routing.file.port.FileCarrier
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileReceiveEnd
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.file.port.FileSendState
import org.yapyap.routing.file.relay.RelayDoor
import org.yapyap.routing.file.relay.RelayForwards
import org.yapyap.routing.file.seal.SealRegistry
import org.yapyap.routing.file.transfer.TransferEngine
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.OutcomeMailbox
import org.yapyap.routing.router.RouterLifecycle
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileIncomingStore
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.FileRequestAttemptStore
import org.yapyap.routing.store.FileSealStore
import org.yapyap.routing.store.FileTransferStore
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.PacketOutbox
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The file concern, both directions, and the orchestrator-facing [FileCarrier]
 * (docs/envelope-redesign.md §5):
 *
 * - **transfers** ([TransferEngine]) — every hop we send, ours and relay forwards alike;
 * - **seals** ([SealRegistry]) — the origin side: push, serve, deposits, delivery state;
 * - **relay** ([RelayDoor], [RelayForwards]) — deposits for other devices, forwarded when whole;
 * - **target** ([TargetSide]) — seals and transfers delivering to us, into the receive engine;
 * - **pull** ([FileRequestProcessor], [FileFetchService]) — REQUEST/serve and the large fetch.
 *
 * Everything file-specific lives in this package; the orchestrator seam is under `port/`.
 */
internal class FileService(
    ctx: RoutingContext,
    private val lifecycle: RouterLifecycle,
    plaintext: FilePlaintextSource,
    ingest: FileIngestPort,
    requests: FileRequestSource,
    sealStore: FileSealStore,
    transferStore: FileTransferStore,
    incomingStore: FileIncomingStore,
    relayStore: FileRelayStore,
    requestAttempts: FileRequestAttemptStore,
    outboxProcessor: OutboxProcessor,
    packetOutbox: PacketOutbox,
    relaySelection: RelaySelectionPolicy,
    peerPolicy: SyncPeerPolicy,
    webRtcLinkKeeper: LinkKeeper,
    torLinkKeeper: LinkKeeper,
    maxIdlePoll: StateFlow<Duration>,
) : FileCarrier {

    private val events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
    private val outcomes = OutcomeMailbox()

    private val engine =
        TransferEngine(ctx, transferStore, sealStore, relayStore, plaintext, outboxProcessor, packetOutbox)
    private val seals = SealRegistry(ctx, sealStore, transferStore, engine, plaintext, relaySelection, events)
    private val forwards = RelayForwards(ctx, relayStore, transferStore, engine)
    private val relayDoor = RelayDoor(ctx, relayStore, forwards)

    // Large-path fetch (design §6.6): the fetch protocol on dedicated file channels; no
    // envelopes, no outbox. One actor per channel on each end; state dies with the channel.
    private val fetchService = FileFetchService(
        devices = ctx.devices,
        transport = ctx.webRtcTransport,
        plaintext = plaintext,
        ingest = ingest,
        requests = requests,
        routerConfig = ctx.routerConfig,
        fileLimits = ctx.fileLimits,
        events = events,
        clock = ctx.clock,
    )

    private val requestProcessor = FileRequestProcessor(
        ctx = ctx,
        attempts = requestAttempts,
        requests = requests,
        outboxProcessor = outboxProcessor,
        peerPolicy = peerPolicy,
        maxIdlePoll = maxIdlePoll,
        events = events,
        linkKeeper = torLinkKeeper,
        webRtcLinks = webRtcLinkKeeper,
        fetchService = fetchService,
    )

    private val target = TargetSide(ctx, incomingStore, ingest, requests, requestProcessor, outboxProcessor)

    /** FILE envelopes, dispatched by routing header. */
    val inboundHandler = FileInboundHandler(
        ctx = ctx,
        target = target,
        door = relayDoor,
        engine = engine,
        seals = seals,
        serve = FileServeHandler(ctx, requests, seals),
    )

    override val fileEvents: Flow<FileEvent> = events.asSharedFlow()

    override suspend fun pushFile(fileId: FileId, target: AccountId) {
        lifecycle.requireStarted("pushing files")
        seals.push(fileId, target)
    }

    override suspend fun cancelFile(fileId: FileId, target: AccountId) {
        lifecycle.requireStarted("cancelling file pushes")
        seals.cancel(fileId, target)
    }

    override suspend fun sendState(fileId: FileId): Map<AccountId, FileSendState> = seals.sendState(fileId)

    override suspend fun startFileRequest(fileId: FileId, firstAttemptAt: Instant) {
        lifecycle.requireStarted("starting file requests")
        requestProcessor.start(fileId, firstAttemptAt)
    }

    override suspend fun abortFileFetch(fileId: FileId, reason: FileFetchAbortReason) {
        fetchService.abortFetch(fileId, reason)
    }

    override suspend fun endIncoming(fileId: FileId, end: FileReceiveEnd) {
        target.endReceive(fileId, end)
    }

    fun offerOutcome(outcome: OutboxOutcome) = outcomes.offer(outcome)

    /**
     * Starts the file loops. The transfers' owners subscribe before the engine runs, so they hear
     * every transfer event; the router's outbox loop dispatches what they all queue.
     */
    fun runIn(scope: CoroutineScope): List<Job> = listOf(
        seals.runIn(scope),
        forwards.runIn(scope),
        engine.runIn(scope),
        target.runIn(scope),
        requestProcessor.runIn(scope),
        fetchService.runIn(scope),
        outcomes.runIn(scope, LogComponent.FILE) { outcome ->
            when (outcome.tag.kind) {
                OutboxTag.Kind.FILE_TRANSFER -> engine.onOutcome(outcome)
                OutboxTag.Kind.FILE_REQUEST -> requestProcessor.onAttemptOutcome(outcome)
                else -> error("Not a file outcome: ${outcome.tag.kind}")
            }
        },
    )
}
