package org.yapyap.routing.file.large

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.protocol.FileId
import org.yapyap.routing.file.HolderSession
import org.yapyap.routing.file.LargeFileServeClaim
import org.yapyap.transport.webrtc.types.FileChannelId

/**
 * The large path's channel registry (design §6.6): the requester session of each channel we
 * opened, the holder session of each channel somebody pulled on, and the serve claims, under one
 * lock. The requester and the holder register and forget their own sessions; the mux only reads
 * it to dispatch. Requester and holder state never mix: each has its own maps.
 */
internal class LargeFileChannels {
    private val lock = Mutex()
    private val requestersByFile = mutableMapOf<FileId, RequesterSession>()
    private val requestersByChannel = mutableMapOf<FileChannelId, RequesterSession>()
    private val holders = mutableMapOf<FileChannelId, HolderSession>()

    /** Channels with a PULL being gated or served; see [claimServe]. */
    private val serveClaims = mutableSetOf<FileChannelId>()

    suspend fun count(): Int = lock.withLock { requestersByFile.size + holders.size }

    /** Registers a pull's session; false when another pull for the file is already live. */
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
}
