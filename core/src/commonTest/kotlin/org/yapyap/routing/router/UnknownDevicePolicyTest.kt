package org.yapyap.routing.router

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.persistence.db.IdentityStatus
import org.yapyap.protocol.*
import org.yapyap.protocol.envelopes.*
import org.yapyap.sync.FakePeerAvailabilityStore
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.tor.TorIncomingEnvelope
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * Unknown-device policy of the inbound envelope processor: sources with no devices row are
 * untrusted by default (silent drop, no disposition, no reachability mark). The single
 * exception is [PacketType.BOOTSTRAP], whose packets carry their own out-of-band
 * authentication enforced inside the bootstrap handler. BANNED always wins, even for
 * BOOTSTRAP.
 */
class UnknownDevicePolicyTest {

    private val localPeer = PeerId("unknownpolicylocaldevice000000000000000000000000000000000000")
    private val knownPeer = PeerId("unknownpolicyknowndevice000000000000000000000000000000000000")
    private val unknownPeer = PeerId("unknownpolicyunknowndevice00000000000000000000000000000000")
    private val bannedPeer = PeerId("unknownpolicybanneddevice0000000000000000000000000000000000")
    private val account = AccountId("unknown-policy-account")

    private fun localDevice(): DeviceIdentityRecord =
        DeviceIdentityRecord(
            deviceId = localPeer,
            signing = IdentityPublicKeyRecord("ls", 0L, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
            encryption = IdentityPublicKeyRecord("le", 0L, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
        )

    private fun identityWith(statuses: Map<PeerId, IdentityStatus> = emptyMap()) =
        FakeIdentityResolverForRouter(
            localDevice = localDevice(),
            peersByAccount = mapOf(account to listOf(knownPeer)),
            deviceStatuses = statuses,
        )

    private fun binaryEnvelope(
        packetType: PacketType,
        source: PeerId,
        payload: ByteArray,
    ): TorIncomingEnvelope =
        TorIncomingEnvelope(
            TorEndpoint("sender.onion", 80),
            BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = packetType,
                dispositionRequested = true,
                createdAt = epochSeconds(10_000L),
                expiresAt = epochSeconds(11_000L),
                source = source,
                target = localPeer,
                payload = payload,
            ),
        )

    private fun plaintextMessagePayload(): ByteArray {
        val text = MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = RoomId(Uuid.random()),
            senderAccountId = account,
            prevIds = emptyList(),
            createdAt = epochSeconds(0L),
            text = "hello-unknown-policy",
            authorDeviceId = knownPeer,
        )
        return MessageEnvelope(
            messageEnvelopeId = text.messageId,
            source = knownPeer,
            target = localPeer,
            createdAt = epochSeconds(10_000L),
            nonce = ByteArray(24) { 3 },
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = text.encode(),
        ).encode()
    }

    @Test
    fun messageFromUnknownDevice_droppedSilently_noDisposition_noDedup() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val recordingDedup = RecordingPacketDeduplicator(InMemoryPacketDeduplicator())
        val router = defaultRouterUnderTest(
            tor = tor,
            identity = identityWith(),
            dedup = recordingDedup,
        )
        router.start()

        val incoming = binaryEnvelope(
            packetType = PacketType.MESSAGE,
            source = unknownPeer,
            payload = plaintextMessagePayload(),
        )
        tor.tryEmitIncoming(incoming)
        delay(400.milliseconds)

        // Dropped before dedup: never seen, never answered (a processed MESSAGE with
        // dispositionRequested would have produced a SYSTEM ACK).
        assertTrue(recordingDedup.firstSeenCalls.isEmpty())
        assertTrue(tor.sendsExcludingHeartbeat().isEmpty())

        router.stop()
    }

    @Test
    fun reachability_markedForKnownDevice_notForUnknownDevice() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val availabilityStore = FakePeerAvailabilityStore()
        val router = DefaultRouter(
            torTransport = tor,
            webRtcTransport = RecordingWebRtcTransport(),
            identityResolver = identityWith(),
            packetDeduplicator = InMemoryPacketDeduplicator(),
            packetOutbox = TrackingPacketOutbox(),
            envelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
            clock = FakeClock(epochSeconds(10_000L)),
            routerConfig = MutableStateFlow(RouterConfig()),
            transportLimits = MutableStateFlow(testTransportLimits()),
            syncRepository = InMemoryPendingSyncRepository(),
            syncPayloadProvider = FakeSyncPayloadProvider(),
            frontierSnapshotProvider = FakeFrontierSnapshotProvider(),
            peerAvailabilityStore = availabilityStore,
        )
        router.start()

        tor.tryEmitIncoming(
            binaryEnvelope(
                packetType = PacketType.MESSAGE,
                source = unknownPeer,
                payload = plaintextMessagePayload(),
            ),
        )
        delay(400.milliseconds)
        assertTrue(
            unknownPeer !in availabilityStore.lastSeen,
            "unknown device must not enter availability math",
        )

        // Control: the wiring records reachability for a known device's traffic.
        tor.tryEmitIncoming(
            binaryEnvelope(
                packetType = PacketType.MESSAGE,
                source = knownPeer,
                payload = plaintextMessagePayload(),
            ),
        )
        delay(400.milliseconds)
        assertTrue(knownPeer in availabilityStore.lastSeen)

        router.stop()
    }

    @Test
    fun bootstrapFromUnknownDevice_reachesHandler_decodeFailedNack() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val recordingDedup = RecordingPacketDeduplicator(InMemoryPacketDeduplicator())
        val router = defaultRouterUnderTest(
            tor = tor,
            identity = identityWith(),
            dedup = recordingDedup,
        )
        router.start()

        val packetId = Uuid.random()
        val incoming = TorIncomingEnvelope(
            TorEndpoint("sender.onion", 80),
            BinaryEnvelope(
                packetId = packetId,
                packetType = PacketType.BOOTSTRAP,
                dispositionRequested = true,
                createdAt = epochSeconds(10_000L),
                expiresAt = epochSeconds(11_000L),
                source = unknownPeer,
                target = localPeer,
                // Not a valid bootstrap envelope: the packet must pass the
                // unknown-device gate and be rejected by the handler itself.
                payload = byteArrayOf(0x00, 0x01, 0x02),
            ),
        )
        tor.tryEmitIncoming(incoming)
        delay(400.milliseconds)

        // Passed the gate (dedup consulted once), rejected by BootstrapInboundHandler,
        // NACKed back to the transport-proven endpoint.
        assertEquals(1, recordingDedup.firstSeenCalls.size)
        assertEquals(1, tor.sendsExcludingHeartbeat().size)
        assertSystemNack(tor.sendsExcludingHeartbeat().single().second, packetId, PacketNackReason.DECODE_FAILED)

        router.stop()
    }

    @Test
    fun bootstrapFromBannedDevice_droppedSilently_bannedWinsOverCarveOut() = runBlocking {
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val recordingDedup = RecordingPacketDeduplicator(InMemoryPacketDeduplicator())
        val router = defaultRouterUnderTest(
            tor = tor,
            identity = identityWith(statuses = mapOf(bannedPeer to IdentityStatus.BANNED)),
            dedup = recordingDedup,
        )
        router.start()

        tor.tryEmitIncoming(
            binaryEnvelope(
                packetType = PacketType.BOOTSTRAP,
                source = bannedPeer,
                payload = byteArrayOf(0x00, 0x01, 0x02),
            ),
        )
        delay(400.milliseconds)

        assertTrue(recordingDedup.firstSeenCalls.isEmpty())
        assertTrue(tor.sendsExcludingHeartbeat().isEmpty())

        router.stop()
    }

    private fun assertSystemNack(
        envelope: BinaryEnvelope,
        expectedPacketId: Uuid,
        expectedReason: PacketNackReason,
    ) {
        assertEquals(PacketType.SYSTEM, envelope.packetType)
        val payload = SystemEnvelope.decode(envelope.payload).decodePayload()
        assertTrue(payload is SystemPayload.PacketNack, "expected PacketNack but was ${payload::class.simpleName}")
        assertEquals(expectedPacketId, payload.packetId)
        assertEquals(expectedReason, payload.reason)
    }
}
