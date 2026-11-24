package org.yapyap.protection.envelope

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.protocol.envelopes.WebRtcSignalKind

/** SIGNAL content: a signal's payload ratchet-encrypted to the peer; the kind rides the header. */
internal interface WebRtcSignalProtection {
    suspend fun seal(signal: WebRtcSignal): ByteArray

    suspend fun open(content: ByteArray, kind: WebRtcSignalKind, source: PeerId, target: PeerId): WebRtcSignal
}

internal class RatchetWebRtcSignalProtection(private val ratchet: RatchetSealer) : WebRtcSignalProtection {
    override suspend fun seal(signal: WebRtcSignal): ByteArray =
        ratchet.seal(signal.payload, signal.target, what = "WebRTC signal")

    override suspend fun open(
        content: ByteArray,
        kind: WebRtcSignalKind,
        source: PeerId,
        target: PeerId,
    ): WebRtcSignal = WebRtcSignal(
        kind = kind,
        source = source,
        target = target,
        payload = ratchet.open(content, source, what = "WebRTC signal"),
    )
}
