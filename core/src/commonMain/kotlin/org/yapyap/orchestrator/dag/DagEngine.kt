package org.yapyap.orchestrator.dag

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.envelopes.MessagePayload

/**
 * Sole writer of message rows (docs/room events.md §3: the engine is the sole
 * verdict writer — and exposes no reads at all). All message lookups go through
 * the persistence repositories (`MessageRepository` for messages,
 * `CausalHoldRepository` for gaps, `RoomRepository` for membership); state
 * changes surface on [verificationStateChanges], new arrivals on the inbound
 * pipeline's `ingestResults`.
 */
internal interface DagEngine {
    /**
     * Chains a new message off the room's covering antichain and stores it.
     *
     * @throws DagException.FrontierUnavailable when the chainable frontier is empty.
     */
    suspend fun append(roomId: RoomId, draft: MessageDraft): MessagePayload

    /**
     * Sole genesis append path: mints the message id, derives the self-certifying
     * room id from it (never supplied — derive-for-A-but-construct-with-B is
     * unrepresentable), ensures the rooms row for the messages FK, signs, and
     * stores the node VERIFIED + ancestry-complete.
     *
     * @throws DagException.RoomAlreadyExists if the derived room already holds messages.
     */
    suspend fun createRoom(draft: RoomCreatedDraft): MessagePayload.RoomEvent
    suspend fun ingest(payload: MessagePayload): IngestResult?

    /**
     * Hot stream of stored messages whose verification state changed (never a "new message" signal).
     * Consumers use it e.g. to drop a message from the GUI when it becomes REJECTED.
     */
    val verificationStateChanges: Flow<VerificationStateChange>

    /**
     * Re-verify messages currently held as PENDING that were authored by [deviceId]. Returns the
     * [VerificationStateChange] per message whose state actually changed and emits each to
     * [verificationStateChanges]. Intended to be called once the author's identity becomes known
     * (e.g. on projector `DeviceAdded`).
     */
    suspend fun reverifyPendingFor(deviceId: PeerId): List<VerificationStateChange>

    /**
     * Re-verify every PENDING message (boot-time safety net). Returns the [VerificationStateChange]
     * per message whose state actually changed and emits each to [verificationStateChanges].
     */
    suspend fun reverifyAllPending(): List<VerificationStateChange>
}
