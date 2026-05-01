package org.yapyap.backend.protection

import org.yapyap.backend.protocol.FileChunk
import org.yapyap.backend.protocol.FileEnvelope
import org.yapyap.backend.protocol.FilePayload
import org.yapyap.backend.protocol.MessageEnvelope
import org.yapyap.backend.protocol.MessagePayload
import org.yapyap.backend.protocol.OpenedFileEnvelope
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.transport.webrtc.WebRtcSignalEnvelope
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal

interface EnvelopeProtectionService {
    // Signal
    fun protectSignal(
        input: WebRtcSignal,
        context: EnvelopeProtectContext,
    ): WebRtcSignalEnvelope

    fun openSignal(
        envelope: WebRtcSignalEnvelope,
    ): WebRtcSignal

    // File
    fun protectFile(
        input: FilePayload,
        context: EnvelopeProtectContext,
    ): FileEnvelope

    fun openFile(
        envelope: FileEnvelope,
    ): OpenedFileEnvelope

    fun decryptFileChunk(
        chunk: FilePayload.EncryptedChunk,
    ): FileChunk

    // Future message envelope
    fun protectMessage(
        input: MessagePayload,
        context: EnvelopeProtectContext,
    ): MessageEnvelope

    fun openMessage(
        envelope: MessageEnvelope,
    ): MessagePayload
}

data class EnvelopeProtectContext(
    val createdAtEpochSeconds: Long,
    val nonce: ByteArray,
    val sourceDeviceId: PeerId,
    val targetDeviceId: PeerId,
    val securityScheme: SignalSecurityScheme,
)