package org.yapyap.transport.webrtc.backend

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.transport.webrtc.types.FileChannelId
import org.yapyap.transport.webrtc.types.WebRtcAvChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcDataFrame
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcFileFrame
import org.yapyap.transport.webrtc.types.WebRtcSessionEvent

internal interface WebRtcBackend {
    val outgoingSignals: Flow<WebRtcSignal>

    /** Envelope and AV frames (never file bytes): stalling file flow never blocks these. */
    val incomingDataFrames: Flow<WebRtcDataFrame>

    /** Raw fetch frames on per-fetch file channels (design §6.6): its own flow and buffer. */
    val incomingFileFrames: Flow<WebRtcFileFrame>
    val sessionEvents: Flow<WebRtcSessionEvent>
    val avChannelEvents: Flow<WebRtcAvChannelEvent>
    val fileChannelEvents: Flow<WebRtcFileChannelEvent>

    suspend fun start(localDevice: PeerId)

    suspend fun stop()

    suspend fun openSession(target: PeerId)

    suspend fun handleRemoteSignal(signal: WebRtcSignal)

    /**
     * True iff the envelope data channel to [target] is open and can carry data immediately.
     * False while the session is still negotiating or after it failed/closed.
     */
    fun hasSession(target: PeerId): Boolean

    suspend fun closeSession(target: PeerId)

    suspend fun sendData(dataFrame: WebRtcDataFrame)

    suspend fun addAvChannel(target: PeerId)
    suspend fun removeAvChannel(target: PeerId)

    /**
     * Open a per-fetch file channel on the existing session (design §6.6). The backend
     * mints the handle; routing never builds label strings. File channels are always
     * reliable (ordered, no retransmit or lifetime limit), whatever the shared config says.
     */
    suspend fun openFileChannel(target: PeerId): FileChannelId

    /** Close one per-fetch file channel; the session stays up. */
    suspend fun closeFileChannel(channel: FileChannelId)

    /** Send one fetch frame on [channel]'s data channel. */
    suspend fun sendFileFrame(channel: FileChannelId, payload: ByteArray)

    /**
     * Suspend until [channel]'s SCTP buffered amount drops at or below [thresholdBytes].
     * Backed by the channel's low-threshold callback; a closed channel returns at once.
     */
    suspend fun awaitBufferedBelow(channel: FileChannelId, thresholdBytes: Long)
}

