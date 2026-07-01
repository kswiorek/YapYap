package org.yapyap.protection.service

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.*
import org.yapyap.transport.webrtc.types.WebRtcSignal

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
