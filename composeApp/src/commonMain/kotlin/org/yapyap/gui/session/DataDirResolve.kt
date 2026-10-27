package org.yapyap.gui.session

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Data-directory resolution order (plan §1):
 * 1) `YAPYAP_DATA` env / `-Dyapyap.dataDir` (multi-instance dev)
 * 2) packaged app → `<compose.application.compose.dir>/data`
 * 3) app root not writable → `~/.yapyap`
 * 4) dev `gradlew run` → `./.yapyap`
 * Android/iOS have their own actuals (app-private dirs).
 */
fun defaultDataDirectory(): Path {
    dataDirOverride()?.takeIf { it.isNotBlank() }?.let { return Path(it) }
    packagedAppDir()?.let { return Path(it, "data") }
    val home = userHome()?.let { Path(it, ".yapyap") }
    if (home != null) {
        val probeOk = runCatching {
            if (!SystemFileSystem.exists(home)) {
                SystemFileSystem.createDirectories(home)
            }
            isWritableDirectory(home)
        }.getOrDefault(false)
        if (probeOk) return home
    }
    return Path(".yapyap")
}

expect fun packagedAppDir(): String?

expect fun userHome(): String?
