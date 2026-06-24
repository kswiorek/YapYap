package org.yapyap.backend.protection

import org.yapyap.backend.protocol.FileChunk
import org.yapyap.backend.protocol.FileEnvelope
import org.yapyap.backend.protocol.FilePayload
import org.yapyap.backend.protocol.MessageEnvelope
import org.yapyap.backend.protocol.MessagePayload
import org.yapyap.backend.protocol.OpenedFileEnvelope
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.protocol.SystemEnvelope
import org.yapyap.backend.protocol.SystemPayload
import org.yapyap.backend.protocol.WebRtcSignalEnvelope
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal

interface EnvelopeProtectionService {
    suspend fun protectSignal(
        input: WebRtcSignal,
        context: EnvelopeProtectContext,
    ): WebRtcSignalEnvelope

    suspend fun openSignal(
        envelope: WebRtcSignalEnvelope,
    ): WebRtcSignal

    suspend fun protectFile(
        input: FilePayload,
        context: EnvelopeProtectContext,
    ): FileEnvelope

    suspend fun openFile(
        envelope: FileEnvelope,
    ): OpenedFileEnvelope

    suspend fun decryptFileChunk(
        chunk: FilePayload.EncryptedChunk,
    ): FileChunk

    suspend fun protectMessage(
        input: MessagePayload,
        context: EnvelopeProtectContext,
    ): MessageEnvelope

    suspend fun openMessage(
        envelope: MessageEnvelope,
    ): MessagePayload

    suspend fun protectSystem(
        input: SystemPayload,
        context: EnvelopeProtectContext,
    ): SystemEnvelope

    suspend fun openSystem(
        envelope: SystemEnvelope,
    ): SystemPayload
}

data class EnvelopeProtectContext(
    val createdAtEpochSeconds: Long,
    val sourceDeviceId: PeerId,
    val targetDeviceId: PeerId,
    val securityScheme: SignalSecurityScheme,
)
