package org.yapyap.routing.router

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.*
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.KmpCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.logging.NoopAppLogger
import org.yapyap.persistence.key.InMemoryOpkRepository
import org.yapyap.persistence.packet.OutboxEntry
import org.yapyap.persistence.packet.PacketDeduplicator
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.protection.PassthroughFileProtection
import org.yapyap.protection.envelope.SignedAndEncryptedMessageProtection
import org.yapyap.protection.envelope.SignedSystemProtection
import org.yapyap.protection.envelope.SignedWebRtcSignalProtection
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.*
import org.yapyap.routing.dispatch.EnvelopeDispatcher
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.policy.SessionOrTorPolicy
import org.yapyap.time.EpochSecondsProvider
import org.yapyap.time.FixedEpochSecondsProvider
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.tor.transport.TorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcSignal
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

internal class PassthroughFakeEnvelopeProtectionService : EnvelopeProtectionService {

    override suspend fun protectSignal(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope =
        WebRtcSignalEnvelope(
            signalEnvelopeId = Uuid.random(),
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
            messageEnvelopeId = messageId,
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
        return SystemEnvelope(
            systemEnvelopeId = Uuid.random(),
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

/**
 * Wraps [delegate] and tracks how many [protectMessage] calls overlap in time.
 * Used to verify multi-peer fan-out runs concurrently rather than sequentially.
 */
internal class ConcurrencyTrackingEnvelopeProtectionService(
    private val delegate: EnvelopeProtectionService,
    private val protectDelayMillis: Long = 200,
) : EnvelopeProtectionService {
    private val protectStatsMutex = Mutex()
    private var activeProtects = 0
    var maxConcurrentProtects = 0
        private set

    override suspend fun protectSignal(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope =
        delegate.protectSignal(input, context)

    override suspend fun openSignal(envelope: WebRtcSignalEnvelope): WebRtcSignal =
        delegate.openSignal(envelope)

    override suspend fun protectFile(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope =
        delegate.protectFile(input, context)

    override suspend fun openFile(envelope: FileEnvelope): OpenedFileEnvelope =
        delegate.openFile(envelope)

    override suspend fun decryptFileChunk(chunk: FilePayload.EncryptedChunk): FileChunk =
        delegate.decryptFileChunk(chunk)

    override suspend fun protectMessage(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
        protectStatsMutex.withLock {
            activeProtects++
            if (activeProtects > maxConcurrentProtects) {
                maxConcurrentProtects = activeProtects
            }
        }
        try {
            delay(protectDelayMillis.milliseconds)
            return delegate.protectMessage(input, context)
        } finally {
            protectStatsMutex.withLock {
                activeProtects--
            }
        }
    }

    override suspend fun openMessage(envelope: MessageEnvelope): MessagePayload =
        delegate.openMessage(envelope)

    override suspend fun protectSystem(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope =
        delegate.protectSystem(input, context)

    override suspend fun openSystem(envelope: SystemEnvelope): SystemPayload =
        delegate.openSystem(envelope)
}


internal class InMemoryPacketDeduplicator : PacketDeduplicator {
    private val seen = mutableSetOf<Pair<PeerId, Uuid>>()
    private val nackReasons = mutableMapOf<Pair<PeerId, Uuid>, PacketNackReason>()

    override fun firstSeen(packetId: Uuid, sourceDeviceId: PeerId, receivedAtEpochSeconds: Long): Boolean {
        val key = sourceDeviceId to packetId
        return seen.add(key)
    }

    override fun clearPacket(
        packetId: Uuid,
        sourceDeviceId: PeerId
    ) {
        seen.remove(sourceDeviceId to packetId)
    }

    override fun markNacked(packetId: Uuid, sourceDeviceId: PeerId, nackReason: PacketNackReason) {
        nackReasons[sourceDeviceId to packetId] = nackReason
    }

    override fun getNackReason(packetId: Uuid, sourceDeviceId: PeerId): PacketNackReason? =
        nackReasons[sourceDeviceId to packetId]

    override fun prune(receivedBeforeEpochSeconds: Long) {
        // No-op for router contract tests
    }
}

internal class RecordingPacketDeduplicator(private val delegate: PacketDeduplicator) : PacketDeduplicator {
    val firstSeenCalls = mutableListOf<Triple<Uuid, PeerId, Long>>()
    val firstSeenResults = mutableListOf<Boolean>()
    val markNackedCalls = mutableListOf<Triple<Uuid, PeerId, PacketNackReason>>()

    override fun firstSeen(packetId: Uuid, sourceDeviceId: PeerId, receivedAtEpochSeconds: Long): Boolean {
        firstSeenCalls.add(Triple(packetId, sourceDeviceId, receivedAtEpochSeconds))
        val result = delegate.firstSeen(packetId, sourceDeviceId, receivedAtEpochSeconds)
        firstSeenResults.add(result)
        return result
    }

    override fun clearPacket(
        packetId: Uuid,
        sourceDeviceId: PeerId
    ) {
        delegate.clearPacket(
            packetId = packetId,
            sourceDeviceId = sourceDeviceId,
        )
    }

    override fun markNacked(packetId: Uuid, sourceDeviceId: PeerId, nackReason: PacketNackReason) {
        markNackedCalls.add(Triple(packetId, sourceDeviceId, nackReason))
        delegate.markNacked(packetId, sourceDeviceId, nackReason)
    }

    override fun getNackReason(packetId: Uuid, sourceDeviceId: PeerId): PacketNackReason? =
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
    override suspend fun getLocalAccountId(): AccountId = error("not used in test")

    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord =
        throw CryptoException.MissingDeviceRecord(deviceId.id)

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

    private val entries = linkedMapOf<Uuid, StoredEntry>()
    val enqueued = mutableListOf<BinaryEnvelope>()
    val markDeliveredCalls = mutableListOf<Uuid>()
    val recordAttemptCalls = mutableListOf<Triple<Uuid, Long, Long>>()
    val setDueForTargetCalls = mutableListOf<Pair<PeerId, Long>>()

    override fun enqueue(envelope: BinaryEnvelope, nextRetryAt: Long, relayMessage: Boolean) {
        val blobSize = envelope.encode().size.toLong()
        entries[envelope.packetId] = StoredEntry(
            envelope = envelope,
            nextRetryAt = nextRetryAt,
            attempts = 0,
            relayMessage = relayMessage,
            expiresAtEpochSeconds = envelope.expiresAtEpochSeconds,
            blobSize = blobSize,
        )
        enqueued.add(envelope)
    }

    override fun markDelivered(packetId: Uuid) {
        markDeliveredCalls.add(packetId)
        entries.remove(packetId)
    }

    override fun setDueForTarget(target: PeerId, nextRetryAt: Long) {
        setDueForTargetCalls.add(target to nextRetryAt)
        for (entry in entries.values) {
            if (entry.envelope.target == target && entry.nextRetryAt > nextRetryAt) {
                entry.nextRetryAt = nextRetryAt
            }
        }
    }

    override fun recordAttempt(packetId: Uuid, nextRetryAt: Long, now: Long) {
        recordAttemptCalls.add(Triple(packetId, nextRetryAt, now))
        val entry = entries[packetId] ?: return
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
                .minWithOrNull(compareBy<StoredEntry> { it.expiresAtEpochSeconds }.thenBy { it.envelope.packetId })
                ?: break
            entries.remove(victim.envelope.packetId)
            evicted++
        }
        return evicted
    }

    fun contains(packetId: Uuid): Boolean = entries.containsKey(packetId)

    fun getNextRetryAt(packetId: Uuid): Long? = entries[packetId]?.nextRetryAt

    fun getAttempts(packetId: Uuid): Long = entries[packetId]?.attempts ?: 0L

    private fun StoredEntry.toOutboxEntry(): OutboxEntry =
        OutboxEntry(
            packetId = envelope.packetId,
            envelope = envelope,
            nextRetryAt = nextRetryAt,
            attempts = attempts,
        )
}

internal class E2eeIdentityResolverForRouter(
    private val local: TestPeerIdentity,
    private val peers: Map<PeerId, TestPeerIdentity>,
    private val peersByAccount: Map<AccountId, List<PeerId>> = emptyMap(),
    private val torByPeer: MutableMap<PeerId, TorEndpoint> = mutableMapOf(),
    val torUpdates: MutableList<Pair<PeerId, TorEndpoint>> = mutableListOf(),
    private val crypto: CryptoProvider = KmpCryptoProvider(),
) : IdentityResolver {

    override suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = local.device

    override suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord =
        error("E2eeIdentityResolverForRouter: account record not stubbed")

    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray =
        when (purpose) {
            IdentityKeyPurpose.SIGNING -> local.signingPrivateKey
            IdentityKeyPurpose.ENCRYPTION -> local.encryptionPrivateKey
            else -> error("unexpected purpose $purpose")
        }

    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray =
        error("E2eeIdentityResolverForRouter: account private key not stubbed")

    override suspend fun getLocalDeviceId(): PeerId = local.device.deviceId
    override suspend fun getLocalAccountId(): AccountId = error("not used in test")

    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord =
        peers[deviceId]?.device ?: throw CryptoException.MissingDeviceRecord(deviceId.id)

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
    ): X3dhRemotePeerKeys {
        val device = resolvePeerIdentityRecord(deviceId)
        val signedPreKey = when {
            signedPreKeyId != null -> {
                device.signedPreKey?.takeIf { it.keyId == signedPreKeyId }
                    ?: error("Signed prekey not found: $signedPreKeyId")
            }
            else -> device.signedPreKey
                ?: error("Missing signed prekey on roster for deviceId=$deviceId")
        }
        require(crypto.verifyDetached(device.signing.publicKey, signedPreKey.publicKey, signedPreKey.signature)) {
            "failed to verify signed prekey signature"
        }
        return X3dhRemotePeerKeys(
            identityEncryptionPublicKey = device.encryption.publicKey,
            signedPreKeyPublicKey = signedPreKey.publicKey,
            signedPreKeyId = signedPreKey.keyId,
        )
    }

    override suspend fun getCurrentLocalSignedPreKey(): SignedPreKeyRecord = local.signedPreKey

    override suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): SignedPreKeyRecord {
        require(signedPreKeyId == local.signedPreKey.keyId) {
            "Signed prekey not found: $signedPreKeyId"
        }
        return local.signedPreKey
    }
}

internal data class E2eeRouterTestStack(
    val peer: TestPeerIdentity,
    val identity: E2eeIdentityResolverForRouter,
    val protection: DefaultEnvelopeProtectionService,
)

internal fun buildE2eeRouterStack(
    local: TestPeerIdentity,
    remote: TestPeerIdentity,
    peersByAccount: Map<AccountId, List<PeerId>>,
    torByPeer: MutableMap<PeerId, TorEndpoint>,
    time: EpochSecondsProvider = FixedEpochSecondsProvider(10_000L),
    crypto: CryptoProvider = KmpCryptoProvider(),
): E2eeRouterTestStack {
    val identity = E2eeIdentityResolverForRouter(
        local = local,
        peers = mapOf(remote.device.deviceId to remote),
        peersByAccount = peersByAccount,
        torByPeer = torByPeer,
        crypto = crypto,
    )
    val sessionManager = DefaultCryptoSessionManager(
        crypto = crypto,
        x3dh = X3dhHandshake(crypto),
        sessionStore = MapBackedCryptoSessionStore(),
        identityResolver = identity,
        opkRepository = InMemoryOpkRepository(crypto),
        timeProvider = time,
    )
    val signatureProvider = DefaultSignatureProvider(identity, crypto)
    val protection = DefaultEnvelopeProtectionService(
        webRtcSignalProtection = SignedWebRtcSignalProtection(signatureProvider, crypto),
        fileProtection = PassthroughFileProtection(),
        messageProtection = SignedAndEncryptedMessageProtection(signatureProvider, sessionManager, crypto),
        systemProtection = SignedSystemProtection(signatureProvider, crypto),
    )
    return E2eeRouterTestStack(
        peer = local,
        identity = identity,
        protection = protection,
    )
}

internal fun e2eeRouterUnderTest(
    stack: E2eeRouterTestStack,
    tor: RecordingTorTransport,
    webRtc: RecordingWebRtcTransport = RecordingWebRtcTransport(),
    dedup: PacketDeduplicator = InMemoryPacketDeduplicator(),
    outbox: PacketOutbox = TrackingPacketOutbox(),
    time: EpochSecondsProvider = FixedEpochSecondsProvider(10_000L),
    routerConfig: RouterConfig = RouterConfig(),
): DefaultRouter =
    DefaultRouter(
        torTransport = tor,
        webRtcTransport = webRtc,
        identityResolver = stack.identity,
        packetDeduplicator = dedup,
        packetOutbox = outbox,
        envelopeProtectionService = stack.protection,
        timeProvider = time,
        logger = NoopAppLogger,
        routerConfig = routerConfig,
    )

internal fun outboxProcessorUnderTest(
    tor: TorTransport = RecordingTorTransport(),
    webRtc: RecordingWebRtcTransport = RecordingWebRtcTransport(),
    identity: FakeIdentityResolverForRouter,
    outbox: PacketOutbox = TrackingPacketOutbox(),
    time: EpochSecondsProvider = FixedEpochSecondsProvider(10_000L),
    routerConfig: RouterConfig = RouterConfig(),
): OutboxProcessor {
    val ctx =
        RoutingContext(
            identityResolver = identity,
            packetDeduplicator = InMemoryPacketDeduplicator(),
            envelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
            torTransport = tor,
            webRtcTransport = webRtc,
            timeProvider = time,
            logger = NoopAppLogger,
            routerConfig = routerConfig,
        )
    return OutboxProcessor(
        ctx = ctx,
        dispatcher = EnvelopeDispatcher(ctx),
        transportPolicy = SessionOrTorPolicy(routerConfig),
        packetOutbox = outbox,
        maxIdlePollSeconds = routerConfig.outboxMaxIdlePollSeconds,
    )
}

internal fun defaultRouterUnderTest(
    tor: TorTransport = RecordingTorTransport(),
    webRtc: RecordingWebRtcTransport = RecordingWebRtcTransport(),
    identity: FakeIdentityResolverForRouter,
    dedup: PacketDeduplicator = InMemoryPacketDeduplicator(),
    outbox: PacketOutbox = TrackingPacketOutbox(),
    time: EpochSecondsProvider = FixedEpochSecondsProvider(10_000L),
    routerConfig: RouterConfig = RouterConfig(),
    envelopeProtectionService: EnvelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
): DefaultRouter =
    DefaultRouter(
        torTransport = tor,
        webRtcTransport = webRtc,
        identityResolver = identity,
        packetDeduplicator = dedup,
        packetOutbox = outbox,
        envelopeProtectionService = envelopeProtectionService,
        timeProvider = time,
        logger = NoopAppLogger,
        routerConfig = routerConfig,
    )
