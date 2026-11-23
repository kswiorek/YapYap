package org.yapyap.routing.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.envelope.FileEnvelopeProtection
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileFetchAbortReason
import org.yapyap.protocol.FileId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.routing.file.fetch.FileFetchService
import org.yapyap.routing.file.inbound.FileInboundHandler
import org.yapyap.routing.file.inbound.FileRelayDoor
import org.yapyap.routing.file.inbound.FileServeHandler
import org.yapyap.routing.file.inbound.FileTargetHandler
import org.yapyap.routing.file.port.FileCarrier
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileReceiveEnd
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.file.port.FileSendState
import org.yapyap.routing.file.push.FilePushEngine
import org.yapyap.routing.file.push.FileSender
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.ProactiveSessionOpener
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.OutcomeMailbox
import org.yapyap.routing.router.RouterLifecycle
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.FileIncomingTransferStore
import org.yapyap.routing.store.FilePushStore
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.FileRequestAttemptStore
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.PacketOutbox
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The file concern, both directions: push (per-device transfers, relay deposits, the small
 * path) and pull (REQUEST/serve and the large WebRTC fetch). The service *is* the
 * orchestrator-facing [FileCarrier]; the push engine, request loop, fetch and inbound face are
 * its internals, not another layer of facades.
 *
 * Everything file-specific lives in this package: the engines under `push/`/`fetch/`, the
 * signaling under `inbound/`, and the orchestrator seam under `port/`.
 */
internal class FileService(
    private val ctx: RoutingContext,
    private val lifecycle: RouterLifecycle,
    private val plaintext: FilePlaintextSource,
    private val ingest: FileIngestPort,
    private val requests: FileRequestSource,
    private val pushesStore: FilePushStore,
    private val incomingStore: FileIncomingTransferStore,
    private val relayStore: FileRelayStore,
    private val requestAttempts: FileRequestAttemptStore,
    private val outboxProcessor: OutboxProcessor,
    private val packetOutbox: PacketOutbox,
    relaySelection: RelaySelectionPolicy,
    private val peerPolicy: SyncPeerPolicy,
    private val sessionOpener: ProactiveSessionOpener,
    private val linkKeeper: LinkKeeper,
    private val maxIdlePoll: StateFlow<Duration>,
) : FileCarrier {

    private val events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
    private val outcomes = OutcomeMailbox()

    /** The FILE family's protection port: reached through the context like every other service's. */
    private val protection: FileEnvelopeProtection = ctx.envelopeProtections.file

    /** The sizes the transports leave the file paths, likewise from the context. */
    private val fileLimits: StateFlow<FileLimits> = ctx.fileLimits

    private val incomingTransfers = FileIncomingTransfers(incomingStore, ctx.clock)

    private val pushEngine = FilePushEngine(
        ctx = ctx,
        protection = protection,
        plaintext = plaintext,
        pushes = pushesStore,
        outbox = outboxProcessor,
        outboxStore = packetOutbox,
        events = events,
        relaySelection = relaySelection,
        relay = relayStore,
        incoming = incomingTransfers,
    )

    private val sender = FileSender(
        ctx = ctx,
        protection = protection,
        plaintext = plaintext,
        pushes = pushesStore,
        engine = pushEngine,
        events = events,
        fileLimits = fileLimits,
    )

    // Large-path fetch (design §6.6): the fetch protocol on dedicated file channels; no
    // envelopes, no outbox. One actor per channel on each end; state dies with the channel.
    private val fetchService = FileFetchService(
        devices = ctx.devices,
        transport = ctx.webRtcTransport,
        plaintext = plaintext,
        ingest = ingest,
        requests = requests,
        routerConfig = ctx.routerConfig,
        fileLimits = fileLimits,
        events = events,
        clock = ctx.clock,
    )

    private val requestProcessor = FileRequestProcessor(
        ctx = ctx,
        protection = protection,
        attempts = requestAttempts,
        requests = requests,
        outboxProcessor = outboxProcessor,
        peerPolicy = peerPolicy,
        maxIdlePoll = maxIdlePoll,
        events = events,
        linkKeeper = linkKeeper,
        sessionOpener = sessionOpener,
        fetchService = fetchService,
    )

    private val targetHandler = FileTargetHandler(
        ctx = ctx,
        protection = protection,
        incoming = incomingTransfers,
        ingest = ingest,
        signals = pushEngine,
        onOfferReceived = { fileId -> requestProcessor.onOfferReceived(fileId) },
    )

    private val relayDoor = FileRelayDoor(
        ctx = ctx,
        protection = protection,
        relay = relayStore,
        packetOutbox = packetOutbox,
        onRelayWhole = { transferId -> pushEngine.onRelayWhole(transferId) },
    )

    private val serveHandler = FileServeHandler(
        ctx = ctx,
        requests = requests,
        sender = sender,
    )

    /** FILE envelopes: local targets to the target/serve handlers, foreign ones to the door. */
    val inboundHandler = FileInboundHandler(
        ctx = ctx,
        protection = protection,
        target = targetHandler,
        door = relayDoor,
        serve = serveHandler,
    )

    override val fileEvents: Flow<FileEvent> = events.asSharedFlow()

    override suspend fun pushFile(fileId: FileId, target: AccountId) {
        lifecycle.requireStarted("pushing files")
        sender.pushFile(fileId, target)
    }

    override suspend fun cancelFile(fileId: FileId, target: AccountId) {
        lifecycle.requireStarted("cancelling file pushes")
        sender.cancelFile(fileId, target)
    }

    override suspend fun sendState(fileId: FileId): Map<AccountId, FileSendState> = sender.sendState(fileId)

    override suspend fun startFileRequest(fileId: FileId, firstAttemptAt: Instant) {
        lifecycle.requireStarted("starting file requests")
        requestProcessor.start(fileId, firstAttemptAt)
    }

    override suspend fun abortFileFetch(fileId: FileId, reason: FileFetchAbortReason) {
        fetchService.abortFetch(fileId, reason)
    }

    override suspend fun endIncoming(fileId: FileId, end: FileReceiveEnd) {
        val payload = when (end) {
            FileReceiveEnd.Ready -> FilePayload.Complete
            is FileReceiveEnd.Cancelled -> FilePayload.Cancel(end.reason)
        }
        endIncomingTransfers(fileId, payload, message = "File receive ended: answered transfer author")
    }

    /**
     * Drops [fileId]'s incoming rows and sends [payload] to each of their authors. Each answer
     * stands alone: one author's failure (logged) never costs the others theirs. The answer is
     * politeness — an unanswered author's transfer ends on its own TTL.
     */
    private suspend fun endIncomingTransfers(fileId: FileId, payload: FilePayload, message: String) {
        val now = ctx.clock.now()
        val expiresAt = now + ctx.routerConfig.value.fileTransferTtl
        for (row in incomingTransfers.endAllFor(fileId)) {
            try {
                outboxProcessor.enqueueAndWake(
                    buildFileControlEnvelope(
                        protection = protection,
                        transferId = row.transferId,
                        source = ctx.localDeviceId,
                        target = row.authorDevice,
                        createdAt = now,
                        expiresAt = expiresAt,
                        payload = payload,
                    ),
                    nextRetryAt = now,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warn(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Could not answer a transfer author; its transfer ends on its TTL",
                    fields = mapOf("fileId" to fileId, "transferId" to row.transferId, "error" to e.toString()),
                )
                continue
            }
            AppLog.info(
                component = LogComponent.FILE,
                event = LogEvent.FILE_PUSH_ENDED,
                message = message,
                fields = mapOf("fileId" to fileId, "transferId" to row.transferId, "author" to row.authorDevice),
            )
        }
    }

    // --- kernel seams -------------------------------------------------------------------

    /**
     * An OFFER marker's relay copies (design §5): a one-round deposit of its authoring
     * transfer. The outbox hands the row's tag, which carries the transfer id directly.
     */
    suspend fun depositMarker(transferId: TransferId): Int = pushEngine.depositRemainder(transferId)

    fun offerOutcome(outcome: OutboxOutcome) = outcomes.offer(outcome)

    /**
     * Starts the file loops: push reconciliation and the deposit sweep, requests, relay
     * forwards, fetch channels, and the outcome consumer. The router's outbox loop dispatches
     * what they queue.
     */
    fun runIn(scope: CoroutineScope): List<Job> = listOf(
        pushEngine.runIn(scope),
        requestProcessor.runIn(scope),
        fetchService.runIn(scope),
        outcomes.runIn(scope, LogComponent.FILE) { outcome ->
            when (outcome.tag.kind) {
                OutboxTag.Kind.FILE_REQUEST -> requestProcessor.onAttemptOutcome(outcome)
                OutboxTag.Kind.FILE_PUSH -> pushEngine.onOutboxOutcome(outcome)
                OutboxTag.Kind.FILE_FORWARD -> pushEngine.onForwardOutcome(outcome)

                else -> error("Not a file outcome: ${outcome.tag.kind}")
            }
        },
    )
}
