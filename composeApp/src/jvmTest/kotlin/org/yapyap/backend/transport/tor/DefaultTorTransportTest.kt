package org.yapyap.backend.transport.tor

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.EnvelopeRoute
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.PeerRole
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.testutil.testPeer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class DefaultTorTransportTest {

    @Test
    fun sendsEnvelopeBetweenTwoPeers() = runBlocking {
        val network = InMemoryTorNetwork()

        val peerA = testPeer(
            account = "alice",
            device = "alice-phone",
            onion = "alice1234567890abcdef1234567890abcdef1234567890abcdef.onion",
        )
        val peerB = testPeer(
            account = "bob",
            device = "bob-pi",
            onion = "bob1234567890abcdef1234567890abcdef1234567890abcdef12.onion",
            role = PeerRole.HEADLESS_RELAY,
        )

        val backendA = InMemoryTorBackend(network, peerA.torEndpoint)
        val backendB = InMemoryTorBackend(network, peerB.torEndpoint)
        val transportA = DefaultTorTransport(backend = backendA, clockEpochSeconds = { 1_700_000_001L })
        val transportB = DefaultTorTransport(backend = backendB, clockEpochSeconds = { 1_700_000_002L })

        transportA.start(peerA)
        transportB.start(peerB)

        val envelope = BinaryEnvelope(
            packetId = PacketId.fromHex("11223344556677889900aabbccddeeff"),
            packetType = PacketType.MESSAGE,
            createdAtEpochSeconds = 1_700_000_000L,
            expiresAtEpochSeconds = 1_700_000_300L,
            hopCount = 0,
            route = EnvelopeRoute(
                destinationAccount = peerB.id.accountName,
                destinationDevice = peerB.id.deviceId,
                nextHopDevice = null,
            ),
            payload = "ciphertext".encodeToByteArray(),
        )

        val incomingDeferred = async {
            withTimeout(2.seconds) {
                transportB.incoming.first()
            }
        }
        val backendIncomingDeferred = async {
            withTimeout(2.seconds) {
                backendB.incomingFrames.first()
            }
        }

        transportA.send(peerB.torEndpoint, envelope)

        val backendIncoming = backendIncomingDeferred.await()
        val decodedFromBackend = BinaryEnvelope.decode(backendIncoming.payload)
        val incoming = incomingDeferred.await()

        assertEquals(peerA.torEndpoint, backendIncoming.source)
        assertEquals(envelope.packetId, decodedFromBackend.packetId)
        assertEquals(peerA.torEndpoint, incoming.source)
        assertEquals(envelope.packetId, incoming.envelope.packetId)
        assertEquals(PacketType.MESSAGE, incoming.envelope.packetType)
        assertContentEquals(envelope.payload, incoming.envelope.payload)

        transportA.stop()
        transportB.stop()
    }

}

private class InMemoryTorNetwork {
    private val mutex = Mutex()
    private val subscribers = mutableMapOf<TorEndpoint, MutableSharedFlow<TorIncomingFrame>>()

    suspend fun register(endpoint: TorEndpoint, inbox: MutableSharedFlow<TorIncomingFrame>) {
        mutex.withLock {
            subscribers[endpoint] = inbox
        }
    }

    suspend fun unregister(endpoint: TorEndpoint) {
        mutex.withLock {
            subscribers.remove(endpoint)
        }
    }

    suspend fun deliver(source: TorEndpoint, target: TorEndpoint, payload: ByteArray) {
        val targetInbox = mutex.withLock { subscribers[target] }
        targetInbox?.emit(TorIncomingFrame(source = source, payload = payload))
    }
}

private class InMemoryTorBackend(
    private val network: InMemoryTorNetwork,
    private val baseEndpoint: TorEndpoint,
) : TorBackend {

    private val localInbox = MutableSharedFlow<TorIncomingFrame>(replay = 1, extraBufferCapacity = 64)
    private var localEndpoint: TorEndpoint? = null

    override val incomingFrames = localInbox

    override suspend fun start(localPort: Int): TorEndpoint {
        val endpoint = baseEndpoint.copy(port = localPort)
        this.localEndpoint = endpoint
        network.register(endpoint, localInbox)
        return endpoint
    }

    override suspend fun stop() {
        localEndpoint?.let { network.unregister(it) }
        localEndpoint = null
    }

    override suspend fun send(target: TorEndpoint, payload: ByteArray) {
        val source = requireNotNull(localEndpoint) { "Backend is not started" }
        network.deliver(source = source, target = target, payload = payload)
    }
}







