package org.yapyap.gui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Universal Enter-to-confirm for in-window overlays and forms.
 *
 * There is no Compose "Form" that submits on Enter — every overlay wires it
 * manually through this one helper instead:
 *
 * - [ScrimOverlay] takes `onConfirm`/`confirmEnabled` and applies it at the
 *   scrim level, so ConfirmDialog / CreateRoomDialog / SponsorDialog confirm
 *   on Enter (or NumpadEnter) for free;
 * - plain screens (setup forms, reset screen) apply [Modifier.onEnterConfirm]
 *   to their own container.
 *
 * Only the key-down press confirms (key-up is ignored) and Shift+Enter never
 * confirms, so multiline fields keep their newline.
 */
fun isConfirmKeyPress(key: Key): Boolean = key == Key.Enter || key == Key.NumPadEnter

fun Modifier.onEnterConfirm(enabled: Boolean = true, onConfirm: () -> Unit): Modifier {
    if (!enabled) return this
    return onPreviewKeyEvent { ev ->
        if (ev.type == KeyEventType.KeyDown && isConfirmKeyPress(ev.key)) {
            onConfirm()
            true
        } else {
            false
        }
    }
}
