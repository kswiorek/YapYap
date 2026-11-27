package org.yapyap.routing.file.large

import kotlinx.coroutines.test.runTest
import org.yapyap.protocol.PeerId
import org.yapyap.routing.file.LargeFileServeClaim
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import org.yapyap.transport.webrtc.types.FileChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Focused unit tests for [LargeFileMux]'s serve-claim and holder-forgetting semantics. */
class LargeFileMuxTest {

    private val aliceDevice = PeerId("mux-alice-device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val bobDevice = PeerId("mux-bob-device-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    private fun mux() = LargeFileMux(RecordingWebRtcTransport())

    private fun channel(peer: PeerId, suffix: String) = FileChannelId(peer = peer, label = "yapyap-file-$suffix")

    @Test
    fun claimServe_firstClaim_claimed() = runTest {
        val mux = mux()

        assertEquals(LargeFileServeClaim.CLAIMED, mux.claimServe(channel(aliceDevice, "a"), maxPerPeer = 2))
    }

    @Test
    fun claimServe_repeatOnSameChannel_already() = runTest {
        val mux = mux()
        val fileChannel = channel(aliceDevice, "a")
        mux.claimServe(fileChannel, maxPerPeer = 2)

        assertEquals(LargeFileServeClaim.ALREADY, mux.claimServe(fileChannel, maxPerPeer = 2))
    }

    @Test
    fun claimServe_peerAtLimit_busyWhileOtherPeerClaims() = runTest {
        val mux = mux()
        mux.claimServe(channel(aliceDevice, "a"), maxPerPeer = 2)
        mux.claimServe(channel(aliceDevice, "b"), maxPerPeer = 2)

        assertEquals(LargeFileServeClaim.BUSY, mux.claimServe(channel(aliceDevice, "c"), maxPerPeer = 2))
        assertEquals(LargeFileServeClaim.CLAIMED, mux.claimServe(channel(bobDevice, "a"), maxPerPeer = 2))
    }

    @Test
    fun forgetHolder_registeredHolder_releasesClaimAndReportsTrue() = runTest {
        val mux = mux()
        val fileChannel = channel(bobDevice, "a")
        mux.registerHolder(HolderSession(fileChannel, lastProgress = 0L, lastProgressAt = 0L))
        mux.claimServe(fileChannel, maxPerPeer = 1)

        assertTrue(mux.forgetHolder(fileChannel))
        assertNull(mux.holderFor(fileChannel))
        assertEquals(LargeFileServeClaim.CLAIMED, mux.claimServe(fileChannel, maxPerPeer = 1))
    }

    @Test
    fun forgetHolder_unknownChannel_reportsFalse() = runTest {
        val mux = mux()

        assertFalse(mux.forgetHolder(channel(aliceDevice, "unknown")))
    }
}
