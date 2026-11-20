package org.yapyap.orchestrator.file

import org.yapyap.protocol.FileId
import org.yapyap.protocol.envelopes.MessagePayload
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The changes to a receive anyone but the receive engine may ask for. The engine is the only writer of
 * `file_receives`; each verb is a guarded transition under its lock, so a verb never overwrites a state
 * it did not expect (a stall never overwrites READY).
 */
internal interface FileReceiveControl {
    /**
     * The gap trigger (design §8): [fileId] goes STALLED if it is still IN_FLIGHT and its prefix has not
     * moved since [idleSince]. False when it completed, progressed or ended meanwhile.
     */
    suspend fun markStalled(fileId: FileId, idleSince: Instant, now: Instant): Boolean

    /** Above the auto-download limit: [attachment]'s file is DECLINED locally unless a receive already exists. */
    suspend fun declineUnseen(attachment: MessagePayload.Attachment, originMessageId: Uuid, now: Instant)

    /**
     * The user declined [fileId]: a receive not yet READY goes DECLINED; with no receive yet, one is
     * recorded DECLINED from [attachment] (when known) so nothing fetches it later.
     */
    suspend fun decline(fileId: FileId, attachment: MessagePayload.Attachment?, now: Instant)

    /**
     * The user accepted [fileId]: a terminal receive (DECLINED, FAILED, CANCELLED, EVICTED) is cleared so the
     * next OFFER or FETCH_OK starts it afresh. False when the file is already READY.
     */
    suspend fun reopen(fileId: FileId): Boolean
}
