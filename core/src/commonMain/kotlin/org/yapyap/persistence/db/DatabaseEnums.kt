package org.yapyap.persistence.db

public enum class VerificationState {
    VERIFIED,
    PENDING,
    REJECTED,
}

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

public enum class OpkStatus {
    ALLOCATED,
    OFFERED,
    CONSUMED,
}
