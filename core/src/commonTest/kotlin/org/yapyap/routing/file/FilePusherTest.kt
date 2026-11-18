package org.yapyap.routing.file

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.protocol.envelopes.WebRtcSignalEnvelope
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protection.service.EnvelopeProtectionService
import org.yapyap.routing.router.FakeFilePlaintextSource
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.InMemoryFilePushStore
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TransportLimits
import org.yapyap.routing.store.FilePushStatus
import org.yapyap.routing.store.OutboxTag
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

private val fixtureFileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
private val fixtureAccount = AccountId("file-push-account")
private val localPeerId = PeerId("pusherlocaldeviceaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
private val peerA = PeerId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
private val peerB = PeerId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

private fun deviceRecord(id: PeerId): DeviceIdentity = DeviceIdentity(
    deviceId = id,
    signingKey = SigningPublicKey(byteArrayOf(1)),
    encryptionKey = EncryptionPublicKey(byteArrayOf(2)),
)

/** Deterministic file protection: identity chunk AEAD, encoded sealed halves, fixed keys. */
private class RecordingFileProtectionService(
    var nextKeyByte: Byte = 0,
) : EnvelopeProtectionService {
    val sealedOffers = mutableListOf<FilePayload.SealedOffer>()
    val encryptedChunks = mutableListOf<Triple<TransferId, Int, ByteArray>>()

    override suspend fun protectSignal(input: WebRtcSignal, context: EnvelopeProtectContext): WebRtcSignalEnvelope =
        error("not used in pusher tests")

    override suspend fun openSignal(envelope: WebRtcSignalEnvelope): WebRtcSignal =
        error("not used in pusher tests")

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
        error("not used in pusher tests")

    override suspend fun decryptFileChunk(
        transferId: TransferId,
        chunkIndex: Int,
        key: ByteArray,
        chunk: FilePayload.EncryptedChunk,
    ): ByteArray = error("not used in pusher tests")

    override suspend fun encryptFileChunk(
        transferId: TransferId,
        chunkIndex: Int,
        key: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        encryptedChunks += Triple(transferId, chunkIndex, plaintext)
        return plaintext
    }

    override suspend fun sealFileOffer(offer: FilePayload.SealedOffer, target: PeerId): ByteArray {
        sealedOffers += offer
        return offer.encode()
    }

    override suspend fun openFileOffer(sealed: ByteArray, source: PeerId): FilePayload.SealedOffer =
        error("not used in pusher tests")

    override fun generateFileTransferKey(): ByteArray = ByteArray(32) { nextKeyByte++ }

    override suspend fun protectMessage(input: MessagePayload, context: EnvelopeProtectContext): MessageEnvelope =
        error("not used in pusher tests")

    override suspend fun openMessage(envelope: MessageEnvelope): MessagePayload =
        error("not used in pusher tests")

    override suspend fun protectSystem(input: SystemPayload, context: EnvelopeProtectContext): SystemEnvelope =
        error("not used in pusher tests")

    override suspend fun openSystem(envelope: SystemEnvelope): SystemPayload =
        error("not used in pusher tests")

    override suspend fun protectBootstrap(
        input: BootstrapPayload,
        context: EnvelopeProtectContext,
        sharedSecret: ByteArray?,
    ): BootstrapEnvelope = error("not used in pusher tests")

    override suspend fun openBootstrap(envelope: BootstrapEnvelope): BootstrapPayload =
        error("not used in pusher tests")

    override suspend fun isBootstrapSessionActive(): Boolean = false
}

private data class Harness(
    val pusher: FilePusher,
    val plaintext: FakeFilePlaintextSource,
    val protection: RecordingFileProtectionService,
    val pushes: InMemoryFilePushStore,
    val sent: MutableList<Pair<BinaryEnvelope, OutboxTag?>>,
    val fileEvents: MutableSharedFlow<FileEvent>,
    val clock: FakeClock,
    val bytes: ByteArray,
    val totalBytes: Long,
    val chunkSize: Int,
    val now: Long,
)

private fun harness(
    totalBytes: Long = 1024L * 1024,
    chunkSize: Int = 220 * 1024,
    now: Long = 10_000L,
): Harness {
    val bytes = ByteArray(totalBytes.toInt()) { (it * 7).toByte() }
    val local = deviceRecord(localPeerId)
    val identity = FakeIdentityResolverForRouter(
        localDevice = local,
        peersByAccount = mapOf(fixtureAccount to listOf(peerA, peerB)),
    )
    val protection = RecordingFileProtectionService()
    val service = protection
    val plaintext = FakeFilePlaintextSource()
    plaintext.describeImpl = { _ ->
        FileDescriptorInfo(
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
    val pushes = InMemoryFilePushStore()
    val sent = mutableListOf<Pair<BinaryEnvelope, OutboxTag?>>()
    val fileEvents = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
    val clock = FakeClock(epochSeconds(now))
    val ctx = RoutingContext(
        localIdentity = identity,
        localDeviceId = localPeerId,
        devices = identity,
        routes = identity,
        packetDeduplicator = InMemoryPacketDeduplicator(),
        envelopeProtectionService = service,
        torTransport = RecordingTorTransport(),
        webRtcTransport = RecordingWebRtcTransport(),
        clock = clock,
        routerConfig = MutableStateFlow(RouterConfig()),
        transportLimits = MutableStateFlow(TransportLimits(4 * 1024 * 1024, 250 * 1024)),
    )
    val pusher = FilePusher(
        ctx = ctx,
        plaintext = plaintext,
        pushes = pushes,
        outbox = object : FileOutbox {
            var tags = mutableListOf<OutboxTag>()

            override suspend fun enqueue(envelope: BinaryEnvelope, tag: OutboxTag) {
                tags += tag
                sent += envelope to tag
            }

            override suspend fun deleteByTag(tag: OutboxTag) {
                sent.removeAll { it.second == tag }
            }
        },
        events = fileEvents,
        chunkSizeBytes = MutableStateFlow(chunkSize),
    )
    return Harness(pusher, plaintext, protection, pushes, sent, fileEvents, clock, bytes, totalBytes, chunkSize, now)
}

private fun Harness.chunkIndices(): List<Int> = sent.mapNotNull { (env, _) ->
    val decoded = FileEnvelope.decode(env.payload).decodePayload()
    (decoded as? FilePayload.EncryptedChunk)?.chunkIndex
}

private fun Harness.offerFor(transferId: TransferId): FilePayload.Offer {
    val env = sent.map { it.first }.first { FileEnvelope.decode(it.payload).transferId == transferId }
    return FileEnvelope.decode(env.payload).decodePayload() as FilePayload.Offer
}

private suspend fun Harness.markerOf(transferId: TransferId): Uuid =
    pushes.live().single { it.transferId == transferId }.let { row ->
        requireNotNull(row.markerPacketId)
    }

private suspend fun Harness.ackMarker(transferId: TransferId, target: PeerId): OutboxOutcome.Acked =
    OutboxOutcome.Acked(OutboxTag(OutboxTag.Kind.FILE, transferId.value), target, markerOf(transferId))

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FilePusherTest {

    private suspend fun Harness.transferIdsFor(device: PeerId): List<TransferId> =
        pushes.live().filter { it.targetDevice == device }.map { it.transferId }

    @Test
    fun pushFile_fansOutPerDevice_withOwnTransferIdKeyAndMarker() = runTest {
        val h = harness()

        h.pusher.pushFile(fixtureFileId, fixtureAccount)

        val transfersA = h.transferIdsFor(peerA)
        val transfersB = h.transferIdsFor(peerB)
        assertEquals(1, transfersA.size)
        assertEquals(1, transfersB.size)
        assertTrue(transfersA.single() != transfersB.single(), "one transfer per device")
        val rowA = h.pushes.find(transfersA.single())!!
        val rowB = h.pushes.find(transfersB.single())!!
        assertTrue(rowA.key.contentEquals(ByteArray(32) { it.toByte() }))
        assertTrue(rowB.key.contentEquals(ByteArray(32) { (it + 32).toByte() }))
        assertEquals(FilePushStatus.CREATED, rowA.status)
        // The OFFER marker is tagged FILE/id so outcomes route back here.
        val markers = h.sent.filter { FileEnvelope.decode(it.first.payload).decodePayload() is FilePayload.Offer }
        assertEquals(2, markers.size)
        assertEquals(
            setOf(OutboxTag(OutboxTag.Kind.FILE, transfersA.single().value), OutboxTag(OutboxTag.Kind.FILE, transfersB.single().value)),
            markers.map { it.second }.toSet(),
        )
        assertNotNull(rowA.markerPacketId)
        // Sealed half carries this transfer's key and file identity.
        assertEquals(2, h.protection.sealedOffers.size)
    }

    @Test
    fun markerAck_startsWindow() = runTest {
        val h = harness(totalBytes = 1024L * 1024, chunkSize = 220 * 1024)
        h.pusher.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        val before = h.sent.size

        h.pusher.onOutboxOutcome(h.ackMarker(transfer, peerA))

        val row = h.pushes.find(transfer)!!
        assertEquals(FilePushStatus.IN_FLIGHT, row.status)
        // Window of maxInFlightChunks=16, file has ceil(1MiB / 220KiB) = 5 chunks.
        val indices = h.chunkIndices()
        assertEquals(listOf(0, 1, 2, 3, 4), indices)
        assertEquals(before + 5, h.sent.size)
        assertEquals(5, h.pushes.find(transfer)!!.nextChunkIndex)
    }

    @Test
    fun coverageAck_advancesResumePoint() = runTest {
        val h = harness()
        h.pusher.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        h.pusher.onOutboxOutcome(h.ackMarker(transfer, peerA))

        h.pusher.onCoverageAck(transfer, contiguousChunks = 2, missingRanges = emptyList())

        val row = h.pushes.find(transfer)!!
        assertEquals(2, row.contiguousAcked)
    }

    @Test
    fun restart_resumesWithoutResendingAckedChunks() = runTest {
        // 31 chunks, window 16: pre-restart pumps 0..15, coverage confirms 0..1.
        val h = harness(totalBytes = 3L * 1024 * 1024, chunkSize = 100 * 1024)
        h.pusher.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerB).single()
        h.pusher.onOutboxOutcome(h.ackMarker(transfer, peerB))
        h.pusher.onCoverageAck(transfer, contiguousChunks = 1, missingRanges = emptyList())

        // Restart: new pusher over the same store; outbox rows are gone with the process.
        val h2 = harness(totalBytes = 3L * 1024 * 1024, chunkSize = 100 * 1024)
        for (row in h.pushes.live()) h2.pushes.insert(row)
        h2.pusher.start()

        val indices = h2.chunkIndices()
        assertEquals(16, indices.size, "window refills from the resume point")
        assertEquals(2, indices.min(), "resume starts past the ACKed prefix, never resends it")
        assertTrue(indices.all { it >= 2 })
    }

    @Test
    fun targetDeclined_onMarker_isTerminal() = runTest {
        val h = harness()
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch { h.fileEvents.collect { seen += it } }
        runCurrent()
        h.pusher.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()

        h.pusher.onOutboxOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE, transfer.value),
                peerA,
                h.markerOf(transfer),
                PacketNackReason.DECLINED,
            ),
        )
        runCurrent()

        assertEquals(FilePushStatus.DECLINED, h.pushes.find(transfer)?.status)
        assertTrue(h.sent.none { FileEnvelope.decode(it.first.payload).transferId == transfer }, "rows dropped")
        val failed = seen.filterIsInstance<FileEvent.Failed>().single()
        assertEquals(FileFailureReason.DECLINED, failed.reason)
    }

    @Test
    fun declinedChunk_endsTransferQuietly() = runTest {
        val h = harness()
        h.pusher.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        h.pusher.onOutboxOutcome(h.ackMarker(transfer, peerA))
        val chunkPacket = h.sent.map { it.first }.first { env ->
            val decoded = FileEnvelope.decode(env.payload).decodePayload()
            decoded is FilePayload.EncryptedChunk &&
                FileEnvelope.decode(env.payload).transferId == transfer
        }.packetId

        h.pusher.onOutboxOutcome(
            OutboxOutcome.Nacked(OutboxTag(OutboxTag.Kind.FILE, transfer.value), peerA, chunkPacket, PacketNackReason.DECLINED),
        )

        // ENDED, never DECLINED (§3.6): the transfer is gone at the target, outcome unknown.
        assertEquals(FilePushStatus.ENDED, h.pushes.find(transfer)?.status)
        assertTrue(h.sent.none { FileEnvelope.decode(it.first.payload).transferId == transfer })
    }

    @Test
    fun otherTerminalNack_endsTransfer() = runTest {
        val h = harness()
        h.pusher.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        h.pusher.onOutboxOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE, transfer.value),
                peerA,
                h.markerOf(transfer),
                PacketNackReason.PERMANENT_PROTECTION_FAILED,
            ),
        )
        assertEquals(FilePushStatus.ENDED, h.pushes.find(transfer)?.status)
    }

    @Test
    fun expiredMarker_reArmsWhileLive() = runTest {
        val h = harness()
        h.pusher.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        val oldMarker = h.markerOf(transfer)
        val countBefore = h.sent.size

        h.pusher.onOutboxOutcome(
            OutboxOutcome.Expired(OutboxTag(OutboxTag.Kind.FILE, transfer.value), peerA, oldMarker),
        )

        val row = h.pushes.find(transfer)!!
        assertEquals(FilePushStatus.CREATED, row.status)
        assertNotEquals(oldMarker, row.markerPacketId)
        assertEquals(countBefore + 1, h.sent.size)
    }

    @Test
    fun expiredTransfer_isTerminal() = runTest {
        val h = harness()
        h.pusher.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        h.clock.advanceTo(epochSeconds(10_000L) + 3.days)
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch { h.fileEvents.collect { seen += it } }
        runCurrent()

        h.pusher.onOutboxOutcome(
            OutboxOutcome.Expired(OutboxTag(OutboxTag.Kind.FILE, transfer.value), peerA, h.markerOf(transfer)),
        )
        runCurrent()

        assertEquals(FilePushStatus.EXPIRED, h.pushes.find(transfer)?.status)
        assertEquals<List<FileEvent>>(listOf(FileEvent.Failed(fixtureFileId, fixtureAccount, FileFailureReason.EXPIRED)), seen)
    }

    @Test
    fun complete_resolvesAndDropsRows() = runTest {
        val h = harness()
        h.pusher.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        h.pusher.onOutboxOutcome(h.ackMarker(transfer, peerA))

        h.pusher.onTransferComplete(transfer)

        assertEquals(FilePushStatus.COMPLETED, h.pushes.find(transfer)?.status)
        assertTrue(h.sent.none { FileEnvelope.decode(it.first.payload).transferId == transfer })
    }

    @Test
    fun cancelFile_cancelsLiveTransfers() = runTest {
        val h = harness()
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch { h.fileEvents.collect { seen += it } }
        runCurrent()
        h.pusher.pushFile(fixtureFileId, fixtureAccount)

        h.pusher.cancelFile(fixtureFileId, fixtureAccount)
        runCurrent()

        assertTrue(h.pushes.live().none { it.fileId == fixtureFileId })
        assertEquals(
            setOf(FileFailureReason.CANCELLED),
            seen.filterIsInstance<FileEvent.Failed>().map { it.reason }.toSet(),
        )
    }

    @Test
    fun unknownTransfer_outcomeIgnored() = runTest {
        val h = harness()
        h.pusher.onOutboxOutcome(
            OutboxOutcome.Acked(OutboxTag(OutboxTag.Kind.FILE, Uuid.random()), peerA, Uuid.random()),
        )
        h.pusher.onTransferComplete(TransferId(Uuid.random()))
        // No rows, no crash.
        assertTrue(h.pushes.live().isEmpty())
    }
}
