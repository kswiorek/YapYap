package org.yapyap.gui.theme

import androidx.compose.ui.unit.dp

/** Motion durations (ms), per 01-visual-language.md §10 / 09-component-states.md §4.
 * Consumed as hover/press/modal/popout/toast animation specs land (M3). */
@Suppress("unused")
object YapMotion {
    const val HOVER_IN_MS = 150
    const val HOVER_OUT_MS = 50
    const val PRESS_MS = 75
    const val MODAL_MS = 175
    const val POPOUT_MS = 135
    const val PANEL_MS = 225
    const val TOAST_IN_MS = 200
    const val TOAST_HOLD_MS = 4_000
    const val TOAST_OUT_MS = 150
    const val TOOLTIP_DELAY_MS = 400
    const val TYPING_DOTS_MS = 1_200
}

/** Focus ring for keyboard navigation (09-component-states.md §3). Used by M3 focus work. */
@Suppress("unused")
val YapFocusRingWidth = 2.dp
