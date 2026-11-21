package org.yapyap.orchestrator.runtime.file

import kotlinx.io.files.Path
import org.yapyap.orchestrator.file.FileDescriptorReader
import org.yapyap.orchestrator.file.FileDescriptorStatus
import org.yapyap.orchestrator.file.FileReceiveReader
import org.yapyap.orchestrator.file.FileReceiveStatus
import org.yapyap.orchestrator.file.FileRequestControl
import org.yapyap.orchestrator.file.FileStore
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.protocol.FileId

/**
 * The GUI's view of received files: reads of the receive and descriptor tables, and the user's
 * accept and decline, which the request coordinator carries out.
 */
internal class DefaultFileTransferService(
    private val receives: FileReceiveReader,
    private val descriptors: FileDescriptorReader,
    private val files: FileAccess,
    private val requests: FileRequestControl,
    private val store: FileStore,
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
        store.touch(fileId)
        return path
    }

    override suspend fun acceptFile(fileId: FileId) = requests.acceptFile(fileId)

    override suspend fun declineFile(fileId: FileId) = requests.declineFile(fileId)
}
