@file:OptIn(ExperimentalForeignApi::class)

package org.yapyap.orchestrator.boot

import kotlinx.cinterop.*
import kotlinx.io.files.Path
import platform.posix.*

internal actual fun acquireDataDirLock(directory: Path): DataDirLock? {
    val path = directory.toString() + "/" + DATA_DIR_LOCK_FILE
    val fd = open(path, O_RDWR or O_CREAT, 0x180)
    if (fd < 0) return null
    if (flock(fd, LOCK_EX or LOCK_NB) != 0) {
        close(fd)
        return null
    }
    runCatching {
        ftruncate(fd, 0)
        memScoped {
            val text = getpid().toString() + "\n"
            write(fd, text.cstr, text.length.toULong())
        }
    }
    return object : DataDirLock {
        override fun close() {
            flock(fd, LOCK_UN)
            close(fd)
        }
    }
}

internal actual fun readDataDirLockHolder(directory: Path): Long? = runCatching {
    val fd = open(directory.toString() + "/" + DATA_DIR_LOCK_FILE, O_RDONLY)
    if (fd < 0) return null
    try {
        memScoped {
            val buf = allocArray<ByteVar>(32)
            val n = read(fd, buf, 32UL)
            if (n <= 0) return null
            val bytes = ByteArray(n.toInt()) { i -> buf[i] }
            bytes.decodeToString().trim().toLongOrNull()
        }
    } finally {
        close(fd)
    }
}.getOrNull()

internal actual fun currentProcessPid(): Long = getpid().toLong()
