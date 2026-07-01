package org.yapyap.protection.service

import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protection.envelope.MessageProtection
import org.yapyap.protection.envelope.SystemProtection
import org.yapyap.protection.envelope.WebRtcSignalProtection
import org.yapyap.protocol.envelopes.*
import org.yapyap.transport.webrtc.types.WebRtcSignal

class DefaultEnvelopeProtectionService(
    val webRtcSignalProtection: WebRtcSignalProtection,
    val fileProtection: FileProtection,
    val messageProtection: MessageProtection,
    val systemProtection: SystemProtection,
): EnvelopeProtectionService {
    override suspend fun protectSignal(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope =
        webRtcSignalProtection.protect(input, context)

    override suspend fun openSignal(envelope: WebRtcSignalEnvelope): WebRtcSignal =
        webRtcSignalProtection.open(envelope)

    override suspend fun protectFile(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope =
        fileProtection.protect(input, context)

    override suspend fun openFile(envelope: FileEnvelope): OpenedFileEnvelope =
        fileProtection.open(envelope)

    override suspend fun decryptFileChunk(chunk: FilePayload.EncryptedChunk): FileChunk =
        fileProtection.decryptChunk(chunk)

    override suspend fun protectMessage(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope =
        messageProtection.protect(input, context)

    override suspend fun openMessage(envelope: MessageEnvelope): MessagePayload =
        messageProtection.open(envelope)

    override suspend fun protectSystem(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope =
        systemProtection.protect(input, context)

    override suspend fun openSystem(envelope: SystemEnvelope): SystemPayload =
        systemProtection.open(envelope)
}