package org.yapyap.routing.router

/** Largest [org.yapyap.protocol.envelopes.BinaryEnvelope] each transport carries; built by the composition root from the backend configs. */
internal data class TransportLimits(
    val torMaxPayloadBytes: Int,
    val webRtcMaxPayloadBytes: Int,
) {
    /** Max BinaryEnvelope that can traverse EITHER transport. Messages use this. */
    val maxRoutableBytes: Int
        get() = minOf(torMaxPayloadBytes, webRtcMaxPayloadBytes)

    /** Max BinaryEnvelope that can traverse the LARGER transport.
     *  Unchunked file envelopes use this. Crypto DoS guard uses this. */
    val maxTransportableBytes: Int
        get() = maxOf(torMaxPayloadBytes, webRtcMaxPayloadBytes)
}
