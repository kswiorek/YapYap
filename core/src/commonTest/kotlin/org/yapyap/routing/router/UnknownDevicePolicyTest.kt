package org.yapyap.routing.router


import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.MessageHeader
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.link.PeerLinks
import org.yapyap.routing.presence.FakePeerAvailabilityStore
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
    private val knownPeer2 = PeerId("unknownpolicyknowndevice2000000000000000000000000000000000000")
    private val unknownPeer = PeerId("unknownpolicyunknowndevice00000000000000000000000000000000")
    private val bannedPeer = PeerId("unknownpolicybanneddevice0000000000000000000000000000000000")
    private val account = AccountId("unknown-policy-account")

    private fun localDevice(): DeviceIdentity =
        DeviceIdentity(
            deviceId = localPeer,
            signingKey = SigningPublicKey(byteArrayOf(1)),
            encryptionKey = EncryptionPublicKey(byteArrayOf(2)),
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
        routingHeader: ByteArray,
        content: ByteArray = byteArrayOf(),
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
                routingHeader = routingHeader,
                content = content,
                signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
            ),
        )

    private fun plaintextMessagePayload(): ByteArray =
        DagPayload.Text(
            messageId = Uuid.random(),
            roomId = RoomId(Uuid.random()),
            senderAccountId = account,
            prevIds = emptyList(),
            createdAt = epochSeconds(0L),
            text = "hello-unknown-policy",
            authorDeviceId = knownPeer,
        ).encode()

    private fun messageRoutingHeader(
        origin: PeerId = knownPeer,
        finalTarget: PeerId = localPeer,
    ): ByteArray = MessageHeader(origin, finalTarget).encode()

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
            routingHeader = messageRoutingHeader(),
            content = plaintextMessagePayload(),
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
        val webRtc = RecordingWebRtcTransport()
        val availabilityStore = FakePeerAvailabilityStore()
        val identity = identityWith()
        val clock = FakeClock(epochSeconds(10_000L))
        val config = MutableStateFlow(RouterConfig())
        val ctx = RoutingContext(
            localIdentity = identity,
            localDeviceId = identity.localDeviceId,
            devices = identity,
            torTransport = tor,
            webRtcTransport = webRtc,
            peerLinks = PeerLinks(identity, webRtc, tor),
            transportLimits = MutableStateFlow(testTransportLimits()),
            fileLimits = MutableStateFlow(FileLimits(220 * 1024, 220 * 1024, maxSealBytes = 64 * 1024)),
            envelopeProtections = PassthroughFakeEnvelopeProtectionService(),
            clock = clock,
            routerConfig = config,
        )
        val router = DefaultRouter(
            ctx = ctx,
            routes = identity,
            packetDeduplicator = InMemoryPacketDeduplicator(),
            packetOutbox = TrackingPacketOutbox(),
            peerAvailabilityStore = availabilityStore,
            syncAttemptStore = InMemorySyncAttemptStore(),
            syncPayloadProvider = FakeSyncPayloadProvider(),
            frontierSnapshotProvider = FakeFrontierSnapshotProvider(),
            fileIngest = RefusingFilePorts(),
            fileRequests = RefusingFilePorts(),
            filePlaintext = FakeFilePlaintextSource(),
            fileIncomingStore = InMemoryFileIncomingStore(),
            fileSealStore = InMemoryFileSealStore(),
            fileTransferStore = InMemoryFileTransferStore(),
            fileRelayStore = InMemoryFileRelayStore(),
            filePullAttemptStore = InMemoryFilePullAttemptStore(),
        )
        router.start()

        tor.tryEmitIncoming(
            binaryEnvelope(
                packetType = PacketType.MESSAGE,
                source = unknownPeer,
                routingHeader = messageRoutingHeader(),
                content = plaintextMessagePayload(),
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
                routingHeader = messageRoutingHeader(),
                content = plaintextMessagePayload(),
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
                // Not a valid bootstrap header: the packet must pass the
                // unknown-device gate and be rejected by the handler itself.
                routingHeader = byteArrayOf(0x00, 0x01, 0x02),
                content = byteArrayOf(),
                signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
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
                routingHeader = byteArrayOf(0x00, 0x01, 0x02),
            ),
        )
        delay(400.milliseconds)

        assertTrue(recordingDedup.firstSeenCalls.isEmpty())
        assertTrue(tor.sendsExcludingHeartbeat().isEmpty())

        router.stop()
    }

    @Test
    fun rotationHealsMapping_onAuthenticatedInboundFromNewOnion() = runBlocking {
        val oldTor = TorEndpoint("peer-old.onion", 80)
        val newTor = TorEndpoint("peer-new.onion", 80)
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val identity = FakeIdentityResolverForRouter(
            localDevice = localDevice(),
            peersByAccount = mapOf(account to listOf(knownPeer)),
            torByPeer = mutableMapOf(knownPeer to oldTor),
        )
        val router = defaultRouterUnderTest(tor = tor, identity = identity)
        router.start()

        // Known peer rotated its onion: the first authenticated packet from the new
        // onion heals the stored mapping (and the ACK still goes to the proven onion).
        tor.tryEmitIncoming(
            TorIncomingEnvelope(
                newTor,
                BinaryEnvelope(
                    packetId = Uuid.random(),
                    packetType = PacketType.MESSAGE,
                    dispositionRequested = true,
                    createdAt = epochSeconds(10_000L),
                    expiresAt = epochSeconds(11_000L),
                    source = knownPeer,
                    target = localPeer,
                    routingHeader = messageRoutingHeader(),
                    content = plaintextMessagePayload(),
                    signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
                ),
            ),
        )
        delay(400.milliseconds)

        // router.start() records the local endpoint; the rotation heal must be the
        // only write for the peer.
        assertEquals(listOf(knownPeer to newTor), identity.torUpdates.filter { it.first == knownPeer })
        assertEquals(1, tor.sendsExcludingHeartbeat().size)
        assertEquals(newTor, tor.sendsExcludingHeartbeat().single().first)

        router.stop()
    }

    @Test
    fun spoofedClaim_rejectedPacket_doesNotOverwriteMapping() = runBlocking {
        val storedTor = TorEndpoint("peer-real.onion", 80)
        val attackerTor = TorEndpoint("attacker.onion", 80)
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val identity = FakeIdentityResolverForRouter(
            localDevice = localDevice(),
            peersByAccount = mapOf(account to listOf(knownPeer)),
            torByPeer = mutableMapOf(knownPeer to storedTor),
        )
        val router = defaultRouterUnderTest(tor = tor, identity = identity)
        router.start()

        // Attacker claims a known peer's device id from their own onion. The packet
        // fails authentication (DECODE_FAILED) and is NACKed — but the mapping must
        // stay untouched: writes happen only on authenticated packets.
        val packetId = Uuid.random()
        tor.tryEmitIncoming(
            TorIncomingEnvelope(
                attackerTor,
                BinaryEnvelope(
                    packetId = packetId,
                    packetType = PacketType.MESSAGE,
                    dispositionRequested = true,
                    createdAt = epochSeconds(10_000L),
                    expiresAt = epochSeconds(11_000L),
                    source = knownPeer,
                    target = localPeer,
                    routingHeader = byteArrayOf(0x00, 0x01, 0x02),
                    content = byteArrayOf(),
                    signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
                ),
            ),
        )
        delay(400.milliseconds)

        assertTrue(identity.torUpdates.none { it.first == knownPeer })
        assertEquals(1, tor.sendsExcludingHeartbeat().size)
        assertSystemNack(tor.sendsExcludingHeartbeat().single().second, packetId, PacketNackReason.DECODE_FAILED)

        router.stop()
    }

    @Test
    fun relayTransit_verifiedSuccess_healsMapping() = runBlocking {
        val storedTor = TorEndpoint("sender-real.onion", 80)
        val transitTor = TorEndpoint("transit-arrival.onion", 80)
        val peer2Tor = TorEndpoint("peer2.onion", 80)
        val tor = RecordingTorTransport(TorEndpoint("self.onion", 80))
        val identity = FakeIdentityResolverForRouter(
            localDevice = localDevice(),
            peersByAccount = mapOf(account to listOf(knownPeer, knownPeer2)),
            torByPeer = mutableMapOf(knownPeer to storedTor, knownPeer2 to peer2Tor),
        )
        val router = defaultRouterUnderTest(tor = tor, identity = identity)
        router.start()

        // Relay transit: binary target is local (passes the gate) but the inner
        // message is for another peer. The hop signature verified the outer source,
        // so endpoint healing applies to relayed traffic as well (envelope-redesign §3.1):
        // the sender's mapping moves to the arrival onion.
        val innerText = DagPayload.Text(
            messageId = Uuid.random(),
            roomId = RoomId(Uuid.random()),
            senderAccountId = account,
            prevIds = emptyList(),
            createdAt = epochSeconds(0L),
            text = "relay-me",
            authorDeviceId = knownPeer,
        )
        tor.tryEmitIncoming(
            TorIncomingEnvelope(
                transitTor,
                BinaryEnvelope(
                    packetId = Uuid.random(),
                    packetType = PacketType.MESSAGE,
                    dispositionRequested = true,
                    createdAt = epochSeconds(10_000L),
                    expiresAt = epochSeconds(11_000L),
                    source = knownPeer,
                    target = localPeer,
                    routingHeader = messageRoutingHeader(origin = knownPeer, finalTarget = knownPeer2),
                    content = innerText.encode(),
                    signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
                ),
            ),
        )
        delay(400.milliseconds)

        assertTrue(identity.torUpdates.any { it.first == knownPeer }, "hop-verified relay transit heals the mapping")

        router.stop()
    }

    private fun assertSystemNack(
        envelope: BinaryEnvelope,
        expectedPacketId: Uuid,
        expectedReason: PacketNackReason,
    ) {
        assertEquals(PacketType.SYSTEM, envelope.packetType)
        val payload = SystemPayload.decode(envelope.routingHeader)
        assertTrue(payload is SystemPayload.PacketNack, "expected PacketNack but was ${payload::class.simpleName}")
        assertEquals(expectedPacketId, payload.packetId)
        assertEquals(expectedReason, payload.reason)
    }
}
