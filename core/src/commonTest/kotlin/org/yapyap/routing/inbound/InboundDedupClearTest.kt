package org.yapyap.routing.inbound

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.ping.PingProvider
import org.yapyap.routing.router.FakeFrontierSnapshotProvider
import org.yapyap.routing.router.FakeSyncPayloadProvider
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RouterTransport
import org.yapyap.routing.sync.SyncHandler
import org.yapyap.sync.FakePeerAvailabilityStore
import org.yapyap.sync.buildSyncRoutingStack
import org.yapyap.sync.testDeviceIdentity
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * R2.3 inbound hardening: a handler that throws on the first delivery clears its dedup
 * entry, so the retry is handled fresh — not replayed as a duplicate — and ACKed.
 */
class InboundDedupClearTest {

    private val localDevice = PeerId("dedup-local-device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val remoteDevice = PeerId("dedup-remote-device-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val remoteAccount = AccountId("dedup-remote-account")
    private val now = epochSeconds(10_000L)

    @Test
    fun throwingHandler_clearsDedup_retryIsHandledAndAcked() = runBlocking {
        val clock = FakeClock(now)
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(remoteAccount to listOf(remoteDevice)),
            torByPeer = mutableMapOf(remoteDevice to TorEndpoint("remote.onion", 80)),
            clock = clock,
        )
        var calls = 0
        val handler = InboundEnvelopeHandler {
            calls++
            if (calls == 1) throw IllegalStateException("boom")
            InboundHandleResult.Success()
        }
        val config = MutableStateFlow(RouterConfig())
        val availability = PeerAvailabilityRegistry(clock, config, FakePeerAvailabilityStore())
        val processor = InboundEnvelopeProcessor(
            ctx = stack.ctx,
            systemSender = stack.systemSender,
            handlers = mapOf(PacketType.MESSAGE to handler),
            outboxProcessor = stack.outboxProcessor,
            syncHandler = SyncHandler(
                stack.ctx,
                stack.outboundMessenger,
                FakeSyncPayloadProvider(),
                stack.systemSender
            ),
            peerAvailabilityRegistry = availability,
            pingProvider = PingProvider(
                stack.ctx,
                config,
                MutableSharedFlow(extraBufferCapacity = 1),
                FakeFrontierSnapshotProvider(),
                stack.systemSender,
                availability,
            ),
            onSyncDeclined = { _, _ -> },
        )
        val packetId = Uuid.random()
        val inbound = BinaryEnvelope(
            packetId = packetId,
            packetType = PacketType.MESSAGE,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 1.hours,
            source = remoteDevice,
            target = localDevice,
            payload = byteArrayOf(1, 2, 3),
        )

        assertFailsWith<IllegalStateException> { processor.handle(inbound, RouterTransport.TOR) }
        processor.handle(inbound, RouterTransport.TOR)
        assertEquals(2, calls, "the retry is handled fresh, not dropped as a duplicate")

        val acks = stack.tor.sendsExcludingHeartbeat().filter { it.second.packetType == PacketType.SYSTEM }
        assertEquals(1, acks.size, "only the successful handling is ACKed")
        val ack = assertIs<SystemPayload.PacketAck>(SystemEnvelope.decode(acks.single().second.payload).decodePayload())
        assertEquals(packetId, ack.packetId)
        assertEquals(PacketType.MESSAGE, ack.packetType)
    }
}
