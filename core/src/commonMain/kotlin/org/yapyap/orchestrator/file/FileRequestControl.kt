package org.yapyap.orchestrator.file

import org.yapyap.protocol.FileId

/** The user's say over fetching a file, owned by the request coordinator (the only writer of its requests). */
internal interface FileRequestControl {
    /** Fetch [fileId] now: a declined or failed receive is reopened and the request starts at once. No-op when READY. */
    suspend fun acceptFile(fileId: FileId)

    /** Never fetch [fileId] unless accepted later: a live fetch is aborted and the receive goes DECLINED. */
    suspend fun declineFile(fileId: FileId)
}
