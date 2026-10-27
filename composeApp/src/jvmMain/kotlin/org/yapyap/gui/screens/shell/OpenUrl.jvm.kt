package org.yapyap.gui.screens.shell

actual fun openUrl(url: String) {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }
    }
}
