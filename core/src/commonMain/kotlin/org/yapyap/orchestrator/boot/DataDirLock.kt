package org.yapyap.orchestrator.boot

import kotlinx.io.files.Path

/**
 * Single-instance guard: at most one live [org.yapyap.orchestrator.Orchestrator]
 * (or anything else) may hold the data directory. Implemented as an exclusive,
 * non-blocking OS file lock on `<dataDirectory>/app.lock`, held open for as long
 * as the directory is ours — the OS releases it on process death, so a held lock
 * always means a *live* holder (never a stale leftover).
 */
internal interface DataDirLock : AutoCloseable {
    override fun close()
}

/**
 * Acquires the data-directory lock, or returns null when another live process
 * (or another orchestrator in this process) already holds it. Genuine I/O
 * failures propagate — only contention maps to null.
 */
internal expect fun acquireDataDirLock(directory: Path): DataDirLock?

/** The data directory is already held by a live YapYap instance — close it first, then retry. */
internal class DataDirectoryInUseException(dataDirectory: String) : IllegalStateException(
    "Another YapYap instance is already using this data directory ($dataDirectory). " +
        "Close it first, then retry.",
)
