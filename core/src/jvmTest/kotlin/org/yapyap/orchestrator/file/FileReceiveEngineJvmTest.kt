package org.yapyap.orchestrator.file

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.file.io.AppendHandle
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.orchestrator.file.io.JvmFileAccess
import org.yapyap.orchestrator.file.receive.FileRetention
import org.yapyap.orchestrator.file.receive.ReceiveEngine
import org.yapyap.orchestrator.file.receive.ReceivePaths
import org.yapyap.orchestrator.store.DefaultMessageRepository
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.FileId
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.routing.file.port.FileAppendResult
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FileReceiveEnd
import org.yapyap.routing.file.port.FileRefusalReason
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.TestFileIds
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.testfixtures.importHeld
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The receives the engine ended, as routing would collect them. */
private class RecordedEnds {
    val all = mutableListOf<FileReceiveEnd>()
    val readyCalls: List<FileId> get() = all.filterIsInstance<FileReceiveEnd.Ready>().map { it.fileId }
    val cancels: List<Pair<FileId, FileCancelReason>>
        get() = all.filterIsInstance<FileReceiveEnd.Cancelled>().map { it.fileId to it.reason }
}

private class FailingWriteAccess(
    private val delegate: FileAccess = JvmFileAccess(),
    private val failAfterBytes: Long,
    private val message: String = "No space left on device",
) : FileAccess by delegate {
    /** Set false to let writes through again, as when space frees up. */
    var failing = true

    override suspend fun openAppendWriter(path: Path, truncateToLength: Long?): AppendHandle {
        val inner = delegate.openAppendWriter(path, truncateToLength)
        if (!failing) return inner
        return object : AppendHandle by inner {
            private var written = 0L

            override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (written >= failAfterBytes) throw IOException(message)
                val allowed = minOf(length.toLong(), failAfterBytes - written).toInt()
                inner.write(bytes, offset, allowed)
                written += allowed
                if (allowed < length) throw IOException(message)
            }
        }
    }
}

/** Counts fsyncs through append handles. */
private class SyncCountingAccess(private val delegate: FileAccess = JvmFileAccess()) : FileAccess by delegate {
    var syncs = 0

    override suspend fun openAppendWriter(path: Path, truncateToLength: Long?): AppendHandle {
        val inner = delegate.openAppendWriter(path, truncateToLength)
        return object : AppendHandle by inner {
            override suspend fun flushAndSync() {
                syncs++
                inner.flushAndSync()
            }
        }
    }
}

private class InjectedCrash(step: String) : RuntimeException("injected crash before $step")

/** Throws before [crashAt] once, as if the process died there; everything before it is durable. */
private class CrashingReceiveStore(
    private val delegate: FileReceiveStore,
    private val crashAt: String,
) : FileReceiveStore by delegate {
    override suspend fun commitDrain(
        fileId: FileId,
        newP: Long,
        mismatches: Int,
        status: FileReceiveStatus,
        now: Instant,
        trimOldStart: Long?,
        trimNewStart: Long?,
        trimNewLength: Long?,
        trimNewPlaintext: ByteArray?,
    ) {
        if (crashAt == "commitDrain") throw InjectedCrash(crashAt)
        delegate.commitDrain(
            fileId,
            newP,
            mismatches,
            status,
            now,
            trimOldStart,
            trimNewStart,
            trimNewLength,
            trimNewPlaintext
        )
    }

    override suspend fun markReady(fileId: FileId, newP: Long, mismatches: Int, now: Instant) {
        if (crashAt == "markReady") throw InjectedCrash(crashAt)
        delegate.markReady(fileId, newP, mismatches, now)
    }
}

class FileReceiveEngineJvmTest {
    private val crypto = DefaultCryptoProvider()

    private data class Harness(
        val engine: ReceiveEngine,
        val receives: FileReceiveStore,
        val descriptors: FileDescriptorStore,
        val ends: RecordedEnds,
        val files: FileAccess,
        val clock: FakeClock,
        val paths: ReceivePaths,
        val backend: Backend,
    )

    private data class Backend(
        val db: org.yapyap.persistence.YapYapDatabase,
        val incomingDir: Path,
        val storeDir: Path,
    )

    private suspend fun TestScope.harness(
        config: OrchestratorConfig = OrchestratorConfig(),
        clock: FakeClock = FakeClock(epochSeconds(10_000L)),
        files: FileAccess = JvmFileAccess(),
        backend: Backend? = null,
        wrapReceives: (FileReceiveStore) -> FileReceiveStore = { it },
        wrapFileStore: (FileStore) -> FileStore = { it },
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
        val receives = wrapReceives(DefaultFileReceiveStore(b.db))
        val descriptors = DefaultFileDescriptorStore(b.db)
        val ends = RecordedEnds()
        val paths = ReceivePaths(b.incomingDir, b.storeDir)
        val configFlow = MutableStateFlow(config)
        val engine = ReceiveEngine(
            receives = receives,
            descriptors = descriptors,
            crypto = crypto,
            files = files,
            paths = paths,
            config = configFlow,
            fileStore = wrapFileStore(DefaultFileStore(descriptors, crypto, files, b.storeDir, configFlow, clock)),
            clock = clock,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.receiveEnds.collect { ends.all += it } }
        engine.recoverNow(clock.now())
        return Harness(engine, receives, descriptors, ends, files, clock, paths, b)
    }

    private suspend fun sampleFile(totalBytes: Long): Triple<FileId, Int, List<ByteArray>> {
        val bytes = ByteArray(totalBytes.toInt()) { (it * 7).toByte() }
        val blockSize = org.yapyap.protocol.blockSizeFor(totalBytes)
        val hashes = TestFileIds.blockHashesForBytes(crypto, bytes, blockSize)
        val fileId = FileIds.fileIdForBlocks(crypto, totalBytes, blockSize, hashes)
        return Triple(fileId, blockSize, hashes)
    }

    private fun bytesFor(totalBytes: Long): ByteArray =
        ByteArray(totalBytes.toInt()) { (it * 7).toByte() }

    private fun offerFor(h: Harness, fileId: FileId, totalBytes: Long, blockSize: Int, hashes: List<ByteArray>) =
        FileManifest(
            fileId = fileId,
            totalBytes = totalBytes,
            blockSize = blockSize,
            blockHashes = hashes,
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
        )

    private fun retentionFor(
        h: Harness,
        config: MutableStateFlow<OrchestratorConfig> = MutableStateFlow(OrchestratorConfig()),
    ): FileRetention {
        val store = DefaultFileStore(h.descriptors, crypto, h.files, h.backend.storeDir, config, h.clock)
        return FileRetention(
            receives = h.receives,
            descriptors = h.descriptors,
            messages = DefaultMessageRepository(h.backend.db),
            engine = h.engine,
            fileStore = store,
            files = h.files,
            paths = h.paths,
            receiveEvents = emptyFlow(),
            config = config,
            clock = h.clock,
        )
    }

    private suspend fun readAll(files: FileAccess, path: Path, size: Int): ByteArray =
        files.openPositionedReader(path).use { reader ->
            val out = ByteArray(size)
            var got = 0
            while (got < out.size) {
                val n = reader.readAt(got.toLong(), out, got, out.size - got)
                check(n > 0)
                got += n
            }
            out
        }

    @Test
    fun mixedChunkings_completeOneFile() = runTest {
        val h = harness()
        val total = 3L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)

        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))

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
        assertEquals(listOf(fileId), h.ends.readyCalls)
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
        val config = OrchestratorConfig(maxStagedBytes = 1500, fileCheckpointBytes = 1024)
        val h = harness(config = config)
        val total = 4L * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)

        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))

        assertIs<FileIngestResult.Staged>(h.engine.ingest(fileId, 1024, bytes.copyOfRange(1024, 2048)))
        val deferred = h.engine.ingest(fileId, 2048, bytes.copyOfRange(2048, 3072))
        assertIs<FileIngestResult.Defer>(deferred)

        h.engine.ingest(fileId, 0, bytes.copyOfRange(0, 1024))
        h.engine.ingest(fileId, 3072, bytes.copyOfRange(3072, 4096))
        h.engine.checkpoint()
        assertEquals(2048L, h.receives.find(fileId)?.contiguousBytes)
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
        // Everything but the last byte: staged and ACKed, below the checkpoint threshold.
        assertIs<FileIngestResult.Staged>(h.engine.ingest(fileId, 0, bytes.copyOfRange(0, total.toInt() - 1)))

        // Crash torn: garbage past P with staged rows still present.
        h.files.openAppendWriter(h.paths.part(fileId), null).use { it.write(ByteArray(100) { 9 }) }

        // Restart on the same DB and directories: the torn tail is cut, staged rows drain.
        val h2 = harness(backend = h.backend, clock = h.clock, files = h.files)
        assertEquals(0L, h2.files.fileSize(h2.paths.part(fileId)), "recovery truncates to P")
        assertIs<FileIngestResult.Ready>(
            h2.engine.ingest(
                fileId,
                total - 1,
                bytes.copyOfRange(total.toInt() - 1, total.toInt())
            )
        )

        val row = assertNotNull(h2.receives.find(fileId))
        assertEquals(FileReceiveStatus.READY, row.status)
        assertEquals(total, row.contiguousBytes)
        assertEquals(listOf(fileId), h2.ends.readyCalls)
        assertContentEquals(bytes, readAll(h2.files, h2.paths.final(fileId), total.toInt()))
    }

    @Test
    fun crashAtEveryCheckpointStep_losesNothingAcked() = runTest {
        for (step in listOf("commitDrain", "move", "markReady", "adopt")) {
            val total = 3L * 1024 * 1024
            val (fileId, blockSize, hashes) = sampleFile(total)
            val bytes = bytesFor(total)
            val crashingFiles = object : FileAccess by JvmFileAccess() {
                override suspend fun move(source: Path, target: Path) {
                    if (step == "move") throw InjectedCrash(step)
                    JvmFileAccess().move(source, target)
                }
            }
            val h = harness(
                files = crashingFiles,
                wrapReceives = { CrashingReceiveStore(it, step) },
                wrapFileStore = { real ->
                    object : FileStore by real {
                        override suspend fun adopt(descriptor: FileDescriptor) {
                            if (step == "adopt") throw InjectedCrash(step)
                            real.adopt(descriptor)
                        }
                    }
                },
            )
            h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes))
            val acked = listOf(
                h.engine.ingest(fileId, 0, bytes.copyOfRange(0, 2 * 1024 * 1024)),
                h.engine.ingest(fileId, 2L * 1024 * 1024, bytes.copyOfRange(2 * 1024 * 1024, total.toInt())),
            )
            assertTrue(acked.none { it is FileIngestResult.Defer || it is FileIngestResult.Refused }, "$step: $acked")

            // Restart without the fault: nothing ACKed is lost, the file completes and is held.
            val h2 = harness(backend = h.backend, clock = h.clock)
            h2.engine.checkpoint()
            val row = assertNotNull(h2.receives.find(fileId), step)
            assertEquals(FileReceiveStatus.READY, row.status, step)
            val descriptor = assertNotNull(h2.descriptors.findById(fileId), "$step: descriptor")
            assertEquals(FileDescriptorStatus.HELD, descriptor.status, step)
            assertContentEquals(bytes, readAll(h2.files, Path(descriptor.path), total.toInt()), step)
            assertNull(h2.files.fileSize(h2.paths.part(fileId)), "$step: no .part left")
        }
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

        val h2 = harness(backend = h.backend, clock = h.clock, files = h.files)
        h2.engine.ingest(fileId, 1536L * 1024, bytes.copyOfRange(1536 * 1024, total.toInt()))
        h2.engine.checkpoint()

        val row = h2.receives.find(fileId)
        assertNotNull(row)
        assertEquals(FileReceiveStatus.READY, row.status)
        val descriptor = assertNotNull(h2.descriptors.findById(fileId))
        assertContentEquals(bytes, h2.files.openPositionedReader(Path(descriptor.path)).use { reader ->
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
        assertEquals(1, h.ends.cancels.size)
        val prefix = h.files.openPositionedReader(h.paths.part(fileId)).use { reader ->
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

        h.engine.ingest(fileId, 2L * 1024 * 1024, bytes.copyOfRange(2 * 1024 * 1024, total.toInt()))
        h.engine.checkpoint()
        assertEquals(FileReceiveStatus.READY, h.receives.find(fileId)?.status)
        assertEquals(listOf(fileId), h.ends.readyCalls)
    }

    @Test
    fun engine_reportsProgressAndReadyOnItsOwnFlow() = runTest {
        val h = harness()
        val total = 4L * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)
        val events = mutableListOf<FileReceiveEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.engine.receiveEvents.collect { events += it } }

        h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes))
        h.engine.ingest(fileId, 0, bytes)
        h.engine.checkpoint()

        assertTrue(events.first() is FileReceiveEvent.Progress, "events: $events")
        assertEquals(listOf(FileReceiveEvent.Ready(fileId)), events.filterIsInstance<FileReceiveEvent.Ready>())
    }

    @Test
    fun gapTrigger_stallsAnIdleReceive() = runTest {
        val h = harness()
        val total = 4L * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes))

        val later = h.clock.now() + 1.days
        assertTrue(h.engine.markStalled(fileId, now = later))
        assertEquals(FileReceiveStatus.STALLED, h.receives.find(fileId)?.status)
    }

    @Test
    fun gapTrigger_racingACompletion_leavesTheFileReady() = runTest {
        val h = harness()
        val total = 4L * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes))
        val sweptRow = assertNotNull(h.receives.find(fileId))
        assertEquals(FileReceiveStatus.IN_FLIGHT, sweptRow.status)
        h.engine.ingest(fileId, 0, bytesFor(total))
        h.engine.checkpoint()
        assertEquals(FileReceiveStatus.READY, h.receives.find(fileId)?.status)

        val later = h.clock.now() + 1.days
        assertFalse(h.engine.markStalled(fileId, now = later))
        assertEquals(FileReceiveStatus.READY, h.receives.find(fileId)?.status)
    }

    @Test
    fun appendFailure_keepsTheReceive_andTheNextCheckpointCompletesIt() = runTest {
        val failing = FailingWriteAccess(failAfterBytes = 512)
        val h = harness(files = failing)
        val total = 4L * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)
        val events = mutableListOf<FileReceiveEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.engine.receiveEvents.collect { events += it } }

        h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes))
        // The chunk is durable before the drain fails, so it is still ACKed.
        assertIs<FileIngestResult.Staged>(h.engine.ingest(fileId, 0, bytes))
        h.engine.checkpoint()
        val row = assertNotNull(h.receives.find(fileId))
        assertEquals(FileReceiveStatus.IN_FLIGHT, row.status, "an I/O error is not a verdict on the file")
        assertEquals(0L, row.contiguousBytes)
        assertTrue(h.ends.cancels.isEmpty())
        assertTrue(events.none { it is FileReceiveEvent.Failed })

        failing.failing = false
        h.engine.checkpoint()
        assertEquals(FileReceiveStatus.READY, h.receives.find(fileId)?.status)
        assertContentEquals(bytes, readAll(h.files, h.paths.final(fileId), total.toInt()))
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

        clock.advanceTo(epochSeconds(10_000L) + 3.days)
        val retention = retentionFor(h)
        retention.sweep(clock.now())

        assertNull(h.receives.find(fileId))
        assertNull(h.files.fileSize(h.paths.final(fileId)))
        assertNull(h.descriptors.findById(fileId))
    }

    @Test
    fun straddlingMismatch_recovers() = runTest {
        val h = harness()
        // Two blocks: 1 MiB each (total 2 MiB, block size 1 MiB).
        val total = 2L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)
        assertEquals(1024 * 1024, blockSize)

        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))
        // Stage a straddling range first: [blockSize-100, total) with a bad second block.
        val straddleStart = blockSize.toLong() - 100
        val straddling = bytes.copyOfRange(straddleStart.toInt(), total.toInt())
        // Tamper the block-1 part (everything from blockSize onward in this range).
        for (i in 100 until straddling.size) {
            straddling[i] = (straddling[i].toInt() xor 0xff).toByte()
        }
        h.engine.ingest(fileId, straddleStart, straddling)
        // Then the good first block.
        h.engine.ingest(fileId, 0, bytes.copyOfRange(0, blockSize))
        h.engine.checkpoint()

        val stalled = assertNotNull(h.receives.find(fileId))
        assertEquals(FileReceiveStatus.STALLED, stalled.status)
        assertEquals(blockSize.toLong(), stalled.contiguousBytes)
        // Earlier block kept on disk.
        val prefix = h.files.openPositionedReader(h.paths.part(fileId)).use { reader ->
            val out = ByteArray(blockSize)
            var got = 0
            while (got < out.size) {
                val n = reader.readAt(got.toLong(), out, got, out.size - got)
                check(n > 0)
                got += n
            }
            out
        }
        assertContentEquals(bytes.copyOfRange(0, blockSize), prefix)

        h.engine.ingest(fileId, blockSize.toLong(), bytes.copyOfRange(blockSize, total.toInt()))
        h.engine.checkpoint()
        assertEquals(FileReceiveStatus.READY, h.receives.find(fileId)?.status)
    }

    @Test
    fun retryLimit_holdsExactly() = runTest {
        val config = OrchestratorConfig(fileHashRetryLimit = 3)
        val h = harness(config = config)
        val total = 2L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)

        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))
        h.engine.ingest(fileId, 0, bytes.copyOfRange(0, 1024 * 1024))
        repeat(2) {
            val bad = bytes.copyOfRange(1024 * 1024, total.toInt())
            bad[0] = (bad[0].toInt() xor 0xff).toByte()
            h.engine.ingest(fileId, 1024L * 1024, bad)
            h.engine.checkpoint()
            assertEquals(FileReceiveStatus.STALLED, h.receives.find(fileId)?.status)
        }
        val bad = bytes.copyOfRange(1024 * 1024, total.toInt())
        bad[0] = (bad[0].toInt() xor 0xff).toByte()
        h.engine.ingest(fileId, 1024L * 1024, bad)
        h.engine.checkpoint()
        assertEquals(FileReceiveStatus.FAILED, h.receives.find(fileId)?.status)
        assertEquals(3, h.receives.find(fileId)?.hashMismatches)
    }

    @Test
    fun concurrentIngestAndCheckpoint_completesOnce() = runTest {
        val h = harness()
        val total = 10L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)

        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))
        kotlinx.coroutines.coroutineScope {
            val chunk = 256 * 1024
            var off = 0L
            val jobs = mutableListOf<kotlinx.coroutines.Job>()
            while (off < total) {
                val start = off
                val end = minOf(off + chunk, total)
                jobs += launch { h.engine.ingest(fileId, start, bytes.copyOfRange(start.toInt(), end.toInt())) }
                if (off % (1024 * 1024) == 0L) jobs += launch { h.engine.checkpoint() }
                off = end
            }
            jobs.joinAll()
        }
        h.engine.checkpoint()
        assertEquals(FileReceiveStatus.READY, h.receives.find(fileId)?.status)
        assertEquals(listOf(fileId), h.ends.readyCalls)
    }

    @Test
    fun twoConcurrentOffers_fitOnlyOneAtATime() = runTest {
        val limited = object : FileAccess by JvmFileAccess() {
            override suspend fun freeSpaceBytes(directory: Path): Long = 1500L
        }
        val h = harness(files = limited)
        val total = 1000L
        val (fileA, blockA, hashesA) = sampleFile(total)
        // A different fileId for the second file (different bytes, same size).
        val bytesB = ByteArray(total.toInt()) { (it * 7 + 1).toByte() }
        val blockB = org.yapyap.protocol.blockSizeFor(total)
        val hashesB = TestFileIds.blockHashesForBytes(crypto, bytesB, blockB)
        val fileB = FileIds.fileIdForBlocks(crypto, total, blockB, hashesB)
        // Two concurrent OFFERs: only one fits in 1500 bytes; the other sees the reservation.
        val deferredA = async { h.engine.checkOffer(offerFor(h, fileA, total, blockA, hashesA)) }
        val deferredB = async { h.engine.checkOffer(offerFor(h, fileB, total, blockB, hashesB)) }
        val verdicts = listOf(deferredA.await(), deferredB.await())
        assertEquals(1, verdicts.filterIsInstance<FileOfferVerdict.Accept>().size, "exactly one fits")
        assertEquals(1, verdicts.filterIsInstance<FileOfferVerdict.Refuse>().size, "the other is refused")
        val refused = verdicts.filterIsInstance<FileOfferVerdict.Refuse>().single()
        assertEquals(FileRefusalReason.NO_SPACE, refused.reason)
    }

    @Test
    fun heldFile_answersAlreadyReady() = runTest {
        val h = harness()
        val total = 1L * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)
        // Import into the store first (held), without any receive row.
        val work = h.backend.storeDir
        val src = Path(work.toString(), "held.bin")
        h.files.openAppendWriter(src, null).use { it.write(bytes); it.flushAndSync() }
        val store = DefaultFileStore(
            h.descriptors,
            crypto,
            h.files,
            h.backend.storeDir,
            MutableStateFlow(OrchestratorConfig()),
            h.clock
        )
        store.importHeld(FileImportSource.PathSource(src), "held.bin", null, keepCopy = true)

        assertIs<FileOfferVerdict.AlreadyReady>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))
        assertNull(h.receives.find(fileId), "held content needs no receive row")
    }

    @Test
    fun unreferencedInflight_deletedAfterTtl() = runTest {
        val clock = FakeClock(epochSeconds(10_000L))
        val h = harness(clock = clock)
        val total = 1L * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)

        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))
        // No message ever references it; the TTL covers every status, not just READY.
        clock.advanceTo(epochSeconds(10_000L) + 3.days)
        val retention = retentionFor(h)
        assertNotNull(h.files.fileSize(h.paths.part(fileId)), "the accepted OFFER made a .part")
        retention.sweep(clock.now())
        assertNull(h.receives.find(fileId))
        assertNull(h.files.fileSize(h.paths.part(fileId)), "the unreferenced .part is deleted")
    }

    @Test
    fun quotaEviction_receivedFile_goesEvictedAndReopens() = runTest {
        val config = MutableStateFlow(OrchestratorConfig(storeQuotaBytes = 1_500_000L))
        val h = harness(config = config.value)
        val total = 1L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)
        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))
        assertIs<FileIngestResult.Ready>(h.engine.ingest(fileId, 0, bytes))
        // A second copy pushes the store over quota: the received file is least recently accessed.
        val retention = retentionFor(h, config)
        val store = DefaultFileStore(h.descriptors, crypto, h.files, h.backend.storeDir, config, h.clock)
        h.clock.advanceTo(h.clock.now() + 1.days)
        val otherSrc = Path(h.backend.incomingDir.toString(), "other-src.bin")
        h.files.openAppendWriter(otherSrc, null)
            .use { it.write(ByteArray(1_000_000) { (it + 1).toByte() }); it.flushAndSync() }
        val other = store.importHeld(FileImportSource.PathSource(otherSrc), "other.bin", null, keepCopy = true)
        retention.enforceQuota()

        assertEquals(FileReceiveStatus.EVICTED, h.receives.find(fileId)?.status)
        assertNull(h.descriptors.findById(fileId), "evicted descriptor is forgotten")
        assertNull(h.files.fileSize(h.paths.final(fileId)), "evicted bytes are deleted")
        assertNotNull(h.descriptors.findById(other.fileId))
        assertNull(h.engine.heldCoverage(fileId), "an evicted file is never fetched automatically")
        assertIs<FileOfferVerdict.Refuse>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))

        // A user re-open fetches it again.
        assertTrue(h.engine.reopen(fileId))
        assertNotNull(h.engine.heldCoverage(fileId))
        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))
        assertIs<FileIngestResult.Ready>(h.engine.ingest(fileId, 0, bytes))
        assertEquals(FileDescriptorStatus.HELD, h.descriptors.findById(fileId)?.status)
        assertContentEquals(bytes, readAll(h.files, h.paths.final(fileId), total.toInt()))
    }

    @Test
    fun endedReceives_needNoRequest() = runTest {
        val limited = object : FileAccess by JvmFileAccess() {
            override suspend fun freeSpaceBytes(directory: Path): Long = 10L
        }
        val h = harness(files = limited)
        val total = 1000L
        val (fileId, blockSize, hashes) = sampleFile(total)
        val refused = h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes))
        assertEquals(FileRefusalReason.NO_SPACE, assertIs<FileOfferVerdict.Refuse>(refused).reason)
        assertEquals(FileReceiveStatus.DECLINED, h.receives.find(fileId)?.status)
        assertNull(h.engine.heldCoverage(fileId), "a no-space DECLINED file is not re-requested")
    }

    @Test
    fun decliningInflight_leavesNoPart() = runTest {
        val h = harness()
        val total = 4L * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)

        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))
        h.engine.ingest(fileId, 0, bytes.copyOfRange(0, 1024))
        h.engine.decline(fileId, null, h.clock.now())
        assertEquals(FileReceiveStatus.DECLINED, h.receives.find(fileId)?.status)
        assertNull(h.files.fileSize(h.paths.part(fileId)), "declining an in-flight file drops its .part")
        assertTrue(h.receives.stagedRefsByFile(fileId).isEmpty())
        assertEquals(1, h.ends.cancels.size)
    }

    // -- Fetch append ----------------------------------------------------------------

    @Test
    fun fetchAppend_writesNoStagedRows() = runTest {
        val h = harness()
        val total = 2L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)
        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))

        var off = 0L
        while (off < total) {
            val end = minOf(off + 256 * 1024, total)
            val result = h.engine.append(fileId, off, bytes.copyOfRange(off.toInt(), end.toInt()))
            assertTrue(result is FileAppendResult.Appended || result is FileAppendResult.Ready, "got $result")
            off = end
            if (result is FileAppendResult.Ready) break
        }

        assertTrue(h.receives.stagedRefsByFile(fileId).isEmpty(), "a fetch never stages")
        assertEquals(FileReceiveStatus.READY, h.receives.find(fileId)?.status)
        assertContentEquals(bytes, readAll(h.files, h.paths.final(fileId), total.toInt()))
    }

    @Test
    fun fetchAppend_checkpointsByBytesAndByTime() = runTest {
        // Interval long enough that only the byte threshold and the explicit checkpoint move P.
        val config = OrchestratorConfig(
            fileCheckpointBytes = 1L * 1024 * 1024,
            fileCheckpointInterval = 1000.seconds,
        )
        val h = harness(config = config)
        val total = 3L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)
        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))

        // Below the byte threshold: durable in the `.part`, P unchanged.
        var r = h.engine.append(fileId, 0, bytes.copyOfRange(0, 256 * 1024))
        assertIs<FileAppendResult.Appended>(r)
        assertEquals(0L, r.committedBytes)
        assertEquals(0L, h.receives.find(fileId)?.contiguousBytes)
        assertEquals(256 * 1024L, h.files.fileSize(h.paths.part(fileId)))

        // Past the threshold: P advances without any staged rows.
        r = h.engine.append(fileId, 256 * 1024, bytes.copyOfRange(256 * 1024, 1152 * 1024))
        assertIs<FileAppendResult.Appended>(r)
        assertEquals(1152 * 1024L, r.committedBytes)
        assertTrue(h.receives.stagedRefsByFile(fileId).isEmpty())

        // Time checkpoint: more bytes without reaching the threshold, then the timer moves P.
        r = h.engine.append(fileId, 1152 * 1024, bytes.copyOfRange(1152 * 1024, 1408 * 1024))
        assertIs<FileAppendResult.Appended>(r)
        assertEquals(1152 * 1024L, r.committedBytes, "below threshold P does not move")
        h.engine.checkpoint()
        assertEquals(1408 * 1024L, h.receives.find(fileId)?.contiguousBytes)
    }

    @Test
    fun fetchAppend_mismatchReportsTheBlock() = runTest {
        val h = harness()
        val total = 2L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)
        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))

        // Corrupt the second block.
        val bad = bytes.copyOf()
        val secondStart = blockSize
        for (i in 0 until minOf(blockSize, bad.size - secondStart)) {
            bad[secondStart + i] = (bad[secondStart + i] + 1).toByte()
        }
        var off = 0L
        var mismatch: FileAppendResult.Mismatch? = null
        while (off < total && mismatch == null) {
            val end = minOf(off + 256 * 1024, total)
            when (val r = h.engine.append(fileId, off, bad.copyOfRange(off.toInt(), end.toInt()))) {
                is FileAppendResult.Mismatch -> mismatch = r
                is FileAppendResult.Ready -> error("corrupt bytes must not complete")
                is FileAppendResult.Appended -> off = end
                is FileAppendResult.NoSpace, is FileAppendResult.Ended, is FileAppendResult.Defer ->
                    error("unexpected $r")
            }
        }
        assertNotNull(mismatch, "a bad block reports mismatch")
        assertEquals(1, mismatch.blockIndex)
        // P rewinds to at most the bad block's start.
        assertTrue((h.receives.find(fileId)?.contiguousBytes ?: Long.MAX_VALUE) <= blockSize.toLong())
    }

    @Test
    fun fetchAppend_resumeFromPTruncatesUncommittedSuffix() = runTest {
        val h = harness()
        val total = 2L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)
        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))

        // Two frames below the checkpoint: durable in the `.part`, P still 0.
        h.engine.append(fileId, 0, bytes.copyOfRange(0, 256 * 1024))
        h.engine.append(fileId, 256 * 1024, bytes.copyOfRange(256 * 1024, 512 * 1024))
        assertEquals(512 * 1024L, h.files.fileSize(h.paths.part(fileId)))
        assertEquals(0L, h.receives.find(fileId)?.contiguousBytes)

        // A new fetch names P (0) while the `.part` still holds the old suffix: truncate and re-send.
        val r = h.engine.append(fileId, 0, bytes.copyOfRange(0, 256 * 1024))
        assertIs<FileAppendResult.Appended>(r)
        assertEquals(256 * 1024L, h.files.fileSize(h.paths.part(fileId)))
        assertContentEquals(
            bytes.copyOfRange(0, 256 * 1024),
            readAll(h.files, h.paths.part(fileId), 256 * 1024),
        )
    }

    @Test
    fun fetchAppend_offsetMismatchThrows() = runTest {
        val h = harness()
        val total = 1L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)
        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))
        h.engine.append(fileId, 0, bytes.copyOfRange(0, 256 * 1024))
        // Neither the committed prefix (0, below the checkpoint) nor the uncommitted end (256 KiB).
        assertFailsWith<IllegalArgumentException> {
            h.engine.append(fileId, 1, bytes.copyOfRange(1, 1 + 256 * 1024))
        }
    }

    @Test
    fun fetchAppend_fsyncsOnlyAtCheckpoints() = runTest {
        val counting = SyncCountingAccess()
        val h = harness(config = OrchestratorConfig(fileCheckpointBytes = 1L * 1024 * 1024), files = counting)
        val total = 3L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)
        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))
        counting.syncs = 0
        // 64 KiB frames: fifteen below the 1 MiB checkpoint, then the sixteenth reaches it.
        val frame = 64 * 1024
        repeat(15) { i ->
            assertIs<FileAppendResult.Appended>(
                h.engine.append(
                    fileId,
                    i.toLong() * frame,
                    bytes.copyOfRange(i * frame, (i + 1) * frame)
                )
            )
        }
        assertEquals(0, counting.syncs, "no fsync between checkpoints")
        val r = h.engine.append(fileId, 15L * frame, bytes.copyOfRange(15 * frame, 16 * frame))
        assertEquals(1024 * 1024L, assertIs<FileAppendResult.Appended>(r).committedBytes)
        assertEquals(1, counting.syncs, "one fsync for the checkpoint")
    }

    @Test
    fun fetchAppend_writeFailure_propagatesAndKeepsTheReceive() = runTest {
        val failing = FailingWriteAccess(failAfterBytes = 100L * 1024, message = "I/O error")
        val h = harness(files = failing)
        val total = 2L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)
        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))
        failing.failing = false
        h.engine.append(fileId, 0, bytes.copyOfRange(0, 256 * 1024))
        failing.failing = true
        assertFailsWith<IOException> {
            h.engine.append(fileId, 256 * 1024, bytes.copyOfRange(256 * 1024, 2 * 256 * 1024))
        }
        // Nothing is declined or deleted: the next fetch resumes from P.
        val row = assertNotNull(h.receives.find(fileId))
        assertEquals(FileReceiveStatus.IN_FLIGHT, row.status)
        assertEquals(0L, row.contiguousBytes)
        assertTrue(h.ends.cancels.isEmpty())
        failing.failing = false
        var off = 0L
        while (off < total) {
            val end = minOf(off + 256 * 1024, total)
            h.engine.append(fileId, off, bytes.copyOfRange(off.toInt(), end.toInt()))
            off = end
        }
        assertEquals(FileReceiveStatus.READY, h.receives.find(fileId)?.status)
        assertContentEquals(bytes, readAll(h.files, h.paths.final(fileId), total.toInt()))
    }

    @Test
    fun fetchAppend_diskFull_declinesTheReceive() = runTest {
        var free = Long.MAX_VALUE
        val filling = object : FileAccess by JvmFileAccess() {
            override suspend fun freeSpaceBytes(directory: Path): Long = free
        }
        val h = harness(files = filling)
        val total = 2L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)
        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))
        h.engine.append(fileId, 0, bytes.copyOfRange(0, 256 * 1024))
        free = 1024
        assertIs<FileAppendResult.NoSpace>(
            h.engine.append(
                fileId,
                256 * 1024,
                bytes.copyOfRange(256 * 1024, 512 * 1024)
            )
        )
        assertEquals(FileReceiveStatus.DECLINED, h.receives.find(fileId)?.status)
        assertNull(h.files.fileSize(h.paths.part(fileId)))
        assertEquals(listOf(fileId to FileCancelReason.DECLINED), h.ends.cancels)
    }

    @Test
    fun fetchAppend_afterTheReceiveEnded_isEnded() = runTest {
        val h = harness()
        val total = 2L * 1024 * 1024
        val (fileId, blockSize, hashes) = sampleFile(total)
        val bytes = bytesFor(total)
        assertIs<FileOfferVerdict.Accept>(h.engine.checkOffer(offerFor(h, fileId, total, blockSize, hashes)))
        h.engine.append(fileId, 0, bytes.copyOfRange(0, 256 * 1024))
        h.engine.decline(fileId, null, h.clock.now())
        // A frame still in flight after the user's decline: an expected race, not an error.
        assertIs<FileAppendResult.Ended>(h.engine.append(fileId, 256 * 1024, bytes.copyOfRange(256 * 1024, 512 * 1024)))
    }
}
