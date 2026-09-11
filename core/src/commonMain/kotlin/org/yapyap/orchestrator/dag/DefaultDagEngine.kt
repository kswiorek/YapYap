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
import org.yapyap.persistence.messaging.*
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.MessagePayload
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Concrete [DagEngine] backed by [MessageRepository] + [CausalHoldRepository].
 *
 * Ordering model: per-room multi-parent DAG. Every new message references the room's
 * current chainable frontier (covering antichain: chainable messages no chainable
 * message references as a parent). Display order is wall-clock
 * (`createdAt`, `messageId`); causal order is recovered by walking `prevIds`
 * edges, never by a scalar clock.
 *
 * Gap model: when [ingest] receives a message with any [prevIds] entry missing from
 * the DB, the message is inserted as an orphan (`is_orphaned = 1`) with
 * `ancestry_complete = 0`, and one `causal_hold` row per missing parent records
 * `missing_prev_id`. As missing parents arrive, holds are deleted; when the last
 * hold closes, the orphan flag clears and ancestry-completeness is re-derived
 * (with a downward cascade to children).
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
     * Serializes [append] / [ingest] read-modify-write sequences (read frontier → insert →
     * gap bookkeeping) so concurrent coroutine calls don't race on the room DAG.
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
        val frontier = messageRepository.findRoomFrontier(roomId)
        val prevIds: List<Uuid> = if (frontier.isNotEmpty()) {
            frontier.map { it.payload.messageId }
        } else if (messageRepository.hasMessages(roomId)) {
            AppLog.warn(
                component = LogComponent.DAG,
                event = LogEvent.APPEND_REFUSED,
                message = "Append refused — room holds messages but the chainable frontier is empty",
                fields = mapOf("roomId" to roomId),
            )
            throw DagException.FrontierUnavailable(roomId)
        } else {
            emptyList()
        }
        val parents = prevIds.mapNotNull { messageRepository.findById(it) }
        val ancestryComplete = parents.size == prevIds.size && parents.all { it.ancestryComplete }
        val messageId = Uuid.random()

        // Create an unsigned payload (signature is null)
        val unsignedPayload = when (draft) {
            is MessageDraft.Text -> MessagePayload.Text(
                messageId = messageId,
                roomId = roomId,
                senderAccountId = senderAccountId,
                authorDeviceId = authorDeviceId,
                prevIds = prevIds,
                createdAt = createdAt,
                text = draft.text,
            )

            is MessageDraft.GlobalEvent -> MessagePayload.GlobalEvent(
                messageId = messageId,
                senderAccountId = senderAccountId,
                authorDeviceId = authorDeviceId,
                prevIds = prevIds,
                createdAt = createdAt,
                eventBytes = draft.event.encode(),
            )
        }

        // Get the bytes to sign (without the signature field)
        val bytesToSign = unsignedPayload.encodeForAuthorSigning()

        // Sign the bytes and create the final payload
        val payload = unsignedPayload.withSignature(signatureProvider.sign(bytesToSign))

        val inserted =
            messageRepository.insert(payload, isOrphaned = false, ancestryComplete, VerificationState.VERIFIED)
        for (parentId in prevIds) {
            messageRepository.insertParent(payload.messageId, parentId)
        }
        if (!inserted) {
            AppLog.warn(
                component = LogComponent.DAG,
                event = LogEvent.MESSAGE_INSERT_CONFLICT,
                message = "Message insert ignored — duplicate message_id",
                fields = mapOf("messageId" to messageId, "roomId" to roomId),
            )
        } else {
            AppLog.debug(
                component = LogComponent.DAG,
                event = LogEvent.MESSAGE_APPENDED,
                message = "Message appended to room DAG",
                fields = mapOf(
                    "messageId" to messageId,
                    "roomId" to roomId,
                    "prevIds" to prevIds,
                ),
            )
        }

        payload
    }

    override suspend fun ingest(payload: MessagePayload): IngestResult? {
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

            // Determine orphan status: orphaned iff any prevId is not in our DB.
            val presentParents = payload.prevIds.mapNotNull { messageRepository.findById(it) }
            val missingPrevIds = payload.prevIds.filter { id ->
                presentParents.none { it.payload.messageId == id }
            }
            val isOrphaned = missingPrevIds.isNotEmpty()
            val ancestryComplete = !isOrphaned && presentParents.all { it.ancestryComplete }

            // Classify authorship -> verification state. Global-room events defer to the
            // projector (the global DAG is self-verifying: it defines who its own authors may be).
            val state = classifyVerification(payload)

            val inserted = messageRepository.insert(payload, isOrphaned, ancestryComplete, state)
            if (!inserted) {
                AppLog.warn(
                    component = LogComponent.DAG,
                    event = LogEvent.MESSAGE_INSERT_CONFLICT,
                    message = "Message ingest ignored — duplicate message_id",
                    fields = mapOf("messageId" to payload.messageId, "roomId" to payload.roomId),
                )
                return@withLock null
            }
            for (parentId in payload.prevIds) {
                messageRepository.insertParent(payload.messageId, parentId)
            }

            // Gap closure: check if any existing orphans were waiting for THIS message as a parent.
            val closedGaps = closeGapsFor(payload.messageId)

            // Gap creation: for each missing parent, record a causal_hold.
            if (isOrphaned) {
                for (missing in missingPrevIds) {
                    causalHoldRepository.insert(
                        gapId = Uuid.random(),
                        missingPrevId = missing,
                        orphanedMessageId = payload.messageId,
                        detectedTimestamp = clock.now(),
                    )
                }
                AppLog.debug(
                    component = LogComponent.DAG,
                    event = LogEvent.GAP_DETECTED,
                    message = "Message ingested as orphan — gaps recorded",
                    fields = mapOf(
                        "messageId" to payload.messageId,
                        "roomId" to payload.roomId,
                        "missingPrevIds" to missingPrevIds,
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
                IngestResult.BecameOrphan(
                    payload = payload,
                    closedGapMissingPrevIds = closedGaps,
                    missingPrevIds = missingPrevIds,
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
    private suspend fun reverify(row: MessageRow): VerificationStateChange? {
        val payload = row.payload
        val newState = classifyVerification(payload)
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
        val visited = mutableSetOf(messageId)
        val queue = ArrayDeque<Uuid>()
        queue.add(messageId)

        while (queue.isNotEmpty() && result.size < limit) {
            val current = queue.removeFirst()
            for (parentId in messageRepository.findParents(current)) {
                if (!visited.add(parentId)) continue
                val parent = messageRepository.findById(parentId) ?: continue
                if (parent.payload.roomId != roomId) continue
                result.add(parent.payload)
                queue.add(parentId)
            }
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
     * deletes their causal_hold rows, clears the orphan flag only for orphans with no
     * remaining holds, and re-derives ancestry-completeness (with a downward cascade
     * to children that were incomplete only because of it).
     *
     * Returns the list of closed `missingPrevId`s (all equal to [arrivedMessageId],
     * one per closed orphan — the UI uses a Set to deduplicate).
     */
    private suspend fun closeGapsFor(
        arrivedMessageId: Uuid,
    ): List<Uuid> {
        val holds = causalHoldRepository.findByMissingPrevId(arrivedMessageId)
        if (holds.isEmpty()) return emptyList()
        causalHoldRepository.deleteByMissingPrevId(arrivedMessageId)

        val refreshed = mutableSetOf<Uuid>()
        for (hold in holds) {
            refreshAncestryDown(hold.orphanedMessageId, refreshed)
        }

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

    /**
     * Re-derives ancestry-completeness for [messageId]: clears the orphan flag when no
     * holds remain, flips `ancestry_complete` false→true when every parent is present
     * and itself complete, and cascades to children. Caller holds [mutex].
     */
    private suspend fun refreshAncestryDown(
        messageId: Uuid,
        refreshed: MutableSet<Uuid>,
    ) {
        if (!refreshed.add(messageId)) return
        val row = messageRepository.findById(messageId) ?: return

        if (row.isOrphaned && causalHoldRepository.countByOrphan(messageId) == 0L) {
            messageRepository.updateOrphanedFlag(messageId, isOrphaned = false)
        }

        val presentParents = row.payload.prevIds.mapNotNull { messageRepository.findById(it) }
        val complete = presentParents.size == row.payload.prevIds.size &&
                presentParents.all { it.ancestryComplete }
        if (complete && !row.ancestryComplete) {
            messageRepository.updateAncestryComplete(messageId, complete = true)
            for (child in messageRepository.findChildrenInRoom(messageId, row.payload.roomId)) {
                refreshAncestryDown(child.payload.messageId, refreshed)
            }
        }
    }
}