package org.yapyap.gui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.yapyap.gui.mentions.MentionMatcher
import org.yapyap.orchestrator.runtime.OrchestratorRuntime
import org.yapyap.orchestrator.runtime.message.Message
import org.yapyap.orchestrator.runtime.message.MessageDisplayItem
import org.yapyap.orchestrator.runtime.message.RoomMessageWindow
import org.yapyap.orchestrator.runtime.message.SendMessageResult
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomId
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface PendingState {
    data object Sending : PendingState
    data class Failed(val reason: String) : PendingState
}

data class PendingMessage(
    val localKey: String,
    val text: String,
    val state: PendingState,
    val at: Instant,
)

data class ChatUiState(
    val items: List<MessageDisplayItem> = emptyList(),
    val pending: List<PendingMessage> = emptyList(),
    val hasMoreOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val sendError: String? = null,
    val typingNames: List<String> = emptyList(),
)

/**
 * Screen-scoped controller for one open room. Owns the `RoomMessageWindow`
 * lifecycle (open on start, close on dispose) and the optimistic-send list,
 * deduped by `messageId` when the real item lands (03-ux-patterns.md §1.6).
 */
class ChatController(
    private val scope: CoroutineScope,
    private val runtime: OrchestratorRuntime,
    private val roomId: RoomId,
    private val localId: AccountId?,
    private val resolveName: suspend (AccountId) -> String?,
) {
    private var window: RoomMessageWindow? = null
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private var typingJob: Job? = null

    fun start() {
        scope.launch {
            val w = runCatching { runtime.messaging.openRoom(roomId) }.getOrNull() ?: return@launch
            window = w
            launch {
                w.displayItems.collect { items ->
                    // Dedupe: drop pending rows whose text now appears as a real item
                    // from the local account (replaced, never duplicated).
                    val realTexts = items
                        .filterIsInstance<MessageDisplayItem.Text>()
                        .filter { it.accountId == localId }
                        .map { it.text }.toSet()
                    _state.value = _state.value.copy(
                        items = items,
                        pending = _state.value.pending.filter { it.text !in realTexts },
                        hasMoreOlder = w.hasMoreOlder.value,
                    )
                }
            }
            launch {
                w.hasMoreOlder.collect { hasMore ->
                    _state.value = _state.value.copy(hasMoreOlder = hasMore)
                }
            }
        }
        scope.launch {
            runtime.messaging.typingState.collect { typing ->
                val ids = typing[roomId].orEmpty().filter { it != localId }
                val names = ids.mapNotNull { runCatching { resolveName(it) }.getOrNull() }
                _state.value = _state.value.copy(typingNames = names)
            }
        }
    }

    fun loadOlder() {
        val w = window ?: return
        if (_state.value.loadingOlder || !_state.value.hasMoreOlder) return
        _state.value = _state.value.copy(loadingOlder = true)
        scope.launch {
            runCatching { w.loadOlder(50) }
            _state.value = _state.value.copy(loadingOlder = false)
        }
    }

    fun send(text: String, maxBytes: Int) {
        val body = text.trimEnd()
        if (body.isBlank()) return
        val bytes = body.encodeToByteArray().size
        if (bytes > maxBytes) {
            _state.value = _state.value.copy(
                sendError = "That message is too long (${bytes - maxBytes} bytes over).",
            )
            return
        }
        val key = Uuid.random().toString()
        _state.value = _state.value.copy(
            pending = _state.value.pending + PendingMessage(key, body, PendingState.Sending, Instant.DISTANT_PAST),
            sendError = null,
        )
        scope.launch {
            when (val r = runCatching { runtime.messaging.sendMessage(roomId, Message(body)) }.getOrElse {
                markFailed(key, it.message ?: "Couldn't send.")
                return@launch
            }) {
                is SendMessageResult.Sent -> {
                    // Real item arrives via displayItems; drop the optimistic row now —
                    // if the window hasn't caught up yet, keep until dedupe fires.
                    _state.value = _state.value.copy(
                        pending = _state.value.pending.filter { it.localKey != key },
                    )
                }

                is SendMessageResult.Refused -> {
                    val msg = org.yapyap.gui.copy.RefusalText.send(
                        r.reason,
                        (bytes - maxBytes).coerceAtLeast(0).toLong(),
                    )
                    markFailed(key, msg)
                }
            }
        }
        setTypingActive()
    }

    fun retryPending(key: String, maxBytes: Int) {
        val p = _state.value.pending.firstOrNull { it.localKey == key } ?: return
        _state.value = _state.value.copy(pending = _state.value.pending.filter { it.localKey != key })
        send(p.text, maxBytes)
    }

    fun dismissSendError() {
        _state.value = _state.value.copy(sendError = null)
    }

    fun onComposerTyped() {
        setTypingActive()
    }

    private fun setTypingActive() {
        typingJob?.cancel()
        scope.launch { runCatching { runtime.messaging.setTyping(roomId, true) } }
        typingJob = scope.launch {
            delay(3.seconds)
            runCatching { runtime.messaging.setTyping(roomId, false) }
        }
    }

    private fun markFailed(key: String, reason: String) {
        _state.value = _state.value.copy(
            pending = _state.value.pending.map {
                if (it.localKey == key) it.copy(state = PendingState.Failed(reason)) else it
            },
        )
    }

    fun mentionsMe(text: String): Boolean {
        val id = localId ?: return false
        return MentionMatcher.mentionsMe(text, id)
    }

    fun dispose() {
        typingJob?.cancel()
        scope.launch {
            runCatching { runtime.messaging.setTyping(roomId, false) }
            runCatching { window?.close() }
        }
    }
}
