package org.yapyap.gui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.yapyap.gui.mentions.MentionMatcher
import org.yapyap.gui.prefs.UiPreferencesController
import org.yapyap.orchestrator.runtime.OrchestratorRuntime
import org.yapyap.orchestrator.runtime.identity.AccountView
import org.yapyap.orchestrator.runtime.message.RoomPreview
import org.yapyap.orchestrator.runtime.room.RoomDetails
import org.yapyap.protocol.RoomId
import kotlin.time.Clock

data class RoomListItem(
    val details: RoomDetails,
    val preview: RoomPreview?,
    val pinned: Boolean,
    val muted: Boolean,
    val unread: Boolean,
    /** Honest mention signal: true = latest preview mentions me (count unknown). */
    val mentionDot: Boolean,
    val title: String,
)

fun roomKey(id: RoomId): String = id.value.toString()

/**
 * Composes the room list: projection order from `rooms.rooms`, sorted by
 * local pins → preview timestamp desc → name (03-ux-patterns.md §2.1).
 */
class RoomsController(
    private val scope: CoroutineScope,
    private val runtime: OrchestratorRuntime,
    private val prefs: UiPreferencesController,
) {
    private val previewTick = MutableStateFlow(0L)
    private val _items = MutableStateFlow<List<RoomListItem>>(emptyList())
    val items: StateFlow<List<RoomListItem>> = _items.asStateFlow()

    fun start() {
        scope.launch {
            combine(
                runtime.rooms.rooms,
                runtime.identity.accounts,
                runtime.identity.localAccount,
                prefs.prefs,
                previewTick,
            ) { rooms, accounts, local, p, _ ->
                buildItems(rooms, accounts, local?.accountId, p.lastReadAt, p.pinnedRooms, p.mutedRooms)
            }.collect { _items.value = it }
        }
        scope.launch {
            runtime.messaging.incomingMessageEvents.collect {
                // Re-pull previews for the affected room (§6 cheat-sheet).
                refreshPreviews()
            }
        }
        scope.launch {
            runtime.rooms.rooms.collect { refreshPreviews() }
        }
    }

    fun refreshPreviews() {
        previewTick.value = Clock.System.now().toEpochMilliseconds()
    }

    private suspend fun buildItems(
        rooms: List<RoomDetails>,
        accounts: List<AccountView>,
        localId: org.yapyap.protocol.AccountId?,
        lastReadAt: Map<String, Long>,
        pinned: List<String>,
        muted: List<String>,
    ): List<RoomListItem> {
        val names = accounts.associate { it.accountId to it.displayName }
        return rooms.map { details ->
            val preview = runCatching { runtime.messaging.roomPreview(details.roomId) }.getOrNull()
            val key = roomKey(details.roomId)
            val title = when {
                details.name.isNotBlank() -> details.name
                else -> details.members.mapNotNull { names[it.accountId] }.sorted()
                    .joinToString(", ").ifBlank { "Empty room" }
            }
            val lastRead = lastReadAt[key] ?: 0L
            val ts = preview?.timestamp?.toEpochMilliseconds() ?: 0L
            val unread = ts > lastRead && preview != null && preview.senderAccountId != localId
            val mentionDot = preview != null && localId != null &&
                    preview.senderAccountId != localId &&
                    preview.item is org.yapyap.orchestrator.runtime.message.MessageDisplayItem.Text &&
                    MentionMatcher.mentionsMe(
                        (preview.item as org.yapyap.orchestrator.runtime.message.MessageDisplayItem.Text).text,
                        localId,
                    )
            RoomListItem(
                details = details,
                preview = preview,
                pinned = key in pinned,
                muted = key in muted,
                unread = unread,
                mentionDot = mentionDot,
                title = title,
            )
        }.sortedWith(
            compareByDescending<RoomListItem> { it.pinned }
                .thenByDescending { it.preview?.timestamp?.toEpochMilliseconds() ?: Long.MIN_VALUE }
                .thenBy { it.title.lowercase() },
        )
    }
}
