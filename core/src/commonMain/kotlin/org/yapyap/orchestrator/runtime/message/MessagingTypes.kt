package org.yapyap.orchestrator.runtime.message

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.dag.RoomId
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface MessageDisplayItem {
    /** Stable identity used as a Compose list key and for targeted removal (e.g. on rejection). */
    val messageId: Uuid
    val accountId: AccountId
    val timestamp: Instant

    data class Text(
        override val messageId: Uuid,
        override val accountId: AccountId,
        override val timestamp: Instant,
        val text: String,
    ) : MessageDisplayItem

    data class File(
        override val messageId: Uuid,
        override val accountId: AccountId,
        override val timestamp: Instant,
        val fileId: String,
        val fileName: String,
        val fileSize: Long,
    ) : MessageDisplayItem

    data class Gap(
        override val messageId: Uuid,
        override val accountId: AccountId,
        override val timestamp: Instant,
        val missingPrevIds: List<Uuid>,
    ) : MessageDisplayItem
}

/**
 * "Something new in this room" signal. Carries no content: the GUI re-pulls
 * [org.yapyap.orchestrator.runtime.message.MessagingService.roomPreview] on it,
 * so messages the render policy hides never leak through a notification path.
 */
data class IncomingMessageEvent(
    val roomId: RoomId,
    val senderAccountId: AccountId,
    val timestamp: Instant,
)

/**
 * Latest *visible* message of a room (docs/room events.md §3): the newest
 * non-`REJECTED` `Text` message whose author passes the render policy. Null
 * when the room holds no visible message (empty, pre-fold, or all-hidden).
 */
data class RoomPreview(
    val senderAccountId: AccountId,
    /** First ~80 chars of text. */
    val preview: String,
    val timestamp: Instant,
)

/**
 * A paginated window into a room's messages.
 * Created by [MessagingService.openRoom]; caller must [close] when done.
 */
interface RoomMessageWindow {
    /** Current loaded messages (oldest→newest). Bind this to the GUI list. */
    val displayItems: StateFlow<List<MessageDisplayItem>>

    /** Whether older messages exist beyond the currently loaded window. */
    val hasMoreOlder: StateFlow<Boolean>

    /**
     * Load the next page of older messages (prepended to [displayItems]).
     * @return Number of messages loaded (0 means no more older messages).
     */
    suspend fun loadOlder(pageSize: Int = 50): Int

    /** Release this window and unsubscribe from updates. */
    suspend fun close()
}