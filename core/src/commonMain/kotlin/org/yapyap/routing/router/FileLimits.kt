package org.yapyap.routing.router

/**
 * File-transfer sizes handed to the router by the composition root: plaintext bytes per
 * small-path chunk, file bytes per large-path DATA frame, and the largest seal an OFFER carries. Chained from the transport limits
 * (`wiring/FileLimits`, the size-limits rule of guide §2): each layer states only its own framing
 * overhead, the composition root subtracts them, and this is the router's piece.
 */
internal data class FileLimits(
    /** Plaintext bytes per small-path chunk: one chunk's envelope fits through either transport. */
    val chunkSizeBytes: Int,
    /** File bytes per large-path DATA frame on a WebRTC file channel. */
    val largeFileFrameBytes: Int,
    /**
     * The most an OFFER's content (a seal) may be: derived from the transports' frame limit minus
     * the OFFER header, so a seal that fits is routable through either transport.
     */
    val maxSealBytes: Int,
)
