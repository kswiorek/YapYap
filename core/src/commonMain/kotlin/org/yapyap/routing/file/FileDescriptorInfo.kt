package org.yapyap.routing.file

import org.yapyap.protocol.FileId

/**
 * What the pusher needs to offer and serve a file (design §3.3, §6): everything the
 * sealed OFFER carries plus the plaintext size, without keys (routing holds those).
 */
internal data class FileDescriptorInfo(
    val fileId: FileId,
    val totalBytes: Long,
    val blockSize: Int,
    val blockHashes: List<ByteArray>,
    val fileName: String,
    val mimeType: String?,
)
