package org.yapyap.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.persistence.db.MessageLifecycleState
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.packet.PacketId
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.packet.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.TorEndpoint
import org.yapyap.routing.router.DefaultRouter
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RouterTransport
import org.yapyap.time.FixedEpochSecondsProvider
import org.yapyap.transport.RecordingTorTransport
import org.yapyap.transport.RecordingWebRtcTransport
import org.yapyap.transport.tor.transport.TorIncomingEnvelope
import org.yapyap.transport.webrtc.types.WebRtcSessionPhase
import org.yapyap.transport.webrtc.types.WebRtcSessionState
import kotlin.time.Duration.Companion.milliseconds

class DefaultRouterOutboxTest {

    private val localPeer =
        PeerId("outboxlocalpeeraaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val remotePeer =
        PeerId("outboxremotepeerbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    @Test
    fun sendMessage_enqueuesOutboxEntry() = runBlocking {
        val tor = RecordingTorTransport()
        val outbox = TrackingPacketOutbox()
        val account = AccountId("outbox-send-account")
        val router = routerForOutboxTests(tor = tor, outbox = outbox, account = account)

        router.start()
        router.sendMessage(account, sampleTextPayload("queued-msg"), RouterTransport.TOR)
        router.stop()

        assertEquals(1, outbox.enqueued.size)
        val packetId = outbox.enqueued.single().packetId
        assertTrue(outbox.contains(packetId))
        assertEquals(10_000L + RouterConfig().torRetryDelaySeconds, outbox.getNextRetryAt(packetId))
    }

    @Test
    fun inboundPacketAck_removesFromOutbox() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val remoteTor = TorEndpoint("remote.onion", 80)
        val outbox = TrackingPacketOutbox()
        val account = AccountId("outbox-ack-account")
        val router = routerForOutboxTests(tor = tor, outbox = outbox, account = account, remoteTor = remoteTor)

        router.start()
        router.sendMessage(account, sampleTextPayload("ack-msg"), RouterTransport.TOR)
        val packetId = outbox.enqueued.single().packetId

        tor.tryEmitIncoming(inboundTorAck(packetId, remoteTor))
        delay(400.milliseconds)
        router.stop()

        assertFalse(outbox.contains(packetId))
        assertEquals(1, outbox.markDeliveredCalls.size)
        assertEquals(packetId, outbox.markDeliveredCalls.single())
    }

    @Test
    fun inboundExpiredNack_removesFromOutbox() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val remoteTor = TorEndpoint("remote.onion", 80)
        val outbox = TrackingPacketOutbox()
        val account = AccountId("outbox-nack-account")
        val router = routerForOutboxTests(tor = tor, outbox = outbox, account = account, remoteTor = remoteTor)

        router.start()
        router.sendMessage(account, sampleTextPayload("nack-msg"), RouterTransport.TOR)
        val packetId = outbox.enqueued.single().packetId

        tor.tryEmitIncoming(
            inboundTorNack(
                nackedPacketId = packetId,
                remoteTor = remoteTor,
                reason = PacketNackReason.EXPIRED,
            ),
        )
        delay(400.milliseconds)
        router.stop()

        assertFalse(outbox.contains(packetId))
        assertEquals(1, outbox.markDeliveredCalls.size)
    }

    @Test
    fun dueOutboxEntry_isRedispatchedOnRouterStart() = runBlocking {
        val tor = RecordingTorTransport()
        val outbox = TrackingPacketOutbox()
        val packetId = PacketId.fromHex("de".repeat(PacketId.SIZE_BYTES))
        val now = 10_000L

        outbox.enqueue(
            envelope = outboxMessageEnvelope(packetId, source = localPeer, target = remotePeer, now = now),
            nextRetryAt = now,
        )

        val router = routerForOutboxTests(
            tor = tor,
            outbox = outbox,
            account = AccountId("outbox-retry-account"),
        )
        router.start()
        delay(500.milliseconds)
        router.stop()

        assertEquals(1, tor.sends.size)
        assertEquals(packetId, tor.sends.single().second.packetId)
    }

    @Test
    fun dispatchFailure_stillRecordsAttempt() = runBlocking {
        val tor = RecordingTorTransport()
        tor.failNextSend = true
        val outbox = TrackingPacketOutbox()
        val packetId = PacketId.fromHex("fa".repeat(PacketId.SIZE_BYTES))
        val now = 10_000L

        outbox.enqueue(
            envelope = outboxMessageEnvelope(packetId, source = localPeer, target = remotePeer, now = now),
            nextRetryAt = now,
        )

        val router = routerForOutboxTests(
            tor = tor,
            outbox = outbox,
            account = AccountId("outbox-fail-account"),
        )
        router.start()
        delay(500.milliseconds)
        router.stop()

        assertEquals(1, outbox.recordAttemptCalls.size)
        assertEquals(packetId, outbox.recordAttemptCalls.single().first)
        assertEquals(1L, outbox.getAttempts(packetId))
    }

    @Test
    fun webrtcConnected_acceleratesOutboxForPeer() = runBlocking {
        val tor = RecordingTorTransport()
        val webRtc = RecordingWebRtcTransport()
        val outbox = TrackingPacketOutbox()
        val packetId = PacketId.random()
        val now = 10_000L

        outbox.enqueue(
            envelope = outboxMessageEnvelope(packetId, source = localPeer, target = remotePeer, now = now),
            nextRetryAt = now + 120,
        )

        val router = routerForOutboxTests(
            tor = tor,
            webRtc = webRtc,
            outbox = outbox,
            account = AccountId("outbox-webrtc-account"),
        )
        router.start()

        webRtc.sessionForPeerResult = "session-1"

        webRtc.tryEmitSessionState(
            WebRtcSessionState(
                sessionId = "session-1",
                peerId = remotePeer,
                phase = WebRtcSessionPhase.CONNECTED,
            ),
        )
        delay(400.milliseconds)
        router.stop()

        assertEquals(now+router.routerConfig.webRtcRetryDelaySeconds, outbox.getNextRetryAt(packetId))
        assertEquals(1, outbox.setDueForTargetCalls.size)
        assertEquals(remotePeer, outbox.setDueForTargetCalls.single().first)
    }

    private fun routerForOutboxTests(
        tor: RecordingTorTransport,
        outbox: TrackingPacketOutbox,
        account: AccountId,
        remoteTor: TorEndpoint = TorEndpoint("peer.onion", 443),
        webRtc: RecordingWebRtcTransport = RecordingWebRtcTransport(),
    ): DefaultRouter {
        val torMap = mutableMapOf(remotePeer to remoteTor)
        val identity =
            FakeIdentityResolverForRouter(
                localDevice = localDevice(),
                peersByAccount = mapOf(account to listOf(remotePeer)),
                torByPeer = torMap,
            )
        return defaultRouterUnderTest(
            tor = tor,
            webRtc = webRtc,
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
                packetId = PacketId.fromHex("a1".repeat(PacketId.SIZE_BYTES)),
                packetType = PacketType.SYSTEM,
                createdAtEpochSeconds = 10_000L,
                expiresAtEpochSeconds = 11_000L,
                source = remotePeer,
                target = localPeer,
                payload = systemEnvelope.encode(),
            )
        return TorIncomingEnvelope(remoteTor, binaryEnvelope)
    }

    private fun inboundTorNack(
        nackedPacketId: PacketId,
        remoteTor: TorEndpoint,
        reason: PacketNackReason,
    ): TorIncomingEnvelope {
        val nackPayload = SystemPayload.PacketNack(
            packetId = nackedPacketId,
            packetType = PacketType.MESSAGE,
            reason = reason,
            reasonText = null,
        )
        val systemEnvelope =
            SystemEnvelope(
                correlationId = "nack:${nackedPacketId.toHex()}",
                source = remotePeer,
                target = localPeer,
                createdAtEpochSeconds = 10_000L,
                nonce = ByteArray(SignalSecurityScheme.SIGNED.nonceSize) { 2 },
                securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
                signature = null,
                payload = nackPayload.encode(),
            )
        val binaryEnvelope =
            BinaryEnvelope(
                packetId = PacketId.fromHex("b2".repeat(PacketId.SIZE_BYTES)),
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
        roomId = "room-outbox",
        senderAccountId = "acct-sender",
        prevId = null,
        lamportClock = 0L,
        messagePayload = "hello-outbox".encodeToByteArray(),
        lifecycleState = MessageLifecycleState.CREATED,
        isOrphaned = false,
    )
