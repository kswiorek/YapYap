package org.yapyap.backend.routing

import org.yapyap.backend.crypto.AccountId
import org.yapyap.backend.crypto.AccountIdentityRecord
import org.yapyap.backend.crypto.CryptoProvider
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityPublicKeyRecord
import org.yapyap.backend.crypto.IdentityResolver
import org.yapyap.backend.crypto.SigningKeyPair
import org.yapyap.backend.crypto.EncryptionKeyPair
import org.yapyap.backend.db.PacketDeduplicator
import org.yapyap.backend.db.PacketIdAllocator
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protection.EnvelopeProtectContext
import org.yapyap.backend.protection.EnvelopeProtectionService
import org.yapyap.backend.protocol.FileChunk
import org.yapyap.backend.protocol.FileEnvelope
import org.yapyap.backend.protocol.FilePayload
import org.yapyap.backend.protocol.MessageEnvelope
import org.yapyap.backend.protocol.MessagePayload
import org.yapyap.backend.protocol.OpenedFileEnvelope
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.time.EpochSecondsProvider
import org.yapyap.backend.transport.webrtc.WebRtcSignalEnvelope
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal
import org.yapyap.backend.transport.RecordingTorTransport
import org.yapyap.backend.transport.RecordingWebRtcTransport

internal class FixedEpochSecondsProvider(private val fixed: Long) : EpochSecondsProvider {
    override fun nowEpochSeconds(): Long = fixed
}

/** Minimal crypto surface used by [DefaultRouter] in tests. */
internal class StubCryptoProvider(
    private val nonceFill: Byte = 1,
) : CryptoProvider {
    override fun sha256(bytes: ByteArray): ByteArray = ByteArray(32) { (it + bytes.size).toByte() }

    override fun randomBytes(size: Int): ByteArray = ByteArray(size) { 3 }

    override fun generateSigningKeyPair(): SigningKeyPair =
        SigningKeyPair(publicKey = byteArrayOf(9), privateKey = byteArrayOf(8))

    override fun generateEncryptionKeyPair(): EncryptionKeyPair =
        EncryptionKeyPair(publicKey = byteArrayOf(7), privateKey = byteArrayOf(6))

    override fun signDetached(privateSigningKey: ByteArray, message: ByteArray): ByteArray =
        byteArrayOf(4, 5, 6)

    override fun verifyDetached(publicSigningKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        true

    override fun generateNonce(scheme: SignalSecurityScheme): ByteArray =
        ByteArray(scheme.nonceSize) { nonceFill }
}

/**
 * Pass-through message protection for routing tests (no real signatures).
 * Signal/file paths are minimal stubs for interface completeness.
 */
internal class PassthroughFakeEnvelopeProtectionService : EnvelopeProtectionService {

    override fun protectSignal(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope =
        WebRtcSignalEnvelope(
            sessionId = input.sessionId,
            kind = input.kind,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = context.nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            protectedPayload = input.payload,
        )

    override fun openSignal(envelope: WebRtcSignalEnvelope): WebRtcSignal =
        WebRtcSignal(
            sessionId = envelope.sessionId,
            kind = envelope.kind,
            source = envelope.source,
            target = envelope.target,
            payload = envelope.protectedPayload,
        )

    override fun protectFile(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope =
        error("not used in router transport tests")

    override fun openFile(envelope: FileEnvelope): OpenedFileEnvelope =
        error("not used in router transport tests")

    override fun decryptFileChunk(chunk: FilePayload.EncryptedChunk): FileChunk =
        error("not used in router transport tests")

    override fun protectMessage(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope {
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
            nonce = context.nonce,
            securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            signature = null,
            payload = input,
        )
    }

    override fun openMessage(envelope: MessageEnvelope): MessagePayload = envelope.decodePayload()
}

internal class SequencedPacketIdAllocator : PacketIdAllocator {
    private var localPeer: PeerId? = null
    private var seq = 0L

    override fun assignLocalDevice(deviceId: PeerId) {
        localPeer = deviceId
    }

    override fun allocate(): PacketId {
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

    override fun firstSeen(packetId: PacketId, sourceDeviceId: PeerId, receivedAtEpochSeconds: Long): Boolean {
        val key = sourceDeviceId.id to packetId.toHex()
        return seen.add(key)
    }

    override fun prune(receivedBeforeEpochSeconds: Long) {
        // No-op for router contract tests
    }
}

internal class RecordingPacketDeduplicator(private val delegate: PacketDeduplicator) : PacketDeduplicator {
    val firstSeenCalls = mutableListOf<Triple<PacketId, PeerId, Long>>()
    val firstSeenResults = mutableListOf<Boolean>()

    override fun firstSeen(packetId: PacketId, sourceDeviceId: PeerId, receivedAtEpochSeconds: Long): Boolean {
        firstSeenCalls.add(Triple(packetId, sourceDeviceId, receivedAtEpochSeconds))
        val result = delegate.firstSeen(packetId, sourceDeviceId, receivedAtEpochSeconds)
        firstSeenResults.add(result)
        return result
    }

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

    override fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = localDevice

    override fun getLocalAccountIdentityRecord(): AccountIdentityRecord =
        error("FakeIdentityResolverForRouter: account record not stubbed")

    override fun loadLocalPrivateKey(purpose: IdentityKeyPurpose): ByteArray =
        error("FakeIdentityResolverForRouter: private key not stubbed")

    override fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord? = null

    override fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint =
        torByPeer[deviceId] ?: TorEndpoint(onionAddress = "missing.onion", port = 80)

    override fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        peersByAccount[accountId].orEmpty()

    override fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) {
        torUpdates.add(deviceId to torEndpoint)
        torByPeer[deviceId] = torEndpoint
    }
}

internal fun defaultRouterUnderTest(
    tor: RecordingTorTransport = RecordingTorTransport(),
    webRtc: RecordingWebRtcTransport = RecordingWebRtcTransport(),
    identity: FakeIdentityResolverForRouter,
    dedup: PacketDeduplicator = InMemoryPacketDeduplicator(),
    allocator: PacketIdAllocator = SequencedPacketIdAllocator(),
    time: EpochSecondsProvider = FixedEpochSecondsProvider(10_000L),
): DefaultRouter =
    DefaultRouter(
        torTransport = tor,
        webRtcTransport = webRtc,
        identityResolver = identity,
        packetIdAllocator = allocator,
        packetDeduplicator = dedup,
        envelopeProtectionService = PassthroughFakeEnvelopeProtectionService(),
        timeProvider = time,
        cryptoProvider = StubCryptoProvider(),
        logger = NoopAppLogger,
    )
