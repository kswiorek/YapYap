package org.yapyap.routing.file.large

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.FileId
import org.yapyap.protocol.LargeFileFrame
import org.yapyap.routing.file.LargeFileServeClaim
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import org.yapyap.transport.webrtc.types.FileChannelId
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcFileFrame
import kotlin.concurrent.Volatile

/**
 * The large path's channel mux (design §6.6): the channel→session maps, the serve claims, their
 * lock, the transport subscription, and dispatch. It decodes each frame and hands it to its owner —
 * the requester for a channel it opened, the holder for a channel somebody pulled on — and never
 * touches the wire or a file itself.
 */
internal class LargeFileMux(private val transport: WebRtcTransport) {
    private val lock = Mutex()
    private val requestersByFile = mutableMapOf<FileId, RequesterSession>()
    private val requestersByChannel = mutableMapOf<FileChannelId, RequesterSession>()
    private val holders = mutableMapOf<FileChannelId, HolderSession>()

    /** Channels with a PULL being gated or served (guarded by [lock]); see [claimServe]. */
    private val serveClaims = mutableSetOf<FileChannelId>()

    private var holder: LargeFileHolder? = null

    @Volatile
    private var scope: CoroutineScope? = null

    /** Connects the holder; the requester is reached through its registered sessions. */
    fun attach(holder: LargeFileHolder) {
        this.holder = holder
    }

    fun runIn(scope: CoroutineScope): Job {
        this.scope = scope
        return scope.launch {
            launch {
                transport.incomingFileFrames.collect { frame ->
                    try {
                        routeFrame(frame)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.error(
                            component = LogComponent.FILE,
                            event = LogEvent.FILE_TASK_FAILED,
                            message = "Failed to route fetch frame",
                            throwable = e,
                        )
                    }
                }
            }
            launch {
                transport.fileChannelEvents.collect { event ->
                    try {
                        routeEvent(event)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.error(
                            component = LogComponent.FILE,
                            event = LogEvent.FILE_TASK_FAILED,
                            message = "Failed to route fetch channel event",
                            throwable = e,
                        )
                    }
                }
            }
        }
    }

    suspend fun count(): Int = lock.withLock { requestersByFile.size + holders.size }

    /** Registers a fetch's session; false when another fetch for the file is already live. */
    suspend fun registerRequester(session: RequesterSession): Boolean = lock.withLock {
        if (requestersByFile.containsKey(session.fileId)) {
            false
        } else {
            requestersByFile[session.fileId] = session
            requestersByChannel[session.channel] = session
            true
        }
    }

    suspend fun requesterForFile(fileId: FileId): RequesterSession? = lock.withLock { requestersByFile[fileId] }

    suspend fun requesterForChannel(channel: FileChannelId): RequesterSession? =
        lock.withLock { requestersByChannel[channel] }

    suspend fun removeRequester(session: RequesterSession) {
        lock.withLock {
            if (requestersByFile[session.fileId] === session) requestersByFile.remove(session.fileId)
            requestersByChannel.remove(session.channel)
        }
    }

    suspend fun registerHolder(session: HolderSession) {
        lock.withLock { holders[session.channel] = session }
    }

    suspend fun holderFor(channel: FileChannelId): HolderSession? = lock.withLock { holders[channel] }

    /**
     * Forgets [channel]'s holder and its serve claim in one step; true when there was a holder
     * (so the caller closes the channel). Atomic, so a racing PULL is claimed fresh, never ALREADY.
     */
    suspend fun forgetHolder(channel: FileChannelId): Boolean = lock.withLock {
        serveClaims.remove(channel)
        holders.remove(channel) != null
    }

    suspend fun claimServe(channel: FileChannelId, maxPerPeer: Int): LargeFileServeClaim = lock.withLock {
        when {
            channel in serveClaims -> LargeFileServeClaim.ALREADY
            serveClaims.count { it.peer == channel.peer } >= maxPerPeer -> LargeFileServeClaim.BUSY
            else -> {
                serveClaims += channel
                LargeFileServeClaim.CLAIMED
            }
        }
    }

    suspend fun releaseServe(channel: FileChannelId) {
        lock.withLock { serveClaims.remove(channel) }
    }

    private suspend fun routeFrame(frame: WebRtcFileFrame) {
        val payload = try {
            LargeFileFrame.decode(frame.payload)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AppLog.debug(
                component = LogComponent.FILE,
                event = LogEvent.FILE_FETCH_FAILED,
                message = "Ignoring undecodable fetch frame",
            )
            return
        }
        when (payload) {
            is LargeFileFrame.Pull -> {
                // Our own requester channel never serves: a holder sending PULL back is ignored.
                if (requesterForChannel(frame.channel) != null) return
                val holder = holder ?: return
                val muxScope = checkNotNull(scope) { "LargeFileMux is not running" }
                // The gate does several lookups: off the shared frame collector, so DATA for every
                // fetch keeps flowing meanwhile (the channel claim stops a duplicate PULL).
                muxScope.launch { holder.handlePull(frame.channel, payload) }
            }

            else -> {
                requesterForChannel(frame.channel)?.let { requester ->
                    requester.onFrame(payload)
                    return
                }
                val session = holderFor(frame.channel) ?: return
                holder?.onFrame(session, payload)
            }
        }
    }

    private suspend fun routeEvent(event: WebRtcFileChannelEvent) {
        when (event) {
            is WebRtcFileChannelEvent.Adding, is WebRtcFileChannelEvent.Active -> Unit
            is WebRtcFileChannelEvent.Removed, is WebRtcFileChannelEvent.Failed -> {
                requesterForChannel(event.channel)?.onChannelGone()
                holder?.onChannelGone(event.channel)
            }
        }
    }
}
