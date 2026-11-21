package org.yapyap.transport.webrtc.types

import org.yapyap.protocol.PeerId

/** Prefix of every per-fetch file-channel label; routing never builds labels, the transport mints them. */
internal const val FILE_CHANNEL_PREFIX = "yapyap-file-"

/**
 * One per-fetch file channel (design §6.6): minted by the transport, never built
 * by routing. One fetch runs on one channel; concurrent fetches to one peer
 * demultiplex by handle.
 */
internal data class FileChannelId(
    val peer: PeerId,
    val label: String,
)

/** Raw fetch frame on one file channel: the channel carries the peer. */
internal data class WebRtcFileFrame(
    val channel: FileChannelId,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as WebRtcFileFrame
        if (channel != other.channel) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = channel.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * File-channel lifecycle (design §6.6): one fetch = one channel on an
 * existing peer connection, opened per fetch by either side. Carries raw fetch
 * frames instead of `BinaryEnvelope`s. Flow control is [WebRtcTransport.awaitBufferedBelow],
 * not events: a full file-frame buffer never stalls envelopes.
 */
internal sealed interface WebRtcFileChannelEvent {
    val channel: FileChannelId

    data class Adding(override val channel: FileChannelId) : WebRtcFileChannelEvent
    data class Active(override val channel: FileChannelId) : WebRtcFileChannelEvent
    data class Removed(override val channel: FileChannelId) : WebRtcFileChannelEvent
    data class Failed(
        override val channel: FileChannelId,
        val reason: String,
    ) : WebRtcFileChannelEvent
}
