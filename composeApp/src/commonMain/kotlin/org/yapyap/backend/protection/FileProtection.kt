package org.yapyap.backend.protection

import org.yapyap.backend.protocol.FileChunk
import org.yapyap.backend.protocol.FileEnvelope
import org.yapyap.backend.protocol.FilePayload
import org.yapyap.backend.protocol.OpenedFileEnvelope

interface FileProtection {
    suspend fun open(input: FileEnvelope): OpenedFileEnvelope
    suspend fun protect(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope
    suspend fun decryptChunk(chunk: FilePayload.EncryptedChunk): FileChunk
}