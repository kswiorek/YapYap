package org.yapyap.crypto.e2ee.session

/**
 * Capacity limits for crypto session wire frames.
 *
 * Derived from transport limits at boot by [org.yapyap.config.MessageLimits] and passed
 * into the crypto layer as a plain data holder. The crypto layer consumes these limits but
 * does not own their derivation — that requires knowledge of protocol envelope overheads
 * outside this layer.
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
data class CryptoLimits(
    val maxSessionWireFrameBytes: Int,
    val maxInnerPlaintextBytes: Int,
    val maxRatchetBodyBytes: Int,
    val maxInnerControlBytes: Int = 4 * 1024,
)
