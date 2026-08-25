package org.yapyap.routing.outbound

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.protection.sampleTextPayload
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.router.*
import org.yapyap.time.FixedEpochProvider
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.tor.TorIncomingEnvelope
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcSessionPhase
import org.yapyap.transport.webrtc.types.WebRtcSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

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
        router.sendMessage(account, sampleTextPayload(), RouterTransport.TOR)
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
        router.sendMessage(account, sampleTextPayload(), RouterTransport.TOR)
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
        router.sendMessage(account, sampleTextPayload(), RouterTransport.TOR)
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
        val packetId = Uuid.random()
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
    fun dueOutboxEntries_areRedispatchedOnRouterStart() = runBlocking {
        val tor = RecordingTorTransport()
        val outbox = TrackingPacketOutbox()
        val now = 10_000L
        val packetId1 = Uuid.random()
        val packetId2 = Uuid.random()

        outbox.enqueue(
            envelope = outboxMessageEnvelope(packetId1, source = localPeer, target = remotePeer, now = now),
            nextRetryAt = now,
        )
        outbox.enqueue(
            envelope = outboxMessageEnvelope(packetId2, source = localPeer, target = remotePeer, now = now),
            nextRetryAt = now,
        )

        val router = routerForOutboxTests(
            tor = tor,
            outbox = outbox,
            account = AccountId("outbox-parallel-account"),
        )

        router.start()
        withTimeout(10.seconds) {tor.awaitSendCount(2)}
        router.stop()

        assertEquals(2, tor.sends.size)
        assertEquals(
            setOf(packetId1, packetId2),
            tor.sends.map { it.second.packetId }.toSet(),
        )
    }

    @Test
    fun dispatchFailure_stillRecordsAttempt() = runBlocking {
        val tor = RecordingTorTransport()
        tor.failNextSend = true
        val outbox = TrackingPacketOutbox()
        val packetId = Uuid.random()
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
        val packetId = Uuid.random()
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

        webRtc.openSession(remotePeer)

        webRtc.tryEmitSessionState(
            WebRtcSessionState(
                peerId = remotePeer,
                phase = WebRtcSessionPhase.CONNECTED,
            ),
        )
        delay(400.milliseconds)
        router.stop()

        assertEquals(now+router.routerConfig.value.webRtcRetryDelaySeconds, outbox.getNextRetryAt(packetId))
        assertEquals(1, outbox.setDueForTargetCalls.size)
        assertEquals(remotePeer, outbox.setDueForTargetCalls.single().first)
    }

    private fun routerForOutboxTests(
        tor: org.yapyap.transport.tor.transport.TorTransport,
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
            time = FixedEpochProvider(10_000L),
            routerConfig = RouterConfig(retryLoopMaxIdlePollSeconds = 1),
        )
    }

    private fun localDevice(): DeviceIdentityRecord =
        DeviceIdentityRecord(
            deviceId = localPeer,
            signing = IdentityPublicKeyRecord("ls", 0L, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
            encryption = IdentityPublicKeyRecord("le", 0L, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
        )

    private fun outboxMessageEnvelope(
        packetId: Uuid,
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
        ackForPacketId: Uuid,
        remoteTor: TorEndpoint,
    ): TorIncomingEnvelope {
        val ackPayload = SystemPayload.PacketAck(ackForPacketId, PacketType.MESSAGE)
        val systemEnvelope =
            SystemEnvelope(
                systemEnvelopeId = Uuid.random(),
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
                packetId = Uuid.random(),
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
        nackedPacketId: Uuid,
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
                systemEnvelopeId = Uuid.random(),
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
                packetId = Uuid.random(),
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
