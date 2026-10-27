package org.yapyap.gui.prefs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Local-only GUI state. Everything here is per-device by design (see
 * 06-yapyap-mapping.md §3): read state, pins, mutes, drafts, embed dismissals.
 * Honest UI: two devices will disagree — that is expected, not a bug.
 */
@Serializable
data class UiPreferences(
    val appearance: AppearancePrefs = AppearancePrefs(),
    val notifications: NotificationPrefs = NotificationPrefs(),
    val chat: ChatPrefs = ChatPrefs(),
    val layout: LayoutPrefs = LayoutPrefs(),
    val embeds: EmbedPrefs = EmbedPrefs(),
    /** roomId-as-string → epoch millis of last read. RoomId has no stable string form in the
     *  API surface, so the GUI keys by [org.yapyap.protocol.RoomId.value] string. */
    val lastReadAt: Map<String, Long> = emptyMap(),
    val pinnedRooms: List<String> = emptyList(),
    val mutedRooms: List<String> = emptyList(),
    val drafts: Map<String, String> = emptyMap(),
    val schemaVersion: Int = CURRENT_SCHEMA,
) {
    companion object {
        const val CURRENT_SCHEMA = 1
        val DEFAULT = UiPreferences()

        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decodeOrDefault(raw: String): UiPreferences =
            runCatching { json.decodeFromString(serializer(), raw) }
                .getOrDefault(DEFAULT)
                .let { if (it.schemaVersion > CURRENT_SCHEMA) DEFAULT else it }
    }
}

fun UiPreferences.encode(): String =
    UiPreferences.json.encodeToString(UiPreferences.serializer(), this)

fun decodeUiPreferences(raw: String): UiPreferences = UiPreferences.decodeOrDefault(raw)

@Serializable
data class AppearancePrefs(
    val theme: String = "dark", // dark only; light/system = stub
    val accent: String = "blurple",
    val density: String = "default",
    val messageDisplay: String = "cozy",
    val groupSpacing: Int = 16,
    val chatTextSize: Int = 16,
    val underlineLinks: Boolean = false,
    val showAvatars: Boolean = true,
    val showStatusDots: Boolean = true,
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false,
    val saturation: Int = 100,
    val sidebarWidth: Int = 300,
)

@Serializable
data class NotificationPrefs(
    val desktopNotifications: Boolean = true,
    val sound: Boolean = true,
    val showUnreadBadges: Boolean = true,
    val showMentionBadges: Boolean = true,
    val notifyWhenFocused: Boolean = false,
    val taskbarBadge: Boolean = true,
)

@Serializable
data class ChatPrefs(
    val sendWithEnter: Boolean = true,
    val showCharCount: Boolean = true,
    val messageDisplay: String = "cozy",
    val fontSize: Int = 16,
    val uiZoom: Int = 100,
    val timestamps24h: Boolean = true,
)

@Serializable
data class LayoutPrefs(
    val showMemberListByDefault: Boolean = true,
)

@Serializable
data class EmbedPrefs(
    /** Global kill-switch for link previews (fetch nothing, render plain links). */
    val previewsEnabled: Boolean = true,
    /** Max bytes for the GUI-owned embed cache. */
    val cacheMaxBytes: Long = 256L * 1024 * 1024,
    /** Max age (days) for cached embed entries before the startup sweep drops them. */
    val cacheRetentionDays: Int = 30,
    /** Receiver-side dismissals: "messageIdHex:linkIndex" keys, LRU-bounded. */
    val dismissed: List<String> = emptyList(),
) {
    companion object {
        const val DISMISSED_CAP = 512
    }
}

/** Receiver-side embed dismissal (M5). Appends [key], LRU-bounded — see §7b. */
@Suppress("unused")
fun EmbedPrefs.withDismissed(key: String): EmbedPrefs {
    val next = (listOf(key) + dismissed.filter { it != key }).take(EmbedPrefs.DISMISSED_CAP)
    return copy(dismissed = next)
}
