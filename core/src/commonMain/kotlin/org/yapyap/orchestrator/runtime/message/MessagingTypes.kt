package org.yapyap.orchestrator.runtime.message

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomId
import kotlin.time.Instant
import kotlin.uuid.Uuid

public sealed interface MessageDisplayItem {
    /** Stable identity used as a Compose list key and for targeted removal (e.g. on rejection). */
    public val messageId: Uuid
    public val accountId: AccountId
    public val timestamp: Instant

    public data class Text(
        override val messageId: Uuid,
        override val accountId: AccountId,
        override val timestamp: Instant,
        val text: String,
    ) : MessageDisplayItem

    public data class File(
        override val messageId: Uuid,
        override val accountId: AccountId,
        override val timestamp: Instant,
        val fileId: String,
        val fileName: String,
        val fileSize: Long,
    ) : MessageDisplayItem

    public data class Gap(
        override val messageId: Uuid,
        override val accountId: AccountId,
        override val timestamp: Instant,
        val missingPrevIds: List<Uuid>,
    ) : MessageDisplayItem
}

public data class Message(
    val content: String,
    val attachments: List<MessageAttachment> = emptyList(),
    /**
     * Files to attach to this send. The messaging service imports each one (hashing into
     * the file store) and publishes the message with the resulting attachments — the GUI
     * never handles `fileId`s itself and issues no second call. Small files start pushing
     * on send; large files are pull-only and wait for the receiver to fetch.
     */
    val pickedFiles: List<PickedFile> = emptyList(),
)

/** A GUI-picked file to attach to a message send. */
public data class PickedFile(
    val fileName: String,
    val mimeType: String?,
    val source: PickedFileSource,
    /**
     * Copy the file into the app's store (it survives the original moving) or only reference it.
     * Null leaves it to the "keep a copy" size threshold in settings.
     */
    val keepCopy: Boolean? = null,
)

/** Where the bytes of a [PickedFile] live. */
public sealed interface PickedFileSource {
    /** A platform file path. */
    public data class FilePath(val path: String) : PickedFileSource

    /** An Android content URI (persistable permission held by the GUI). */
    public data class ContentUri(val uri: String) : PickedFileSource
}

public data class MessageAttachment(
    val fileId: String,
    val fileName: String,
    val mimeType: String?,
    val totalBytes: Long,
)

/**
 * "Something new in this room" signal carrying the policy-vetted
 * [MessageDisplayItem], unformatted — presentation (truncation, "sent a file"
 * labels) is the GUI's call. The emit path is gated on the renderable check
 * (docs/room events.md §3, enforced in SQL), so hidden messages and
 * non-displayable payloads never ride this event; [org.yapyap.orchestrator.runtime.message.MessagingService.roomPreview]
 * remains the source of truth for initial population and re-pulls (e.g.
 * after a REJECTED drop).
 */
public data class IncomingMessageEvent(
    val roomId: RoomId,
    val senderAccountId: AccountId,
    val item: MessageDisplayItem,
)

/**
 * Latest *visible* message of a room (docs/room events.md §3): the newest row
 * of the renderable page query — non-`REJECTED` and inside the removal boundary —
 * that maps to a [MessageDisplayItem]. Null when the room holds no visible message
 * (empty, pre-fold, or all-hidden).
 *
 * The item is carried unformatted: truncation, "sent a file" labels and any
 * other presentation decisions are GUI concerns.
 */
public data class RoomPreview(
    val messageId: Uuid,
    val senderAccountId: AccountId,
    val timestamp: Instant,
    val item: MessageDisplayItem,
)

/**
 * A paginated window into a room's messages.
 * Created by [MessagingService.openRoom]; caller must [close] when done.
 */
public interface RoomMessageWindow {
    /** Current loaded messages (oldest→newest). Bind this to the GUI list. */
    public val displayItems: StateFlow<List<MessageDisplayItem>>

    /** Whether older messages exist beyond the currently loaded window. */
    public val hasMoreOlder: StateFlow<Boolean>

    /**
     * Load the next page of older messages (prepended to [displayItems]).
     * @return Number of messages loaded (0 means no more older messages).
     */
    public suspend fun loadOlder(pageSize: Int = 50): Int

    /** Release this window and unsubscribe from updates. */
    public suspend fun close()
}

/** Outcome of the GUI-facing text-send flow. Domain refusals are values;
 *  infrastructure failures (transport, storage) still throw. */
public sealed interface SendMessageResult {
    /** Appended to the room DAG and fanned out. [fanout] is a send-time
     *  reachability snapshot — advisory, never an error state. */
    public data class Sent(val fanout: FanoutReport) : SendMessageResult

    /** Refused before any write — nothing appended, nothing sent. */
    public data class Refused(val reason: SendRefusal) : SendMessageResult
}

public sealed interface SendRefusal {
    /** Local account holds a committed REMOVED row for the room. */
    public data object NotMember : SendRefusal

    /** Text exceeds [MessagingService.maxTextMessageBytes]. */
    public data object TooLarge : SendRefusal

    /** Room frontier unchainable — still syncing. */
    public data object HistoryIncomplete : SendRefusal
}

/**
 * [accountId] is now known to hold [messageId], one of the local account's messages in
 * [roomId] (see [MessagingService.deliveryUpdates]).
 */
public data class MessageDelivery(
    val roomId: RoomId,
    val messageId: Uuid,
    val accountId: AccountId,
)

/**
 * Reachability snapshot at send time, per *member account* (devices collapsed;
 * the orchestrator never deals in peer ids).
 *
 * The local account is excluded: own devices converge via the pull path
 * regardless, and the user's mental model of delivery is other members.
 *
 * [membersPullOnly] covers every no-live-push situation — deferred sessions,
 * no known devices, failed pushes — because in all of them the pull path
 * (ping frontiers + sync) is the delivery path. "Pending" would overpromise
 * for the no-devices case; the GUI may still render it as "pending sync".
 */
public data class FanoutReport(
    /** Other member accounts fanned out to. */
    val membersTotal: Int,
    /** Accounts with at least one device queued to the outbox now. */
    val membersQueued: Int,
    /** Accounts with no live push path — they pick the message up via sync. */
    val membersPullOnly: Int,
)