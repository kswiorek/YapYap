package org.yapyap.routing.file

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.routing.file.port.FileCarrier
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileReceiveEnd
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.store.FileIncomingTransferStore
import kotlin.time.Clock
import kotlin.time.Instant

/** Pusher side of [FileCarrier]: the per-device push machinery owns this contract. */
internal interface FilePushOps {
    suspend fun pushFile(fileId: FileId, target: AccountId)
    suspend fun cancelFile(fileId: FileId, target: AccountId)

    /**
     * Serve-on-REQUEST (design §9): a fresh transfer to one [device], covering the
     * suffix from the floor-aligned [fromOffset]. Supersedes a live push of the same
     * file to that device (old transfer CANCELled). False when not held or large.
     */
    suspend fun serveFile(fileId: FileId, device: PeerId, fromOffset: Long): Boolean
}

/**
 * Router-side file carrier (design §12): account-level ops and events. The orchestrator
 * speaks files and accounts through this port; routing fans out per device and signals
 * per transfer. Transfer keys live in routing rows and never cross this seam.
 */
internal class DefaultFileCarrier(
    private val localDeviceId: PeerId,
    private val protection: EnvelopeProtectionService,
    private val incoming: FileIncomingTransferStore,
    private val enqueue: suspend (BinaryEnvelope) -> Unit,
    private val clock: Clock,
    private val routerConfig: StateFlow<RouterConfig>,
    private val pushOps: FilePushOps,
    events: MutableSharedFlow<FileEvent>,
    private val startRequest: suspend (FileId, Instant) -> Unit,
    private val abortFetch: suspend (FileId) -> Unit,
) : FileCarrier {
    override val fileEvents: Flow<FileEvent> = events.asSharedFlow()

    override suspend fun pushFile(fileId: FileId, target: AccountId) =
        pushOps.pushFile(fileId, target)

    override suspend fun cancelFile(fileId: FileId, target: AccountId) =
        pushOps.cancelFile(fileId, target)

    override suspend fun startFileRequest(fileId: FileId, firstAttemptAt: Instant) =
        startRequest(fileId, firstAttemptAt)

    override suspend fun abortFileFetch(fileId: FileId) = abortFetch(fileId)

    /** Answers a receive the engine ended: COMPLETE when ready, CANCEL when it stopped. */
    suspend fun onReceiveEnd(end: FileReceiveEnd) = when (end) {
        is FileReceiveEnd.Ready ->
            endIncoming(end.fileId, FilePayload.Complete, "File READY: COMPLETE sent to transfer author")

        is FileReceiveEnd.Cancelled -> endIncoming(
            end.fileId,
            FilePayload.Cancel(end.reason, reasonText = null),
            "File receive stopped: CANCEL sent to transfer author",
        )
    }

    /** Sends [payload] to every live transfer author of [fileId] and drops their incoming rows. */
    private suspend fun endIncoming(fileId: FileId, payload: FilePayload, message: String) {
        val now = clock.now()
        val expiresAt = now + routerConfig.value.fileTransferTtl
        for (row in incoming.findByFile(fileId)) {
            enqueue(
                buildFileControlEnvelope(
                    protection = protection,
                    transferId = row.transferId,
                    source = localDeviceId,
                    target = row.authorDevice,
                    createdAt = now,
                    expiresAt = expiresAt,
                    payload = payload,
                ),
            )
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_MESSAGE_QUEUED,
                message = message,
                fields = mapOf("fileId" to fileId, "transferId" to row.transferId, "author" to row.authorDevice),
            )
        }
        incoming.deleteByFile(fileId)
    }
}
