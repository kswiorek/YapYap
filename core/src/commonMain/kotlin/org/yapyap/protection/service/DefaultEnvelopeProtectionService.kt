package org.yapyap.protection.service

import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.MessageProtection
import org.yapyap.protection.envelope.SystemProtection
import org.yapyap.protection.envelope.WebRtcSignalProtection
import org.yapyap.protocol.envelopes.*

internal class DefaultEnvelopeProtectionService(
    val webRtcSignalProtection: WebRtcSignalProtection,
    val messageProtection: MessageProtection,
    val systemProtection: SystemProtection,
    val bootstrapProtection: BootstrapProtection,
) : EnvelopeProtectionService {
    override suspend fun protectSignal(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope =
        webRtcSignalProtection.protect(input, context)

    override suspend fun openSignal(envelope: WebRtcSignalEnvelope): WebRtcSignal =
        webRtcSignalProtection.open(envelope)

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
