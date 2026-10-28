package org.yapyap.orchestrator.boot

import kotlinx.io.files.Path
import platform.posix.*

internal actual fun acquireDataDirLock(directory: Path): DataDirLock? {
    // Owner read/write (0o600). Kotlin has no octal literals; 0x180 is the hex form.
    val fd = open(directory.toString() + "/app.lock", O_RDWR or O_CREAT, 0x180)
    if (fd < 0) return null
    if (flock(fd, LOCK_EX or LOCK_NB) != 0) {
        close(fd)
        return null
    }
    return object : DataDirLock {
        override fun close() {
            flock(fd, LOCK_UN)
            close(fd)
        }
    }
}
