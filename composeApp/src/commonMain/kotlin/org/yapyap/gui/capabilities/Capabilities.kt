package org.yapyap.gui.capabilities

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Feature gate model per 08-feature-readiness.md §1/§4. Defaults reflect **today**:
 * screens read the flag and pick `Real()` vs `Stub()` once, at the top.
 */
@Immutable
data class Capabilities(
    // READY today
    val roomChat: Boolean = true,
    val typing: Boolean = true,
    val attachmentCards: Boolean = true,
    val mentions: Boolean = true,
    val embeds: Boolean = false, // M5 — fetch pipeline not built yet
    val inlinePreviews: Boolean = false, // M5
    // STUB today
    val realAvatars: Boolean = false,
    val avatarsUpload: Boolean = false,
    val attachments: Boolean = false,
    val reactions: Boolean = false,
    val gifs: Boolean = false,
    val stickers: Boolean = false,
    val customEmoji: Boolean = false,
    val messageEditing: Boolean = false,
    val messageDeletion: Boolean = false,
    val replies: Boolean = false,
    val threads: Boolean = false,
    val pins: Boolean = false,
    val search: Boolean = false,
    val friends: Boolean = false,
    val guilds: Boolean = false,
    val categories: Boolean = false,
    val voice: Boolean = false,
    val video: Boolean = false,
    val screenShare: Boolean = false,
    val richPresence: Boolean = false,
    val statusMessage: Boolean = false,
    val profileEditing: Boolean = false,
    val multipleAccounts: Boolean = false,
    val lightTheme: Boolean = false,
    val customKeybinds: Boolean = false,
    val pushNotifications: Boolean = false,
    val localisation: Boolean = false,
)

val LocalCapabilities = staticCompositionLocalOf { Capabilities() }
