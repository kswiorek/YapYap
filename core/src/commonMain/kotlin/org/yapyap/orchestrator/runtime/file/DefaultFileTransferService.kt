package org.yapyap.orchestrator.runtime.file

import kotlinx.io.files.Path
import org.yapyap.orchestrator.file.FileDescriptorStatus
import org.yapyap.orchestrator.file.FileDescriptorStore
import org.yapyap.orchestrator.file.FileReceiveStatus
import org.yapyap.orchestrator.file.FileReceiveStore
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.protocol.FileId

internal class DefaultFileTransferService(
    private val receives: FileReceiveStore,
    private val descriptors: FileDescriptorStore,
    private val files: FileAccess,
) : FileTransferService {
    override suspend fun receiveState(fileId: FileId): FileReceiveSnapshot? {
        val row = receives.find(fileId) ?: return null
        return FileReceiveSnapshot(
            fileId = row.fileId,
            status = row.status,
            contiguousBytes = row.contiguousBytes,
            totalBytes = row.totalBytes,
        )
    }

    override suspend fun localFilePath(fileId: FileId): Path? {
        val row = receives.find(fileId)
        if (row != null && row.status != FileReceiveStatus.READY) return null
        val descriptor = descriptors.findById(fileId) ?: return null
        if (descriptor.status != FileDescriptorStatus.HELD) return null
        val path = Path(descriptor.path)
        if (files.fileSize(path) == null) return null
        return path
    }
}
