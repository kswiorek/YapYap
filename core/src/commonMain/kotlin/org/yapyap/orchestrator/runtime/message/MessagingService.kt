package org.yapyap.orchestrator.runtime.message

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.routing.router.SendMessageResult

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
    val typingState: StateFlow<Map<String, Set<AccountId>>>

    /** Outbound: append to local DAG, fan out to room members via router. */
    suspend fun sendTextMessage(roomId: String, text: String): SendMessageResult

    /**
     * Open a room for viewing. Returns a paginated window.
     * Caller must call [RoomMessageWindow.close] when navigating away.
     */
    suspend fun openRoom(roomId: String, initialPageSize: Int = 100): RoomMessageWindow

    /**
     * Start/stop announcing that the local user is typing in [roomId]. While active, the
     * service announces to the room's members every
     * [OrchestratorConfig.typingIndicatorInterval]. The GUI calls this on typing-state
     * changes (e.g. first keystroke after an idle pause / idle timeout); announcements are
     * periodic heartbeats, so there is no explicit "stopped typing" wire message.
     */
    suspend fun setTyping(roomId: String, isTyping: Boolean)
}
