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
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.transport.RecordingTorTransport
import org.yapyap.backend.transport.RecordingWebRtcTransport

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
    fun duplicateInboundPacket_invokesDedupTwice_secondSeenAsDuplicate() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val remoteTor = TorEndpoint("remote.onion", 80)
        val torMap = mutableMapOf(remotePeer to remoteTor)
        val identity =
            FakeIdentityResolverForRouter(
                localDevice = localDevice(),
                torByPeer = torMap,
            )
        val innerDedup = InMemoryPacketDeduplicator()
        val recordingDedup = RecordingPacketDeduplicator(innerDedup)
        val router =
            defaultRouterUnderTest(
                tor = tor,
                identity = identity,
                dedup = recordingDedup,
            )

        router.start()

        val text = sampleTextPayload("dedup-msg")
        val msgEnv =
            MessageEnvelope(
                messageId = text.messageId,
                source = remotePeer,
                target = localPeer,
                createdAtEpochSeconds = 10_000L,
                nonce = ByteArray(24) { 3 },
                securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
                signature = null,
                payload = text,
            )
        val packetId = PacketId.fromHex("aa".repeat(PacketId.SIZE_BYTES))
        val bin =
            BinaryEnvelope(
                packetId = packetId,
                packetType = PacketType.MESSAGE,
                createdAtEpochSeconds = 10_000L,
                expiresAtEpochSeconds = 11_000L,
                source = remotePeer,
                target = localPeer,
                payload = msgEnv.encode(),
            )
        val incoming = org.yapyap.backend.transport.tor.TorIncomingEnvelope(remoteTor, bin)

        tor.tryEmitIncoming(incoming)
        delay(400)
        tor.tryEmitIncoming(incoming)
        delay(400)

        assertEquals(2, recordingDedup.firstSeenCalls.size)
        assertEquals(packetId, recordingDedup.firstSeenCalls[0].first)
        assertEquals(packetId, recordingDedup.firstSeenCalls[1].first)
        assertTrue(recordingDedup.firstSeenResults[0])
        assertTrue(!recordingDedup.firstSeenResults[1])

        router.stop()
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
