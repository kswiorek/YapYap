package org.yapyap.protocol

import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapHeader
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.MessageHeader
import org.yapyap.protocol.envelopes.SignalHeader

internal enum class FieldSensitivity {
    /** Readable by every device that handles the packet: the hop's receiver and any relay. */
    ROUTING_VISIBLE,

    /** Readable only by the packet's final target, without end-to-end encryption. */
    ENDPOINT_VISIBLE,

    /** End-to-end encrypted: only the final target can read it. */
    PROTECTED,
}

internal data class ObservabilityProfile(
    val schemaId: String,
    val fields: Map<String, FieldSensitivity>,
)

/**
 * Which metadata is visible on the wire (docs/envelope-redesign.md §2, §4). Every family shares
 * one envelope: the hop header and the routing header are routing-visible by construction, and
 * the content is what the family seals. A family that adds a routing-header field adds it to its
 * profile here.
 */
internal object EnvelopeObservability {
    val binaryEnvelope = ObservabilityProfile(
        schemaId = "binary-envelope-v2",
        fields = mapOf(
            BinaryEnvelope.Companion.Fields.PACKET_ID to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.PACKET_TYPE to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.DISPOSITION_REQUESTED to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.CREATED_AT to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.EXPIRES_AT to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.SOURCE to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.TARGET to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.ROUTING_HEADER to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.SIGNATURE to FieldSensitivity.ROUTING_VISIBLE,
        ),
    )

    val messageHeader = ObservabilityProfile(
        schemaId = "message-header-v1",
        fields = mapOf(
            MessageHeader.Companion.Fields.ORIGIN to FieldSensitivity.ROUTING_VISIBLE,
            MessageHeader.Companion.Fields.FINAL_TARGET to FieldSensitivity.ROUTING_VISIBLE,
        ),
    )

    val signalHeader = ObservabilityProfile(
        schemaId = "signal-header-v1",
        fields = mapOf(SignalHeader.Companion.Fields.KIND to FieldSensitivity.ROUTING_VISIBLE),
    )

    /**
     * SYSTEM packets are header-only and only hop-signed: ACK/NACK, pings with their room
     * frontiers, typing and log-off are all readable by the hop's receiver, which is always the
     * packet's target (SYSTEM is never relayed).
     */
    val systemHeader = ObservabilityProfile(
        schemaId = "system-header-v1",
        fields = mapOf(
            "kind" to FieldSensitivity.ROUTING_VISIBLE,
            "packetId" to FieldSensitivity.ROUTING_VISIBLE,
            "packetType" to FieldSensitivity.ROUTING_VISIBLE,
            "reason" to FieldSensitivity.ROUTING_VISIBLE,
            "roomId" to FieldSensitivity.ROUTING_VISIBLE,
            "intervalMillis" to FieldSensitivity.ROUTING_VISIBLE,
            "pingId" to FieldSensitivity.ROUTING_VISIBLE,
            "selfReportedAvailability" to FieldSensitivity.ROUTING_VISIBLE,
            "roomFrontiers" to FieldSensitivity.ROUTING_VISIBLE,
        ),
    )

    /**
     * The bootstrap scheme must be readable before the content is opened; the content is
     * ENDPOINT_VISIBLE at least (a RECOVERY_REQUEST is account-signed plaintext by design, an
     * INTRO is AEAD-sealed under the onboarding secret, which exceeds that floor).
     */
    val bootstrapHeader = ObservabilityProfile(
        schemaId = "bootstrap-header-v1",
        fields = mapOf(
            BootstrapHeader.Companion.Fields.SCHEME to FieldSensitivity.ROUTING_VISIBLE,
            BootstrapHeader.Companion.Fields.BOOTSTRAP_ENVELOPE_ID to FieldSensitivity.ROUTING_VISIBLE,
        ),
    )

    /**
     * FILE routing headers: transfer ids, the OFFER's origin, final target and shape, chunk
     * indices, coverage, cancel reasons and a COMPLETE's seal id. The `fileId`, block list, seal
     * key, file name and a REQUEST's coverage live only in sealed content.
     */
    val fileHeader = ObservabilityProfile(
        schemaId = "file-header-v2",
        fields = mapOf(
            FileHeader.Companion.Fields.TRANSFER_ID to FieldSensitivity.ROUTING_VISIBLE,
            FileHeader.Companion.Fields.ORIGIN to FieldSensitivity.ROUTING_VISIBLE,
            FileHeader.Companion.Fields.FINAL_TARGET to FieldSensitivity.ROUTING_VISIBLE,
            FileHeader.Companion.Fields.CHUNK_SIZE to FieldSensitivity.ROUTING_VISIBLE,
            FileHeader.Companion.Fields.BYTE_FROM to FieldSensitivity.ROUTING_VISIBLE,
            FileHeader.Companion.Fields.BYTE_TO to FieldSensitivity.ROUTING_VISIBLE,
            FileHeader.Companion.Fields.CHUNK_INDEX to FieldSensitivity.ROUTING_VISIBLE,
            FileHeader.Companion.Fields.HIGHEST_CONTIGUOUS_INDEX to FieldSensitivity.ROUTING_VISIBLE,
            FileHeader.Companion.Fields.MISSING_RANGES to FieldSensitivity.ROUTING_VISIBLE,
            FileHeader.Companion.Fields.CANCEL_REASON to FieldSensitivity.ROUTING_VISIBLE,
            FileHeader.Companion.Fields.SEAL_ID to FieldSensitivity.ROUTING_VISIBLE,
            "fileId" to FieldSensitivity.PROTECTED,
            "blockHashes" to FieldSensitivity.PROTECTED,
            "sealKey" to FieldSensitivity.PROTECTED,
            "fileName" to FieldSensitivity.PROTECTED,
            "mimeType" to FieldSensitivity.PROTECTED,
            "contiguousBytes" to FieldSensitivity.PROTECTED,
        ),
    )

    /** How each family's content is protected; SYSTEM has none. */
    val content: Map<PacketType, FieldSensitivity> = mapOf(
        PacketType.MESSAGE to FieldSensitivity.PROTECTED,
        PacketType.SIGNAL to FieldSensitivity.PROTECTED,
        PacketType.FILE to FieldSensitivity.PROTECTED,
        PacketType.BOOTSTRAP to FieldSensitivity.ENDPOINT_VISIBLE,
    )
}
