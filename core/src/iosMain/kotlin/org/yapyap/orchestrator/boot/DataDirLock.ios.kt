package org.yapyap.orchestrator.boot

import kotlinx.io.files.Path
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.LOCK_UN
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.flock
import platform.posix.open

internal actual fun acquireDataDirLock(directory: Path): DataDirLock? {
    val fd = open(directory.toString() + "/app.lock", O_RDWR or O_CREAT, 0o600)
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
