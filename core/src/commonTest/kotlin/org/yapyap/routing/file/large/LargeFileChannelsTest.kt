package org.yapyap.routing.file.large

import kotlinx.coroutines.test.runTest
import org.yapyap.protocol.PeerId
import org.yapyap.routing.file.HolderSession
import org.yapyap.routing.file.LargeFileServeClaim
import org.yapyap.transport.webrtc.types.FileChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Focused unit tests for [LargeFileChannels]' serve-claim and holder-forgetting semantics. */
class LargeFileChannelsTest {

    private val aliceDevice = PeerId("mux-alice-device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val bobDevice = PeerId("mux-bob-device-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    private fun channels() = LargeFileChannels()

    private fun channel(peer: PeerId, suffix: String) = FileChannelId(peer = peer, label = "yapyap-file-$suffix")

    @Test
    fun claimServe_firstClaim_claimed() = runTest {
        val channels = channels()

        assertEquals(LargeFileServeClaim.CLAIMED, channels.claimServe(channel(aliceDevice, "a"), maxPerPeer = 2))
    }

    @Test
    fun claimServe_repeatOnSameChannel_already() = runTest {
        val channels = channels()
        val fileChannel = channel(aliceDevice, "a")
        channels.claimServe(fileChannel, maxPerPeer = 2)

        assertEquals(LargeFileServeClaim.ALREADY, channels.claimServe(fileChannel, maxPerPeer = 2))
    }

    @Test
    fun claimServe_peerAtLimit_busyWhileOtherPeerClaims() = runTest {
        val channels = channels()
        channels.claimServe(channel(aliceDevice, "a"), maxPerPeer = 2)
        channels.claimServe(channel(aliceDevice, "b"), maxPerPeer = 2)

        assertEquals(LargeFileServeClaim.BUSY, channels.claimServe(channel(aliceDevice, "c"), maxPerPeer = 2))
        assertEquals(LargeFileServeClaim.CLAIMED, channels.claimServe(channel(bobDevice, "a"), maxPerPeer = 2))
    }

    @Test
    fun forgetHolder_registeredHolder_releasesClaimAndReportsTrue() = runTest {
        val channels = channels()
        val fileChannel = channel(bobDevice, "a")
        channels.registerHolder(
            HolderSession(
                fileChannel,
                lastProgress = 0L,
                lastProgressAt = Instant.fromEpochMilliseconds(0)
            )
        )
        channels.claimServe(fileChannel, maxPerPeer = 1)

        assertTrue(channels.forgetHolder(fileChannel))
        assertNull(channels.holderFor(fileChannel))
        assertEquals(LargeFileServeClaim.CLAIMED, channels.claimServe(fileChannel, maxPerPeer = 1))
    }

    @Test
    fun forgetHolder_unknownChannel_reportsFalse() = runTest {
        val channels = channels()

        assertFalse(channels.forgetHolder(channel(aliceDevice, "unknown")))
    }
}
