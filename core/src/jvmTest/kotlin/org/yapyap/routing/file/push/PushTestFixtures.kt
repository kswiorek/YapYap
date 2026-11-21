package org.yapyap.routing.file.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapEnvelope
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.OpenedFileEnvelope
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.protocol.envelopes.WebRtcSignalEnvelope
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.file.FileIncomingTransfers
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileReadResult
import org.yapyap.routing.policy.NoRelaySelection
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.router.FakeFilePlaintextSource
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TransportLimits
import org.yapyap.routing.store.DefaultFileIncomingTransferStore
import org.yapyap.routing.store.DefaultFilePushStore
import org.yapyap.routing.store.DefaultFileRelayStore
import org.yapyap.routing.store.FilePushStatus
import org.yapyap.routing.store.FilePushStore
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.RelayBackup
import org.yapyap.routing.store.RetryClass
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

internal val fixtureFileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
internal val fixtureAccount = AccountId("file-push-account")
internal val localPeerId = PeerId("pusherlocaldeviceaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
internal val peerA = PeerId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
internal val peerB = PeerId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
internal val relay1 = PeerId("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
internal val relay2 = PeerId("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")

private fun deviceRecord(id: PeerId): DeviceIdentity = DeviceIdentity(
    deviceId = id,
    signingKey = SigningPublicKey(byteArrayOf(1)),
    encryptionKey = EncryptionPublicKey(byteArrayOf(2)),
)

/** Deterministic file protection: identity chunk AEAD, encoded sealed halves, fixed keys. */
internal class RecordingFileProtectionService(
    var nextKeyByte: Byte = 0,
) : EnvelopeProtectionService {
    val sealedOffers = mutableListOf<FilePayload.SealedOffer>()
    var encryptChunkCalls = 0

    override suspend fun protectSignal(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope =
        error("not used in push tests")

    override suspend fun openSignal(envelope: WebRtcSignalEnvelope): WebRtcSignal =
        error("not used in push tests")

    override suspend fun protectFile(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope =
        FileEnvelope(
            transferId = requireNotNull(context.transferId),
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAt = context.createdAt,
            nonce = ByteArray(24),
            securityScheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            signature = byteArrayOf(9),
            payload = input.encode(),
        )

    override suspend fun openFile(envelope: FileEnvelope): OpenedFileEnvelope =
        error("not used in push tests")

    override suspend fun decryptFileChunk(
        transferId: TransferId,
        chunkIndex: Int,
        key: ByteArray,
        chunk: FilePayload.EncryptedChunk,
    ): ByteArray = error("not used in push tests")

    override suspend fun encryptFileChunk(
        transferId: TransferId,
        chunkIndex: Int,
        key: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        encryptChunkCalls += 1
        return plaintext
    }

    override suspend fun sealFileOffer(offer: FilePayload.SealedOffer, target: PeerId): ByteArray {
        sealedOffers += offer
        return offer.encode()
    }

    override suspend fun openFileOffer(sealed: ByteArray, source: PeerId): FilePayload.SealedOffer =
        error("not used in push tests")

    override fun generateFileTransferKey(): ByteArray = ByteArray(32) { nextKeyByte++ }

    override suspend fun protectMessage(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope =
        error("not used in push tests")

    override suspend fun openMessage(envelope: MessageEnvelope): MessagePayload =
        error("not used in push tests")

    override suspend fun protectSystem(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope =
        error("not used in push tests")

    override suspend fun openSystem(envelope: SystemEnvelope): SystemPayload =
        error("not used in push tests")

    override suspend fun protectBootstrap(
        input: BootstrapPayload,
        context: EnvelopeProtectContext,
        sharedSecret: ByteArray?,
    ): BootstrapEnvelope = error("not used in push tests")

    override suspend fun openBootstrap(envelope: BootstrapEnvelope): BootstrapPayload =
        error("not used in push tests")

    override suspend fun isBootstrapSessionActive(): Boolean = false
}

internal data class PushHarness(
    val engine: FilePushEngine,
    val sender: FileSender,
    val plaintext: FakeFilePlaintextSource,
    val protection: RecordingFileProtectionService,
    val pushes: FilePushStore,
    val sent: MutableList<Pair<BinaryEnvelope, OutboxTag?>>,
    val packetIndex: MutableMap<Uuid, Int>,
    val fileEvents: MutableSharedFlow<FileEvent>,
    val clock: FakeClock,
    val bytes: ByteArray,
    val totalBytes: Long,
    val chunkSize: Int,
    val ctx: RoutingContext,
)

internal fun pushHarness(
    totalBytes: Long = 1024L * 1024,
    chunkSize: Int = 220 * 1024,
    now: Long = 10_000L,
    relaySelection: RelaySelectionPolicy = NoRelaySelection,
): PushHarness {
    val bytes = ByteArray(totalBytes.toInt()) { (it * 7).toByte() }
    val local = deviceRecord(localPeerId)
    val identity = FakeIdentityResolverForRouter(
        localDevice = local,
        peersByAccount = mapOf(fixtureAccount to listOf(peerA, peerB)),
    )
    val protection = RecordingFileProtectionService()
    val plaintext = FakeFilePlaintextSource()
    plaintext.describeImpl = { _ ->
        FileManifest(
            fileId = fixtureFileId,
            totalBytes = totalBytes,
            blockSize = 1024 * 1024,
            blockHashes = listOf(ByteArray(32) { 7 }),
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
        )
    }
    plaintext.readImpl = { _, offset, max ->
        val end = minOf(offset + max, totalBytes)
        if (offset >= end) FileReadResult.Ok(byteArrayOf(), eof = true)
        else FileReadResult.Ok(bytes.copyOfRange(offset.toInt(), end.toInt()), eof = end == totalBytes)
    }
    // Real SQL store on in-memory SQLite: restart recovery, the deposit claim and the
    // chunk index all rely on store atomicity, not on fake semantics.
    val pushes = DefaultFilePushStore(openMemoryDatabase().database, Dispatchers.Unconfined)
    val sent = mutableListOf<Pair<BinaryEnvelope, OutboxTag?>>()
    val packetIndex = mutableMapOf<Uuid, Int>()
    val fileEvents = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
    val clock = FakeClock(epochSeconds(now))
    val ctx = RoutingContext(
        localIdentity = identity,
        localDeviceId = localPeerId,
        devices = identity,
        routes = identity,
        packetDeduplicator = InMemoryPacketDeduplicator(),
        envelopeProtectionService = protection,
        torTransport = RecordingTorTransport(),
        webRtcTransport = RecordingWebRtcTransport(),
        clock = clock,
        routerConfig = MutableStateFlow(RouterConfig()),
        transportLimits = MutableStateFlow(TransportLimits(4 * 1024 * 1024, 250 * 1024)),
    )
    val outbox = object : FileOutbox {
        override suspend fun enqueue(
            envelope: BinaryEnvelope,
            tag: OutboxTag,
            relayBackup: RelayBackup,
            retryClass: RetryClass
        ) {
            sent += envelope to tag
            // Recover the chunk index the same way the actor records it.
            val decoded = FileEnvelope.decode(envelope.payload).decodePayload()
            if (decoded is FilePayload.EncryptedChunk) packetIndex[envelope.packetId] = decoded.chunkIndex
        }

        override suspend fun enqueueDirect(envelope: BinaryEnvelope) {
            sent += envelope to null
        }

        override suspend fun deleteByTag(tag: OutboxTag) {
            sent.removeAll { it.second == tag }
        }

        override suspend fun packetIdsByTag(tag: OutboxTag): List<Uuid> =
            sent.mapNotNull { (env, t) -> if (t == tag) env.packetId else null }
    }
    val engine = FilePushEngine(
        ctx = ctx,
        plaintext = plaintext,
        pushes = pushes,
        outbox = outbox,
        events = fileEvents,
        relaySelection = relaySelection,
        relay = DefaultFileRelayStore(openMemoryDatabase().database, Dispatchers.Unconfined),
        incoming = testIncomingTransfers(clock),
    )
    val sender = FileSender(ctx, plaintext, pushes, engine, fileEvents, MutableStateFlow(chunkSize))
    return PushHarness(
        engine,
        sender,
        plaintext,
        protection,
        pushes,
        sent,
        packetIndex,
        fileEvents,
        clock,
        bytes,
        totalBytes,
        chunkSize,
        ctx
    )
}

internal fun PushHarness.chunkIndices(): List<Int> = sent.mapNotNull { (env, _) ->
    val decoded = FileEnvelope.decode(env.payload).decodePayload()
    (decoded as? FilePayload.EncryptedChunk)?.chunkIndex
}

internal suspend fun PushHarness.transferIdsFor(device: PeerId): List<TransferId> =
    pushes.live().filter { it.targetDevice == device }.map { it.transferId }

internal suspend fun PushHarness.markerOf(transferId: TransferId): Uuid {
    withTimeout(5_000) {
        while (pushes.live().singleOrNull { it.transferId == transferId }?.markerPacketId == null) delay(10)
    }
    return pushes.live().single { it.transferId == transferId }.let { row ->
        requireNotNull(row.markerPacketId)
    }
}

internal suspend fun PushHarness.ackMarker(transferId: TransferId, target: PeerId): OutboxOutcome.Acked =
    OutboxOutcome.Acked(OutboxTag(OutboxTag.Kind.FILE_PUSH, transferId.value), target, markerOf(transferId))

internal suspend fun PushHarness.awaitStatus(transferId: TransferId, vararg want: FilePushStatus) {
    withTimeout(5.seconds) {
        while (pushes.find(transferId)?.status !in want) delay(10)
    }
}

internal suspend fun PushHarness.awaitLive(device: PeerId, count: Int = 1) {
    withTimeout(5.seconds) {
        while (transferIdsFor(device).size < count) delay(10)
    }
}

internal suspend fun PushHarness.awaitMarkers(device: PeerId, count: Int = 1) {
    withTimeout(5_000) {
        while (true) {
            val rows = transferIdsFor(device)
            if (rows.size >= count && rows.all { pushes.find(it)?.markerPacketId != null }) break
            delay(10)
        }
    }
}

internal suspend fun PushHarness.awaitOffers(count: Int) {
    withTimeout(5_000) {
        while (sent.count {
                FileEnvelope.decode(it.first.payload).decodePayload() is FilePayload.Offer
            } < count
        ) delay(10)
    }
}

/** Target-side rows on a real in-memory store, for engines whose tests never receive. */
internal fun testIncomingTransfers(clock: Clock): FileIncomingTransfers =
    FileIncomingTransfers(
        DefaultFileIncomingTransferStore(openMemoryDatabase().database, Dispatchers.Unconfined),
        clock
    )
