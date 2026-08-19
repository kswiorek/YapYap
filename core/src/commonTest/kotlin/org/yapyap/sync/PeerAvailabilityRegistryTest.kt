package org.yapyap.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.time.FixedEpochProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerAvailabilityRegistryTest {

    private fun peer(suffix: String) = PeerId("avail-$suffix")

    @Test
    fun unknownPeer_isOffline() {
        val registry = PeerAvailabilityRegistry(FixedEpochProvider(1_000L))
        assertFalse(registry.isOnline(peer("a")))
    }

    @Test
    fun markReachable_marksPeerOnlineAndAddsToOnlineDevices() = runTest {
        val registry = PeerAvailabilityRegistry(FixedEpochProvider(1_000L))
        registry.markReachable(peer("a"), 1_000L)

        assertTrue(registry.isOnline(peer("a")))
        assertEquals(setOf(peer("a")), registry.onlineDevices.first())
    }

    @Test
    fun isOnline_expiresAfterThreshold() {
        val time = FixedEpochProvider(1_000L)
        val registry = PeerAvailabilityRegistry(time, onlineThresholdSeconds = 60)
        registry.markReachable(peer("a"), 1_000L)
        assertTrue(registry.isOnline(peer("a")))

        time.advanceTo(1_060L)
        assertFalse(registry.isOnline(peer("a")))
    }

    @Test
    fun onlineEvents_emitsOncePerOfflineToOnlineTransition() = runTest {
        val registry = PeerAvailabilityRegistry(FixedEpochProvider(1_000L))
        val events = mutableListOf<PeerId>()
        val job = launch { registry.onlineEvents.collect { events.add(it) } }
        testScheduler.runCurrent()

        registry.markReachable(peer("a"), 1_000L)
        registry.markReachable(peer("a"), 1_010L)
        testScheduler.runCurrent()

        assertEquals(listOf(peer("a")), events)
        job.cancel()
    }

    @Test
    fun onlineEvents_emitsForDistinctNewPeers() = runTest {
        val registry = PeerAvailabilityRegistry(FixedEpochProvider(1_000L))
        val events = mutableListOf<PeerId>()
        val job = launch { registry.onlineEvents.collect { events.add(it) } }
        testScheduler.runCurrent()

        registry.markReachable(peer("a"), 1_000L)
        registry.markReachable(peer("b"), 1_000L)
        testScheduler.runCurrent()

        assertEquals(setOf(peer("a"), peer("b")), events.toSet())
        job.cancel()
    }
}
