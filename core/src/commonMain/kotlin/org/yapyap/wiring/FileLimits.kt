package org.yapyap.wiring

import org.yapyap.protection.envelope.FileEnvelopeProtection
import org.yapyap.protocol.FileFetchFrame
import org.yapyap.protocol.MIN_FILE_CHUNK_SIZE_BYTES
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.routing.router.TransportLimits

/**
 * File-transfer sizes, derived from the transport backend configs like [MessageLimits], never
 * configured on their own. Each layer states its own framing next to its code
 * ([FileEnvelope.maxPayloadBytes], [FilePayload.EncryptedChunk.ENCODED_OVERHEAD_BYTES],
 * [FileEnvelopeProtection.CHUNK_OVERHEAD_BYTES], [FileFetchFrame.Data.ENCODED_OVERHEAD_BYTES]); this only
 * chains them. The transport sizes are not user settings, so these are fixed while the node
 * runs. A transfer snapshots [chunkSizeBytes] when it is created and receivers are
 * chunking-agnostic (design §8), so a different size in a later run reads fine; a size lowered
 * under queued chunk rows would not, which is why the transport sizes stay out of the settings.
 */
internal data class FileLimits(
    /** Plaintext bytes per small-path chunk: one chunk's envelope fits through either transport. */
    val chunkSizeBytes: Int,
    /** File bytes per large-path DATA frame on a WebRTC file channel. */
    val fetchFrameBytes: Int,
) {
    companion object {
        /** Smallest chunk or frame worth transferring; a transport too small for it fails at startup. */
        const val MIN_CHUNK_SIZE_BYTES: Int = MIN_FILE_CHUNK_SIZE_BYTES

        fun from(config: RuntimeConfig): FileLimits = from(
            TransportLimits(
                torMaxPayloadBytes = config.tor.maxPayloadBytes,
                webRtcMaxPayloadBytes = config.webRtc.maxPayloadBytes,
            ),
        )

        /** Throws [IllegalArgumentException] when [transport] is too small to carry file chunks. */
        fun from(transport: TransportLimits): FileLimits {
            val chunkSize = FileEnvelope.maxPayloadBytes(transport.maxRoutableBytes) -
                    FilePayload.EncryptedChunk.ENCODED_OVERHEAD_BYTES -
                    FileEnvelopeProtection.CHUNK_OVERHEAD_BYTES
            val fetchFrame = transport.webRtcMaxPayloadBytes - FileFetchFrame.Data.ENCODED_OVERHEAD_BYTES
            require(chunkSize >= MIN_CHUNK_SIZE_BYTES) {
                "Transport frames (${transport.maxRoutableBytes} bytes) leave $chunkSize bytes per file chunk; " +
                        "at least $MIN_CHUNK_SIZE_BYTES are needed"
            }
            require(fetchFrame >= MIN_CHUNK_SIZE_BYTES) {
                "WebRTC frames (${transport.webRtcMaxPayloadBytes} bytes) leave $fetchFrame bytes per fetch frame; " +
                        "at least $MIN_CHUNK_SIZE_BYTES are needed"
            }
            return FileLimits(chunkSizeBytes = chunkSize, fetchFrameBytes = fetchFrame)
        }
    }
}
