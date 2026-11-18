package org.yapyap.routing.file

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.store.FileIncomingTransferStore
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Pusher side of [FileCarrier] (step 13 owns the per-device push machinery). */
internal interface FilePushOps {
    suspend fun pushFile(fileId: FileId, target: AccountId)
    suspend fun cancelFile(fileId: FileId, target: AccountId)
}

/**
 * Scaffolding until step 13 wires the pusher: loud (never silent) if a push is
 * attempted before the push machinery exists. Nothing calls it in steps 11–12
 * (no sender exists yet).
 */
internal class UnwiredFilePushOps : FilePushOps {
    override suspend fun pushFile(fileId: FileId, target: AccountId): Unit =
        error("File push lands in step 13 (FilePusher)")

    override suspend fun cancelFile(fileId: FileId, target: AccountId): Unit =
        error("File push lands in step 13 (FilePusher)")
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
) : FileCarrier {
    private val events = events
    override val fileEvents: Flow<FileEvent> = events.asSharedFlow()

    override suspend fun pushFile(fileId: FileId, target: AccountId) =
        pushOps.pushFile(fileId, target)

    override suspend fun cancelFile(fileId: FileId, target: AccountId) =
        pushOps.cancelFile(fileId, target)

    override suspend fun fileReady(fileId: FileId) {
        val now = clock.now()
        val expiresAt = now + routerConfig.value.fileTransferTtl
        val rows = incoming.findByFile(fileId)
        for (row in rows) {
            val envelope = buildFileControlEnvelope(
                protection = protection,
                transferId = row.transferId,
                source = localDeviceId,
                target = row.authorDevice,
                createdAt = now,
                expiresAt = expiresAt,
                payload = FilePayload.Complete,
            )
            enqueue(envelope)
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.OUTBOX_MESSAGE_QUEUED,
                message = "File READY: COMPLETE sent to transfer author",
                fields = mapOf(
                    "fileId" to fileId,
                    "transferId" to row.transferId,
                    "author" to row.authorDevice,
                ),
            )
        }
        incoming.deleteByFile(fileId)
        emit(FileEvent.Ready(fileId))
    }

    override suspend fun emit(event: FileEvent) {
        events.emit(event)
    }
}
