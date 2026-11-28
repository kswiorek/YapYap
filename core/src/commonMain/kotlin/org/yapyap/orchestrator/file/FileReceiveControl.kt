package org.yapyap.orchestrator.file

import org.yapyap.protocol.FileId
import org.yapyap.protocol.MessageId
import org.yapyap.protocol.envelopes.DagPayload
import kotlin.time.Instant

/**
 * The changes to a receive anyone but the receive engine may ask for. The engine is the only writer of
 * `file_receives`; each verb is a guarded transition under its lock, so a verb never overwrites a state
 * it did not expect (a stall never overwrites READY).
 */
internal interface FileReceiveControl {
    /**
     * The gap trigger (design §8): [fileId] goes STALLED if it is still IN_FLIGHT.
     * The coordinator tracks the last P advance per file on a monotonic clock (time while
     * this device runs) and decides staleness; the engine re-checks IN_FLIGHT under its
     * lock, so a completion meanwhile wins. False when it completed or ended meanwhile.
     */
    suspend fun markStalled(fileId: FileId, now: Instant): Boolean

    /** Above the auto-download limit: [attachment]'s file is DECLINED locally unless a receive already exists. */
    suspend fun declineUnseen(attachment: DagPayload.Attachment, originMessageId: MessageId, now: Instant)

    /**
     * The user declined [fileId]: a receive not yet READY goes DECLINED; with no receive yet, one is
     * recorded DECLINED from [attachment] (when known) so nothing fetches it later.
     * An IN_FLIGHT receive also drops its `.part` and staged rows and sends CANCEL (DECLINED).
     */
    suspend fun decline(fileId: FileId, attachment: DagPayload.Attachment?, now: Instant)

    /**
     * The user accepted [fileId]: a terminal receive (DECLINED, FAILED, CANCELLED, EVICTED) is cleared so the
     * next OFFER or PULL_OK starts it afresh. False when the file is already READY.
     */
    suspend fun reopen(fileId: FileId): Boolean

    /**
     * Quota eviction (design §10): a READY receive goes EVICTED under the file lock, then its
     * descriptor is forgotten and its final file deleted, in that order. False when [fileId] has no
     * READY receive (a sent copy, or already evicted): the caller evicts it as a plain copy.
     */
    suspend fun evicted(fileId: FileId, now: Instant): Boolean

    /** Unreferenced retention: drop [fileId]'s receive, `.part` and staged rows, whatever its status. */
    suspend fun forgetUnreferenced(fileId: FileId)

    /**
     * A message now references [fileId]: record [messageId] as its origin (retention backstop).
     * No-op when the receive already has an origin or is gone.
     */
    suspend fun updateOrigin(fileId: FileId, messageId: MessageId, now: Instant)
}
