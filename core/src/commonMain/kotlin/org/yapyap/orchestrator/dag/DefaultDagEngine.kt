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
 * message references as a parent). Lamport clock = MAX(parent lamports) + 1, so
 * lamport-ascending order is always a valid topological order; ties occur between
 * concurrent messages and are resolved by (createdAt, messageId).
 *
 * Gap model: when [ingest] receives a message with any [prevIds] entry missing from
 * the DB, the message is inserted as an orphan (`is_orphaned = 1`) with
 * `ancestry_complete = 0`, and one `causal_hold` row per missing parent records
 * `missing_prev_id`. As missing parents arrive, holds are deleted; when the last
 * hold closes, the orphan flag clears, ancestry-completeness is re-derived (with a
 * downward cascade to children), and the lamport structural check runs.
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
        val frontier = messageRepository.findRoomFrontier(roomId)
        val prevIds: List<Uuid>
        val lamport: Long
        if (frontier.isNotEmpty()) {
            prevIds = frontier.map { it.payload.messageId }
            lamport = frontier.maxOf { it.payload.lamportClock } + 1
        } else {
            // TODO(append-guard): refuse to append when the room holds messages but the
            // chainable frontier is empty (every tip parked) — appending now would fork
            // a second root. Falls back to the highest-lamport stored message for now.
            val latest = messageRepository.findLatestInRoom(roomId)
            prevIds = latest?.let { listOf(it.payload.messageId) } ?: emptyList()
            lamport = latest?.payload?.lamportClock?.let { it + 1 } ?: 0L
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
                lamportClock = lamport,
                createdAt = createdAt,
                text = draft.text,
            )
            is MessageDraft.GlobalEvent -> MessagePayload.GlobalEvent(
                messageId = messageId,
                senderAccountId = senderAccountId,
                authorDeviceId = authorDeviceId,
                prevIds = prevIds,
                lamportClock = lamport,
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
                    "lamportClock" to lamport,
                    "prevIds" to prevIds,
                ),
            )
        }

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

            // Determine orphan status: orphaned iff any prevId is not in our DB.
            val presentParents = payload.prevIds.mapNotNull { messageRepository.findById(it) }
            val missingPrevIds = payload.prevIds.filter { id ->
                presentParents.none { it.payload.messageId == id }
            }
            val isOrphaned = missingPrevIds.isNotEmpty()
            val ancestryComplete = !isOrphaned && presentParents.all { it.ancestryComplete }

            // Classify authorship + structure -> verification state. Global-room events defer to the
            // projector (the global DAG is self-verifying: it defines who its own authors may be).
            val state = resolveVerificationState(payload, presentParents, missingPrevIds)

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
            val closedGaps = closeGapsFor(payload.messageId, gapClosureRejections)

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
     * The lamport structural check (`lamport == max(parent lamports) + 1`) can only run when
     * *all* parents are present ([missingPrevIds] empty); it is deferred to gap closure otherwise.
     * Empty [MessagePayload.prevIds] (the DAG root) always passes the structural check here —
     * root uniqueness is a separate, deferred rule.
     */
    private suspend fun resolveVerificationState(
        payload: MessagePayload,
        presentParents: List<MessageRow>,
        missingPrevIds: List<Uuid>,
    ): VerificationState {
        var state = classifyVerification(payload)
        if (missingPrevIds.isEmpty() && payload.prevIds.isNotEmpty()) {
            val expected = presentParents.maxOf { it.payload.lamportClock } + 1
            if (payload.lamportClock != expected) {
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
        val presentParents = payload.prevIds.mapNotNull { messageRepository.findById(it) }
        val missingPrevIds = payload.prevIds.filter { id ->
            presentParents.none { it.payload.messageId == id }
        }
        val newState = resolveVerificationState(payload, presentParents, missingPrevIds)
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
     * Orphans whose holds all close get the lamport structural check
     * (`lamport == max(parent lamports) + 1`) — all parents are present at that point;
     * each violation is appended to [rejections] for out-of-lock emission by the caller.
     *
     * Returns the list of closed `missingPrevId`s (all equal to [arrivedMessageId],
     * one per closed orphan — the UI uses a Set to deduplicate).
     */
    private suspend fun closeGapsFor(
        arrivedMessageId: Uuid,
        rejections: MutableList<VerificationStateChange>,
    ): List<Uuid> {
        val holds = causalHoldRepository.findByMissingPrevId(arrivedMessageId)
        if (holds.isEmpty()) return emptyList()
        causalHoldRepository.deleteByMissingPrevId(arrivedMessageId)

        val refreshed = mutableSetOf<Uuid>()
        for (hold in holds) {
            refreshAncestryDown(hold.orphanedMessageId, refreshed, rejections)
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
     * and itself complete (running the structural check on that flip), and cascades to
     * children. Caller holds [mutex].
     */
    private suspend fun refreshAncestryDown(
        messageId: Uuid,
        refreshed: MutableSet<Uuid>,
        rejections: MutableList<VerificationStateChange>,
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
            if (row.payload.prevIds.isNotEmpty() &&
                row.payload.lamportClock != presentParents.maxOf { it.payload.lamportClock } + 1
            ) {
                messageRepository.updateVerificationState(messageId, VerificationState.REJECTED)
                rejections += VerificationStateChange(
                    messageId = messageId,
                    roomId = row.payload.roomId,
                    fromState = row.verificationState,
                    toState = VerificationState.REJECTED,
                )
            }
            for (child in messageRepository.findChildrenInRoom(messageId, row.payload.roomId)) {
                refreshAncestryDown(child.payload.messageId, refreshed, rejections)
            }
        }
    }
}