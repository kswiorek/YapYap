package org.yapyap.routing

import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.primitives.SigningKeyPair
import org.yapyap.crypto.primitives.EncryptionKeyPair
import org.yapyap.crypto.identity.SignedPreKeyRecord
import org.yapyap.crypto.e2ee.X3dhRemotePeerKeys
import org.yapyap.persistence.packet.OutboxEntry
import org.yapyap.persistence.packet.PacketDeduplicator
import org.yapyap.persistence.packet.PacketIdAllocator
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.logging.NoopAppLogger
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileChunk
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.packet.PacketId
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.OpenedFileEnvelope
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.time.EpochSecondsProvider
import org.yapyap.protocol.envelopes.WebRtcSignalEnvelope
import org.yapyap.routing.router.DefaultRouter
import org.yapyap.routing.router.RouterConfig
import org.yapyap.time.FixedEpochSecondsProvider
import org.yapyap.transport.webrtc.types.WebRtcSignal
import org.yapyap.transport.RecordingTorTransport
import org.yapyap.transport.RecordingWebRtcTransport

internal class PassthroughFakeEnvelopeProtectionService : EnvelopeProtectionService {

    override suspend fun protectSignal(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope =
        WebRtcSignalEnvelope(
            sessionId = input.sessionId,
            kind = input.kind,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = ByteArray(context.securityScheme.nonceSize) { 1 },
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = input.payload,
        )

    override suspend fun openSignal(envelope: WebRtcSignalEnvelope): WebRtcSignal =
        WebRtcSignal(
            sessionId = envelope.sessionId,
            kind = envelope.kind,
            source = envelope.source,
            target = envelope.target,
            payload = envelope.payload,
        )

    override suspend fun protectFile(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope =
        error("not used in router transport tests")

    override suspend fun openFile(envelope: FileEnvelope): OpenedFileEnvelope =
        error("not used in router transport tests")

    override suspend fun decryptFileChunk(chunk: FilePayload.EncryptedChunk): FileChunk =
        error("not used in router transport tests")

    override suspend fun protectMessage(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
        val messageId =
            when (input) {
                is MessagePayload.Text -> input.messageId
                is MessagePayload.GlobalEvent -> input.messageId
            }
        return MessageEnvelope(
            messageId = messageId,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = ByteArray(context.securityScheme.nonceSize) { 1 },
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = input.encode(),
        )
    }

    override suspend fun openMessage(envelope: MessageEnvelope): MessagePayload = envelope.decodePayload()

    override suspend fun protectSystem(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope {
        val correlationId = when (input) {
            is SystemPayload.PacketAck -> "ack:${input.packetId.toHex()}"
            is SystemPayload.PacketNack -> "nack:${input.packetId.toHex()}"
        }
        return SystemEnvelope(
            correlationId = correlationId,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = ByteArray(context.securityScheme.nonceSize) { 1 },
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = input.encode(),
        )
    }

    override suspend fun openSystem(envelope: SystemEnvelope): SystemPayload = envelope.decodePayload()
}

internal class SequencedPacketIdAllocator : PacketIdAllocator {
    private var localPeer: PeerId? = null
    private var seq = 0L

    override fun assignLocalDevice(deviceId: PeerId) {
        localPeer = deviceId
    }

    override fun allocate(now: Long): PacketId {
        checkNotNull(localPeer) { "assignLocalDevice first" }
        seq++
        val bytes = ByteArray(PacketId.SIZE_BYTES)
        for (i in 0 until 8) {
            bytes[PacketId.SIZE_BYTES - 1 - i] = ((seq shr (8 * i)) and 0xFFL).toInt().toByte()
        }
        return PacketId.fromBytes(bytes)
    }
}

internal class InMemoryPacketDeduplicator : PacketDeduplicator {
    private val seen = mutableSetOf<Pair<String, String>>()
    private val nackReasons = mutableMapOf<Pair<String, String>, PacketNackReason>()

    override fun firstSeen(packetId: PacketId, sourceDeviceId: PeerId, receivedAtEpochSeconds: Long): Boolean {
        val key = sourceDeviceId.id to packetId.toHex()
        return seen.add(key)
    }

    override fun markNacked(packetId: PacketId, sourceDeviceId: PeerId, nackReason: PacketNackReason) {
        nackReasons[sourceDeviceId.id to packetId.toHex()] = nackReason
    }

    override fun getNackReason(packetId: PacketId, sourceDeviceId: PeerId): PacketNackReason? =
        nackReasons[sourceDeviceId.id to packetId.toHex()]

    override fun prune(receivedBeforeEpochSeconds: Long) {
        // No-op for router contract tests
    }
}

internal class RecordingPacketDeduplicator(private val delegate: PacketDeduplicator) : PacketDeduplicator {
    val firstSeenCalls = mutableListOf<Triple<PacketId, PeerId, Long>>()
    val firstSeenResults = mutableListOf<Boolean>()
    val markNackedCalls = mutableListOf<Triple<PacketId, PeerId, PacketNackReason>>()

    override fun firstSeen(packetId: PacketId, sourceDeviceId: PeerId, receivedAtEpochSeconds: Long): Boolean {
        firstSeenCalls.add(Triple(packetId, sourceDeviceId, receivedAtEpochSeconds))
        val result = delegate.firstSeen(packetId, sourceDeviceId, receivedAtEpochSeconds)
        firstSeenResults.add(result)
        return result
    }

    override fun markNacked(packetId: PacketId, sourceDeviceId: PeerId, nackReason: PacketNackReason) {
        markNackedCalls.add(Triple(packetId, sourceDeviceId, nackReason))
        delegate.markNacked(packetId, sourceDeviceId, nackReason)
    }

    override fun getNackReason(packetId: PacketId, sourceDeviceId: PeerId): PacketNackReason? =
        delegate.getNackReason(packetId, sourceDeviceId)

    override fun prune(receivedBeforeEpochSeconds: Long) {
        delegate.prune(receivedBeforeEpochSeconds)
    }
}

internal class FakeIdentityResolverForRouter(
    private val localDevice: DeviceIdentityRecord,
    private val peersByAccount: Map<AccountId, List<PeerId>> = emptyMap(),
    private val torByPeer: MutableMap<PeerId, TorEndpoint> = mutableMapOf(),
    val torUpdates: MutableList<Pair<PeerId, TorEndpoint>> = mutableListOf(),
) : IdentityResolver {

    override suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = localDevice

    override suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord =
        error("FakeIdentityResolverForRouter: account record not stubbed")

    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray =
        error("FakeIdentityResolverForRouter: private key not stubbed")

    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray =
        error("FakeIdentityResolverForRouter: private key not stubbed")

    override suspend fun getLocalDeviceId(): PeerId  = error("not used")

    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord? = null

    override fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint =
        torByPeer[deviceId] ?: TorEndpoint(onionAddress = "missing.onion", port = 80)

    override fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        peersByAccount[accountId].orEmpty()

    override fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) {
        torUpdates.add(deviceId to torEndpoint)
        torByPeer[deviceId] = torEndpoint
    }

    override suspend fun resolvePeerX3dhRemoteKeys(
        deviceId: PeerId,
        signedPreKeyId: String?,
    ): X3dhRemotePeerKeys = error("not used in test")

    override suspend fun getCurrentLocalSignedPreKey(): SignedPreKeyRecord =
        error("FakeIdentityResolverForRouter: signed prekey not stubbed")

    override suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): SignedPreKeyRecord =
        error("FakeIdentityResolverForRouter: signed prekey not stubbed")
}

internal class TrackingPacketOutbox : PacketOutbox {
    private data class StoredEntry(
        val envelope: BinaryEnvelope,
        var nextRetryAt: Long,
        var attempts: Long,
        val relayMessage: Boolean,
        val expiresAtEpochSeconds: Long,
        val blobSize: Long,
    )

    private val entries = linkedMapOf<String, StoredEntry>()
    val enqueued = mutableListOf<BinaryEnvelope>()
    val markDeliveredCalls = mutableListOf<PacketId>()
    val recordAttemptCalls = mutableListOf<Triple<PacketId, Long, Long>>()
    val setDueForTargetCalls = mutableListOf<Pair<PeerId, Long>>()

    override fun enqueue(envelope: BinaryEnvelope, nextRetryAt: Long, relayMessage: Boolean) {
        val blobSize = envelope.encode().size.toLong()
        entries[envelope.packetId.toHex()] = StoredEntry(
            envelope = envelope,
            nextRetryAt = nextRetryAt,
            attempts = 0,
            relayMessage = relayMessage,
            expiresAtEpochSeconds = envelope.expiresAtEpochSeconds,
            blobSize = blobSize,
        )
        enqueued.add(envelope)
    }

    override fun markDelivered(packetId: PacketId) {
        markDeliveredCalls.add(packetId)
        entries.remove(packetId.toHex())
    }

    override fun setDueForTarget(target: PeerId, nextRetryAt: Long) {
        setDueForTargetCalls.add(target to nextRetryAt)
        for (entry in entries.values) {
            if (entry.envelope.target == target && entry.nextRetryAt > nextRetryAt) {
                entry.nextRetryAt = nextRetryAt
            }
        }
    }

    override fun recordAttempt(packetId: PacketId, nextRetryAt: Long, now: Long) {
        recordAttemptCalls.add(Triple(packetId, nextRetryAt, now))
        val entry = entries[packetId.toHex()] ?: return
        entry.attempts += 1
        entry.nextRetryAt = nextRetryAt
    }

    override fun listAllForTarget(target: PeerId): List<OutboxEntry> =
        entries.values
            .filter { it.envelope.target == target }
            .map { it.toOutboxEntry() }

    override fun listDue(now: Long): List<OutboxEntry> =
        entries.values
            .filter { it.nextRetryAt <= now }
            .map { it.toOutboxEntry() }

    override fun pruneExpired(now: Long): Int {
        val expiredKeys = entries.filterValues { it.expiresAtEpochSeconds <= now }.keys
        expiredKeys.forEach { entries.remove(it) }
        return expiredKeys.size
    }

    override fun earliestPendingRetryAt(): Long? =
        entries.values.minOfOrNull { it.nextRetryAt }

    override fun relayCacheBytes(): Long =
        entries.values.filter { it.relayMessage }.sumOf { it.blobSize }

    override fun pruneRelayOverCapacity(maxBytes: Long): Int {
        var evicted = 0
        while (relayCacheBytes() > maxBytes) {
            val victim = entries.values
                .filter { it.relayMessage }
                .minWithOrNull(compareBy<StoredEntry> { it.expiresAtEpochSeconds }.thenBy { it.envelope.packetId.toHex() })
                ?: break
            entries.remove(victim.envelope.packetId.toHex())
            evicted++
        }
        return evicted
    }

    fun contains(packetId: PacketId): Boolean = entries.containsKey(packetId.toHex())

    fun getNextRetryAt(packetId: PacketId): Long? = entries[packetId.toHex()]?.nextRetryAt

    fun getAttempts(packetId: PacketId): Long = entries[packetId.toHex()]?.attempts ?: 0L

    private fun StoredEntry.toOutboxEntry(): OutboxEntry =
        OutboxEntry(
            packetId = envelope.packetId,
            envelope = envelope,
            nextRetryAt = nextRetryAt,
            attempts = attempts,
        )
}

internal fun defaultRouterUnderTest(
    tor: RecordingTorTransport = RecordingTorTransport(),
    webRtc: RecordingWebRtcTransport = RecordingWebRtcTransport(),
    identity: FakeIdentityResolverForRouter,
    dedup: PacketDeduplicator = InMemoryPacketDeduplicator(),
    outbox: PacketOutbox = TrackingPacketOutbox(),
    allocator: PacketIdAllocator = SequencedPacketIdAllocator(),
    time: EpochSecondsProvider = FixedEpochSecondsProvider(10_000L),
    routerConfig: RouterConfig = RouterConfig(),
): DefaultRouter =
    DefaultRouter(
        torTransport = tor,
        webRtcTransport = webRtc,
        identityResolver = identity,
        packetIdAllocator = allocator,
        packetDeduplicator = dedup,
        packetOutbox = outbox,
        envelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
        timeProvider = time,
        logger = NoopAppLogger,
        routerConfig = routerConfig,
    )
