package org.yapyap.backend.transport.webrtc

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.PeerRole
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.transport.tor.TorInboundEnvelope
import org.yapyap.backend.transport.tor.TorTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DefaultWebRtcTransportTest {

    @Test
    fun incomingOfferRequiresAcceptanceBeforeBackendApply() = runBlocking {
        val network = InMemoryTorTransportNetwork()

        val peerA = testPeer("alice", "alice-phone", "alice1234567890abcdef1234567890abcdef1234567890abcdef.onion")
        val peerB = testPeer("bob", "bob-pi", "bob1234567890abcdef1234567890abcdef1234567890abcdef12.onion")

        val torA = InMemoryTorTransport(network, peerA.torEndpoint)
        val torB = InMemoryTorTransport(network, peerB.torEndpoint)
        val backendA = FakeWebRtcBackend()
        val backendB = FakeWebRtcBackend()

        val transportA = TorRoutedWebRtcTransport(
            delegate = DefaultWebRtcTransport(
                backend = backendA,
                packetIdGenerator = { PacketId.fromHex("01010101010101010101010101010101") },
            ),
            torTransport = torA,
            protection = PlaintextWebRtcSignalProtection(),
            protectionContext = WebRtcSignalProtectionContext(
                nowEpochSeconds = { 1_700_000_001L },
                nonceGenerator = { byteArrayOf(1, 2, 3, 4) },
                resolveTorEndpoint = { peer -> if (peer == peerA.id) peerA.torEndpoint else peerB.torEndpoint },
            ),
            packetIdGenerator = { PacketId.fromHex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") },
        )
        val transportB = TorRoutedWebRtcTransport(
            delegate = DefaultWebRtcTransport(
                backend = backendB,
                packetIdGenerator = { PacketId.fromHex("02020202020202020202020202020202") },
            ),
            torTransport = torB,
            protection = PlaintextWebRtcSignalProtection(),
            protectionContext = WebRtcSignalProtectionContext(
                nowEpochSeconds = { 1_700_000_002L },
                nonceGenerator = { byteArrayOf(9, 8, 7, 6) },
                resolveTorEndpoint = { peer -> if (peer == peerA.id) peerA.torEndpoint else peerB.torEndpoint },
            ),
            packetIdGenerator = { PacketId.fromHex("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb") },
        )
        transportA.start(peerA)
        transportB.start(peerB)

        val sessionId = "session-offer-1"
        val pendingRequestDeferred = async {
            withTimeout(2.seconds) { transportB.incomingSessionRequests.first() }
        }

        backendA.emitOutgoingSignal(
            WebRtcSignal(
                sessionId = sessionId,
                kind = WebRtcSignalKind.OFFER,
                source = peerA.id,
                target = peerB.id,
                payload = "offer".encodeToByteArray(),
            )
        )

        val request = pendingRequestDeferred.await()
        assertEquals(sessionId, request.sessionId)
        assertEquals(peerA.id, request.source)
        assertTrue(backendB.handledSignals.isEmpty())

        transportB.acceptSession(sessionId)
        assertEquals(1, backendB.handledSignals.size)
        assertEquals(WebRtcSignalKind.OFFER, backendB.handledSignals.single().kind)

        transportA.stop()
        transportB.stop()
    }

    @Test
    fun rejectingOfferSendsRejectSignalBack() = runBlocking {
        val network = InMemoryTorTransportNetwork()

        val peerA = testPeer("alice", "alice-phone", "alice1234567890abcdef1234567890abcdef1234567890abcdef.onion")
        val peerB = testPeer("bob", "bob-pi", "bob1234567890abcdef1234567890abcdef1234567890abcdef12.onion")

        val torA = InMemoryTorTransport(network, peerA.torEndpoint)
        val torB = InMemoryTorTransport(network, peerB.torEndpoint)
        val backendA = FakeWebRtcBackend()
        val backendB = FakeWebRtcBackend()

        val transportA = TorRoutedWebRtcTransport(
            delegate = DefaultWebRtcTransport(
                backend = backendA,
                packetIdGenerator = { PacketId.fromHex("03030303030303030303030303030303") },
            ),
            torTransport = torA,
            protection = PlaintextWebRtcSignalProtection(),
            protectionContext = WebRtcSignalProtectionContext(
                nowEpochSeconds = { 1_700_000_001L },
                nonceGenerator = { byteArrayOf(1, 2, 3, 4) },
                resolveTorEndpoint = { peer -> if (peer == peerA.id) peerA.torEndpoint else peerB.torEndpoint },
            ),
            packetIdGenerator = { PacketId.fromHex("cccccccccccccccccccccccccccccccc") },
        )
        val transportB = TorRoutedWebRtcTransport(
            delegate = DefaultWebRtcTransport(
                backend = backendB,
                packetIdGenerator = { PacketId.fromHex("04040404040404040404040404040404") },
            ),
            torTransport = torB,
            protection = PlaintextWebRtcSignalProtection(),
            protectionContext = WebRtcSignalProtectionContext(
                nowEpochSeconds = { 1_700_000_002L },
                nonceGenerator = { byteArrayOf(9, 8, 7, 6) },
                resolveTorEndpoint = { peer -> if (peer == peerA.id) peerA.torEndpoint else peerB.torEndpoint },
            ),
            packetIdGenerator = { PacketId.fromHex("dddddddddddddddddddddddddddddddd") },
        )

        transportA.start(peerA)
        transportB.start(peerB)

        val sessionId = "session-offer-2"
        val rejectedStateDeferred = async {
            withTimeout(2.seconds) {
                transportB.sessionStates.first { it.sessionId == sessionId && it.phase == WebRtcSessionPhase.REJECTED }
            }
        }

        backendA.emitOutgoingSignal(
            WebRtcSignal(
                sessionId = sessionId,
                kind = WebRtcSignalKind.OFFER,
                source = peerA.id,
                target = peerB.id,
                payload = "offer".encodeToByteArray(),
            )
        )

        withTimeout(2.seconds) {
            transportB.incomingSessionRequests.first { it.sessionId == sessionId }
        }
        transportB.rejectSession(sessionId, "busy")

        val rejected = rejectedStateDeferred.await()
        assertEquals("busy", rejected.reason)
        assertTrue(backendB.handledSignals.isEmpty())

        transportA.stop()
        transportB.stop()
    }

    private fun testPeer(account: String, device: String, onion: String): PeerDescriptor {
        return PeerDescriptor(
            id = PeerId(accountName = account, deviceId = device),
            torEndpoint = TorEndpoint(onionAddress = onion),
            role = PeerRole.USER_DEVICE,
            announcedAtEpochSeconds = 1_700_000_000L,
        )
    }
}

private class FakeWebRtcBackend : WebRtcBackend {
    private val outgoingFlow = MutableSharedFlow<WebRtcSignal>(extraBufferCapacity = 64)
    private val incomingDataFlow = MutableSharedFlow<WebRtcIncomingDataFrame>(extraBufferCapacity = 64)
    private val sessionEventFlow = MutableSharedFlow<WebRtcSessionEvent>(extraBufferCapacity = 64)

    override val outgoingSignals: Flow<WebRtcSignal> = outgoingFlow.asSharedFlow()
    override val incomingDataFrames: Flow<WebRtcIncomingDataFrame> = incomingDataFlow.asSharedFlow()
    override val sessionEvents: Flow<WebRtcSessionEvent> = sessionEventFlow.asSharedFlow()

    val handledSignals = mutableListOf<WebRtcSignal>()

    override suspend fun start(localPeer: PeerDescriptor) = Unit

    override suspend fun stop() = Unit

    override suspend fun openSession(target: PeerId, sessionId: String) = Unit

    override suspend fun handleRemoteSignal(signal: WebRtcSignal) {
        handledSignals += signal
    }

    override suspend fun closeSession(sessionId: String) = Unit

    override suspend fun sendData(sessionId: String, target: PeerId, payload: ByteArray) = Unit

    suspend fun emitOutgoingSignal(signal: WebRtcSignal) {
        outgoingFlow.emit(signal)
    }
}

private class InMemoryTorTransportNetwork {
    private val mutex = Mutex()
    private val subscribers = mutableMapOf<TorEndpoint, MutableSharedFlow<TorInboundEnvelope>>()

    suspend fun register(endpoint: TorEndpoint, inbox: MutableSharedFlow<TorInboundEnvelope>) {
        mutex.withLock { subscribers[endpoint] = inbox }
    }

    suspend fun unregister(endpoint: TorEndpoint) {
        mutex.withLock { subscribers.remove(endpoint) }
    }

    suspend fun deliver(source: TorEndpoint, target: TorEndpoint, envelope: BinaryEnvelope) {
        val inbox = mutex.withLock { subscribers[target] }
        inbox?.emit(
            TorInboundEnvelope(
                envelope = envelope,
                source = source,
                receivedAtEpochSeconds = 1_700_000_111L,
            )
        )
    }
}

private class InMemoryTorTransport(
    private val network: InMemoryTorTransportNetwork,
    private val endpoint: TorEndpoint,
) : TorTransport {

    private val incomingFlow = MutableSharedFlow<TorInboundEnvelope>(replay = 1, extraBufferCapacity = 64)
    override val incoming: Flow<TorInboundEnvelope> = incomingFlow.asSharedFlow()

    private var started = false

    override suspend fun start(localPeer: PeerDescriptor) {
        started = true
        network.register(endpoint, incomingFlow)
    }

    override suspend fun stop() {
        started = false
        network.unregister(endpoint)
    }

    override suspend fun send(target: TorEndpoint, envelope: BinaryEnvelope) {
        check(started) { "Tor transport is not started" }
        network.deliver(source = endpoint, target = target, envelope = envelope)
    }
}


