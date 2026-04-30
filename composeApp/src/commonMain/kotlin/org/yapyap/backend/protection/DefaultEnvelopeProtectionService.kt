package org.yapyap.backend.protection

import org.yapyap.backend.protocol.FileChunk
import org.yapyap.backend.protocol.FileEnvelope
import org.yapyap.backend.protocol.FilePayload
import org.yapyap.backend.protocol.MessageEnvelope
import org.yapyap.backend.protocol.MessagePayload
import org.yapyap.backend.protocol.OpenedFileEnvelope
import org.yapyap.backend.transport.webrtc.WebRtcSignalEnvelope
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal

class DefaultEnvelopeProtectionService(
    val webRtcSignalProtection: WebRtcSignalProtection,
    val fileProtection: FileProtection,
    val messageProtection: MessageProtection,
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
        TODO("Not yet implemented")
    }

    override fun openMessage(envelope: MessageEnvelope): MessagePayload {
        TODO("Not yet implemented")
    }


}