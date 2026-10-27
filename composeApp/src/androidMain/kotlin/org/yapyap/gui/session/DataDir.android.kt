package org.yapyap.gui.session

import kotlinx.io.files.Path
import org.yapyap.YapYapApplication

actual fun resolveDataDirectory(): Path {
    val base = YapYapApplication.appContext?.filesDir?.absolutePath ?: "/tmp/yapyap"
    return Path(base, "yapyap")
}

actual fun isWritableDirectory(path: Path): Boolean = true

actual fun dataDirOverride(): String? = null

actual fun packagedAppDir(): String? = null

actual fun userHome(): String? = null

actual fun platformNotificationSink(): NotificationSink = NotificationSink.NoOp
