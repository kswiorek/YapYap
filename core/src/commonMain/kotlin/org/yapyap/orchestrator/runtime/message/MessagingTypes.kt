package org.yapyap.orchestrator.runtime.message

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.dag.RoomId
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface MessageDisplayItem {
    val accountId: AccountId
    val timestamp: Instant
    val displayOrderId: Long

    data class Text(
        override val accountId: AccountId,
        override val timestamp: Instant,
        override val displayOrderId: Long,
        val text: String,
    ): MessageDisplayItem
    data class File(
        override val accountId: AccountId,
        override val timestamp: Instant,
        override val displayOrderId: Long,
        val fileId: String,
        val fileName: String,
        val fileSize: Long,
    ): MessageDisplayItem
    data class Gap(
        override val accountId: AccountId,
        override val timestamp: Instant,
        override val displayOrderId: Long,
        val missingPrevId: Uuid,
    ): MessageDisplayItem
}

data class IncomingMessageEvent(
    val roomId: RoomId,
    val senderAccountId: AccountId,
    val messagePreview: String,   // first ~80 chars of text
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