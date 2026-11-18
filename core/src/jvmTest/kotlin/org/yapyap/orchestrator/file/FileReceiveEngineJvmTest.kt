package org.yapyap.orchestrator.file

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.orchestrator.file.io.AppendHandle
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.orchestrator.file.io.JvmFileAccess
import org.yapyap.orchestrator.file.io.PositionedHandle
import org.yapyap.orchestrator.store.DefaultMessageRepository
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.routing.file.FileCarrier
import org.yapyap.routing.file.FileEvent
import org.yapyap.routing.file.FileFailureReason
import org.yapyap.routing.file.FileIngestResult
import org.yapyap.routing.file.FileOfferInfo
import org.yapyap.routing.file.FileOfferVerdict
import org.yapyap.routing.router.RouterConfig
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

private class RecordingFileCarrier : FileCarrier {
    val events = mutableListOf<FileEvent>()
    val readyCalls = mutableListOf<FileId>()
    val cancels = mutableListOf<Pair<FileId, FileCancelReason>>()

    override val fileEvents: kotlinx.coroutines.flow.Flow<FileEvent>
        get() = error("recorded instead")

    override suspend fun pushFile(fileId: FileId, target: AccountId): Unit =
        error("not used in receive engine tests")

    override suspend fun cancelFile(fileId: FileId, target: AccountId): Unit =
        error("not used in receive engine tests")

    override suspend fun fileReady(fileId: FileId) {
        readyCalls += fileId
    }

    override suspend fun cancelIncoming(fileId: FileId, reason: FileCancelReason) {
        cancels += fileId to reason
    }

    override suspend fun emit(event: FileEvent) {
        events += event
    }
}

private class FailingWriteAccess(
    private val delegate: FileAccess = JvmFileAccess(),
    private val failAfterBytes: Long,
    private val message: String = "No space left on device",
) : FileAccess by delegate {
    override fun openAppendWriter(path: Path, truncateToLength: Long?): AppendHandle {
        val inner = delegate.openAppendWriter(path, truncateToLength)
        return object : AppendHandle by inner {
            private var written = 0L

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (written >= failAfterBytes) throw kotlinx.io.IOException(message)
                val allowed = minOf(length.toLong(), failAfterBytes - written).toInt()
                inner.write(bytes, offset, allowed)
                written += allowed
                if (allowed < length) throw kotlinx.io.IOException(message)
            }
        }
    }
}

class FileReceiveEngineJvmTest {
    private val crypto = DefaultCryptoProvider()

    private data class Harness(
        val engine: DefaultFileReceiveEngine,
        val receives: FileReceiveStore,
        val descriptors: FileDescriptorStore,
        val carrier: RecordingFileCarrier,
        val files: FileAccess,
        val clock: FakeClock,
        val incomingDir: Path,
        val storeDir: Path,
        val backend: Backend,
    )

    private data class Backend(
        val db: org.yapyap.persistence.YapYapDatabase,
        val incomingDir: Path,
        val storeDir: Path,
    )

    private fun harness(
        config: FileServiceConfig = FileServiceConfig(),
        clock: FakeClock = FakeClock(epochSeconds(10_000L)),
        files: FileAccess = JvmFileAccess(),
        backend: Backend? = null,
    ): Harness {
        val b = backend ?: run {
            val db = openMemoryDatabase().database
            val work = Path(
                SystemTemporaryDirectory.toString(),
                "yapyap-recv-${Uuid.random()}",
            )
            files.createDirectories(work)
            val incomingDir = Path(work.toString(), "incoming")
            val storeDir = Path(work.toString(), "store")
            files.createDirectories(incomingDir)
            files.createDirectories(storeDir)
            Backend(db, incomingDir, storeDir)
        }
        val receives = DefaultFileReceiveStore(b.db)
        val descriptors = DefaultFileDescriptorStore(b.db)
        val carrier = RecordingFileCarrier()
        val engine = DefaultFileReceiveEngine(
            receives = receives,
            descriptors = descriptors,
            messages = DefaultMessageRepository(b.db),
            crypto = crypto,
            files = files,
            storeDirectory = b.storeDir,
            incomingDirectory = b.incomingDir,
            config = config,
            fileStore = DefaultFileStore(descriptors, crypto, files, b.storeDir, config, clock),
            carrier = carrier,
            routerConfig = MutableStateFlow(RouterConfig()),
            clock = clock,
        )
        return Harness(engine, receives, descriptors, carrier, files, clock, b.incomingDir, b.storeDir, b)
    }

    private suspend fun sampleFile(totalBytes: Long): Triple<FileId, Int, List<ByteArray>> {
        val bytes = ByteArray(totalBytes.toInt()) { (it * 7).toByte() }
        val blockSize = org.yapyap.protocol.blockSizeFor(totalBytes)
        val hashes = FileIds.blockHashesForBytes(crypto, bytes, blockSize)
        val fileId = FileIds.fileIdForBlocks(crypto, totalBytes, blockSize, hashes)
        // Stash the bytes for the test via a side channel: re-derive deterministically.
        return Triple(fileId, blockSize, hashes)
    }

    private fun bytesFor(totalBytes: Long): ByteArray =
        ByteArray(totalBytes.toInt()) { (it * 7).toByte() }

    private fun offerFor(h: Harness, fileId: FileId, totalBytes: Long, blockSize: Int, hashes: List<ByteArray>) =
        FileOfferInfo(
            fileId = fileId,
            totalBytes = totalBytes,
            blockSize = blockSize,
            blockHashes = hashes,
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
        )

    @Test
    fun mixedChunkings_completeOneFile() = runTest {
        val h = harness()
        val total = 3L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)

        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))

        // Two chunkings interleaved: 256 KiB pieces and 1 MiB pieces, plus a
        // byte-exact fill pass (duplicates collapse into coverage).
        var chunkA = 0L
        var chunkB = 512L * 1024
        while (chunkA < total || chunkB < total) {
            if (chunkA < total) {
                val end = minOf(chunkA + 256 * 1024, total)
                h.engine.ingest(fileId, chunkA, bytes.copyOfRange(chunkA.toInt(), end.toInt()))
                chunkA += 512 * 1024
            }
            if (chunkB < total) {
                val end = minOf(chunkB + 1024 * 1024, total)
                if (chunkB < end) {
                    h.engine.ingest(fileId, chunkB, bytes.copyOfRange(chunkB.toInt(), end.toInt()))
                }
                chunkB += 2 * 1024 * 1024
            }
        }
        var off = 0L
        while (off < total) {
            val end = minOf(off + 300 * 1024, total)
            h.engine.ingest(fileId, off, bytes.copyOfRange(off.toInt(), end.toInt()))
            off = end
        }

        h.engine.checkpoint()

        val row = h.receives.find(fileId)
        assertNotNull(row)
        assertEquals(FileReceiveStatus.READY, row.status)
        assertEquals(total, row.contiguousBytes)
        assertEquals(listOf(fileId), h.carrier.readyCalls)
        val descriptor = h.descriptors.findById(fileId)
        assertNotNull(descriptor)
        assertEquals(FileDescriptorStatus.HELD, descriptor.status)
        val finalBytes = h.files.openPositionedReader(Path(descriptor.path)).use { reader ->
            val out = ByteArray(total.toInt())
            var got = 0
            while (got < out.size) {
                val n = reader.readAt(got.toLong(), out, got, out.size - got)
                check(n > 0)
                got += n
            }
            out
        }
        assertContentEquals(bytes, finalBytes)
    }

    @Test
    fun racingTransfer_hitsStagingBoundAndDefers() = runTest {
        val config = FileServiceConfig(maxStagedBytes = 1500, fileCheckpointBytes = 1024)
        val h = harness(config = config)
        val total = 4L * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)

        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))

        // A racing transfer stages a suffix behind a stuck gap.
        assertIs<FileIngestResult.Staged>(h.engine.ingest(fileId, 1024, bytes.copyOfRange(1024, 2048)))
        val deferred = h.engine.ingest(fileId, 2048, bytes.copyOfRange(2048, 3072))
        assertIs<FileIngestResult.Defer>(deferred)

        // Once the gap fills, the sender's retry lands and everything drains.
        h.engine.ingest(fileId, 0, bytes.copyOfRange(0, 1024))
        h.engine.ingest(fileId, 3072, bytes.copyOfRange(3072, 4096))
        h.engine.checkpoint()
        assertEquals(2048L, h.receives.find(fileId)?.contiguousBytes)
        // The DEFERred chunk is retried (flat delay, step 13) and lands.
        h.engine.ingest(fileId, 2048, bytes.copyOfRange(2048, 3072))
        h.engine.checkpoint()
        assertEquals(FileReceiveStatus.READY, h.receives.find(fileId)?.status)
    }

    @Test
    fun crashBetweenAppendAndPCommit_truncatesTornTailAndRedrains() = runTest {
        val h = harness()
        val total = 2L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)

        h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes))
        h.engine.ingest(fileId, 0, bytes.copyOfRange(0, total.toInt()))

        // Crash torn: garbage past P with staged rows still present.
        val part = Path(h.incomingDir.toString(), "${fileId.value}.part")
        h.files.openAppendWriter(part, null).use { it.write(ByteArray(100) { 9 }) }

        // Restart on the same DB and directories: the torn tail is cut, staged rows drain.
        val h2 = harness(backend = h.backend, clock = h.clock, files = h.files)
        h2.engine.checkpoint()

        val row = h2.receives.find(fileId)
        assertNotNull(row)
        assertEquals(FileReceiveStatus.READY, row.status)
        assertEquals(total, row.contiguousBytes)
        assertEquals(listOf(fileId), h2.carrier.readyCalls)
    }

    @Test
    fun crashBetweenCheckpoints_resumesFromP() = runTest {
        val h = harness()
        val total = 3L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)

        h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes))
        h.engine.ingest(fileId, 0, bytes.copyOfRange(0, 1536 * 1024))
        h.engine.checkpoint()
        assertEquals(1536L * 1024, h.receives.find(fileId)?.contiguousBytes)

        // Restart: the partial block at P is re-hashed from the .part, staged rows rehydrate.
        val h2 = harness(backend = h.backend, clock = h.clock, files = h.files)
        h2.engine.ingest(fileId, 1536L * 1024, bytes.copyOfRange(1536 * 1024, total.toInt()))
        h2.engine.checkpoint()

        val row = h2.receives.find(fileId)
        assertNotNull(row)
        assertEquals(FileReceiveStatus.READY, row.status)
        assertContentEquals(bytes, h2.files.openPositionedReader(Path(row.finalPath)).use { reader ->
            val out = ByteArray(total.toInt())
            var got = 0
            while (got < out.size) {
                val n = reader.readAt(got.toLong(), out, got, out.size - got)
                check(n > 0)
                got += n
            }
            out
        })
    }

    @Test
    fun blockMismatch_keepsEarlierBlocks_thenCompletes() = runTest {
        val h = harness()
        val total = 3L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)

        h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes))
        h.engine.ingest(fileId, 0, bytes.copyOfRange(0, 2 * 1024 * 1024))
        val tampered = bytes.copyOfRange(2 * 1024 * 1024, total.toInt())
        tampered[0] = (tampered[0].toInt() xor 0xff).toByte()
        h.engine.ingest(fileId, 2L * 1024 * 1024, tampered)
        h.engine.checkpoint()

        val stalled = h.receives.find(fileId)
        assertNotNull(stalled)
        assertEquals(FileReceiveStatus.STALLED, stalled.status)
        assertEquals(2L * 1024 * 1024, stalled.contiguousBytes)
        assertEquals(1, stalled.hashMismatches)
        assertEquals(1, h.carrier.cancels.size)
        // Earlier blocks survived on disk.
        val prefix = h.files.openPositionedReader(
            Path(stalled.partPath),
        ).use { reader ->
            val out = ByteArray(2 * 1024 * 1024)
            var got = 0
            while (got < out.size) {
                val n = reader.readAt(got.toLong(), out, got, out.size - got)
                check(n > 0)
                got += n
            }
            out
        }
        assertContentEquals(bytes.copyOfRange(0, 2 * 1024 * 1024), prefix)

        // Re-requested bytes from the same device land and complete the file.
        h.engine.ingest(fileId, 2L * 1024 * 1024, bytes.copyOfRange(2 * 1024 * 1024, total.toInt()))
        h.engine.checkpoint()
        assertEquals(FileReceiveStatus.READY, h.receives.find(fileId)?.status)
        assertEquals(listOf(fileId), h.carrier.readyCalls)
    }

    @Test
    fun enospc_isCleanFailure() = runTest {
        val failing = FailingWriteAccess(failAfterBytes = 512)
        val h = harness(files = failing)
        val total = 4L * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)

        h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes))
        h.engine.ingest(fileId, 0, bytes.copyOfRange(0, total.toInt()))
        h.engine.checkpoint()

        val row = h.receives.find(fileId)
        assertNotNull(row)
        assertEquals(FileReceiveStatus.FAILED, row.status)
        assertEquals(1, h.carrier.cancels.size)
        val failed = h.carrier.events.filterIsInstance<FileEvent.Failed>().single()
        assertEquals(fileId, failed.fileId)
        assertEquals(FileFailureReason.NO_SPACE, failed.reason)
    }

    @Test
    fun alreadyReady_answersCompleteAtOffer() = runTest {
        val h = harness()
        val total = 1L * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)

        h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes))
        h.engine.ingest(fileId, 0, bytes)
        h.engine.checkpoint()
        assertEquals(FileReceiveStatus.READY, h.receives.find(fileId)?.status)

        assertIs<FileOfferVerdict.AlreadyReady>(
            h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)),
        )
    }

    @Test
    fun readyFile_noMessageReference_deletedAfterTtl() = runTest {
        val clock = FakeClock(epochSeconds(10_000L))
        val h = harness(clock = clock)
        val total = 1L * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)

        h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes))
        h.engine.ingest(fileId, 0, bytes)
        h.engine.checkpoint()
        val row = h.receives.find(fileId)
        assertNotNull(row)
        assertEquals(FileReceiveStatus.READY, row.status)
        val finalPath = row.finalPath

        clock.advanceTo(epochSeconds(10_000L) + 3.days)
        h.engine.sweep()

        assertNull(h.receives.find(fileId))
        assertNull(h.files.fileSize(Path(finalPath)))
        assertNull(h.descriptors.findById(fileId))
    }
}
