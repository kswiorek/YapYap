package org.yapyap.orchestrator.file.io

import kotlinx.io.files.Path

/**
 * Chunk-shaped I/O boundary (design §11).
 *
 * The receiver is a single append-only writer position; the sender reads at an
 * offset. Only reads are positioned — the writer never seeks.
 */
internal interface AppendHandle : AutoCloseable {
    suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)
    suspend fun flushAndSync()
    suspend fun truncate(length: Long)
    suspend fun size(): Long
}

internal interface PositionedHandle : AutoCloseable {
    suspend fun size(): Long
    suspend fun readAt(offset: Long, buf: ByteArray, bufOffset: Int, length: Int): Int
}

/**
 * Platform file access, declared by the orchestrator and supplied by `Platform`.
 *
 * Regular `Path` files use the app's data directory. Content URIs (`content://…`)
 * exist only on Android, where the core cannot open them alone — the platform
 * implementation bridges to `ContentResolver`. On JVM both content methods
 * return null; on iOS content URIs do not occur.
 *
 * All functions are suspend and switch to the IO dispatcher internally, so no
 * blocking fsync ever runs on a router or node coroutine.
 *
 * The orchestrator never imports `wiring` (guide §3): the composition root
 * connects this port to `Platform`.
 */
internal interface FileAccess {
    suspend fun exists(path: Path): Boolean
    suspend fun createDirectories(path: Path)
    suspend fun fileSize(path: Path): Long?
    suspend fun freeSpaceBytes(directory: Path): Long
    suspend fun openAppendWriter(path: Path, truncateToLength: Long?): AppendHandle
    suspend fun openPositionedReader(path: Path, startOffset: Long = 0): PositionedHandle
    suspend fun delete(path: Path, mustExist: Boolean = false)

    /** Atomic move: fails instead of falling back to a non-atomic move. */
    suspend fun move(source: Path, target: Path)

    /** Fsync the directory holding [path], so a READY rename survives a crash. */
    suspend fun syncDirectory(path: Path)
    suspend fun contentSize(uri: String): Long?
    suspend fun openContentPositionedReader(uri: String, startOffset: Long = 0): PositionedHandle?

    /** Deletes the files in [storeDir] whose names start with [prefix]. A missing directory is empty. */
    suspend fun cleanTmpFiles(storeDir: Path, prefix: String)
}
