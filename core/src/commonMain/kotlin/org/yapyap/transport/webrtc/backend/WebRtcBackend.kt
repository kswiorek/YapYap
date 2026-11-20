package org.yapyap.transport.webrtc.backend

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.transport.webrtc.types.WebRtcAvChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcDataFrame
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcSessionEvent

internal interface WebRtcBackend {
    val outgoingSignals: Flow<WebRtcSignal>
    val incomingDataFrames: Flow<WebRtcDataFrame>
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

    /** Open a per-fetch file channel on the existing session (design §6.6, step 20). */
    suspend fun openFileChannel(target: PeerId, channelLabel: String)

    /** Close one per-fetch file channel; the session stays up. */
    suspend fun closeFileChannel(target: PeerId, channelLabel: String)

    /** Buffered-amount low threshold for one file channel (flow-control resume signal). */
    suspend fun setFileChannelBufferedThreshold(target: PeerId, channelLabel: String, thresholdBytes: Long)

    /** Current SCTP buffered amount for one file channel, or null when it has no channel. */
    fun fileChannelBufferedAmount(target: PeerId, channelLabel: String): Long?
}

