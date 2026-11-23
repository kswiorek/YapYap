package org.yapyap.protocol

import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapEnvelope
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.WebRtcSignalEnvelope

internal enum class FieldSensitivity {
    ROUTING_VISIBLE,
    ENDPOINT_VISIBLE,
    PROTECTED,
}

internal data class ObservabilityProfile(
    val schemaId: String,
    val fields: Map<String, FieldSensitivity>,
)

/**
 * Central source of truth for metadata visibility rules by envelope schema.
 *
 * Envelope builders/protection adapters should keep cleartext fields limited to what
 * these profiles mark as non-protected.
 */
internal object EnvelopeObservability {
    val binaryEnvelope = ObservabilityProfile(
        schemaId = "binary-envelope-v1",
        fields = mapOf(
            BinaryEnvelope.Companion.Fields.PACKET_ID to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.PACKET_TYPE to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.CREATED_AT to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.EXPIRES_AT to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.SOURCE to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.TARGET to FieldSensitivity.ROUTING_VISIBLE,
            BinaryEnvelope.Companion.Fields.PAYLOAD to FieldSensitivity.ENDPOINT_VISIBLE,
        ),
    )

    /**
     * Bootstrap envelope visibility. The header is plaintext by necessity — `scheme` selects how
     * to open the payload before anything is decoded ([BootstrapEnvelope] carries no payload kind
     * in clear) and the whole header is bound as AEAD AAD, so it cannot be swapped or re-targeted.
     *
     * `PAYLOAD` is the honest floor, `ENDPOINT_VISIBLE`: RECOVERY_REQUEST is account-signed
     * plaintext *by design* (the responder has no session yet — the signature is the
     * authentication), while INTRO payloads are additionally AEAD-sealed, which trivially exceeds
     * this floor. No payload profile: unlike FILE, bootstrap payload visibility is uniform per
     * kind, never mixed within one payload.
     */
    val bootstrapEnvelope = ObservabilityProfile(
        schemaId = "bootstrap-envelope-v1",
        fields = mapOf(
            BootstrapEnvelope.Companion.Fields.SCHEME to FieldSensitivity.ROUTING_VISIBLE,
            BootstrapEnvelope.Companion.Fields.BOOTSTRAP_ENVELOPE_ID to FieldSensitivity.ROUTING_VISIBLE,
            BootstrapEnvelope.Companion.Fields.SOURCE to FieldSensitivity.ROUTING_VISIBLE,
            BootstrapEnvelope.Companion.Fields.TARGET to FieldSensitivity.ROUTING_VISIBLE,
            BootstrapEnvelope.Companion.Fields.CREATED_AT to FieldSensitivity.ROUTING_VISIBLE,
            BootstrapEnvelope.Companion.Fields.PAYLOAD to FieldSensitivity.ENDPOINT_VISIBLE,
        ),
    )

    val fileEnvelope = ObservabilityProfile(
        schemaId = "file-envelope-v1",
        fields = mapOf(
            FileEnvelope.Companion.Fields.TRANSFER_ID to FieldSensitivity.ROUTING_VISIBLE,
            FileEnvelope.Companion.Fields.SOURCE to FieldSensitivity.ROUTING_VISIBLE,
            FileEnvelope.Companion.Fields.TARGET to FieldSensitivity.ROUTING_VISIBLE,
            FileEnvelope.Companion.Fields.CREATED_AT to FieldSensitivity.ROUTING_VISIBLE,
            FileEnvelope.Companion.Fields.NONCE to FieldSensitivity.ENDPOINT_VISIBLE,
            FileEnvelope.Companion.Fields.SECURITY_SCHEME to FieldSensitivity.ROUTING_VISIBLE,
            FileEnvelope.Companion.Fields.SIGNATURE to FieldSensitivity.ENDPOINT_VISIBLE,
            FileEnvelope.Companion.Fields.PAYLOAD to FieldSensitivity.PROTECTED,
        ),
    )

    /**
     * FILE payload visibility (design §3.2): the OFFER public half, the CHUNK index,
     * ACK coverage, COMPLETE and the CANCEL reason are visible to relays; sealed halves,
     * keys, block lists and REQUEST coverage are ratchet-sealed and never appear in clear.
     */
    val filePayload = ObservabilityProfile(
        schemaId = "file-payload-v1",
        fields = mapOf(
            "chunkSizeBytes" to FieldSensitivity.ROUTING_VISIBLE,
            "totalBytes" to FieldSensitivity.ROUTING_VISIBLE,
            "startIndex" to FieldSensitivity.ROUTING_VISIBLE,
            "chunkIndex" to FieldSensitivity.ROUTING_VISIBLE,
            "highestContiguousIndex" to FieldSensitivity.ROUTING_VISIBLE,
            "missingRanges" to FieldSensitivity.ROUTING_VISIBLE,
            "complete" to FieldSensitivity.ROUTING_VISIBLE,
            "cancelReason" to FieldSensitivity.ROUTING_VISIBLE,
            "fileId" to FieldSensitivity.PROTECTED,
            "blockHashes" to FieldSensitivity.PROTECTED,
            "transferKey" to FieldSensitivity.PROTECTED,
            "fileName" to FieldSensitivity.PROTECTED,
            "mimeType" to FieldSensitivity.PROTECTED,
            "contiguousBytes" to FieldSensitivity.PROTECTED,
        ),
    )

    val webRtcSignalEnvelope = ObservabilityProfile(
        schemaId = "webrtc-signal-envelope-v1",
        fields = mapOf(
            WebRtcSignalEnvelope.Companion.Fields.SIGNAL_ENVELOPE_ID to FieldSensitivity.ROUTING_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.KIND to FieldSensitivity.ROUTING_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.SOURCE to FieldSensitivity.ROUTING_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.TARGET to FieldSensitivity.ROUTING_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.CREATED_AT to FieldSensitivity.ROUTING_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.NONCE to FieldSensitivity.ENDPOINT_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.SECURITY_SCHEME to FieldSensitivity.ROUTING_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.SIGNATURE to FieldSensitivity.ENDPOINT_VISIBLE,
            WebRtcSignalEnvelope.Companion.Fields.PROTECTED_PAYLOAD to FieldSensitivity.PROTECTED,
        ),
    )

    val messageEnvelope = ObservabilityProfile(
        schemaId = "message-envelope-v1",
        fields = mapOf(
            MessageEnvelope.Companion.Fields.MESSAGE_ID to FieldSensitivity.ROUTING_VISIBLE,
            MessageEnvelope.Companion.Fields.SOURCE to FieldSensitivity.ROUTING_VISIBLE,
            MessageEnvelope.Companion.Fields.TARGET to FieldSensitivity.ROUTING_VISIBLE,
            MessageEnvelope.Companion.Fields.CREATED_AT to FieldSensitivity.ROUTING_VISIBLE,
            MessageEnvelope.Companion.Fields.NONCE to FieldSensitivity.ENDPOINT_VISIBLE,
            MessageEnvelope.Companion.Fields.SECURITY_SCHEME to FieldSensitivity.ROUTING_VISIBLE,
            MessageEnvelope.Companion.Fields.SIGNATURE to FieldSensitivity.ENDPOINT_VISIBLE,
            MessageEnvelope.Companion.Fields.PAYLOAD to FieldSensitivity.PROTECTED,
        ),
    )

    val systemEnvelope = ObservabilityProfile(
        schemaId = "system-envelope-v1",
        fields = mapOf(
            SystemEnvelope.Companion.Fields.CORRELATION_ID to FieldSensitivity.ROUTING_VISIBLE,
            SystemEnvelope.Companion.Fields.SOURCE to FieldSensitivity.ROUTING_VISIBLE,
            SystemEnvelope.Companion.Fields.TARGET to FieldSensitivity.ROUTING_VISIBLE,
            SystemEnvelope.Companion.Fields.CREATED_AT to FieldSensitivity.ROUTING_VISIBLE,
            SystemEnvelope.Companion.Fields.NONCE to FieldSensitivity.ENDPOINT_VISIBLE,
            SystemEnvelope.Companion.Fields.SECURITY_SCHEME to FieldSensitivity.ROUTING_VISIBLE,
            SystemEnvelope.Companion.Fields.SIGNATURE to FieldSensitivity.ENDPOINT_VISIBLE,
            SystemEnvelope.Companion.Fields.PAYLOAD to FieldSensitivity.PROTECTED,
        ),
    )
}
