package org.yapyap.transport.webrtc

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.PeerId
import org.yapyap.transport.webrtc.transport.DefaultWebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcSessionEvent
import org.yapyap.transport.webrtc.types.WebRtcSessionPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Backend events land in a per-peer last-known-state map: one peer's transitions never
 * clobber another's, terminal states are kept, and a late subscriber replays the
 * accurate current state rather than a stale global slot.
 */
class DefaultWebRtcTransportTest {

    private val self = PeerId("transport-self-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val peerA = PeerId("transport-peer-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val peerB = PeerId("transport-peer-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    @Test
    fun sessionEvents_mapToPerPeerStates() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            assertTrue(transport.sessionStates.value.isEmpty())

            assertTrue(backend.tryEmitSessionEvent(WebRtcSessionEvent.Connecting(peerA)))
            val negotiating = withTimeout(5.seconds) {
                transport.sessionStates.first { it[peerA]?.phase == WebRtcSessionPhase.NEGOTIATING }
            }
            assertEquals(WebRtcSessionPhase.NEGOTIATING, negotiating[peerA]?.phase)

            assertTrue(backend.tryEmitSessionEvent(WebRtcSessionEvent.Connected(peerA)))
            val connected = withTimeout(5.seconds) {
                transport.sessionStates.first { it[peerA]?.phase == WebRtcSessionPhase.CONNECTED }
            }
            assertEquals(WebRtcSessionPhase.CONNECTED, connected[peerA]?.phase)
        } finally {
            transport.stop()
        }
    }

    @Test
    fun peerBEvents_doNotClobberPeerAState() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            assertTrue(backend.tryEmitSessionEvent(WebRtcSessionEvent.Connected(peerA)))
            withTimeout(5.seconds) {
                transport.sessionStates.first { it[peerA]?.phase == WebRtcSessionPhase.CONNECTED }
            }

            assertTrue(backend.tryEmitSessionEvent(WebRtcSessionEvent.Failed(peerB, "ice boom")))
            withTimeout(5.seconds) {
                transport.sessionStates.first { it[peerB]?.phase == WebRtcSessionPhase.FAILED }
            }

            val states = transport.sessionStates.value
            assertEquals(WebRtcSessionPhase.CONNECTED, states[peerA]?.phase)
            assertEquals(WebRtcSessionPhase.FAILED, states[peerB]?.phase)
            assertEquals("ice boom", states[peerB]?.reason)
        } finally {
            transport.stop()
        }
    }

    @Test
    fun rejected_mapsToRejectedWithReason() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            assertTrue(backend.tryEmitSessionEvent(WebRtcSessionEvent.Rejected(peerA, "busy")))
            withTimeout(5.seconds) {
                transport.sessionStates.first { it[peerA]?.phase == WebRtcSessionPhase.REJECTED }
            }
            assertEquals("busy", transport.sessionStates.value[peerA]?.reason)
        } finally {
            transport.stop()
        }
    }

    @Test
    fun closed_isStoredAsTerminalState() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            assertTrue(backend.tryEmitSessionEvent(WebRtcSessionEvent.Connected(peerA)))
            val connected = withTimeout(5.seconds) {
                transport.sessionStates.first { it[peerA]?.phase == WebRtcSessionPhase.CONNECTED }
            }
            assertEquals(WebRtcSessionPhase.CONNECTED, connected[peerA]?.phase)

            assertTrue(backend.tryEmitSessionEvent(WebRtcSessionEvent.Closed(peerA)))
            val closed = withTimeout(5.seconds) {
                transport.sessionStates.first { it[peerA]?.phase == WebRtcSessionPhase.CLOSED }
            }
            assertEquals(WebRtcSessionPhase.CLOSED, closed[peerA]?.phase)
        } finally {
            transport.stop()
        }
    }

    @Test
    fun lateSubscriber_replaysAccurateCurrentState() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            assertTrue(backend.tryEmitSessionEvent(WebRtcSessionEvent.Connected(peerA)))
            withTimeout(5.seconds) {
                transport.sessionStates.first { it[peerA]?.phase == WebRtcSessionPhase.CONNECTED }
            }

            // A brand-new collection with no fresh emission in flight must still see it:
            // replay is ground truth, not a stale hazard.
            val replayed = withTimeout(5.seconds) {
                transport.sessionStatesOf(peerA).first { it.phase == WebRtcSessionPhase.CONNECTED }
            }
            assertEquals(WebRtcSessionPhase.CONNECTED, replayed.phase)
        } finally {
            transport.stop()
        }
    }

    @Test
    fun sessionStatesOf_waitsForFreshTransition() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            assertNull(transport.sessionStates.value[peerB])

            val waiter = async {
                withTimeout(5.seconds) {
                    transport.sessionStatesOf(peerB).first { it.phase == WebRtcSessionPhase.CONNECTED }
                }
            }
            assertTrue(backend.tryEmitSessionEvent(WebRtcSessionEvent.Connected(peerB)))
            assertEquals(WebRtcSessionPhase.CONNECTED, waiter.await().phase)
        } finally {
            transport.stop()
        }
    }

    @Test
    fun stop_clearsSessionStates() = runBlocking {
        val backend = RecordingWebRtcBackend()
        val transport = DefaultWebRtcTransport(backend)
        transport.start(self)
        try {
            assertTrue(backend.tryEmitSessionEvent(WebRtcSessionEvent.Connected(peerA)))
            withTimeout(5.seconds) {
                transport.sessionStates.first { it[peerA]?.phase == WebRtcSessionPhase.CONNECTED }
            }
        } finally {
            transport.stop()
        }
        assertTrue(transport.sessionStates.value.isEmpty())
    }
}
