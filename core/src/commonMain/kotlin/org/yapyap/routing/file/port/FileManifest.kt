package org.yapyap.routing.file.port

import org.yapyap.protocol.FileId

/**
 * What a file is, without its bytes or keys (design §3.3): the fields the sealed OFFER and FETCH_OK
 * carry. Routing builds one from an opened OFFER for the receiver's check, and the plaintext source
 * describes a held file with one for the sender.
 */
internal data class FileManifest(
    val fileId: FileId,
    val totalBytes: Long,
    val blockSize: Int,
    val blockHashes: List<ByteArray>,
    val fileName: String,
    val mimeType: String?,
)
