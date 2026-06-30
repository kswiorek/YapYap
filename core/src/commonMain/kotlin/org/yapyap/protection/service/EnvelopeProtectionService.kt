package org.yapyap.protection.service

import org.yapyap.protocol.envelopes.FileChunk
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.OpenedFileEnvelope
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.WebRtcSignalEnvelope
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
