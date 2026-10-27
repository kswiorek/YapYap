package org.yapyap.gui.session

import kotlinx.io.files.Path

/** Platform-specific resolution of the YapYap data directory (§1 of the plan). */
expect fun resolveDataDirectory(): Path

/** True when [path] exists and is writable (app-root writability probe). */
expect fun isWritableDirectory(path: Path): Boolean

/** Override from `-Dyapyap.dataDir` / `YAPYAP_DATA`, if present. */
expect fun dataDirOverride(): String?
