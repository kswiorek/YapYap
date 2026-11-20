package org.yapyap.routing.file

import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Builds a signed FILE control envelope (COMPLETE/CANCEL) and wraps it for the outbox. */
internal suspend fun buildFileControlEnvelope(
    protection: EnvelopeProtectionService,
    transferId: TransferId,
    source: PeerId,
    target: PeerId,
    createdAt: Instant,
    expiresAt: Instant,
    payload: FilePayload,
): BinaryEnvelope {
    val envelope = protection.protectFile(
        payload,
        EnvelopeProtectContext(
            createdAt = createdAt,
            sourceDeviceId = source,
            targetDeviceId = target,
            securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            transferId = transferId,
        ),
    )
    return BinaryEnvelope(
        packetId = Uuid.random(),
        packetType = PacketType.FILE,
        dispositionRequested = true,
        createdAt = createdAt,
        expiresAt = expiresAt,
        source = source,
        target = target,
        payload = envelope.encode(),
    )
}
