package org.yapyap.routing.file

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.OpenedFileEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.protocol.envelopes.WebRtcSignalEnvelope
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileFailureReason
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileReadResult
import org.yapyap.routing.policy.NoRelaySelection
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.router.FakeFilePlaintextSource
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.InMemoryFilePushStore
import org.yapyap.routing.router.InMemoryPacketDeduplicator
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TransportLimits
import org.yapyap.routing.store.FilePushKind
import org.yapyap.routing.store.FilePushStatus
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.RelayBackup
import org.yapyap.routing.store.RetryClass
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.session.SessionPhase
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val fixtureFileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
private val fixtureAccount = AccountId("file-push-account")
private val localPeerId = PeerId("pusherlocaldeviceaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
private val peerA = PeerId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
private val peerB = PeerId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
private val relay1 = PeerId("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
private val relay2 = PeerId("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")

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
    val backups: MutableMap<Uuid, RelayBackup>,
    val fileEvents: MutableSharedFlow<FileEvent>,
    val clock: FakeClock,
    val tor: RecordingTorTransport,
    val webRtc: RecordingWebRtcTransport,
    val bytes: ByteArray,
    val totalBytes: Long,
    val chunkSize: Int,
    val now: Long,
)

private fun harness(
    totalBytes: Long = 1024L * 1024,
    chunkSize: Int = 220 * 1024,
    now: Long = 10_000L,
    relaySelection: RelaySelectionPolicy = NoRelaySelection,
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
    val pushes = InMemoryFilePushStore()
    val sent = mutableListOf<Pair<BinaryEnvelope, OutboxTag?>>()
    val backups = mutableMapOf<Uuid, RelayBackup>()
    val fileEvents = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
    val clock = FakeClock(epochSeconds(now))
    val tor = RecordingTorTransport()
    val webRtc = RecordingWebRtcTransport()
    val ctx = RoutingContext(
        localIdentity = identity,
        localDeviceId = localPeerId,
        devices = identity,
        routes = identity,
        packetDeduplicator = InMemoryPacketDeduplicator(),
        envelopeProtectionService = service,
        torTransport = tor,
        webRtcTransport = webRtc,
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

            override suspend fun enqueue(
                envelope: BinaryEnvelope,
                tag: OutboxTag,
                relayBackup: RelayBackup,
                retryClass: RetryClass
            ) {
                tags += tag
                backups[envelope.packetId] = relayBackup
                sent += envelope to tag
            }

            override suspend fun enqueueDirect(envelope: BinaryEnvelope) {
                sent += envelope to null
            }

            override suspend fun deleteByTag(tag: OutboxTag) {
                sent.removeAll { it.second == tag }
            }

            override suspend fun hasQueued(tag: OutboxTag): Boolean =
                sent.any { it.second == tag }

            override suspend fun isPacketQueued(packetId: Uuid): Boolean =
                sent.any { it.first.packetId == packetId }
        },
        events = fileEvents,
        chunkSizeBytes = MutableStateFlow(chunkSize),
        relaySelection = relaySelection,
    )
    return Harness(
        pusher,
        plaintext,
        protection,
        pushes,
        sent,
        backups,
        fileEvents,
        clock,
        tor,
        webRtc,
        bytes,
        totalBytes,
        chunkSize,
        now
    )
}

private fun Harness.chunkIndices(): List<Int> = sent.mapNotNull { (env, _) ->
    val decoded = FileEnvelope.decode(env.payload).decodePayload()
    (decoded as? FilePayload.EncryptedChunk)?.chunkIndex
}

private suspend fun Harness.markerOf(transferId: TransferId): Uuid =
    pushes.live().single { it.transferId == transferId }.let { row ->
        requireNotNull(row.markerPacketId)
    }

private suspend fun Harness.ackMarker(transferId: TransferId, target: PeerId): OutboxOutcome.Acked =
    OutboxOutcome.Acked(OutboxTag(OutboxTag.Kind.FILE_PUSH, transferId.value), target, markerOf(transferId))

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
            setOf(
                OutboxTag(OutboxTag.Kind.FILE_PUSH, transfersA.single().value),
                OutboxTag(OutboxTag.Kind.FILE_PUSH, transfersB.single().value)
            ),
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
                OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer.value),
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
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer.value),
                peerA,
                chunkPacket,
                PacketNackReason.DECLINED
            ),
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
                OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer.value),
                peerA,
                h.markerOf(transfer),
                PacketNackReason.PERMANENT_PROTECTION_FAILED,
            ),
        )
        assertEquals(FilePushStatus.ENDED, h.pushes.find(transfer)?.status)
    }

    @Test
    fun endedTransfer_isReportedAsEnded_neverFailed() = runTest {
        val h = harness()
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch { h.fileEvents.collect { seen += it } }
        runCurrent()
        h.pusher.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        h.pusher.onOutboxOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer.value),
                peerA,
                h.markerOf(transfer),
                PacketNackReason.PERMANENT_PROTECTION_FAILED,
            ),
        )
        runCurrent()
        assertEquals(
            listOf(FileFailureReason.ENDED),
            seen.filterIsInstance<FileEvent.Failed>().map { it.reason },
        )
    }

    @Test
    fun targetCancel_keepsItsReason() = runTest {
        val expected = mapOf(
            FileCancelReason.DECLINED to (FilePushStatus.DECLINED to FileFailureReason.DECLINED),
            FileCancelReason.NO_SPACE to (FilePushStatus.DECLINED to FileFailureReason.DECLINED),
            FileCancelReason.ABORT to (FilePushStatus.ENDED to FileFailureReason.ENDED),
        )
        for ((cancelReason, outcome) in expected) {
            val h = harness()
            val seen = mutableListOf<FileEvent>()
            val collector = launch { h.fileEvents.collect { seen += it } }
            runCurrent()
            h.pusher.pushFile(fixtureFileId, fixtureAccount)
            val transfer = h.transferIdsFor(peerA).single()

            h.pusher.onTransferCancelled(transfer, cancelReason)
            runCurrent()

            assertEquals(outcome.first, h.pushes.find(transfer)?.status, "$cancelReason")
            assertEquals(
                listOf(outcome.second),
                seen.filterIsInstance<FileEvent.Failed>().map { it.reason },
                "$cancelReason",
            )
            collector.cancel()
        }
    }

    @Test
    fun expiredMarker_reArmsWhileLive() = runTest {
        val h = harness()
        h.pusher.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        val oldMarker = h.markerOf(transfer)
        val countBefore = h.sent.size

        h.pusher.onOutboxOutcome(
            OutboxOutcome.Expired(OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer.value), peerA, oldMarker),
        )

        val row = h.pushes.find(transfer)!!
        assertEquals(FilePushStatus.CREATED, row.status)
        assertNotEquals(oldMarker, row.markerPacketId)
        assertEquals(countBefore + 1, h.sent.size)
    }

    @Test
    fun serve_startsFreshTransferAtFloorAlignedStart() = runTest {
        // 5 chunks of 220 KiB; a request from byte 300_000 lands in chunk 1.
        val h = harness(totalBytes = 1024L * 1024, chunkSize = 220 * 1024)
        assertTrue(h.pusher.serveFile(fixtureFileId, peerA, fromOffset = 300_000L))

        val row = h.pushes.live().single()
        assertEquals(FilePushKind.SERVE, row.kind)
        assertEquals(1, row.startIndex)
        assertEquals(1, row.nextChunkIndex)
        assertEquals(0, row.contiguousAcked)
        val offer = h.sent.mapNotNull { (env, _) ->
            FileEnvelope.decode(env.payload).decodePayload() as? FilePayload.Offer
        }.single()
        assertEquals(1, offer.startIndex)
        assertEquals(FilePushStatus.CREATED, row.status)
    }

    @Test
    fun serve_unknownFile_returnsFalseWithoutRow() = runTest {
        val h = harness()
        h.plaintext.describeImpl = { null }
        assertFalse(h.pusher.serveFile(fixtureFileId, peerA, fromOffset = 0L))
        assertTrue(h.pushes.live().isEmpty())
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun serve_supersedesLivePushWithCancel() = runTest {
        val h = harness()
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch { h.fileEvents.collect { seen += it } }
        runCurrent()
        h.pusher.pushFile(fixtureFileId, fixtureAccount)
        val pushed = h.transferIdsFor(peerA).single()

        assertTrue(h.pusher.serveFile(fixtureFileId, peerA, fromOffset = 0L))
        runCurrent()

        // Old transfer is CANCELled, its rows gone, and no failure is reported.
        assertEquals(FilePushStatus.CANCELLED, h.pushes.find(pushed)?.status)
        assertTrue(seen.none { it is FileEvent.Failed })
        val cancels = h.sent.mapNotNull { (env, tag) ->
            if (tag == null) FileEnvelope.decode(env.payload).decodePayload() as? FilePayload.Cancel else null
        }
        assertEquals(1, cancels.size)
        // The fresh serve runs as its own transfer; the sibling device's push is untouched.
        val liveA = h.transferIdsFor(peerA)
        assertEquals(1, liveA.size)
        val serveRow = h.pushes.find(liveA.single())!!
        assertEquals(FilePushKind.SERVE, serveRow.kind)
        assertEquals(FilePushStatus.CREATED, serveRow.status)
        val liveB = h.transferIdsFor(peerB)
        assertEquals(1, liveB.size)
        assertEquals(FilePushKind.DIRECT, h.pushes.find(liveB.single())!!.kind)
        assertTrue(seen.any { it is FileEvent.ServeStarted && it.fileId == fixtureFileId })
    }

    @Test
    fun transferComplete_reportsOutgoingComplete() = runTest {
        val h = harness()
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch { h.fileEvents.collect { seen += it } }
        runCurrent()
        h.pusher.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        h.pusher.onTransferComplete(transfer)
        runCurrent()
        val completed = seen.filterIsInstance<FileEvent.OutgoingComplete>()
        assertEquals(listOf(fixtureFileId to fixtureAccount), completed.map { it.fileId to it.target })
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
            OutboxOutcome.Expired(OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer.value), peerA, h.markerOf(transfer)),
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
            OutboxOutcome.Acked(OutboxTag(OutboxTag.Kind.FILE_PUSH, Uuid.random()), peerA, Uuid.random()),
        )
        h.pusher.onTransferComplete(TransferId(Uuid.random()))
        // No rows, no crash.
        assertTrue(h.pushes.live().isEmpty())
    }

    // -- Deposits (design §6.3–§6.4, step 19) ---------------------------------------

    private fun depositHarness(
        relays: List<PeerId> = listOf(relay1, relay2),
    ): Harness = harness(
        relaySelection = object : RelaySelectionPolicy {
            override suspend fun selectRelays(targetDevice: PeerId): List<PeerId> = relays
        },
    )

    /** Pushes to `device` and ACKs its marker: IN_FLIGHT with chunk rows in flight. */
    private suspend fun Harness.drivePushToInFlight(device: PeerId = peerA): TransferId {
        pusher.pushFile(fixtureFileId, fixtureAccount)
        val transfer = pushes.live()
            .first { it.targetDevice == device && it.kind == FilePushKind.DIRECT }.transferId
        pusher.onOutboxOutcome(ackMarker(transfer, device))
        return transfer
    }

    private suspend fun Harness.depositsFor(device: PeerId = peerA) =
        pushes.live().filter { it.targetDevice == device && it.kind == FilePushKind.DEPOSIT }

    @Test
    fun noLinkAtSweep_depositsRemainderFromContiguousPrefix() = runTest {
        val h = depositHarness()
        val transfer = h.drivePushToInFlight()
        // Prefix [0, 1] ACKed; chunk 2 unacked; 3–4 never sent (window refilled on ACK).
        h.pusher.onCoverageAck(transfer, 1, emptyList())
        runCurrent()

        h.pusher.sweepStalls()

        // One deposit per relay: same file and ultimate target, fresh transferIds and
        // keys, remainder suffix only, outer-addressed to the relay.
        val deposits = h.depositsFor()
        assertEquals(2, deposits.size)
        assertEquals(setOf(relay1, relay2), deposits.map { it.viaRelay }.toSet())
        assertTrue(deposits.all { it.startIndex == 2 && it.contiguousAcked == 1 })
        assertEquals(2, deposits.map { it.transferId }.toSet().size, "each deposit is its own transfer")
        assertNotEquals(deposits[0].key.toList(), deposits[1].key.toList(), "each deposit has its own key")
        val markerOuters = h.sent.filter { (env, tag) ->
            tag?.let { it.kind == OutboxTag.Kind.FILE_PUSH && deposits.any { d -> d.transferId.value == it.id } } == true
        }
        assertEquals(2, markerOuters.size)
        for ((env, _) in markerOuters) {
            val inner = FileEnvelope.decode(env.payload)
            assertEquals(peerA, inner.target, "the sealed half stays targeted at the ultimate device")
            assertTrue(env.target == relay1 || env.target == relay2, "outer envelope rides to the relay")
        }
        // The authoring marker went out message-backed; deposit markers never do.
        assertEquals(RelayBackup.PENDING, h.backups[h.markerOf(transfer)])
        for (d in deposits) {
            assertEquals(RelayBackup.NONE, h.backups[h.markerOf(d.transferId)])
        }
    }

    @Test
    fun depositRound_isAtMostOnce() = runTest {
        val h = depositHarness()
        val transfer = h.drivePushToInFlight()
        h.pusher.sweepStalls()
        assertEquals(2, h.depositsFor().size)
        val sentBefore = h.sent.size

        // The outbox backup path for the same marker, re-sweeps and explicit
        // re-deposits never open a second round (the DEPOSIT rows are the claim).
        h.pusher.depositByMarkerPacket(h.markerOf(transfer))
        h.pusher.sweepStalls()
        h.pusher.depositRemainder(transfer)
        h.pusher.sweepStalls()
        assertEquals(2, h.depositsFor().size, "one round per (file, target), retry or restart")
        assertEquals(sentBefore, h.sent.size, "no second round re-enqueues anything")
    }

    @Test
    fun depositMarker_neverChases() = runTest {
        val h = depositHarness()
        h.drivePushToInFlight()
        h.pusher.sweepStalls()
        val deposit = h.depositsFor().single { it.viaRelay == relay1 }
        // The backup path for a deposit's own marker is a no-op (never chase, §6.4).
        assertEquals(0, h.pusher.depositByMarkerPacket(checkNotNull(deposit.markerPacketId)))
        assertEquals(0, h.pusher.depositRemainder(deposit.transferId))
        assertEquals(2, h.depositsFor().size)
    }

    @Test
    fun slowButAckingLink_neverDepositsUntilItGoesSilent() = runTest {
        val h = depositHarness()
        h.webRtc.simulateEnvelopeChannelOpen(peerA)
        val transfer = h.drivePushToInFlight()
        val chunkPacket = h.sent.map { it.first }
            .first { it.packetId != h.markerOf(transfer) }.packetId
        val tag = OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer.value)

        // A chunk ACK every 30s keeps the direct path looking alive: no deposit.
        repeat(3) {
            h.clock.advanceBy(30.seconds)
            h.pusher.onOutboxOutcome(OutboxOutcome.Acked(tag, peerA, chunkPacket))
            h.pusher.sweepStalls()
            runCurrent()
        }
        assertTrue(h.depositsFor().isEmpty(), "one ACKing chunk is a working link, not a signal")

        // Silence past chunkAckTimeout (45s) deposits at once.
        h.clock.advanceBy(60.seconds)
        h.pusher.sweepStalls()
        assertEquals(2, h.depositsFor().size)
    }

    @Test
    fun linkClose_depositsAtOnce() = runTest {
        val h = depositHarness()
        h.pusher.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        val job = h.pusher.runIn(backgroundScope)
        runCurrent()
        h.pusher.onOutboxOutcome(h.ackMarker(transfer, peerA))
        runCurrent()
        assertTrue(h.depositsFor().isEmpty())

        // Link up then down: the close deposits without waiting for the timeout.
        h.webRtc.setSessionState(WebRtcSessionState(peerA, SessionPhase.CONNECTED))
        runCurrent()
        h.webRtc.setSessionState(WebRtcSessionState(peerA, SessionPhase.CLOSED))
        runCurrent()
        runCurrent()

        assertEquals(2, h.depositsFor().size, "a closed link deposits at once")
        job.cancel()
    }

    @Test
    fun noRelaySelected_noDeposit() = runTest {
        val h = harness()
        h.drivePushToInFlight()
        h.pusher.sweepStalls()
        assertTrue(h.depositsFor().isEmpty(), "empty selection means no deposit; the pull path covers it")
        assertEquals(0, h.pusher.depositRemainder(h.transferIdsFor(peerA).single()))
    }

    @Test
    fun fullyAckedTransfer_noDeposit() = runTest {
        val h = depositHarness()
        val transfer = h.drivePushToInFlight()
        h.pusher.onCoverageAck(transfer, 4, emptyList())
        runCurrent()
        h.pusher.sweepStalls()
        assertTrue(h.depositsFor().isEmpty(), "nothing unacked: nothing to insure")
    }

    @Test
    fun complete_cancelsDepositsPolitelyAndStaysSilent() = runTest {
        val h = depositHarness()
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch { h.fileEvents.collect { seen += it } }
        runCurrent()
        val transfer = h.drivePushToInFlight()
        h.pusher.sweepStalls()
        val deposits = h.depositsFor()
        assertEquals(2, deposits.size)

        h.pusher.onTransferComplete(transfer)
        runCurrent()

        // The send completes once; the deposits are CANCELled at their relays, silently.
        assertEquals(FilePushStatus.COMPLETED, h.pushes.find(transfer)?.status)
        assertTrue(deposits.all { h.pushes.find(it.transferId)?.status == FilePushStatus.CANCELLED })
        val cancels = h.sent.mapNotNull { (env, tag) ->
            if (tag == null) FileEnvelope.decode(env.payload).decodePayload() as? FilePayload.Cancel else null
        }
        assertEquals(2, cancels.size, "one CANCEL per outstanding deposit")
        val cancelTargets = h.sent.mapNotNull { (env, tag) ->
            if (tag == null) env.target else null
        }
        assertEquals(setOf(relay1, relay2), cancelTargets.toSet(), "CANCELs ride outer to the relays")
        assertTrue(seen.none { it is FileEvent.Failed }, "deposits never surface as failures")
        assertEquals(1, seen.filterIsInstance<FileEvent.OutgoingComplete>().size)
    }

    @Test
    fun cancelFile_cancelsDepositsAtTheirRelays() = runTest {
        val h = depositHarness()
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch { h.fileEvents.collect { seen += it } }
        runCurrent()
        h.drivePushToInFlight()
        h.pusher.sweepStalls()
        assertEquals(2, h.depositsFor().size)

        h.pusher.cancelFile(fixtureFileId, fixtureAccount)
        runCurrent()

        assertTrue(h.pushes.live().none { it.fileId == fixtureFileId })
        val cancelTargets = h.sent.mapNotNull { (env, tag) ->
            if (tag == null) env.target else null
        }
        // Direct rows cancel at the target, deposit rows at their relays.
        assertTrue(peerA in cancelTargets && relay1 in cancelTargets && relay2 in cancelTargets)
        // The user cancel still reports the send; deposits stay silent.
        assertTrue(seen.filterIsInstance<FileEvent.Failed>().all { it.reason == FileFailureReason.CANCELLED })
    }
}
