package org.yapyap.config

data class TransportLimits(
    val torMaxPayloadBytes: Long,
    val webRtcMaxPayloadBytes: Long,
) {
    /** Max BinaryEnvelope that can traverse EITHER transport. Messages use this. */
    val maxRoutableBytes: Long
        get() = minOf(torMaxPayloadBytes, webRtcMaxPayloadBytes)

    /** Max BinaryEnvelope that can traverse the LARGER transport.
     *  Unchunked file envelopes use this. Crypto DoS guard uses this. */
    val maxTransportableBytes: Long
        get() = maxOf(torMaxPayloadBytes, webRtcMaxPayloadBytes)

    companion object {
        fun from(config: RuntimeConfig) = TransportLimits(
            torMaxPayloadBytes = config.tor.maxPayloadBytes,
            webRtcMaxPayloadBytes = config.webRtc.maxPayloadBytes,
        )
    }
}