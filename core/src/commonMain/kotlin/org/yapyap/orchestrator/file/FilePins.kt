package org.yapyap.orchestrator.file

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.protocol.FileId

/**
 * Files a send is still preparing or appending (design §10): pinned from import until the message
 * reaches the DAG or the send gives up. Quota never evicts a pinned file, and only the last unpin
 * of a file may delete a copy a send created, so two concurrent sends of the same file never pull
 * it from under each other. Counts are per file, in memory: a restart has no sends in flight.
 */
internal class FilePins {
    private val mutex = Mutex()
    private val counts = mutableMapOf<FileId, Int>()

    suspend fun pin(fileId: FileId) = mutex.withLock {
        counts[fileId] = (counts[fileId] ?: 0) + 1
    }

    /**
     * Drops one pin of [fileId]. When it was the last, [onLast] runs while still holding the pin
     * lock, so no other send can pin the file between the check and what [onLast] deletes.
     */
    suspend fun unpin(fileId: FileId, onLast: suspend () -> Unit = {}) = mutex.withLock {
        val left = (counts[fileId] ?: return@withLock) - 1
        if (left > 0) {
            counts[fileId] = left
        } else {
            counts.remove(fileId)
            onLast()
        }
    }

    suspend fun pinned(): Set<FileId> = mutex.withLock { counts.keys.toSet() }
}
