package org.yapyap.orchestrator.boot

import android.os.Process
import kotlinx.io.files.Path
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

internal actual fun acquireDataDirLock(directory: Path): DataDirLock? {
    val raf = RandomAccessFile(directory.toString() + "/" + DATA_DIR_LOCK_FILE, "rw")
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
            raf.writeBytes("${androidProcessPid()}\n")
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
    RandomAccessFile(directory.toString() + "/" + DATA_DIR_LOCK_FILE, "r").use { raf ->
        raf.readLine()?.trim()?.toLongOrNull()
    }
}.getOrNull()

internal actual fun currentProcessPid(): Long = androidProcessPid()

/**
 * `Process.myPid()` on device; the android.jar stub throws in local JVM unit tests, so
 * fall back to `ProcessHandle` there (lazy, so API 24-25 devices never load that class).
 * Both sides of the lock protocol use this one helper, so holder records stay consistent
 * wherever they are written and read.
 */
private fun androidProcessPid(): Long =
    runCatching { Process.myPid().toLong() }.getOrElse { ProcessHandle.current().pid() }
