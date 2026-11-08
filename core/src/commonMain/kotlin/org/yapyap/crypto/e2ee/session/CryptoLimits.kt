package org.yapyap.crypto.e2ee.session

import org.yapyap.crypto.primitives.DefaultCryptoProvider

/**
 * Capacity limits for crypto session wire frames.
 *
 * Built by [forSessionWireFrameBudget] from the largest session wire frame the transports can
 * carry. The composition root works out that budget (transport limits minus protocol envelope
 * framing); the crypto overheads below it are known only here.
 *
 * These replace the capacity-related constants that previously lived on [CryptoWireLimits].
 * [CryptoWireLimits] retains only protocol-invariant constraints (key sizes, string ID caps,
 * binding length, skipped-key count) determined by the cryptographic algorithms themselves.
 *
 * Enforcement lives in [CryptoWireCodec]: encoding a too-large value throws [IllegalArgumentException]
 * (programming error), decoding a peer-sent oversized value throws
 * [org.yapyap.crypto.e2ee.CryptoSessionException.OversizedFrame].
 *
 * [maxInnerControlBytes] is not derived from transport capacity; control blocks (OPK offers)
 * are structurally tiny and kept at a fixed, generous cap.
 */
internal data class CryptoLimits(
    val maxSessionWireFrameBytes: Int,
    val maxInnerPlaintextBytes: Int,
    val maxRatchetBodyBytes: Int,
    val maxInnerControlBytes: Int = 4 * 1024,
) {
    companion object {
        /**
         * Limits for session wire frames of at most [maxSessionWireFrameBytes], by subtracting
         * each crypto layer's own framing: the wire-frame and ratchet headers, then the AEAD tag
         * and inner-plaintext framing.
         */
        fun forSessionWireFrameBudget(maxSessionWireFrameBytes: Int): CryptoLimits {
            val maxRatchetBody = (maxSessionWireFrameBytes
                    - SessionWireFrame.MAX_HEADER_BYTES
                    - RatchetCiphertext.HEADER_BYTES)

            val maxInnerPlaintext = (maxRatchetBody
                    - DefaultCryptoProvider.AEAD_OVERHEAD_BYTES
                    - RatchetInnerPlaintext.ENCODED_OVERHEAD)

            return CryptoLimits(
                maxSessionWireFrameBytes = maxSessionWireFrameBytes,
                maxInnerPlaintextBytes = maxInnerPlaintext,
                maxRatchetBodyBytes = maxRatchetBody,
            )
        }
    }
}
