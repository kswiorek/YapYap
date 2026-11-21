package org.yapyap.protection.service

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.BootstrapEnvelope
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
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
)
