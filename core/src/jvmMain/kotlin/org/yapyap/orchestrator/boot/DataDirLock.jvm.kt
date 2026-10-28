package org.yapyap.orchestrator.boot

import kotlinx.io.files.Path
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

internal actual fun acquireDataDirLock(directory: Path): DataDirLock? {
    val channel = RandomAccessFile(directory.toString() + "/app.lock", "rw").channel
    return try {
        val lock = channel.tryLock() ?: run {
            channel.close()
            return null
        }
        object : DataDirLock {
            override fun close() {
                runCatching { lock.release() }
                runCatching { channel.close() }
            }
        }
    } catch (e: OverlappingFileLockException) {
        // Same-process overlap: our own orchestrator already holds it.
        channel.close()
        null
    }
}
