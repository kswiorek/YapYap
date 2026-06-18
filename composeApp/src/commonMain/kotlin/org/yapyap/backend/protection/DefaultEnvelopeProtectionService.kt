package org.yapyap.backend.protection

import org.yapyap.backend.protocol.FileChunk
import org.yapyap.backend.protocol.FileEnvelope
import org.yapyap.backend.protocol.FilePayload
import org.yapyap.backend.protocol.MessageEnvelope
import org.yapyap.backend.protocol.MessagePayload
import org.yapyap.backend.protocol.OpenedFileEnvelope
import org.yapyap.backend.protocol.SystemEnvelope
import org.yapyap.backend.protocol.SystemPayload
import org.yapyap.backend.transport.webrtc.WebRtcSignalEnvelope
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal

class DefaultEnvelopeProtectionService(
    val webRtcSignalProtection: WebRtcSignalProtection,
    val fileProtection: FileProtection,
    val messageProtection: MessageProtection,
    val systemProtection: SystemProtection,
): EnvelopeProtectionService {
    override fun protectSignal(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope {
        return webRtcSignalProtection.protect(input, context)
    }

    override fun openSignal(envelope: WebRtcSignalEnvelope): WebRtcSignal {
        return webRtcSignalProtection.open(envelope)
    }

    override fun protectFile(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope {
        return fileProtection.protect(input, context)
    }

    override fun openFile(envelope: FileEnvelope): OpenedFileEnvelope {
        return fileProtection.open(envelope)
    }

    override fun decryptFileChunk(chunk: FilePayload.EncryptedChunk): FileChunk {
        return fileProtection.decryptChunk(chunk)
    }

    override fun protectMessage(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
        return messageProtection.protect(input, context)
    }

    override fun openMessage(envelope: MessageEnvelope): MessagePayload {
        return messageProtection.open(envelope)
    }

    override fun protectSystem(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope {
        return systemProtection.protect(input, context)
    }

    override fun openSystem(envelope: SystemEnvelope): SystemPayload {
        return systemProtection.open(envelope)
    }
}