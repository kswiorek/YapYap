package org.yapyap.wiring

import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protocol.LargeFileFrame
import org.yapyap.protocol.MIN_FILE_CHUNK_SIZE_BYTES
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.TransportLimits

/**
 * Chains the file-transfer sizes from the transport limits, like [MessageLimits] does for
 * messages; they are not independently configurable. Each layer states its own framing next to
 * its code ([BinaryEnvelope.maxContentBytes], [FileHeader.CHUNK_ENCODED_BYTES],
 * [FileHeader.OFFER_ENCODED_BYTES], [FileProtection.CHUNK_OVERHEAD_BYTES],
 * [LargeFileFrame.Data.ENCODED_OVERHEAD_BYTES]);
 * this only subtracts them and hands the router its piece ([FileLimits]). The large path's frame
 * size has no chunk-floor rule: it stores no rows, and its credit window, pacing and the transport
 * payload limit bound it. The transport sizes are
 * not user settings, so these are fixed while the node runs. A transfer's chunk size is part of
 * its public shape, fixed when it is created, and receivers place chunks by byte range
 * (docs/envelope-redesign.md §5.3), so a different size in a later run reads fine; a size lowered
 * under queued chunk rows would not, which is why the transport sizes stay out of the settings.
 */
internal fun fileLimitsFor(transport: TransportLimits): FileLimits {
    val chunkSize = BinaryEnvelope.maxContentBytes(transport.maxRoutableBytes, FileHeader.CHUNK_ENCODED_BYTES) -
            FileProtection.CHUNK_OVERHEAD_BYTES
    val largeFileFrame = transport.webRtcMaxPayloadBytes - LargeFileFrame.Data.ENCODED_OVERHEAD_BYTES
    val maxSealBytes = BinaryEnvelope.maxContentBytes(transport.maxRoutableBytes, FileHeader.OFFER_ENCODED_BYTES)
    require(chunkSize >= MIN_FILE_CHUNK_SIZE_BYTES) {
        "Transport frames (${transport.maxRoutableBytes} bytes) leave $chunkSize bytes per file chunk; " +
                "at least $MIN_FILE_CHUNK_SIZE_BYTES are needed"
    }
    require(largeFileFrame > 0) {
        "WebRTC frames (${transport.webRtcMaxPayloadBytes} bytes) leave no room for a large-file frame"
    }
    require(maxSealBytes > 0) {
        "Transport frames (${transport.maxRoutableBytes} bytes) leave no room for a file seal"
    }
    return FileLimits(
        chunkSizeBytes = chunkSize,
        largeFileFrameBytes = largeFileFrame,
        maxSealBytes = maxSealBytes,
    )
}

/** Chains the file sizes from a node's transport config; throws when a transport is too small. */
internal fun fileLimitsFor(config: RuntimeConfig): FileLimits = fileLimitsFor(
    TransportLimits(
        torMaxPayloadBytes = config.tor.maxPayloadBytes,
        webRtcMaxPayloadBytes = config.webRtc.maxPayloadBytes,
    ),
)
