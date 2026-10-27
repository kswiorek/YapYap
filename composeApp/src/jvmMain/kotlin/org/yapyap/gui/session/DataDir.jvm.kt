package org.yapyap.gui.session

import kotlinx.io.files.Path

actual fun resolveDataDirectory(): Path = defaultDataDirectory()

actual fun isWritableDirectory(path: Path): Boolean =
    runCatching { java.io.File(path.toString()).canWrite() }.getOrDefault(false)

actual fun dataDirOverride(): String? =
    System.getProperty("yapyap.dataDir") ?: System.getenv("YAPYAP_DATA")

actual fun packagedAppDir(): String? =
    System.getProperty("compose.application.compose.dir")

actual fun userHome(): String? = System.getProperty("user.home")

actual fun platformNotificationSink(): NotificationSink = NotificationSink.NoOp
