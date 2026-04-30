package org.yapyap.backend.routing

import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.FileEnvelope
import org.yapyap.backend.transport.webrtc.WebRtcSignalEnvelope

enum class FieldSensitivity {
    ROUTING_VISIBLE,
    ENDPOINT_VISIBLE,
    PROTECTED,
}

data class ObservabilityProfile(
    val schemaId: String,
    val fields: Map<String, FieldSensitivity>,
)

/**
 * Central source of truth for metadata visibility rules by envelope schema.
 *
 * Envelope builders/protection adapters should keep cleartext fields limited to what
 * these profiles mark as non-protected.
 */
object EnvelopeObservability {
    val binaryEnvelope = ObservabilityProfile(
        schemaId = "binary-envelope-v1",
        fields = mapOf(
            BinaryEnvelope.Companion.Fields.PACKET_ID to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.PACKET_TYPE to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.CREATED_AT_EPOCH_SECONDS to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.EXPIRES_AT_EPOCH_SECONDS to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.SOURCE to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.TARGET to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.PAYLOAD to FieldSensitivity.PROTECTED,
        ),
    )

    val fileEnvelope = ObservabilityProfile(
        schemaId = "file-envelope-v1",
        fields = mapOf(
            FileEnvelope.Companion.Fields.TRANSFER_ID to FieldSensitivity.ROUTING_VISIBLE,
            FileEnvelope.Companion.Fields.KIND to FieldSensitivity.ROUTING_VISIBLE,
            FileEnvelope.Companion.Fields.SOURCE to FieldSensitivity.ROUTING_VISIBLE,
            FileEnvelope.Companion.Fields.TARGET to FieldSensitivity.ROUTING_VISIBLE,
            FileEnvelope.Companion.Fields.CREATED_AT_EPOCH_SECONDS to FieldSensitivity.ROUTING_VISIBLE,
            FileEnvelope.Companion.Fields.NONCE to FieldSensitivity.ENDPOINT_VISIBLE,
            FileEnvelope.Companion.Fields.SECURITY_SCHEME to FieldSensitivity.ROUTING_VISIBLE,
            FileEnvelope.Companion.Fields.SIGNATURE to FieldSensitivity.ENDPOINT_VISIBLE,
            FileEnvelope.Companion.Fields.PAYLOAD to FieldSensitivity.PROTECTED,
        ),
    )

    val webRtcSignalEnvelope = ObservabilityProfile(
        schemaId = "webrtc-signal-envelope-v1",
        fields = mapOf(
            WebRtcSignalEnvelope.Companion.Fields.SESSION_ID to FieldSensitivity.ROUTING_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.KIND to FieldSensitivity.ROUTING_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.SOURCE to FieldSensitivity.ROUTING_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.TARGET to FieldSensitivity.ROUTING_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.CREATED_AT_EPOCH_SECONDS to FieldSensitivity.ROUTING_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.NONCE to FieldSensitivity.ENDPOINT_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.SECURITY_SCHEME to FieldSensitivity.ROUTING_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.SIGNATURE to FieldSensitivity.ENDPOINT_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.PROTECTED_PAYLOAD to FieldSensitivity.PROTECTED,
        ),
    )

    val byFamily: Map<EnvelopeFamily, List<ObservabilityProfile>> = mapOf(
        EnvelopeFamily.MESSAGE to listOf(binaryEnvelope),
        EnvelopeFamily.FILE to listOf(fileEnvelope),
        EnvelopeFamily.SIGNAL to listOf(binaryEnvelope, webRtcSignalEnvelope),
    )
}
