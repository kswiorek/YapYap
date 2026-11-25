package org.yapyap.gui.screens.shell

import androidx.compose.ui.Modifier

/**
 * Runs [onClick] on secondary-button press (desktop right-click); touch/long-press
 * users get the same action via the primary click path. No-op on platforms with
 * no secondary button (mobile stubs until mobile input lands).
 */
expect fun Modifier.secondaryClick(onClick: () -> Unit): Modifier
