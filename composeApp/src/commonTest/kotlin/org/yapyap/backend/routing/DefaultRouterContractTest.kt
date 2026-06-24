package org.yapyap.backend.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.yapyap.backend.crypto.AccountId
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityPublicKeyRecord
import org.yapyap.backend.db.MessageLifecycleState
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.MessageEnvelope
import org.yapyap.backend.protocol.MessagePayload
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PacketNackReason
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.protocol.SystemEnvelope
import org.yapyap.backend.protocol.SystemPayload
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.time.FixedEpochSecondsProvider
import org.yapyap.backend.transport.RecordingTorTransport
import org.yapyap.backend.transport.RecordingWebRtcTransport
import org.yapyap.backend.transport.tor.TorIncomingEnvelope

class DefaultRouterContractTest {

    private val localPeer =
        PeerId("localrouteraaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val remotePeer =
        PeerId("remoterouterbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    private fun localDevice(): DeviceIdentityRecord =
        DeviceIdentityRecord(
            deviceId = localPeer,
            signing = IdentityPublicKeyRecord("ls", 0L, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
            encryption = IdentityPublicKeyRecord("le", 0L, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
        )

    @Test
    fun start_startsTransports_updatesTorEndpoint_stop_clearsRunning() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 4444))
        val web = RecordingWebRtcTransport()
        val identity = FakeIdentityResolverForRouter(localDevice = localDevice())
        val router = defaultRouterUnderTest(tor = tor, webRtc = web, identity = identity)

        router.start()

        assertTrue(router.isRunning())
        assertEquals(1, tor.startCalls)
        assertEquals(1, web.startCalls.size)
        assertEquals(localPeer, web.startCalls.single())
        assertTrue(identity.torUpdates.any { it.first == localPeer })

        router.stop()
        assertTrue(!router.isRunning())
        assertEquals(1, tor.stopCalls)
        assertEquals(1, web.stopCalls.size)
    }

    @Test
    fun start_throwsWhenAlreadyStarted() = runBlocking {
        val router =
            defaultRouterUnderTest(
                identity = FakeIdentityResolverForRouter(localDevice = localDevice()),
            )
        router.start()
        assertFailsWith<IllegalStateException> {
            router.start()
        }
        router.stop()
    }

    @Test
    fun sendMessage_withNoPeers_doesNotSendTor() = runBlocking {
        val tor = RecordingTorTransport()
        val targetAccount = AccountId("empty-account")
        val identity =
            FakeIdentityResolverForRouter(
                localDevice = localDevice(),
                peersByAccount = mapOf(targetAccount to emptyList()),
            )
        val router = defaultRouterUnderTest(tor = tor, identity = identity)
        router.start()

        val payload = sampleTextPayload("no-peer-msg")
        router.sendMessage(targetAccount, payload, RouterTransport.TOR)

        assertTrue(tor.sends.isEmpty())
        router.stop()
    }

    @Test
    fun sendMessage_tor_forcesTorSendWithExpectedEndpoint() = runBlocking {
        val tor = RecordingTorTransport()
        val account = AccountId("acc-with-peer")
        val peerTor = TorEndpoint("peer.onion", 443)
        val torMap = mutableMapOf(remotePeer to peerTor)
        val identity =
            FakeIdentityResolverForRouter(
                localDevice = localDevice(),
                peersByAccount = mapOf(account to listOf(remotePeer)),
                torByPeer = torMap,
            )
        val router = defaultRouterUnderTest(tor = tor, identity = identity)
        router.start()

        router.sendMessage(account, sampleTextPayload("tor-send"), RouterTransport.TOR)

        assertEquals(1, tor.sends.size)
        assertEquals(peerTor, tor.sends[0].first)
        assertEquals(PacketType.MESSAGE, tor.sends[0].second.packetType)
        router.stop()
    }

    @Test
    fun inboundMessage_firstReceive_sendsSystemAck() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val remoteTor = TorEndpoint("remote.onion", 80)
        val router = routerForInboundTests(tor, remoteTor)

        router.start()

        val packetId = PacketId.fromHex("aa".repeat(PacketId.SIZE_BYTES))
        val incoming = inboundTorMessage(packetId = packetId, remoteTor = remoteTor)

        tor.tryEmitIncoming(incoming)
        delay(400)

        assertEquals(1, tor.sends.size)
        assertSystemAck(
            envelope = tor.sends.single().second,
            expectedPacketId = packetId,
            expectedPacketType = PacketType.MESSAGE,
        )

        router.stop()
    }

    @Test
    fun duplicateInboundPacket_invokesDedupTwice_secondSeenAsDuplicate_andReSendsAck() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val remoteTor = TorEndpoint("remote.onion", 80)
        val innerDedup = InMemoryPacketDeduplicator()
        val recordingDedup = RecordingPacketDeduplicator(innerDedup)
        val router =
            routerForInboundTests(
                tor = tor,
                remoteTor = remoteTor,
                dedup = recordingDedup,
            )

        router.start()

        val packetId = PacketId.fromHex("aa".repeat(PacketId.SIZE_BYTES))
        val incoming = inboundTorMessage(packetId = packetId, remoteTor = remoteTor)

        tor.tryEmitIncoming(incoming)
        delay(400)
        tor.tryEmitIncoming(incoming)
        delay(400)

        assertEquals(2, recordingDedup.firstSeenCalls.size)
        assertEquals(packetId, recordingDedup.firstSeenCalls[0].first)
        assertEquals(packetId, recordingDedup.firstSeenCalls[1].first)
        assertTrue(recordingDedup.firstSeenResults[0])
        assertTrue(!recordingDedup.firstSeenResults[1])
        assertTrue(recordingDedup.markNackedCalls.isEmpty())

        assertEquals(2, tor.sends.size)
        assertSystemAck(tor.sends[0].second, packetId, PacketType.MESSAGE)
        assertSystemAck(tor.sends[1].second, packetId, PacketType.MESSAGE)

        router.stop()
    }

    @Test
    fun expiredInboundPacket_sendsNack_andDuplicateReSendsNack() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val remoteTor = TorEndpoint("remote.onion", 80)
        val recordingDedup = RecordingPacketDeduplicator(InMemoryPacketDeduplicator())
        val router =
            routerForInboundTests(
                tor = tor,
                remoteTor = remoteTor,
                dedup = recordingDedup,
                time = FixedEpochSecondsProvider(10_001L),
            )

        router.start()

        val packetId = PacketId.fromHex("bb".repeat(PacketId.SIZE_BYTES))
        val incoming =
            inboundTorMessage(
                packetId = packetId,
                remoteTor = remoteTor,
                expiresAtEpochSeconds = 10_000L,
            )

        tor.tryEmitIncoming(incoming)
        delay(400)
        tor.tryEmitIncoming(incoming)
        delay(400)

        assertEquals(1, recordingDedup.markNackedCalls.size)
        assertEquals(PacketNackReason.EXPIRED, recordingDedup.markNackedCalls.single().third)

        assertEquals(2, tor.sends.size)
        assertSystemNack(tor.sends[0].second, packetId, PacketNackReason.EXPIRED)
        assertSystemNack(tor.sends[1].second, packetId, PacketNackReason.EXPIRED)

        router.stop()
    }

    @Test
    fun wrongTargetInbound_sendsNack_andDuplicateReSendsNack() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val remoteTor = TorEndpoint("remote.onion", 80)
        val recordingDedup = RecordingPacketDeduplicator(InMemoryPacketDeduplicator())
        val router =
            routerForInboundTests(
                tor = tor,
                remoteTor = remoteTor,
                dedup = recordingDedup,
            )

        router.start()

        val packetId = PacketId.fromHex("cc".repeat(PacketId.SIZE_BYTES))
        val incoming =
            inboundTorMessage(
                packetId = packetId,
                remoteTor = remoteTor,
                target = PeerId("wrongtargetcccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
            )

        tor.tryEmitIncoming(incoming)
        delay(400)
        tor.tryEmitIncoming(incoming)
        delay(400)

        assertEquals(1, recordingDedup.markNackedCalls.size)
        assertEquals(PacketNackReason.WRONG_TARGET, recordingDedup.markNackedCalls.single().third)

        assertEquals(2, tor.sends.size)
        assertSystemNack(tor.sends[0].second, packetId, PacketNackReason.WRONG_TARGET)
        assertSystemNack(tor.sends[1].second, packetId, PacketNackReason.WRONG_TARGET)

        router.stop()
    }

    @Test
    fun decodeFailureInbound_sendsNack_andDuplicateReSendsNack() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val remoteTor = TorEndpoint("remote.onion", 80)
        val recordingDedup = RecordingPacketDeduplicator(InMemoryPacketDeduplicator())
        val router =
            routerForInboundTests(
                tor = tor,
                remoteTor = remoteTor,
                dedup = recordingDedup,
            )

        router.start()

        val packetId = PacketId.fromHex("dd".repeat(PacketId.SIZE_BYTES))
        val incoming =
            TorIncomingEnvelope(
                remoteTor,
                BinaryEnvelope(
                    packetId = packetId,
                    packetType = PacketType.MESSAGE,
                    createdAtEpochSeconds = 10_000L,
                    expiresAtEpochSeconds = 11_000L,
                    source = remotePeer,
                    target = localPeer,
                    payload = byteArrayOf(0x00, 0x01, 0x02),
                ),
            )

        tor.tryEmitIncoming(incoming)
        delay(400)
        tor.tryEmitIncoming(incoming)
        delay(400)

        assertEquals(1, recordingDedup.markNackedCalls.size)
        assertEquals(PacketNackReason.DECODE_FAILED, recordingDedup.markNackedCalls.single().third)

        assertEquals(2, tor.sends.size)
        assertSystemNack(tor.sends[0].second, packetId, PacketNackReason.DECODE_FAILED)
        assertSystemNack(tor.sends[1].second, packetId, PacketNackReason.DECODE_FAILED)

        router.stop()
    }

    private fun routerForInboundTests(
        tor: RecordingTorTransport,
        remoteTor: TorEndpoint,
        dedup: org.yapyap.backend.db.PacketDeduplicator = InMemoryPacketDeduplicator(),
        time: FixedEpochSecondsProvider = FixedEpochSecondsProvider(10_000L),
    ): DefaultRouter {
        val torMap = mutableMapOf(remotePeer to remoteTor)
        val identity =
            FakeIdentityResolverForRouter(
                localDevice = localDevice(),
                torByPeer = torMap,
            )
        return defaultRouterUnderTest(
            tor = tor,
            identity = identity,
            dedup = dedup,
            time = time,
        )
    }

    private fun inboundTorMessage(
        packetId: PacketId,
        remoteTor: TorEndpoint,
        text: MessagePayload.Text = sampleTextPayload("dedup-msg"),
        expiresAtEpochSeconds: Long = 11_000L,
        target: PeerId = localPeer,
    ): TorIncomingEnvelope {
        val msgEnv =
            MessageEnvelope(
                messageId = text.messageId,
                source = remotePeer,
                target = target,
                createdAtEpochSeconds = 10_000L,
                nonce = ByteArray(24) { 3 },
                securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
                signature = null,
                payload = text.encode(),
            )
        val bin =
            BinaryEnvelope(
                packetId = packetId,
                packetType = PacketType.MESSAGE,
                createdAtEpochSeconds = 10_000L,
                expiresAtEpochSeconds = expiresAtEpochSeconds,
                source = remotePeer,
                target = target,
                payload = msgEnv.encode(),
            )
        return TorIncomingEnvelope(remoteTor, bin)
    }

    private fun assertSystemAck(
        envelope: BinaryEnvelope,
        expectedPacketId: PacketId,
        expectedPacketType: PacketType,
    ) {
        assertEquals(PacketType.SYSTEM, envelope.packetType)
        val payload = SystemEnvelope.decode(envelope.payload).decodePayload()
        assertTrue(payload is SystemPayload.PacketAck, "expected PacketAck but was ${payload::class.simpleName}")
        assertEquals(expectedPacketId, payload.packetId)
        assertEquals(expectedPacketType, payload.packetType)
    }

    private fun assertSystemNack(
        envelope: BinaryEnvelope,
        expectedPacketId: PacketId,
        expectedReason: PacketNackReason,
    ) {
        assertEquals(PacketType.SYSTEM, envelope.packetType)
        val payload = SystemEnvelope.decode(envelope.payload).decodePayload()
        assertTrue(payload is SystemPayload.PacketNack, "expected PacketNack but was ${payload::class.simpleName}")
        assertEquals(expectedPacketId, payload.packetId)
        assertEquals(expectedReason, payload.reason)
    }
}

private fun sampleTextPayload(id: String): MessagePayload.Text =
    MessagePayload.Text(
        messageId = id,
        roomId = "room-1",
        senderAccountId = "acct-sender",
        prevId = null,
        lamportClock = 0L,
        messagePayload = "hello-router".encodeToByteArray(),
        lifecycleState = MessageLifecycleState.CREATED,
        isOrphaned = false,
    )
