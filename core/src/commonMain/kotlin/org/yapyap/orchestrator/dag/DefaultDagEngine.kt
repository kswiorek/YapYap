package org.yapyap.orchestrator.dag

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.persistence.db.MessageLifecycleState
import org.yapyap.persistence.messaging.CausalHoldRepository
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.time.EpochSecondsProvider
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Concrete [DagEngine] backed by [MessageRepository] + [CausalHoldRepository].
 *
 * Ordering model: per-room linear chain. Every new message chains off the
 * room's current highest-lamport tail (tie-break by createdAt DESC, messageId DESC).
 * Lamport clock = MAX(lamport_clock) in room + 1. Concurrent senders can collide on
 * lamport (sibling branches); display ordering resolves ties via the composite
 * (createdAtEpochSeconds, lamportClock, messageId).
 *
 * Gap model: when [ingest] receives a message whose [prevId] is not in the DB,
 * the message is inserted as an orphan (`is_orphaned = 1`) and a `causal_hold`
 * row is created recording `missing_prev_id = prevId`. When the missing message
 * later arrives, all causal_hold rows pointing at it are deleted and the
 * corresponding orphans are marked non-orphaned (`closedGapMissingPrevIds`).
 */
@OptIn(ExperimentalUuidApi::class)
class DefaultDagEngine(
    private val messageRepository: MessageRepository,
    private val causalHoldRepository: CausalHoldRepository,
    private val identityResolver: IdentityResolver,
    private val signatureProvider: SignatureProvider,
    private val timeProvider: EpochSecondsProvider,
    private val logger: AppLogger = NoopAppLogger,
) : DagEngine {

    /**
     * Serializes [append] / [ingest] read-modify-write sequences (read tail → compute lamport → insert →
     * gap bookkeeping) so concurrent coroutine calls don't race on the room counter.
     */
    private val mutex = Mutex()

    override suspend fun append(roomId: String, draft: MessageDraft): MessagePayload = mutex.withLock {
        val senderAccountId = identityResolver.getLocalAccountId()
        val authorDeviceId = identityResolver.getLocalDeviceId()
        val createdAt = timeProvider.nowEpochSeconds()
        val tail = messageRepository.findRoomTail(roomId)
        val prevId = tail?.payload?.messageId
        val lamport = tail?.payload?.lamportClock?.let { it + 1 } ?: 0L
        val messageId = Uuid.random()

        // Create an unsigned payload (signature is null)
        val unsignedPayload = when (draft) {
            is MessageDraft.Text -> MessagePayload.Text(
                messageId = messageId,
                roomId = roomId,
                senderAccountId = senderAccountId,
                authorDeviceId = authorDeviceId,
                prevId = prevId,
                lamportClock = lamport,
                createdAtEpochSeconds = createdAt,
                text = draft.text,
            )
            is MessageDraft.GlobalEvent -> MessagePayload.GlobalEvent(
                messageId = messageId,
                roomId = roomId,
                senderAccountId = senderAccountId,
                authorDeviceId = authorDeviceId,
                prevId = prevId,
                lamportClock = lamport,
                createdAtEpochSeconds = createdAt,
                eventBytes = draft.eventBytes,
            )
        }

        // Get the bytes to sign (without the signature field)
        val bytesToSign = unsignedPayload.encodeForAuthorSigning()

        // Sign the bytes and create the final payload
        val payload = unsignedPayload.withSignature(signatureProvider.sign(bytesToSign))

        val inserted = messageRepository.insert(payload, MessageLifecycleState.CREATED, isOrphaned = false)
        if (!inserted) {
            logger.warn(
                component = LogComponent.DAG,
                event = LogEvent.MESSAGE_INSERT_CONFLICT,
                message = "Message insert ignored — duplicate message_id",
                fields = mapOf("messageId" to messageId, "roomId" to roomId),
            )
        }

        logger.debug(
            component = LogComponent.DAG,
            event = LogEvent.MESSAGE_APPENDED,
            message = "Message appended to room DAG",
            fields = mapOf(
                "messageId" to messageId,
                "roomId" to roomId,
                "lamportClock" to lamport,
                "prevId" to (prevId ?: "null"),
            ),
        )

        payload
    }

    override suspend fun ingest(payload: MessagePayload): IngestResult? = mutex.withLock {
        // Verify author signature before processing
        val signature = payload.authorSignature
        if (signature == null) {
            logger.warn(
                component = LogComponent.DAG,
                event = LogEvent.MESSAGE_REJECTED_INVALID_SIGNATURE,
                message = "Message rejected — missing author signature",
                fields = mapOf(
                    "messageId" to payload.messageId,
                    "roomId" to payload.roomId,
                    "senderAccountId" to payload.senderAccountId,
                    "authorDeviceId" to payload.authorDeviceId,
                ),
            )
            return@withLock null
        }

        val signedBytes = payload.encodeForAuthorSigning()
        val signatureValid = signatureProvider.verifyMessageAuthorship(
            accountId = payload.senderAccountId,
            authorDeviceId = payload.authorDeviceId,
            signedBytes = signedBytes,
            signature = signature,
        )
        if (!signatureValid) {
            logger.warn(
                component = LogComponent.DAG,
                event = LogEvent.MESSAGE_REJECTED_INVALID_SIGNATURE,
                message = "Message rejected — invalid author signature",
                fields = mapOf(
                    "messageId" to payload.messageId,
                    "roomId" to payload.roomId,
                    "senderAccountId" to payload.senderAccountId,
                    "authorDeviceId" to payload.authorDeviceId,
                ),
            )
            return@withLock null
        }

        // Dedup: if we already have this message, treat as already-inserted (no new gaps closed).
        if (messageRepository.findById(payload.messageId) != null) {
            logger.debug(
                component = LogComponent.DAG,
                event = LogEvent.MESSAGE_DEDUPED,
                message = "Ingested duplicate message — already present",
                fields = mapOf("messageId" to payload.messageId, "roomId" to payload.roomId),
            )
            return@withLock null
        }

        // Determine orphan status: orphaned iff prevId is non-null AND not in our DB.
        val isOrphaned = payload.prevId != null && messageRepository.findById(payload.prevId!!) == null

        // Insert the message.
        messageRepository.insert(payload, MessageLifecycleState.CREATED, isOrphaned)

        // Gap closure: check if any existing orphans were waiting for THIS message as their prev.
        val closedGaps = closeGapsFor(payload.messageId)

        // Gap creation: if this message is an orphan, record the causal_hold.
        if (isOrphaned) {
            val gapId = Uuid.random().toString()
            causalHoldRepository.insert(
                gapId = gapId,
                missingPrevId = payload.prevId!!,
                orphanedMessageId = payload.messageId,
                detectedTimestamp = timeProvider.nowEpochSeconds(),
            )
            logger.debug(
                component = LogComponent.DAG,
                event = LogEvent.GAP_DETECTED,
                message = "Message ingested as orphan — gap recorded",
                fields = mapOf(
                    "messageId" to payload.messageId,
                    "roomId" to payload.roomId,
                    "missingPrevId" to payload.prevId!!,
                ),
            )
        } else {
            logger.debug(
                component = LogComponent.DAG,
                event = LogEvent.MESSAGE_INGESTED,
                message = "Message ingested successfully",
                fields = mapOf(
                    "messageId" to payload.messageId,
                    "roomId" to payload.roomId,
                    "closedGaps" to closedGaps.size,
                ),
            )
        }

        if (isOrphaned) {
            IngestResult.BecameOrphan(
                payload = payload,
                closedGapMissingPrevIds = closedGaps,
                missingPrevId = payload.prevId!!,
            )
        } else {
            IngestResult.Inserted(
                payload = payload,
                closedGapMissingPrevIds = closedGaps,
            )
        }
    }

    override suspend fun getMessagesInRoom(roomId: String): List<MessagePayload> {
        return messageRepository.findAllInRoom(roomId).map { it.payload }
    }

    override suspend fun getMessagesInRoom(
        roomId: String,
        limit: Int,
        before: MessagePageCursor?,
    ): List<MessagePayload> {
        return messageRepository.findMessagesInRoomPageDesc(
            roomId = roomId,
            limit = limit,
            cursorCreated = before?.createdAtEpochSeconds,
            cursorLamport = before?.lamportClock ?: 0L,
            cursorMessageId = before?.messageId,
        ).map { it.payload }
    }

    override suspend fun ancestorsOf(roomId: String, messageId: Uuid, limit: Int): List<MessagePayload> {
        val result = mutableListOf<MessagePayload>()
        var current = messageRepository.findById(messageId) ?: return result
        var steps = 0

        while (steps < limit) {
            val prevId = current.payload.prevId ?: break
            val prev = messageRepository.findById(prevId) ?: break
            result.add(prev.payload)
            current = prev
            steps++
        }

        return result
    }

    override suspend fun openGaps(roomId: String): List<Gap> {
        return causalHoldRepository.findByRoom(roomId).map { row ->
            Gap(
                missingPrevId = row.missingPrevId,
                orphanedMessageId = row.orphanedMessageId,
            )
        }
    }

    override suspend fun openGaps(): List<Gap> {
        return causalHoldRepository.findAll().map { row ->
            Gap(
                missingPrevId = row.missingPrevId,
                orphanedMessageId = row.orphanedMessageId,
            )
        }
    }

    /**
     * Closes all causal_hold entries whose `missing_prev_id` equals [arrivedMessageId]:
     * marks the orphaned messages as non-orphaned, deletes their causal_hold rows,
     * and returns the list of closed `missingPrevId`s (all equal to [arrivedMessageId],
     * one per closed orphan — the UI uses a Set to deduplicate).
     */
    private fun closeGapsFor(arrivedMessageId: Uuid): List<Uuid> {
        val holds = causalHoldRepository.findByMissingPrevId(arrivedMessageId)
        if (holds.isEmpty()) return emptyList()

        for (hold in holds) {
            messageRepository.updateOrphanedFlag(hold.orphanedMessageId, isOrphaned = false)
        }
        causalHoldRepository.deleteByMissingPrevId(arrivedMessageId)

        logger.info(
            component = LogComponent.DAG,
            event = LogEvent.GAP_CLOSED,
            message = "Gaps closed by arriving message",
            fields = mapOf(
                "arrivedMessageId" to arrivedMessageId,
                "closedOrphanCount" to holds.size,
            ),
        )

        return holds.map { it.missingPrevId }
    }
}