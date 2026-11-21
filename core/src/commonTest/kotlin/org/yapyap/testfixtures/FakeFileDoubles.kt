package org.yapyap.testfixtures

import org.yapyap.orchestrator.file.FileCoverageReader
import org.yapyap.orchestrator.file.FileDescriptor
import org.yapyap.orchestrator.file.FileDescriptorReader
import org.yapyap.orchestrator.file.FileDescriptorStatus
import org.yapyap.orchestrator.file.FileReceiveReader
import org.yapyap.orchestrator.file.FileReceiveRow
import org.yapyap.orchestrator.file.FileStorage
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.routing.file.port.FileAppendResult
import org.yapyap.routing.file.port.FileCoverage
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.PlaintextStream
import org.yapyap.routing.router.FakeFilePorts
import kotlin.time.Instant

/**
 * Shared configurable fake for routing's file ports (ingest + request source + serve gate),
 * replacing the per-test `FakeFileIngest`/`Gate` doubles. Knobs mirror what the tests drive:
 * held coverage, candidate accounts, serve verdict, and served sizes (per-file override wins
 * over the default; null means unknown).
 */
internal class FakeFileSource(
    var coverage: FileCoverage? = FileCoverage(0L, emptyList()),
    var candidates: List<AccountId> = emptyList(),
    var canServe: Boolean = true,
    var defaultTotalBytes: Long? = null,
    var totalBytesByFile: Map<FileId, Long?> = emptyMap(),
) : FakeFilePorts {
    var checkOffers = 0
    var ingestedBytes = 0L

    override suspend fun checkOffer(offer: FileManifest): FileOfferVerdict {
        checkOffers++
        return FileOfferVerdict.Accept
    }

    override suspend fun ingest(
        fileId: FileId,
        byteOffset: Long,
        plaintext: ByteArray,
    ): FileIngestResult {
        ingestedBytes += plaintext.size
        val total = totalBytesByFile[fileId] ?: defaultTotalBytes ?: (byteOffset + plaintext.size)
        return FileIngestResult.Staged(byteOffset + plaintext.size, total)
    }

    override suspend fun append(fileId: FileId, offset: Long, bytes: ByteArray): FileAppendResult {
        ingestedBytes += bytes.size
        val total = totalBytesByFile[fileId] ?: defaultTotalBytes ?: (offset + bytes.size)
        return FileAppendResult.Appended(offset + bytes.size)
    }

    override suspend fun heldCoverage(fileId: FileId): FileCoverage? = coverage
    override suspend fun requestCandidates(fileId: FileId): List<AccountId> = candidates
    override suspend fun canServe(fileId: FileId, requester: AccountId): Boolean = canServe
    override suspend fun requestTotalBytes(fileId: FileId): Long? =
        totalBytesByFile[fileId] ?: defaultTotalBytes
}

/** Read-only file descriptors: [held] are HELD, everything else is unknown. */
internal class FakeDescriptors : FileDescriptorReader {
    val held = mutableSetOf<FileId>()
    override suspend fun findById(fileId: FileId): FileDescriptor? {
        if (fileId !in held) return null
        return FileDescriptor(
            fileId = fileId,
            fileName = "f",
            mimeType = null,
            totalBytes = 10L,
            blockSize = 1024 * 1024,
            blockHashes = emptyList(),
            storage = FileStorage.COPY,
            pathKind = org.yapyap.orchestrator.file.FilePathKind.PATH,
            path = "/tmp/f",
            status = FileDescriptorStatus.HELD,
            createdAt = Instant.DISTANT_PAST,
            lastAccessAt = Instant.DISTANT_PAST,
        )
    }
}

/** File receives: nothing held (tests seed through the engine or the store instead). */
internal class FakeReceives : FileReceiveReader {
    override suspend fun find(fileId: FileId): FileReceiveRow? = null
    override suspend fun active(): List<FileReceiveRow> = emptyList()
}

/** Held coverage: nothing held unless a test sets [coverage] (tests needing served bytes set it). */
internal class FakeCoverage(var coverage: FileCoverage? = null) : FileCoverageReader {
    override suspend fun heldCoverage(fileId: FileId): FileCoverage? = coverage
}

/** Local identity stub reporting a fixed account. */
internal class FakeLocalIdentity(private val account: AccountId) : org.yapyap.crypto.identity.LocalIdentity {
    override suspend fun getLocalDeviceIdentity(): org.yapyap.protocol.identity.DeviceIdentity = error("not used")
    override suspend fun getLocalAccountIdentity(): org.yapyap.protocol.identity.AccountIdentity = error("not used")
    override suspend fun getLocalDeviceId(): PeerId = error("not used")
    override suspend fun getLocalAccountId(): AccountId = account
}

/** A plaintext source that holds nothing: for tests that wire the large path but never serve. */
internal object NoPlaintext : FilePlaintextSource {
    override suspend fun open(fileId: FileId, fromOffset: Long): PlaintextStream? = null
    override suspend fun describe(fileId: FileId): FileManifest? = null
}
