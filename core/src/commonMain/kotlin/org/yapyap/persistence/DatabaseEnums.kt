package org.yapyap.persistence

//TODO: [Sprint 5] move these to the file-transfer store that owns their tables and delete this file
public enum class FileTransferStatus {
    IN_FLIGHT,
    PAUSED,
    COMPLETED,
    CANCELLED,
}

public enum class FileChunkStatus {
    MISSING,
    REQUESTED,
    WRITTEN,
}
