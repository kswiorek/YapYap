package org.yapyap.backend.protection

import org.yapyap.backend.protocol.FileChunk
import org.yapyap.backend.protocol.FileEnvelope
import org.yapyap.backend.protocol.FilePayload
import org.yapyap.backend.protocol.OpenedFileEnvelope

interface FileProtection {
    fun open(input: FileEnvelope): OpenedFileEnvelope
    fun protect(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope
    fun decryptChunk(chunk: FilePayload.EncryptedChunk): FileChunk
}