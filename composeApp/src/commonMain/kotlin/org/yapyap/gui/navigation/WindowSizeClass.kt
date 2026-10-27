package org.yapyap.gui.navigation

/** Four collapsing-shell classes (02-layout-and-adaptive.md §2). */
enum class WindowSizeClass { Compact, Medium, Expanded, Wide }

/** Window-size bucket for the M4 adaptive shell (Compact/Medium/Expanded/Wide). */
@Suppress("unused")
fun windowSizeClassFor(widthDp: Int): WindowSizeClass = when {
    widthDp < 600 -> WindowSizeClass.Compact
    widthDp < 1024 -> WindowSizeClass.Medium
    widthDp < 1440 -> WindowSizeClass.Expanded
    else -> WindowSizeClass.Wide
}
