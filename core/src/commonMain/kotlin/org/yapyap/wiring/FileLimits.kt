package org.yapyap.wiring

import org.yapyap.protection.envelope.FileEnvelopeProtection
import org.yapyap.protocol.FileFetchFrame
import org.yapyap.protocol.MIN_FILE_CHUNK_SIZE_BYTES
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.TransportLimits

/**
 * Chains the file-transfer sizes from the transport limits, like [MessageLimits] does for
 * messages; they are not independently configurable. Each layer states its own framing next to
 * its code ([FileEnvelope.maxPayloadBytes], [FilePayload.EncryptedChunk.ENCODED_OVERHEAD_BYTES],
 * [FileEnvelopeProtection.CHUNK_OVERHEAD_BYTES], [FileFetchFrame.Data.ENCODED_OVERHEAD_BYTES]);
 * this only subtracts them and hands the router its piece ([FileLimits]). The transport sizes are
 * not user settings, so these are fixed while the node runs. A transfer snapshots
 * `chunkSizeBytes` when it is created and receivers are chunking-agnostic (design §8), so a
 * different size in a later run reads fine; a size lowered under queued chunk rows would not,
 * which is why the transport sizes stay out of the settings.
 */
internal fun fileLimitsFor(transport: TransportLimits): FileLimits {
    val chunkSize = FileEnvelope.maxPayloadBytes(transport.maxRoutableBytes) -
            FilePayload.EncryptedChunk.ENCODED_OVERHEAD_BYTES -
            FileEnvelopeProtection.CHUNK_OVERHEAD_BYTES
    val fetchFrame = transport.webRtcMaxPayloadBytes - FileFetchFrame.Data.ENCODED_OVERHEAD_BYTES
    require(chunkSize >= MIN_FILE_CHUNK_SIZE_BYTES) {
        "Transport frames (${transport.maxRoutableBytes} bytes) leave $chunkSize bytes per file chunk; " +
                "at least $MIN_FILE_CHUNK_SIZE_BYTES are needed"
    }
    require(fetchFrame >= MIN_FILE_CHUNK_SIZE_BYTES) {
        "WebRTC frames (${transport.webRtcMaxPayloadBytes} bytes) leave $fetchFrame bytes per fetch frame; " +
                "at least $MIN_FILE_CHUNK_SIZE_BYTES are needed"
    }
    return FileLimits(chunkSizeBytes = chunkSize, fetchFrameBytes = fetchFrame)
}

/** Chains the file sizes from a node's transport config; throws when a transport is too small. */
internal fun fileLimitsFor(config: RuntimeConfig): FileLimits = fileLimitsFor(
    TransportLimits(
        torMaxPayloadBytes = config.tor.maxPayloadBytes,
        webRtcMaxPayloadBytes = config.webRtc.maxPayloadBytes,
    ),
)
