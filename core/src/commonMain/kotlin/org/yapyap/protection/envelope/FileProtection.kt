package org.yapyap.protection.envelope

import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.envelopes.FileChunk
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.OpenedFileEnvelope

interface FileProtection {
    suspend fun open(input: FileEnvelope): OpenedFileEnvelope
    suspend fun protect(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope
    suspend fun decryptChunk(chunk: FilePayload.EncryptedChunk): FileChunk
}