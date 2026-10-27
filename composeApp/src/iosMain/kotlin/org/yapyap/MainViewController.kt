package org.yapyap

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.session.platformNotificationSink
import org.yapyap.gui.session.resolveDataDirectory

fun MainViewController() = ComposeUIViewController {
    val session = remember {
        YapSession(
            dataDirectory = resolveDataDirectory(),
            notificationSink = platformNotificationSink(),
            readPrefs = { null },
            writePrefs = { },
        ).also { it.start() }
    }
    App(session)
}
