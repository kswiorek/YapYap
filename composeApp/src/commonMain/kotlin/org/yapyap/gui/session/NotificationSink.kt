package org.yapyap.gui.session

/** Outbound user-visible notification (M4: AWT tray; mobile: system notification). */
interface NotificationSink {
    fun notify(title: String, body: String)

    object NoOp : NotificationSink {
        override fun notify(title: String, body: String) = Unit
    }
}

expect fun platformNotificationSink(): NotificationSink
