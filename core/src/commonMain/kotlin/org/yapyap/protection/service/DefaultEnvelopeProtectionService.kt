package org.yapyap.protection.service

import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protection.envelope.MessageProtection
import org.yapyap.protection.envelope.SystemProtection
import org.yapyap.protection.envelope.WebRtcSignalProtection
import org.yapyap.protocol.envelopes.BootstrapEnvelope
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.OpenedFileEnvelope
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.protocol.envelopes.WebRtcSignalEnvelope
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId

internal class DefaultEnvelopeProtectionService(
    val webRtcSignalProtection: WebRtcSignalProtection,
    val fileProtection: FileProtection,
    val messageProtection: MessageProtection,
    val systemProtection: SystemProtection,
    val bootstrapProtection: BootstrapProtection,
) : EnvelopeProtectionService {
    override suspend fun protectSignal(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope =
        webRtcSignalProtection.protect(input, context)

    override suspend fun openSignal(envelope: WebRtcSignalEnvelope): WebRtcSignal =
        webRtcSignalProtection.open(envelope)

    override suspend fun protectFile(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope =
        fileProtection.protect(input, context)

    override suspend fun openFile(envelope: FileEnvelope): OpenedFileEnvelope =
        fileProtection.open(envelope)

    override suspend fun decryptFileChunk(
        transferId: TransferId,
        chunkIndex: Int,
        key: ByteArray,
        chunk: FilePayload.EncryptedChunk,
    ): ByteArray =
        fileProtection.decryptChunk(transferId, chunkIndex, key, chunk)

    override suspend fun encryptFileChunk(
        transferId: TransferId,
        chunkIndex: Int,
        key: ByteArray,
        plaintext: ByteArray,
    ): ByteArray =
        fileProtection.encryptChunk(transferId, chunkIndex, key, plaintext)

    override suspend fun sealFileOffer(
        offer: FilePayload.SealedOffer,
        target: PeerId,
    ): ByteArray =
        fileProtection.sealOffer(offer, target)

    override suspend fun openFileOffer(
        sealed: ByteArray,
        source: PeerId,
    ): FilePayload.SealedOffer =
        fileProtection.openOffer(sealed, source)

    override suspend fun protectMessage(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope =
        messageProtection.protect(input, context)

    override suspend fun openMessage(envelope: MessageEnvelope): MessagePayload =
        messageProtection.open(envelope)

    override suspend fun protectSystem(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope =
        systemProtection.protect(input, context)

    override suspend fun openSystem(envelope: SystemEnvelope): SystemPayload =
        systemProtection.open(envelope)

    override suspend fun protectBootstrap(
        input: BootstrapPayload,
        context: EnvelopeProtectContext,
        sharedSecret: ByteArray?
    ): BootstrapEnvelope =
        bootstrapProtection.protect(
            input,
            context.sourceDeviceId,
            context.targetDeviceId,
            context.createdAt,
            sharedSecret
        )

    override suspend fun openBootstrap(envelope: BootstrapEnvelope): BootstrapPayload =
        bootstrapProtection.open(envelope)

    override suspend fun isBootstrapSessionActive(): Boolean =
        bootstrapProtection.hasActiveSession()
}