package org.yapyap.backend.routing

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityPublicKeyRecord
import org.yapyap.backend.db.DefaultPacketOutbox
import org.yapyap.backend.db.FixtureAccountId
import org.yapyap.backend.db.FixtureDevicePeerId
import org.yapyap.backend.db.FixtureRemotePeerId
import org.yapyap.backend.db.FixtureTorEndpoint
import org.yapyap.backend.db.MessageLifecycleState
import org.yapyap.backend.db.openMemoryDatabase
import org.yapyap.backend.db.seedLocalAndRemoteDevices
import org.yapyap.backend.db.DatabaseConnection
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.MessagePayload
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.protocol.SystemEnvelope
import org.yapyap.backend.protocol.SystemPayload
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.transport.RecordingTorTransport
import org.yapyap.backend.transport.tor.TorIncomingEnvelope

class DefaultRouterOutboxIntegrationTest {

    private var connection: DatabaseConnection? = null

    private val localPeer = FixtureDevicePeerId
    private val remotePeer = FixtureRemotePeerId
    private val account = FixtureAccountId

    @AfterTest
    fun closeDb() {
        connection?.driver?.close()
        connection = null
    }

    @Test
    fun sendMessage_persistsOutbox_andAckRemovesRow() = runBlocking {
        connection = openMemoryDatabase()
        seedLocalAndRemoteDevices(connection!!.database)

        val outbox = DefaultPacketOutbox(connection!!.database)
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val router = routerWithRealOutbox(tor, outbox)

        router.start()
        router.sendMessage(account, sampleTextPayload("persisted-msg"), RouterTransport.TOR)

        val now = 10_000L
        assertTrue(outbox.listDue(now).isEmpty())
        assertEquals(now + RouterConfig().torRetryDelaySeconds, outbox.earliestPendingRetryAt())
        assertEquals(1, tor.sends.size)

        val packetId = tor.sends.single().second.packetId
        tor.tryEmitIncoming(inboundTorAck(packetId, FixtureTorEndpoint))
        delay(400)

        assertTrue(outbox.listDue(now).isEmpty())
        router.stop()
    }

    @Test
    fun dueOutboxEntry_isRedispatchedOnRouterStart() = runBlocking {
        connection = openMemoryDatabase()
        seedLocalAndRemoteDevices(connection!!.database)

        val outbox = DefaultPacketOutbox(connection!!.database)
        val tor = RecordingTorTransport()
        val packetId = PacketId.random()
        val now = 10_000L

        outbox.enqueue(
            envelope = outboxMessageEnvelope(
                packetId = packetId,
                source = localPeer,
                target = remotePeer,
                now = now,
            ),
            nextRetryAt = now,
        )

        val router = routerWithRealOutbox(tor, outbox)
        router.start()
        delay(500)
        router.stop()

        assertEquals(1, tor.sends.size)
        assertEquals(packetId, tor.sends.single().second.packetId)
        assertEquals(1, outbox.listAllForTarget(remotePeer).size)
        assertEquals(1L, outbox.listAllForTarget(remotePeer).single().attempts)
    }

    private fun routerWithRealOutbox(
        tor: RecordingTorTransport,
        outbox: DefaultPacketOutbox,
    ): DefaultRouter {
        val identity =
            FakeIdentityResolverForRouter(
                localDevice = localDevice(),
                peersByAccount = mapOf(account to listOf(remotePeer)),
                torByPeer = mutableMapOf(remotePeer to FixtureTorEndpoint),
            )
        return defaultRouterUnderTest(
            tor = tor,
            identity = identity,
            outbox = outbox,
            time = FixedEpochSecondsProvider(10_000L),
            routerConfig = RouterConfig(outboxMaxIdlePollSeconds = 1),
        )
    }

    private fun localDevice(): DeviceIdentityRecord =
        DeviceIdentityRecord(
            deviceId = localPeer,
            signing = IdentityPublicKeyRecord("ls", 0L, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
            encryption = IdentityPublicKeyRecord("le", 0L, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
        )

    private fun outboxMessageEnvelope(
        packetId: PacketId,
        source: PeerId,
        target: PeerId,
        now: Long,
    ): BinaryEnvelope =
        BinaryEnvelope(
            packetId = packetId,
            packetType = PacketType.MESSAGE,
            createdAtEpochSeconds = now,
            expiresAtEpochSeconds = now + 3_600,
            source = source,
            target = target,
            payload = byteArrayOf(0x01, 0x02, 0x03),
        )

    private fun inboundTorAck(
        ackForPacketId: PacketId,
        remoteTor: TorEndpoint,
    ): TorIncomingEnvelope {
        val ackPayload = SystemPayload.PacketAck(ackForPacketId, PacketType.MESSAGE)
        val systemEnvelope =
            SystemEnvelope(
                correlationId = "ack:${ackForPacketId.toHex()}",
                source = remotePeer,
                target = localPeer,
                createdAtEpochSeconds = 10_000L,
                nonce = ByteArray(SignalSecurityScheme.SIGNED.nonceSize) { 1 },
                securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
                signature = null,
                payload = ackPayload.encode(),
            )
        val binaryEnvelope =
            BinaryEnvelope(
                packetId = PacketId.fromHex("c3".repeat(PacketId.SIZE_BYTES)),
                packetType = PacketType.SYSTEM,
                createdAtEpochSeconds = 10_000L,
                expiresAtEpochSeconds = 11_000L,
                source = remotePeer,
                target = localPeer,
                payload = systemEnvelope.encode(),
            )
        return TorIncomingEnvelope(remoteTor, binaryEnvelope)
    }
}

private fun sampleTextPayload(id: String): MessagePayload.Text =
    MessagePayload.Text(
        messageId = id,
        roomId = "room-integration",
        senderAccountId = "acct-sender",
        prevId = null,
        lamportClock = 0L,
        messagePayload = "hello-integration".encodeToByteArray(),
        lifecycleState = MessageLifecycleState.CREATED,
        isOrphaned = false,
    )
