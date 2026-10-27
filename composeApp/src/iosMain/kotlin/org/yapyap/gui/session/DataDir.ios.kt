package org.yapyap.gui.session

import kotlinx.io.files.Path
import platform.Foundation.NSHomeDirectory

actual fun resolveDataDirectory(): Path =
    Path(NSHomeDirectory(), "Library/Application Support/yapyap")

actual fun isWritableDirectory(path: Path): Boolean = true

actual fun dataDirOverride(): String? = null

actual fun packagedAppDir(): String? = null

actual fun userHome(): String? = NSHomeDirectory()

actual fun platformNotificationSink(): NotificationSink = NotificationSink.NoOp
