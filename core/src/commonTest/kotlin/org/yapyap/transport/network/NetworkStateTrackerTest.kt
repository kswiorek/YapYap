package org.yapyap.transport.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class NetworkStateTrackerTest {

    private val home = setOf("wlan0/192.168.1.20")
    private val office = setOf("wlan0/10.0.4.7")
    private val poll = 2.seconds

    private fun tracker() = NetworkStateTracker(settlePolls = 2, wakeGap = 30.seconds)

    @Test
    fun firstPoll_isInitialState() {
        val t = tracker()
        assertEquals(NetworkState(online = true, generation = 0, change = null), t.onPoll(home, null))
        assertEquals(NetworkState(online = false, generation = 0, change = null), tracker().onPoll(emptySet(), null))
    }

    @Test
    fun unchangedKeys_emitNothing() {
        val t = tracker()
        t.onPoll(home, null)
        assertNull(t.onPoll(home, poll))
        assertNull(t.onPoll(home, poll))
    }

    @Test
    fun switch_needsSettlePolls_thenBumpsGeneration() {
        val t = tracker()
        t.onPoll(home, null)
        assertNull(t.onPoll(office, poll))
        assertEquals(
            NetworkState(online = true, generation = 1, change = NetworkChange.SWITCHED),
            t.onPoll(office, poll)
        )
        assertNull(t.onPoll(office, poll))
    }

    @Test
    fun transientBlip_backToCommitted_isIgnored() {
        val t = tracker()
        t.onPoll(home, null)
        assertNull(t.onPoll(emptySet(), poll))
        assertNull(t.onPoll(home, poll))
        assertNull(t.onPoll(home, poll))
        assertEquals(0, t.state?.generation)
    }

    @Test
    fun intermediateStates_restartSettling() {
        val t = tracker()
        t.onPoll(home, null)
        assertNull(t.onPoll(emptySet(), poll))
        assertNull(t.onPoll(office, poll))
        assertEquals(NetworkChange.SWITCHED, t.onPoll(office, poll)?.change)
    }

    @Test
    fun offlineThenOnline() {
        val t = tracker()
        t.onPoll(home, null)
        t.onPoll(emptySet(), poll)
        assertEquals(
            NetworkState(online = false, generation = 1, change = NetworkChange.OFFLINE),
            t.onPoll(emptySet(), poll)
        )
        t.onPoll(office, poll)
        assertEquals(NetworkState(online = true, generation = 2, change = NetworkChange.ONLINE), t.onPoll(office, poll))
    }

    @Test
    fun wallClockGap_isWakeEvenOnSameNetwork() {
        val t = tracker()
        t.onPoll(home, null)
        assertEquals(
            NetworkState(online = true, generation = 1, change = NetworkChange.WOKE),
            t.onPoll(home, 300.seconds)
        )
    }

    @Test
    fun wake_onDifferentNetwork_commitsImmediately() {
        val t = tracker()
        t.onPoll(home, null)
        assertEquals(NetworkChange.WOKE, t.onPoll(office, 300.seconds)?.change)
        assertNull(t.onPoll(office, poll))
    }
}
