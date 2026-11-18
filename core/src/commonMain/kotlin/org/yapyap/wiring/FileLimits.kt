package org.yapyap.wiring

import org.yapyap.routing.router.TransportLimits

/**
 * Derived file-transfer limits, chained from the transport backend configs like
 * [MessageLimits] — never independently configured. Each transfer snapshots the current
 * derived value at creation (design §2, §15).
 */
internal data class FileLimits(
    /** Plaintext bytes per transfer chunk: fits either transport with framing margin. */
    val chunkSizeBytes: Int,
) {
    companion object {
        /**
         * Reserve against the smaller transport's frame for envelope framing, AEAD overhead
         * and signatures, with wide margin. The exact value is not consensus-critical:
         * receivers are chunking-agnostic (design §8), so mixed chunkings coexist.
         * With defaults (250 KiB WebRTC) this is ≈220 KiB (design §6.2).
         */
        const val CHUNK_RESERVE_BYTES: Int = 30 * 1024

        /** Smallest chunk a transfer is ever cut into. */
        const val MIN_CHUNK_SIZE_BYTES: Int = 64 * 1024

        fun from(config: RuntimeConfig): FileLimits = from(
            TransportLimits(
                torMaxPayloadBytes = config.tor.maxPayloadBytes,
                webRtcMaxPayloadBytes = config.webRtc.maxPayloadBytes,
            ),
        )

        fun from(transport: TransportLimits): FileLimits {
            val chunkSize = minOf(transport.torMaxPayloadBytes, transport.webRtcMaxPayloadBytes) -
                CHUNK_RESERVE_BYTES
            return FileLimits(maxOf(chunkSize, MIN_CHUNK_SIZE_BYTES))
        }
    }
}
