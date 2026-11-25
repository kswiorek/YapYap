package org.yapyap.gui.screens.shell

import androidx.compose.ui.Modifier

actual fun Modifier.secondaryClick(onClick: () -> Unit): Modifier {
    // M4: platform secondary-button integration. Stub keeps the build green on mobile.
    return this
}
