package org.yapyap.orchestrator.runtime.message

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.protocol.RoomId
import kotlin.uuid.Uuid

interface MessagingService {

    /** Max text length in bytes that [sendTextMessage] will accept. Derived from transport limits. */
    val maxTextMessageBytes: Int

    val incomingMessageEvents: Flow<IncomingMessageEvent>

    /**
     * Accounts currently typing per room (roomId → typing accounts), derived from received
     * typing indicators with an idle timeout of ~2x the sender's announced cadence.
     * Backend for the GUI "typing…" display. The local account's own announcements
     * (e.g. from another device) are excluded.
     */
    val typingState: StateFlow<Map<RoomId, Set<AccountId>>>

    /** Outbound: append to local DAG (refused up front on policy violations),
     *  then fan out to room members. The message is durable once [SendTextResult.Sent] returns. */
    suspend fun sendTextMessage(roomId: RoomId, text: String): SendTextResult

    /** Lookup of a single message by id, mapped to its GUI display item (null when unknown or not displayable). */
    suspend fun getMessage(messageId: Uuid): MessageDisplayItem?

    /**
     * Open a room for viewing. Returns a paginated window.
     * Caller must call [RoomMessageWindow.close] when navigating away.
     */
    suspend fun openRoom(roomId: RoomId, initialPageSize: Int = 100): RoomMessageWindow

    /**
     * Latest visible message of [roomId] for the room list (docs/room events.md
     * §3): walks newest→oldest over one page and returns the first non-`REJECTED`
     * displayable message whose author passes the render policy. Null when no
     * visible message exists (empty room, pre-fold room, deferred authors,
     * all-hidden). The returned item is unformatted — the GUI decides how to
     * render it. The GUI re-pulls this on [incomingMessageEvents]; the event
     * itself carries no content, so hidden messages never leak through the
     * preview path.
     */
    suspend fun roomPreview(roomId: RoomId, scanLimit: Int = 100): RoomPreview?

    /**
     * Start/stop announcing that the local user is typing in [roomId]. While active, the
     * service announces to the room's members every
     * `typingIndicatorInterval`. The GUI calls this on typing-state
     * changes (e.g. first keystroke after an idle pause / idle timeout); announcements are
     * periodic heartbeats, so there is no explicit "stopped typing" wire message.
     */
    suspend fun setTyping(roomId: RoomId, isTyping: Boolean)
}
