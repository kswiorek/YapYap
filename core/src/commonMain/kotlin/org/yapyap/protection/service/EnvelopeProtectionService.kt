package org.yapyap.protection.service

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TransferId
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
import kotlin.time.Instant

internal interface EnvelopeProtectionService {
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
        transferId: TransferId,
        chunkIndex: Int,
        key: ByteArray,
        chunk: FilePayload.EncryptedChunk,
    ): ByteArray

    suspend fun encryptFileChunk(
        transferId: TransferId,
        chunkIndex: Int,
        key: ByteArray,
        plaintext: ByteArray,
    ): ByteArray

    suspend fun sealFileOffer(
        offer: FilePayload.SealedOffer,
        target: PeerId,
    ): ByteArray

    suspend fun openFileOffer(
        sealed: ByteArray,
        source: PeerId,
    ): FilePayload.SealedOffer

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

    /** @param sharedSecret sender's in-memory secret, required for INTRO; unused for RECOVERY_REQUEST. */
    suspend fun protectBootstrap(
        input: BootstrapPayload,
        context: EnvelopeProtectContext,
        sharedSecret: ByteArray? = null,
    ): BootstrapEnvelope

    suspend fun openBootstrap(
        envelope: BootstrapEnvelope,
    ): BootstrapPayload

    /**
     * True when this node holds an active bootstrap onboarding session (the AEAD intro gate
     * is armed). Sibling of [openBootstrap], which surfaces the same state by throwing
     * `ProtectionException.BootstrapSessionInactive` for INTRO envelopes.
     */
    suspend fun isBootstrapSessionActive(): Boolean
}

internal data class EnvelopeProtectContext(
    val createdAt: Instant,
    val sourceDeviceId: PeerId,
    val targetDeviceId: PeerId,
    val securityScheme: SignalSecurityScheme,
    /** File transfers address the envelope by transfer; message/system/signal protectors ignore it. */
    val transferId: TransferId? = null,
)
