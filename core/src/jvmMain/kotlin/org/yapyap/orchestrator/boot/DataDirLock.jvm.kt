package org.yapyap.orchestrator.boot

import kotlinx.io.files.Path
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

internal actual fun acquireDataDirLock(directory: Path): DataDirLock? {
    val raf = RandomAccessFile(directory.toString() + "/app.lock", "rw")
    val channel = raf.channel
    fun release() {
        runCatching { channel.close() }
        runCatching { raf.close() }
    }
    return try {
        val lock = channel.tryLock() ?: run {
            release()
            return null
        }
        runCatching {
            raf.setLength(0)
            raf.writeBytes("${ProcessHandle.current().pid()}\n")
        }
        object : DataDirLock {
            override fun close() {
                runCatching { lock.release() }
                release()
            }
        }
    } catch (e: OverlappingFileLockException) {
        // Same-process overlap: our own orchestrator already holds it.
        release()
        null
    }
}

internal actual fun readDataDirLockHolder(directory: Path): Long? = runCatching {
    RandomAccessFile(directory.toString() + "/app.lock", "r").use { raf ->
        raf.readLine()?.trim()?.toLongOrNull()
    }
}.getOrNull()

internal actual fun currentProcessPid(): Long = ProcessHandle.current().pid()
