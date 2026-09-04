package org.yapyap.orchestrator.dag

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.signature.AuthorshipOutcome
import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.db.VerificationState
import org.yapyap.persistence.messaging.CausalHoldRepository
import org.yapyap.persistence.messaging.MessageCursor
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.MessagePayload
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Concrete [DagEngine] backed by [MessageRepository] + [CausalHoldRepository].
 *
 * Ordering model: per-room linear chain. Every new message chains off the
 * room's current highest-lamport tail (tie-break by createdAt DESC, messageId DESC).
 * Lamport clock = MAX(lamport_clock) in room + 1. Concurrent senders can collide on
 * lamport (sibling branches); display ordering resolves ties via the composite
 * (createdAt, lamportClock, messageId).
 *
 * Gap model: when [ingest] receives a message whose [prevId] is not in the DB,
 * the message is inserted as an orphan (`is_orphaned = 1`) and a `causal_hold`
 * row is created recording `missing_prev_id = prevId`. When the missing message
 * later arrives, all causal_hold rows pointing at it are deleted and the
 * corresponding orphans are marked non-orphaned (`closedGapMissingPrevIds`).
 */
class DefaultDagEngine(
    private val messageRepository: MessageRepository,
    private val causalHoldRepository: CausalHoldRepository,
    private val roomRepository: RoomRepository,
    private val identityResolver: IdentityResolver,
    private val signatureProvider: SignatureProvider,
    private val clock: Clock,
) : DagEngine {

    /**
     * Serializes [append] / [ingest] read-modify-write sequences (read tail → compute lamport → insert →
     * gap bookkeeping) so concurrent coroutine calls don't race on the room counter.
     */
    private val mutex = Mutex()

    private val _verificationStateChanges = MutableSharedFlow<VerificationStateChange>(extraBufferCapacity = 64)
    override val verificationStateChanges: Flow<VerificationStateChange> = _verificationStateChanges.asSharedFlow()

    /**
     * Emits [changes] to [verificationStateChanges]. Never called from inside [mutex] so emission
     * (which can suspend) does not stall the engine lock.
     */
    private suspend fun emitVerificationChanges(changes: List<VerificationStateChange>) {
        changes.forEach { _verificationStateChanges.emit(it) }
    }

    override suspend fun append(roomId: RoomId, draft: MessageDraft): MessagePayload = mutex.withLock {
        val senderAccountId = identityResolver.getLocalAccountId()
        val authorDeviceId = identityResolver.getLocalDeviceId()
        val createdAt = clock.now()
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
                createdAt = createdAt,
                text = draft.text,
            )
            is MessageDraft.GlobalEvent -> MessagePayload.GlobalEvent(
                messageId = messageId,
                senderAccountId = senderAccountId,
                authorDeviceId = authorDeviceId,
                prevId = prevId,
                lamportClock = lamport,
                createdAt = createdAt,
                eventBytes = draft.eventBytes,
            )
        }

        // Get the bytes to sign (without the signature field)
        val bytesToSign = unsignedPayload.encodeForAuthorSigning()

        // Sign the bytes and create the final payload
        val payload = unsignedPayload.withSignature(signatureProvider.sign(bytesToSign))

        val inserted = messageRepository.insert(payload, isOrphaned = false, verificationState = VerificationState.VERIFIED)
        if (!inserted) {
            AppLog.warn(
                component = LogComponent.DAG,
                event = LogEvent.MESSAGE_INSERT_CONFLICT,
                message = "Message insert ignored — duplicate message_id",
                fields = mapOf("messageId" to messageId, "roomId" to roomId),
            )
        }
        else roomRepository.updateLocalSeq(roomId, lamport)

        AppLog.debug(
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

    override suspend fun ingest(payload: MessagePayload): IngestResult? {
        val gapClosureRejections = mutableListOf<VerificationStateChange>()

        val result = mutex.withLock {
            // Dedup: if we already have this message, treat as already-inserted (no new gaps closed).
            if (messageRepository.findById(payload.messageId) != null) {
                AppLog.debug(
                    component = LogComponent.DAG,
                    event = LogEvent.MESSAGE_DEDUPED,
                    message = "Ingested duplicate message — already present",
                    fields = mapOf("messageId" to payload.messageId, "roomId" to payload.roomId),
                )
                return@withLock null
            }

            // Determine orphan status: orphaned iff prevId is non-null AND not in our DB.
            val isOrphaned = payload.prevId != null && messageRepository.findById(payload.prevId!!) == null

            // Classify authorship + structure -> verification state. Global-room events defer to the
            // projector (the global DAG is self-verifying: it defines who its own authors may be).
            val state = resolveVerificationState(payload, isOrphaned)

            val inserted = messageRepository.insert(payload, isOrphaned, state)
            if (!inserted) {
                AppLog.warn(
                    component = LogComponent.DAG,
                    event = LogEvent.MESSAGE_INSERT_CONFLICT,
                    message = "Message ingest ignored — duplicate message_id",
                    fields = mapOf("messageId" to payload.messageId, "roomId" to payload.roomId),
                )
                return@withLock null
            }
            roomRepository.updateLocalSeq(payload.roomId, payload.lamportClock)

            // Gap closure: check if any existing orphans were waiting for THIS message as their prev.
            val closedGaps = closeGapsFor(payload.messageId, gapClosureRejections)

            // Gap creation: if this message is an orphan, record the causal_hold.
            if (isOrphaned) {
                val gapId = Uuid.random()
                causalHoldRepository.insert(
                    gapId = gapId,
                    missingPrevId = payload.prevId!!,
                    orphanedMessageId = payload.messageId,
                    detectedTimestamp = clock.now(),
                )
                AppLog.debug(
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
                AppLog.debug(
                    component = LogComponent.DAG,
                    event = LogEvent.MESSAGE_INGESTED,
                    message = "Message ingested successfully",
                    fields = mapOf(
                        "messageId" to payload.messageId,
                        "roomId" to payload.roomId,
                        "closedGaps" to closedGaps.size,
                        "verificationState" to state,
                    ),
                )
            }

            if (isOrphaned) {
                val anchorLamport = messageRepository.maxLamportBelow(payload.roomId, payload.lamportClock)
                IngestResult.BecameOrphan(
                    payload = payload,
                    closedGapMissingPrevIds = closedGaps,
                    missingPrevId = payload.prevId!!,
                    anchorLamport = anchorLamport ?: -1L,
                    verificationState = state,
                )
            } else {
                IngestResult.Inserted(
                    payload = payload,
                    closedGapMissingPrevIds = closedGaps,
                    verificationState = state,
                )
            }
        }

        emitVerificationChanges(gapClosureRejections)
        return result
    }

    private suspend fun classifyVerification(payload: MessagePayload): VerificationState =
        if (payload.roomId == RoomId.GLOBAL) {
            VerificationState.PENDING
        } else {
            when (signatureProvider.classifyMessageAuthorship(
                accountId = payload.senderAccountId,
                authorDeviceId = payload.authorDeviceId,
                signedBytes = payload.encodeForAuthorSigning(),
                signature = payload.authorSignature,
            )) {
                AuthorshipOutcome.VALID -> VerificationState.VERIFIED
                AuthorshipOutcome.INVALID -> VerificationState.REJECTED
                AuthorshipOutcome.UNKNOWN_AUTHOR -> VerificationState.PENDING
            }
        }

    /**
     * Combined authorship + structural verification state for [payload]. Reused by both [ingest]
     * and the re-verification paths so a message resolves identically wherever it is evaluated.
     *
     * [isOrphaned] must be true iff the payload's prevId is missing: the lamport structural check
     * can only run when the parent is present (it is deferred to gap closure otherwise).
     */
    private suspend fun resolveVerificationState(payload: MessagePayload, isOrphaned: Boolean): VerificationState {
        var state = classifyVerification(payload)
        if (!isOrphaned && payload.prevId != null) {
            val parentLamport = messageRepository.findById(payload.prevId!!)?.payload?.lamportClock
            if (parentLamport != null && payload.lamportClock != parentLamport + 1) {
                state = VerificationState.REJECTED
            }
        }
        return state
    }

    //TODO: projector trigger verify
    override suspend fun reverifyPendingFor(deviceId: PeerId): List<VerificationStateChange> {
        val changes = mutex.withLock {
            messageRepository.findPendingByAuthor(deviceId).mapNotNull { reverify(it) }
        }
        emitVerificationChanges(changes)
        return changes
    }

    override suspend fun reverifyAllPending(): List<VerificationStateChange> {
        val changes = mutex.withLock {
            messageRepository.findAllPending().mapNotNull { reverify(it) }
        }
        emitVerificationChanges(changes)
        return changes
    }

    /**
     * Re-evaluates a stored [org.yapyap.persistence.messaging.MessageRow] under current identity
     * state. Returns a [VerificationStateChange] only if its state actually changed, otherwise null
     * (nothing to report). Caller is responsible for emitting and for holding [mutex].
     */
    private suspend fun reverify(row: org.yapyap.persistence.messaging.MessageRow): VerificationStateChange? {
        val payload = row.payload
        val newState = resolveVerificationState(payload, row.isOrphaned)
        if (newState == row.verificationState) return null

        messageRepository.updateVerificationState(payload.messageId, newState)
        return VerificationStateChange(
            messageId = payload.messageId,
            roomId = payload.roomId,
            fromState = row.verificationState,
            toState = newState,
        )
    }

    override suspend fun getMessagesInRoom(roomId: RoomId): List<MessagePayload> {
        return messageRepository.findAllInRoom(roomId).map { it.payload }
    }

    override suspend fun getMessagesInRoom(
        roomId: RoomId,
        limit: Int,
        before: MessageCursor?,
    ): List<MessagePayload> {
        return messageRepository.findMessagesInRoomPageDesc(
            roomId = roomId,
            limit = limit,
            cursor = before,
        ).map { it.payload }
    }

    override suspend fun ancestorsOf(roomId: RoomId, messageId: Uuid, limit: Int): List<MessagePayload> {
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

    override suspend fun openGaps(roomId: RoomId): List<Gap> {
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
     *
     * Orphans whose lamport does not follow the arriving parent's by one are declared REJECTED;
     * each such transition is appended to [rejections] for out-of-lock emission by the caller.
     */
    private suspend fun closeGapsFor(
        arrivedMessageId: Uuid,
        rejections: MutableList<VerificationStateChange>,
    ): List<Uuid> {
        val holds = causalHoldRepository.findByMissingPrevId(arrivedMessageId)
        if (holds.isEmpty()) return emptyList()

        val arrivedLamport = messageRepository.findById(arrivedMessageId)?.payload?.lamportClock

        for (hold in holds) {
            val orphan = messageRepository.findById(hold.orphanedMessageId)
            if (orphan != null && arrivedLamport != null) {
                // Structural lamport check: an orphan whose lamport is not exactly parent+1 is
                // REJECTED (kept in storage so it still occupies its DAG position).
                if (orphan.payload.lamportClock != arrivedLamport + 1) {
                    messageRepository.updateVerificationState(hold.orphanedMessageId, VerificationState.REJECTED)
                    rejections += VerificationStateChange(
                        messageId = hold.orphanedMessageId,
                        roomId = orphan.payload.roomId,
                        fromState = orphan.verificationState,
                        toState = VerificationState.REJECTED,
                    )
                }
            }
            messageRepository.updateOrphanedFlag(hold.orphanedMessageId, isOrphaned = false)
        }
        causalHoldRepository.deleteByMissingPrevId(arrivedMessageId)

        AppLog.info(
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