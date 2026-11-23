package org.yapyap.routing.router

/**
 * File-transfer sizes handed to the router by the composition root: plaintext bytes per
 * small-path chunk and file bytes per large-path DATA frame. Chained from the transport limits
 * (`wiring/FileLimits`, the size-limits rule of guide §2): each layer states only its own framing
 * overhead, the composition root subtracts them, and this is the router's piece.
 */
internal data class FileLimits(
    /** Plaintext bytes per small-path chunk: one chunk's envelope fits through either transport. */
    val chunkSizeBytes: Int,
    /** File bytes per large-path DATA frame on a WebRTC file channel. */
    val fetchFrameBytes: Int,
)
