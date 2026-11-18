package org.yapyap.orchestrator.file.io

import kotlinx.io.Source
import kotlinx.io.files.Path

/**
 * Chunk-shaped I/O boundary (design §11).
 *
 * The receiver is a single append-only writer position; the sender reads at an
 * offset. Only reads are positioned — the writer never seeks.
 */
internal interface AppendHandle : AutoCloseable {
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)
    fun flushAndSync()
    fun truncate(length: Long)
    fun size(): Long
}

internal interface PositionedHandle : AutoCloseable {
    fun size(): Long
    fun readAt(offset: Long, buf: ByteArray, bufOffset: Int, length: Int): Int
}

/**
 * Platform file access, declared by the orchestrator and supplied by `Platform`.
 *
 * Regular `Path` files use the app's data directory. Content URIs (`content://…`)
 * exist only on Android, where the core cannot open them alone — the platform
 * implementation bridges to `ContentResolver`. On JVM both content methods
 * return null; on iOS content URIs do not occur.
 *
 * The orchestrator never imports `wiring` (guide §3): the composition root
 * connects this port to `Platform`.
 */
internal interface FileAccess {
    fun exists(path: Path): Boolean
    fun createDirectories(path: Path)
    fun fileSize(path: Path): Long?
    fun freeSpaceBytes(directory: Path): Long
    fun openAppendWriter(path: Path, truncateToLength: Long?): AppendHandle
    fun openPositionedReader(path: Path, startOffset: Long = 0): PositionedHandle
    fun delete(path: Path, mustExist: Boolean = false)
    fun move(source: Path, target: Path)
    suspend fun contentSize(uri: String): Long?
    fun openContentSource(uri: String): Source?
    fun openContentPositionedReader(uri: String, startOffset: Long = 0): PositionedHandle?
}
