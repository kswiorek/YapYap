package org.yapyap.gui.state

import org.yapyap.gui.session.YapSession

/** Local read-state + room prefs helpers backed by UiPreferences. */
class ReadStateStore(
    private val session: YapSession,
) {
    private val prefs get() = session.prefsController

    fun markRead(roomId: org.yapyap.protocol.RoomId, atMillis: Long) {
        prefs.update { it.copy(lastReadAt = it.lastReadAt + (roomKey(roomId) to atMillis)) }
    }

    /** Wired to the room-list context menu (M3). */
    @Suppress("unused")
    fun togglePin(roomId: org.yapyap.protocol.RoomId) {
        val key = roomKey(roomId)
        prefs.update {
            val pinned = if (key in it.pinnedRooms) it.pinnedRooms - key else it.pinnedRooms + key
            it.copy(pinnedRooms = pinned)
        }
    }

    /** Wired to the room-list context menu (M3). */
    @Suppress("unused")
    fun toggleMute(roomId: org.yapyap.protocol.RoomId) {
        val key = roomKey(roomId)
        prefs.update {
            val muted = if (key in it.mutedRooms) it.mutedRooms - key else it.mutedRooms + key
            it.copy(mutedRooms = muted)
        }
    }

    fun saveDraft(roomId: org.yapyap.protocol.RoomId, text: String) {
        prefs.update { it.copy(drafts = it.drafts + (roomKey(roomId) to text)) }
    }
}
