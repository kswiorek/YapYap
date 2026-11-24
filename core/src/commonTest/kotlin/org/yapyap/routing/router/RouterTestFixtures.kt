package org.yapyap.routing.router

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.CryptoSessionConfig
import org.yapyap.crypto.e2ee.MapBackedCryptoSessionStore
import org.yapyap.crypto.e2ee.TestPeerIdentity
import org.yapyap.crypto.e2ee.manager.DefaultCryptoSessionManager
import org.yapyap.crypto.e2ee.session.X3dhHandshake
import org.yapyap.crypto.e2ee.testCryptoLimits
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.crypto.identity.LocalIdentity
import org.yapyap.crypto.identity.LocalKeys
import org.yapyap.crypto.identity.LocalSignedPreKey
import org.yapyap.crypto.identity.PeerKeys
import org.yapyap.crypto.identity.PeerPreKeyBundle
import org.yapyap.crypto.keys.EncryptionPrivateKey
import org.yapyap.crypto.keys.SigningPrivateKey
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.crypto.store.BootstrapKeySource
import org.yapyap.protection.PassthroughFileProtection
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.FileEnvelopeProtection
import org.yapyap.protection.envelope.MessageProtection
import org.yapyap.protection.envelope.PlaintextMessageProtection
import org.yapyap.protection.envelope.PlaintextSystemProtection
import org.yapyap.protection.envelope.PlaintextWebRtcSignalProtection
import org.yapyap.protection.envelope.SignedAndEncryptedMessageProtection
import org.yapyap.protection.envelope.SignedAndEncryptedWebRtcSignalProtection
import org.yapyap.protection.envelope.SignedSystemProtection
import org.yapyap.protection.envelope.SystemProtection
import org.yapyap.protection.envelope.WebRtcSignalProtection
import org.yapyap.protection.service.DefaultEnvelopeProtections
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protection.service.EnvelopeProtections
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SyncPayload
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.protocol.envelopes.WebRtcSignalEnvelope
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.SignedPreKeyId
import org.yapyap.routing.file.port.FileAppendResult
import org.yapyap.routing.file.port.FileCoverage
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileRefusalReason
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.file.port.PlaintextStream
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.link.PeerLinks
import org.yapyap.routing.message.port.FrontierSnapshotProvider
import org.yapyap.routing.message.port.SyncPayloadProvider
import org.yapyap.routing.outbound.EnvelopeDispatcher
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.RelayCopies
import org.yapyap.routing.policy.LinkPreferencePolicy
import org.yapyap.routing.presence.FakePeerAvailabilityStore
import org.yapyap.routing.presence.PeerAvailabilityRegistry
import org.yapyap.routing.store.AdmitResult
import org.yapyap.routing.store.DueOutboxRow
import org.yapyap.routing.store.FileIncomingTransfer
import org.yapyap.routing.store.FileIncomingTransferStore
import org.yapyap.routing.store.FilePush
import org.yapyap.routing.store.FilePushStatus
import org.yapyap.routing.store.FilePushStore
import org.yapyap.routing.store.FileRelayChunk
import org.yapyap.routing.store.FileRelayChunkRef
import org.yapyap.routing.store.FileRelayStatus
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.FileRelayTransfer
import org.yapyap.routing.store.FileRequestAttempt
import org.yapyap.routing.store.FileRequestAttemptStore
import org.yapyap.routing.store.FileRequestClass
import org.yapyap.routing.store.OutboxEntry
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.PacketDeduplicator
import org.yapyap.routing.store.PacketOutbox
import org.yapyap.routing.store.RelayBackup
import org.yapyap.routing.store.RetryClass
import org.yapyap.routing.store.SyncAttempt
import org.yapyap.routing.store.SyncAttemptStore
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.InMemoryOpkRepository
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.tor.transport.TorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal class PassthroughFakeEnvelopeProtectionService(
    crypto: CryptoProvider = DefaultCryptoProvider(),
    fileProtection: FileEnvelopeProtection = PassthroughFileProtection(),
) : EnvelopeProtections {
    override val signal: WebRtcSignalProtection = LenientPlaintextWebRtcSignalProtection(crypto)
    override val message: MessageProtection = LenientPlaintextMessageProtection(crypto)
    override val system: SystemProtection = LenientPlaintextSystemProtection(crypto)
    override val bootstrap: BootstrapProtection = BootstrapProtection(crypto, BootstrapKeySource { null })
    override val file: FileEnvelopeProtection = fileProtection
}

/**
 * Plaintext sealing that tolerates the production schemes the senders stamp on their
 * contexts ([SignalSecurityScheme.SIGNED] for system/signal, ENCRYPTED_AND_SIGNED for
 * messages): the payload rides in the clear either way, so routing tests exercise the real
 * send/disposition paths without keys. Strict scheme enforcement stays covered by the
 * protection unit tests, which use the strict plaintext fakes directly.
 */
internal class LenientPlaintextMessageProtection(
    private val crypto: CryptoProvider = DefaultCryptoProvider(),
) : MessageProtection {
    private val plaintext = PlaintextMessageProtection(crypto)

    override suspend fun protect(
        input: MessagePayload,
        context: EnvelopeProtectContext,
    ): MessageEnvelope =
        plaintext.protect(input, context.copy(securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY))

    override suspend fun open(envelope: MessageEnvelope): MessagePayload =
        if (envelope.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) plaintext.open(envelope)
        else MessagePayload.decode(envelope.payload)
}

/** [LenientPlaintextMessageProtection] for system envelopes. */
internal class LenientPlaintextSystemProtection(
    private val crypto: CryptoProvider = DefaultCryptoProvider(),
) : SystemProtection {
    private val plaintext = PlaintextSystemProtection(crypto)

    override suspend fun protect(
        input: SystemPayload,
        context: EnvelopeProtectContext,
    ): SystemEnvelope =
        plaintext.protect(input, context.copy(securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY))

    override suspend fun open(envelope: SystemEnvelope): SystemPayload =
        if (envelope.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) plaintext.open(envelope)
        else SystemPayload.decode(envelope.payload)
}

/** [LenientPlaintextMessageProtection] for WebRTC signals. */
internal class LenientPlaintextWebRtcSignalProtection(
    private val crypto: CryptoProvider = DefaultCryptoProvider(),
) : WebRtcSignalProtection {
    private val plaintext = PlaintextWebRtcSignalProtection(crypto)

    override suspend fun protect(
        input: WebRtcSignal,
        context: EnvelopeProtectContext,
    ): WebRtcSignalEnvelope =
        plaintext.protect(input, context.copy(securityScheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY))

    override suspend fun open(envelope: WebRtcSignalEnvelope): WebRtcSignal =
        if (envelope.securityScheme == SignalSecurityScheme.PLAINTEXT_TEST_ONLY) plaintext.open(envelope)
        else WebRtcSignal(
            kind = envelope.kind,
            source = envelope.source,
            target = envelope.target,
            payload = envelope.payload,
        )
}

/**
 * Wraps [delegate]'s message protection and tracks how many [MessageProtection.protect] calls
 * overlap in time. Used to verify multi-peer fan-out runs concurrently rather than sequentially.
 */
internal class ConcurrencyTrackingEnvelopeProtectionService(
    private val delegate: EnvelopeProtections,
    private val protectDelayMillis: Long = 200,
) : EnvelopeProtections by delegate {
    private val protectStatsMutex = Mutex()
    private var activeProtects = 0
    var maxConcurrentProtects = 0
        private set

    private inner class TrackingMessageProtection(
        private val inner: MessageProtection,
    ) : MessageProtection {
        override suspend fun protect(
            input: MessagePayload,
            context: EnvelopeProtectContext,
        ): MessageEnvelope {
            protectStatsMutex.withLock {
                activeProtects++
                if (activeProtects > maxConcurrentProtects) {
                    maxConcurrentProtects = activeProtects
                }
            }
            try {
                delay(protectDelayMillis.milliseconds)
                return inner.protect(input, context)
            } finally {
                protectStatsMutex.withLock {
                    activeProtects--
                }
            }
        }

        override suspend fun open(envelope: MessageEnvelope): MessagePayload = inner.open(envelope)
    }

    override val message: MessageProtection = TrackingMessageProtection(delegate.message)
}

/** Router transport tests have no pending syncs: nothing to ask for, nothing served. */
internal class FakeSyncPayloadProvider : SyncPayloadProvider {
    override suspend fun syncRequest(syncId: Uuid): SyncPayload.Request? = null

    override suspend fun syncCandidates(syncId: Uuid): List<AccountId> = emptyList()

    override suspend fun syncsWithCandidate(account: AccountId): List<Uuid> = emptyList()

    override suspend fun serveSync(request: SyncPayload.Request, requester: AccountId): List<DagPayload> =
        emptyList()
}

/** In-memory [SyncAttemptStore], mirroring the SQL semantics. */
internal class InMemorySyncAttemptStore : SyncAttemptStore {
    private data class Row(
        var attempts: Int,
        var nextAttemptAt: Instant,
        val declined: MutableSet<PeerId> = mutableSetOf()
    )

    private val rows = linkedMapOf<Uuid, Row>()
    private val mutex = Mutex()

    override suspend fun start(syncId: Uuid, firstAttemptAt: Instant) = mutex.withLock {
        if (syncId !in rows) rows[syncId] = Row(0, firstAttemptAt)
    }

    override suspend fun drop(syncId: Uuid) {
        mutex.withLock { rows.remove(syncId) }
    }

    override suspend fun earliestDueAt(): Instant? = mutex.withLock { rows.values.minOfOrNull { it.nextAttemptAt } }

    override suspend fun findDue(now: Instant, limit: Int): List<SyncAttempt> = mutex.withLock {
        rows.filterValues { it.nextAttemptAt <= now }.entries.take(limit)
            .map { (id, row) -> SyncAttempt(id, row.attempts, row.declined.toSet()) }
    }

    override suspend fun recordAttempt(syncId: Uuid, nextAttemptAt: Instant) = mutex.withLock {
        rows[syncId]?.let { it.attempts += 1; it.nextAttemptAt = nextAttemptAt }
        Unit
    }

    override suspend fun postpone(syncId: Uuid, nextAttemptAt: Instant) = mutex.withLock {
        rows[syncId]?.nextAttemptAt = nextAttemptAt
    }

    override suspend fun advanceToNow(syncIds: Collection<Uuid>, now: Instant) = mutex.withLock {
        syncIds.forEach { id -> rows[id]?.let { if (it.nextAttemptAt > now) it.nextAttemptAt = now } }
    }

    override suspend fun declinedDevices(syncId: Uuid): Set<PeerId> =
        mutex.withLock { rows[syncId]?.declined?.toSet().orEmpty() }

    override suspend fun addDeclined(syncId: Uuid, deviceId: PeerId) = mutex.withLock {
        rows[syncId]?.declined?.add(deviceId)
        Unit
    }

    override suspend fun removeDeclined(syncId: Uuid, deviceIds: Collection<PeerId>) = mutex.withLock {
        rows[syncId]?.declined?.removeAll(deviceIds.toSet())
        Unit
    }

    fun contains(syncId: Uuid): Boolean = rows.containsKey(syncId)
    fun attemptsOf(syncId: Uuid): Int? = rows[syncId]?.attempts
    fun nextAttemptAt(syncId: Uuid): Instant? = rows[syncId]?.nextAttemptAt
}

/** In-memory [FilePushStore], mirroring the SQL semantics. */internal class InMemoryFilePushStore : FilePushStore {
    private val rows = linkedMapOf<TransferId, FilePush>()
    private val chunks = linkedMapOf<Pair<TransferId, Int>, Uuid>()
    private val mutex = Mutex()

    override suspend fun insert(row: FilePush) = mutex.withLock {
        rows[row.transferId] = row
    }

    override suspend fun find(transferId: TransferId): FilePush? = mutex.withLock {
        rows[transferId]
    }

    override suspend fun findLiveByFileAndTarget(fileId: FileId, target: PeerId): List<FilePush> = mutex.withLock {
        rows.values.filter { it.fileId == fileId && it.targetDevice == target && it.status.isLive }
    }

    override suspend fun deleteTerminalByFileAndTarget(fileId: FileId, target: PeerId) = mutex.withLock {
        rows.values.removeAll { it.fileId == fileId && it.targetDevice == target && !it.status.isLive }
        Unit
    }

    override suspend fun live(): List<FilePush> = mutex.withLock {
        rows.values.filter { it.status.isLive }
    }

    override suspend fun allByFile(fileId: FileId): List<FilePush> = mutex.withLock {
        rows.values.filter { it.fileId == fileId }
    }

    override suspend fun setStatus(transferId: TransferId, status: FilePushStatus) = mutex.withLock {
        rows[transferId]?.let { rows[transferId] = it.copy(status = status) }
        Unit
    }

    override suspend fun setMarker(transferId: TransferId, markerPacketId: Uuid?) = mutex.withLock {
        rows[transferId]?.let { rows[transferId] = it.copy(markerPacketId = markerPacketId) }
        Unit
    }

    override suspend fun setProgress(transferId: TransferId, nextChunkIndex: Int, contiguousAcked: Int) =
        mutex.withLock {
            rows[transferId]?.let { rows[transferId] = it.copy(nextChunkIndex = nextChunkIndex, contiguousAcked = contiguousAcked) }
            Unit
        }

    override suspend fun claimDeposited(transferId: TransferId, now: Instant): Boolean = mutex.withLock {
        val row = rows[transferId] ?: return@withLock false
        if (row.depositedAt != null) return@withLock false
        rows[transferId] = row.copy(depositedAt = now)
        true
    }

    override suspend fun terminalBefore(now: Instant): List<FilePush> = mutex.withLock {
        rows.values.filter { !it.status.isLive && it.expiresAt <= now }
    }

    override suspend fun delete(transferId: TransferId) = mutex.withLock {
        rows.remove(transferId)
        chunks.keys.removeAll { it.first == transferId }
        Unit
    }

    override suspend fun recordChunk(transferId: TransferId, chunkIndex: Int, packetId: Uuid) = mutex.withLock {
        chunks[transferId to chunkIndex] = packetId
        Unit
    }

    override suspend fun chunksByTransfer(transferId: TransferId): Map<Int, Uuid> = mutex.withLock {
        chunks.entries.filter { it.key.first == transferId }.associate { it.key.second to it.value }
    }

    override suspend fun chunkByPacket(packetId: Uuid): Pair<TransferId, Int>? = mutex.withLock {
        chunks.entries.firstOrNull { it.value == packetId }?.let { it.key.first to it.key.second }
    }

    override suspend fun deleteChunkByPacket(packetId: Uuid) = mutex.withLock {
        chunks.entries.firstOrNull { it.value == packetId }?.let { chunks.remove(it.key) }
        Unit
    }

    override suspend fun deleteChunksByTransfer(transferId: TransferId) = mutex.withLock {
        chunks.keys.removeAll { it.first == transferId }
        Unit
    }

    fun all(): List<FilePush> = rows.values.toList()
}

/** In-memory [FileRelayStore], mirroring the SQL semantics (PK-dedup, cascade delete). */
internal class InMemoryFileRelayStore : FileRelayStore {
    private val transfers = linkedMapOf<TransferId, FileRelayTransfer>()
    private val chunks = linkedMapOf<Pair<TransferId, Int>, FileRelayChunk>()
    private val mutex = Mutex()

    override suspend fun insert(row: FileRelayTransfer) = mutex.withLock {
        transfers.getOrPut(row.transferId) { row }
        Unit
    }

    override suspend fun find(transferId: TransferId): FileRelayTransfer? = mutex.withLock {
        transfers[transferId]
    }

    override suspend fun live(): List<FileRelayTransfer> = mutex.withLock {
        transfers.values.toList()
    }

    override suspend fun setStatus(transferId: TransferId, status: FileRelayStatus) = mutex.withLock {
        transfers[transferId]?.let { transfers[transferId] = it.copy(status = status) }
        Unit
    }

    override suspend fun setMarker(transferId: TransferId, markerPacketId: Uuid?) = mutex.withLock {
        transfers[transferId]?.let {
            transfers[transferId] = it.copy(markerPacketId = markerPacketId, status = FileRelayStatus.FORWARDING)
        }
        Unit
    }

    override suspend fun touch(transferId: TransferId, now: Instant) = mutex.withLock {
        transfers[transferId]?.let { transfers[transferId] = it.copy(lastChunkAt = now) }
        Unit
    }

    override suspend fun reservedBytes(): Long = mutex.withLock {
        transfers.values.sumOf { it.reservedBytes }
    }

    override suspend fun delete(transferId: TransferId) = mutex.withLock {
        transfers.remove(transferId)
        chunks.keys.removeAll { it.first == transferId }
        Unit
    }

    override suspend fun chunkRefs(transferId: TransferId): List<FileRelayChunkRef> = mutex.withLock {
        chunks.values.filter { it.transferId == transferId }.sortedBy { it.chunkIndex }
            .map { FileRelayChunkRef(it.chunkIndex, it.packetId) }
    }

    override suspend fun chunkBlob(transferId: TransferId, chunkIndex: Int): ByteArray? = mutex.withLock {
        chunks[transferId to chunkIndex]?.blob
    }

    override suspend fun chunkCount(transferId: TransferId): Long = mutex.withLock {
        chunks.values.count { it.transferId == transferId }.toLong()
    }

    override suspend fun setChunkPacket(transferId: TransferId, chunkIndex: Int, packetId: Uuid) = mutex.withLock {
        chunks[transferId to chunkIndex]?.let { chunks[transferId to chunkIndex] = it.copy(packetId = packetId) }
        Unit
    }

    override suspend fun findChunkByPacket(packetId: Uuid): FileRelayChunk? = mutex.withLock {
        chunks.values.firstOrNull { it.packetId == packetId }
    }

    override suspend fun deleteChunkByPacket(packetId: Uuid) = mutex.withLock {
        chunks.keys.firstOrNull { key -> chunks[key]?.packetId == packetId }?.let { chunks.remove(it) }
        Unit
    }

    override suspend fun admit(
        row: FileRelayTransfer,
        reserveBytes: Long,
        budget: Long,
    ): AdmitResult = mutex.withLock {
        val used = transfers.values.sumOf { it.reservedBytes }
        if (used + reserveBytes <= budget) {
            transfers.getOrPut(row.transferId) { row }
            AdmitResult(true, emptyList())
        } else {
            // Choose victims read-only first: a refused admission never disturbs the store.
            val victims = mutableListOf<TransferId>()
            var remaining = used
            for (candidate in transfers.values
                .sortedWith(compareBy({ it.lastChunkAt }, { it.transferId.value }))
            ) {
                if (remaining + reserveBytes <= budget) break
                if (candidate.transferId == row.transferId) continue
                victims += candidate.transferId
                remaining -= candidate.reservedBytes
            }
            if (remaining + reserveBytes > budget) {
                AdmitResult(false, emptyList())
            } else {
                for (victim in victims) {
                    transfers.remove(victim)
                    chunks.keys.removeAll { it.first == victim }
                }
                transfers.getOrPut(row.transferId) { row }
                AdmitResult(true, victims)
            }
        }
    }

    private fun suffixCompleteLocked(transferId: TransferId): Boolean {
        val row = transfers[transferId] ?: return false
        val count = if (row.totalBytes == 0L) 0 else ((row.totalBytes - 1) / row.chunkSizeBytes + 1).toInt()
        val expected = maxOf(0, count - row.startIndex)
        return chunks.values.count { it.transferId == transferId }.toLong() >= expected
    }

    override suspend fun storeChunk(
        transferId: TransferId,
        chunkIndex: Int,
        blob: ByteArray,
        now: Instant,
    ): FileRelayStatus? = mutex.withLock {
        if (transfers[transferId] == null) return@withLock null
        chunks.getOrPut(transferId to chunkIndex) { FileRelayChunk(transferId, chunkIndex, null, blob) }
        transfers[transferId]?.let { transfers[transferId] = it.copy(lastChunkAt = now) }
        val row = transfers.getValue(transferId)
        if (row.status == FileRelayStatus.RESERVED && suffixCompleteLocked(transferId)) {
            transfers[transferId] = row.copy(status = FileRelayStatus.WHOLE)
            FileRelayStatus.WHOLE
        } else {
            row.status
        }
    }

    override suspend fun promoteIfWhole(transferId: TransferId): Boolean = mutex.withLock {
        val row = transfers[transferId] ?: return@withLock false
        if (row.status == FileRelayStatus.WHOLE) return@withLock true
        if (row.status == FileRelayStatus.RESERVED && suffixCompleteLocked(transferId)) {
            transfers[transferId] = row.copy(status = FileRelayStatus.WHOLE)
            true
        } else {
            false
        }
    }
}

/** Scriptable [FilePlaintextSource] for pusher tests. */
internal class FakeFilePlaintextSource(
    var describeImpl: (FileId) -> FileManifest? = { null },
    var openImpl: (FileId, Long) -> PlaintextStream? = { _, _ -> null },
) : FilePlaintextSource {
    override suspend fun describe(fileId: FileId): FileManifest? = describeImpl(fileId)

    override suspend fun open(fileId: FileId, fromOffset: Long): PlaintextStream? =
        openImpl(fileId, fromOffset)
}

/** In-memory [FileIncomingTransferStore], mirroring the SQL semantics. */
internal class InMemoryFileRequestAttemptStore : FileRequestAttemptStore {
    private data class Row(
        var attempts: Int = 0,
        var nextAttemptAt: Instant = Instant.DISTANT_PAST,
        var waitingDevice: PeerId? = null,
        var waitingUntil: Instant? = null,
        var requestTransferId: TransferId? = null,
        var requestClass: FileRequestClass = FileRequestClass.SMALL,
        val declined: MutableSet<PeerId> = mutableSetOf(),
    )

    private val rows = linkedMapOf<FileId, Row>()
    private val mutex = Mutex()

    override suspend fun start(fileId: FileId, requestClass: FileRequestClass, firstAttemptAt: Instant) =
        mutex.withLock {
            if (fileId !in rows) rows[fileId] = Row(nextAttemptAt = firstAttemptAt, requestClass = requestClass)
            Unit
        }

    override suspend fun drop(fileId: FileId) {
        mutex.withLock { rows.remove(fileId) }
    }

    override suspend fun earliestDueAt(requestClass: FileRequestClass): Instant? = mutex.withLock {
        rows.values.filter { it.requestClass == requestClass }.minOfOrNull { it.nextAttemptAt }
    }

    override suspend fun findDue(
        now: Instant,
        limit: Int,
        requestClass: FileRequestClass,
    ): List<FileRequestAttempt> = mutex.withLock {
        rows.filterValues { it.nextAttemptAt <= now && it.requestClass == requestClass }
            .entries.sortedBy { it.value.nextAttemptAt }.take(limit).map { (id, row) -> row.toAttempt(id) }
    }

    override suspend fun findById(fileId: FileId): FileRequestAttempt? = mutex.withLock {
        rows[fileId]?.toAttempt(fileId)
    }

    override suspend fun findByTransferId(transferId: TransferId): FileRequestAttempt? = mutex.withLock {
        rows.entries.firstOrNull { it.value.requestTransferId == transferId }?.let { (id, row) -> row.toAttempt(id) }
    }

    override suspend fun recordAttempt(fileId: FileId, nextAttemptAt: Instant) = mutex.withLock {
        rows[fileId]?.let { it.attempts += 1; it.nextAttemptAt = nextAttemptAt }
        Unit
    }

    override suspend fun postpone(fileId: FileId, nextAttemptAt: Instant) = mutex.withLock {
        rows[fileId]?.nextAttemptAt = nextAttemptAt
    }

    override suspend fun advanceToNow(fileIds: Collection<FileId>, now: Instant) = mutex.withLock {
        fileIds.forEach { id -> rows[id]?.let { if (it.nextAttemptAt > now) it.nextAttemptAt = now } }
    }

    override suspend fun setWaiting(fileId: FileId, device: PeerId, until: Instant, requestTransferId: TransferId) =
        mutex.withLock {
            rows[fileId]?.let {
                it.waitingDevice = device
                it.waitingUntil = until
                it.requestTransferId = requestTransferId
            }
            Unit
        }

    override suspend fun clearWaiting(fileId: FileId) = mutex.withLock {
        rows[fileId]?.let {
            it.waitingDevice = null
            it.waitingUntil = null
            it.requestTransferId = null
        }
        Unit
    }

    override suspend fun setRequestTransfer(fileId: FileId, transferId: TransferId) = mutex.withLock {
        rows[fileId]?.requestTransferId = transferId
        Unit
    }

    override suspend fun declinedDevices(fileId: FileId): Set<PeerId> =
        mutex.withLock { rows[fileId]?.declined?.toSet().orEmpty() }

    override suspend fun addDeclined(fileId: FileId, device: PeerId) = mutex.withLock {
        rows[fileId]?.declined?.add(device)
        Unit
    }

    override suspend fun removeDeclined(fileId: FileId, devices: Collection<PeerId>) = mutex.withLock {
        rows[fileId]?.declined?.removeAll(devices.toSet())
        Unit
    }

    override suspend fun idsOf(requestClass: FileRequestClass): List<FileId> = mutex.withLock {
        rows.filterValues { it.requestClass == requestClass }.keys.toList()
    }

    private fun Row.toAttempt(id: FileId) = FileRequestAttempt(
        fileId = id,
        attempts = attempts,
        declinedDevices = declined.toSet(),
        waitingDevice = waitingDevice,
        waitingUntil = waitingUntil,
        requestTransferId = requestTransferId,
        requestClass = requestClass,
    )
}

/** All three file ports in one test double, so a fake can be passed for each. */
internal interface FakeFilePorts : FileIngestPort, FileRequestSource

/** File ports for tests without a receive engine: declines everything cleanly. */
internal class RefusingFilePorts : FakeFilePorts {
    override suspend fun checkOffer(offer: FileManifest): FileOfferVerdict =
        FileOfferVerdict.Refuse(FileRefusalReason.NO_SPACE)

    override suspend fun ingest(
        fileId: FileId,
        byteOffset: Long,
        plaintext: ByteArray,
    ): FileIngestResult =
        FileIngestResult.Refused

    override suspend fun append(fileId: FileId, offset: Long, bytes: ByteArray): FileAppendResult =
        FileAppendResult.NoSpace

    override suspend fun heldCoverage(fileId: FileId): FileCoverage? = null
    override suspend fun requestCandidates(fileId: FileId): List<AccountId> = emptyList()
    override suspend fun canServe(fileId: FileId, requester: AccountId): Boolean = false
    override suspend fun requestTotalBytes(fileId: FileId): Long? = null
}

/** In-memory [FileIncomingTransferStore], mirroring the SQL semantics. */
internal class InMemoryFileIncomingTransferStore : FileIncomingTransferStore {
    private val rows = linkedMapOf<TransferId, FileIncomingTransfer>()
    private val mutex = Mutex()

    override suspend fun insert(row: FileIncomingTransfer) = mutex.withLock {
        rows[row.transferId] = row
    }

    override suspend fun find(transferId: TransferId): FileIncomingTransfer? = mutex.withLock {
        rows[transferId]
    }

    override suspend fun findByFile(fileId: FileId): List<FileIncomingTransfer> = mutex.withLock {
        rows.values.filter { it.fileId == fileId }
    }

    override suspend fun delete(transferId: TransferId) = mutex.withLock {
        rows.remove(transferId)
        Unit
    }

    override suspend fun deleteByFile(fileId: FileId) = mutex.withLock {
        rows.entries.removeAll { it.value.fileId == fileId }
        Unit
    }

    override suspend fun pruneExpired(now: Instant): List<FileIncomingTransfer> = mutex.withLock {
        val expired = rows.values.filter { it.expiresAt <= now }
        expired.forEach { rows.remove(it.transferId) }
        expired
    }
}


internal class InMemoryPacketDeduplicator : PacketDeduplicator {
    private val seen = mutableSetOf<Pair<PeerId, Uuid>>()
    private val nackReasons = mutableMapOf<Pair<PeerId, Uuid>, PacketNackReason>()

    override suspend fun firstSeen(packetId: Uuid, sourceDeviceId: PeerId, receivedAt: Instant): Boolean {
        val key = sourceDeviceId to packetId
        return seen.add(key)
    }

    override suspend fun clearPacket(
        packetId: Uuid,
        sourceDeviceId: PeerId
    ) {
        seen.remove(sourceDeviceId to packetId)
    }

    override suspend fun markNacked(packetId: Uuid, sourceDeviceId: PeerId, nackReason: PacketNackReason) {
        nackReasons[sourceDeviceId to packetId] = nackReason
    }

    override suspend fun getNackReason(packetId: Uuid, sourceDeviceId: PeerId): PacketNackReason? =
        nackReasons[sourceDeviceId to packetId]

    override suspend fun prune(receivedBefore: Instant) {
        // No-op for router contract tests
    }
}

internal class RecordingPacketDeduplicator(private val delegate: PacketDeduplicator) : PacketDeduplicator {
    val firstSeenCalls = mutableListOf<Triple<Uuid, PeerId, Instant>>()
    val firstSeenResults = mutableListOf<Boolean>()
    val markNackedCalls = mutableListOf<Triple<Uuid, PeerId, PacketNackReason>>()

    override suspend fun firstSeen(packetId: Uuid, sourceDeviceId: PeerId, receivedAt: Instant): Boolean {
        firstSeenCalls.add(Triple(packetId, sourceDeviceId, receivedAt))
        val result = delegate.firstSeen(packetId, sourceDeviceId, receivedAt)
        firstSeenResults.add(result)
        return result
    }

    override suspend fun clearPacket(
        packetId: Uuid,
        sourceDeviceId: PeerId
    ) {
        delegate.clearPacket(
            packetId = packetId,
            sourceDeviceId = sourceDeviceId,
        )
    }

    override suspend fun markNacked(packetId: Uuid, sourceDeviceId: PeerId, nackReason: PacketNackReason) {
        markNackedCalls.add(Triple(packetId, sourceDeviceId, nackReason))
        delegate.markNacked(packetId, sourceDeviceId, nackReason)
    }

    override suspend fun getNackReason(packetId: Uuid, sourceDeviceId: PeerId): PacketNackReason? =
        delegate.getNackReason(packetId, sourceDeviceId)

    override suspend fun prune(receivedBefore: Instant) {
        delegate.prune(receivedBefore)
    }
}

internal class FakeIdentityResolverForRouter(
    private val localDevice: DeviceIdentity,
    private val peersByAccount: Map<AccountId, List<PeerId>> = emptyMap(),
    private val torByPeer: MutableMap<PeerId, TorEndpoint> = mutableMapOf(),
    val torUpdates: MutableList<Pair<PeerId, TorEndpoint>> = mutableListOf(),
    val accountStatuses: MutableMap<AccountId, IdentityStatus> = mutableMapOf(),
    private val deviceStatuses: Map<PeerId, IdentityStatus> = emptyMap(),
    private val localAccount: AccountId = AccountId("local-account"),
) : LocalIdentity, LocalKeys, PeerKeys, DeviceDirectory, DeviceRouteStore {

    val localDeviceId: PeerId get() = localDevice.deviceId

    override suspend fun getLocalDeviceIdentity(): DeviceIdentity = localDevice

    override suspend fun getLocalAccountIdentity(): AccountIdentity =
        error("FakeIdentityResolverForRouter: account record not stubbed")

    override suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus? =
        deviceStatuses[deviceId] ?: if (isKnownDevice(deviceId)) IdentityStatus.ACTIVE else null

    private fun isKnownDevice(deviceId: PeerId): Boolean =
        deviceId == localDevice.deviceId || peersByAccount.values.any { deviceId in it }

    override suspend fun getAccountStatus(accountId: AccountId): IdentityStatus? =
        accountStatuses[accountId]

    suspend fun isLocalAccountAdmin(): Boolean =
        error("FakeIdentityResolverForRouter: admin flag not stubbed")

    suspend fun isLocalAccountOwner(): Boolean =
        error("FakeIdentityResolverForRouter: owner flag not stubbed")

    override suspend fun localDeviceSigningKey(): SigningPrivateKey =
        error("FakeIdentityResolverForRouter: private key not stubbed")

    override suspend fun localDeviceEncryptionKey(): EncryptionPrivateKey =
        error("FakeIdentityResolverForRouter: private key not stubbed")

    override suspend fun localAccountSigningKey(): SigningPrivateKey =
        error("FakeIdentityResolverForRouter: private key not stubbed")

    override suspend fun getLocalDeviceId(): PeerId = error("not used")
    override suspend fun getLocalAccountId(): AccountId = localAccount

    override suspend fun resolvePeerIdentity(deviceId: PeerId): DeviceIdentity =
        throw CryptoException.MissingDeviceRecord(deviceId.id)

    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint? =
        torByPeer[deviceId]

    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        peersByAccount[accountId].orEmpty()

    suspend fun getAllPeers(): List<PeerId> = peersByAccount.values.flatten().distinct()

    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? =
        peersByAccount.entries.firstOrNull { deviceId in it.value }?.key

    override suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) {
        torUpdates.add(deviceId to torEndpoint)
        torByPeer[deviceId] = torEndpoint
    }

    override suspend fun resolvePeerPreKeyBundle(deviceId: PeerId, signedPreKeyId: SignedPreKeyId?): PeerPreKeyBundle =
        error("not used in test")

    override suspend fun getCurrentLocalSignedPreKey(): LocalSignedPreKey =
        error("FakeIdentityResolverForRouter: signed prekey not stubbed")

    override suspend fun resolveLocalSignedPreKey(id: SignedPreKeyId): LocalSignedPreKey =
        error("FakeIdentityResolverForRouter: signed prekey not stubbed")

    override suspend fun getAllActiveDeviceIds(): List<PeerId> =
        getAllPeers().filter { getDeviceStatus(it) != IdentityStatus.BANNED }

    override suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> =
        accountIds.flatMap { getAllPeerDevicesForAccount(it) }


    override suspend fun getDeclaredTorEndpoint(deviceId: PeerId): TorEndpoint? = resolveTorEndpointForDevice(deviceId)
}

@OptIn(InternalCoroutinesApi::class)
internal class TrackingPacketOutbox : PacketOutbox {
    private data class StoredEntry(
        val envelope: BinaryEnvelope,
        var nextRetryAt: Instant,
        var attempts: Long,
        val relayMessage: Boolean,
        val expiresAt: Instant,
        val blobSize: Long,
        val targetEndpoint: TorEndpoint? = null,
        var relayBackup: RelayBackup = RelayBackup.NONE,
        val tag: OutboxTag? = null,
        val retryClass: RetryClass = RetryClass.STANDARD,
    )

    private val entries = linkedMapOf<Uuid, StoredEntry>()
    private val lock = SynchronizedObject()
    val enqueued = mutableListOf<BinaryEnvelope>()

    /** Every tag ever enqueued, kept after the row leaves (delivered, removed, expired). */
    val enqueuedTags = mutableListOf<OutboxTag>()
    val removeCalls = mutableListOf<Uuid>()
    val recordAttemptCalls = mutableListOf<Triple<Uuid, Instant, Instant>>()
    val setDueForTargetCalls = mutableListOf<Pair<PeerId, Instant>>()
    override suspend fun enqueue(
        envelope: BinaryEnvelope,
        nextRetryAt: Instant,
        relayMessage: Boolean,
        targetEndpoint: TorEndpoint?,
        relayBackup: RelayBackup,
        tag: OutboxTag?,
        retryClass: RetryClass,
    ) {
        val blobSize = envelope.encode().size.toLong()
        synchronized(lock) {
            entries[envelope.packetId] = StoredEntry(
                envelope = envelope,
                nextRetryAt = nextRetryAt,
                attempts = 0,
                relayMessage = relayMessage,
                expiresAt = envelope.expiresAt,
                blobSize = blobSize,
                targetEndpoint = targetEndpoint,
                relayBackup = relayBackup,
                tag = tag,
                retryClass = retryClass,
            )
            if (tag != null) enqueuedTags.add(tag)
        }
        enqueued.add(envelope)
    }

    fun relayBackupOf(packetId: Uuid): RelayBackup? = synchronized(lock) { entries[packetId]?.relayBackup }

    override suspend fun claimRelayBackup(packetId: Uuid): Boolean = synchronized(lock) {
        val entry = entries[packetId] ?: return@synchronized false
        if (entry.relayBackup != RelayBackup.PENDING) return@synchronized false
        entry.relayBackup = RelayBackup.DEPOSITED
        true
    }

    override suspend fun remove(packetId: Uuid): OutboxEntry? {
        removeCalls.add(packetId)
        return synchronized(lock) { entries.remove(packetId)?.toOutboxEntry() }
    }

    override suspend fun hasTagged(tag: OutboxTag): Boolean =
        synchronized(lock) { entries.values.any { it.tag == tag } }

    fun tagOf(packetId: Uuid): OutboxTag? = synchronized(lock) { entries[packetId]?.tag }

    override suspend fun setDueForTarget(target: PeerId, nextRetryAt: Instant) {
        setDueForTargetCalls.add(target to nextRetryAt)
        synchronized(lock) {
            for (entry in entries.values) {
                if (entry.envelope.target == target && entry.nextRetryAt > nextRetryAt) {
                    entry.nextRetryAt = nextRetryAt
                }
            }
        }
    }

    override suspend fun recordAttempt(packetId: Uuid, nextRetryAt: Instant, at: Instant) {
        recordAttemptCalls.add(Triple(packetId, nextRetryAt, at))
        synchronized(lock) {
            val entry = entries[packetId] ?: return
            entry.attempts += 1
            entry.nextRetryAt = nextRetryAt
        }
    }

    val deferRetryCalls = mutableListOf<Pair<Uuid, Instant>>()

    override suspend fun deferRetry(packetId: Uuid, nextRetryAt: Instant) {
        deferRetryCalls.add(packetId to nextRetryAt)
        synchronized(lock) { entries[packetId]?.nextRetryAt = nextRetryAt }
    }

    override suspend fun listAllForTarget(target: PeerId): List<OutboxEntry> =
        synchronized(lock) {
            entries.values
                .filter { it.envelope.target == target }
                .map { it.toOutboxEntry() }
        }

    override suspend fun listDueIds(
        now: Instant,
        nonFileLimit: Int,
        fileLimit: Int,
        excludeTargets: Collection<PeerId>,
    ): List<DueOutboxRow> =
        synchronized(lock) {
            val excluded = excludeTargets.map { it.id }.toSet()
            val due = entries.values.filter { it.nextRetryAt <= now && it.envelope.target.id !in excluded }
            val (file, nonFile) = due.partition { it.tag?.kind in OutboxTag.Kind.FILE_KINDS }
            (nonFile.take(nonFileLimit) + file.take(fileLimit)).map { stored ->
                DueOutboxRow(
                    packetId = stored.envelope.packetId,
                    target = stored.envelope.target,
                    attempts = stored.attempts,
                    tag = stored.tag,
                    retryClass = stored.retryClass,
                )
            }
        }

    val getByPacketIdCalls = mutableListOf<Uuid>()

    override suspend fun getByPacketId(packetId: Uuid): OutboxEntry? {
        getByPacketIdCalls.add(packetId)
        return synchronized(lock) { entries[packetId]?.toOutboxEntry() }
    }

    override suspend fun deleteByTag(tag: OutboxTag) {
        synchronized(lock) { entries.entries.removeAll { it.value.tag == tag } }
    }

    override suspend fun packetIdsByTag(tag: OutboxTag): List<Uuid> =
        synchronized(lock) { entries.values.filter { it.tag == tag }.map { it.envelope.packetId } }

    override suspend fun pruneExpired(now: Instant): List<OutboxEntry> =
        synchronized(lock) {
            val expired = entries.filterValues { it.expiresAt <= now }
            expired.keys.forEach { entries.remove(it) }
            expired.values.map { it.toOutboxEntry() }
        }

    override suspend fun earliestPendingRetryAt(): Instant? =
        synchronized(lock) { entries.values.map { it.nextRetryAt }.minOrNull() }

    override suspend fun earliestPendingRetryAfter(after: Instant): Instant? =
        synchronized(lock) { entries.values.map { it.nextRetryAt }.filter { it > after }.minOrNull() }

    override suspend fun relayCacheBytes(): Long =
        synchronized(lock) {
            entries.values.filter { it.relayMessage }.sumOf { it.blobSize }
        }

    override suspend fun pruneRelayOverCapacity(maxBytes: Long): Int {
        var evicted = 0
        while (relayCacheBytes() > maxBytes) {
            val victim = synchronized(lock) {
                entries.values
                    .filter { it.relayMessage }
                    .minWithOrNull(compareBy<StoredEntry> { it.expiresAt }.thenBy { it.envelope.packetId })
            } ?: break
            synchronized(lock) { entries.remove(victim.envelope.packetId) }
            evicted++
        }
        return evicted
    }

    fun contains(packetId: Uuid): Boolean = synchronized(lock) { entries.containsKey(packetId) }

    fun getNextRetryAt(packetId: Uuid): Instant? = synchronized(lock) { entries[packetId]?.nextRetryAt }

    fun getAttempts(packetId: Uuid): Long = synchronized(lock) { entries[packetId]?.attempts ?: 0L }

    private fun StoredEntry.toOutboxEntry(): OutboxEntry =
        OutboxEntry(
            packetId = envelope.packetId,
            envelope = envelope,
            nextRetryAt = nextRetryAt,
            attempts = attempts,
            targetEndpoint = targetEndpoint,
            tag = tag,
            retryClass = retryClass,
            relayBackup = relayBackup,
        )
}

internal class E2eeIdentityResolverForRouter(
    private val local: TestPeerIdentity,
    private val peers: Map<PeerId, TestPeerIdentity>,
    private val peersByAccount: Map<AccountId, List<PeerId>> = emptyMap(),
    private val torByPeer: MutableMap<PeerId, TorEndpoint> = mutableMapOf(),
    val torUpdates: MutableList<Pair<PeerId, TorEndpoint>> = mutableListOf(),
    private val crypto: CryptoProvider = DefaultCryptoProvider(),
    private val accountStatuses: Map<AccountId, IdentityStatus> = emptyMap(),
    private val deviceStatuses: Map<PeerId, IdentityStatus> = emptyMap(),
    private val localAccount: AccountId = AccountId("local-account"),
) : LocalIdentity, LocalKeys, PeerKeys, DeviceDirectory, DeviceRouteStore {

    val localDeviceId: PeerId get() = local.device.deviceId

    override suspend fun getLocalDeviceIdentity(): DeviceIdentity = local.device

    override suspend fun getLocalAccountIdentity(): AccountIdentity =
        error("E2eeIdentityResolverForRouter: account record not stubbed")

    override suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus? =
        deviceStatuses[deviceId]
            ?: if (deviceId == local.device.deviceId || deviceId in peers) IdentityStatus.ACTIVE else null

    override suspend fun getAccountStatus(accountId: AccountId): IdentityStatus? =
        accountStatuses[accountId]

    suspend fun isLocalAccountAdmin(): Boolean =
        error("E2eeIdentityResolverForRouter: admin flag not stubbed")

    suspend fun isLocalAccountOwner(): Boolean =
        error("E2eeIdentityResolverForRouter: owner flag not stubbed")

    override suspend fun localDeviceSigningKey(): SigningPrivateKey = local.signingPrivateKey

    override suspend fun localDeviceEncryptionKey(): EncryptionPrivateKey = local.encryptionPrivateKey

    override suspend fun localAccountSigningKey(): SigningPrivateKey =
        error("E2eeIdentityResolverForRouter: account private key not stubbed")

    override suspend fun getLocalDeviceId(): PeerId = local.device.deviceId
    override suspend fun getLocalAccountId(): AccountId = localAccount

    override suspend fun resolvePeerIdentity(deviceId: PeerId): DeviceIdentity =
        peers[deviceId]?.device ?: throw CryptoException.MissingDeviceRecord(deviceId.id)

    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint? =
        torByPeer[deviceId]

    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        peersByAccount[accountId].orEmpty()

    suspend fun getAllPeers(): List<PeerId> = peers.keys.toList()

    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? =
        peersByAccount.entries.firstOrNull { deviceId in it.value }?.key

    override suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) {
        torUpdates.add(deviceId to torEndpoint)
        torByPeer[deviceId] = torEndpoint
    }

    override suspend fun resolvePeerPreKeyBundle(
        deviceId: PeerId,
        signedPreKeyId: SignedPreKeyId?,
    ): PeerPreKeyBundle {
        val device = resolvePeerIdentity(deviceId)
        val signedPreKey = when {
            signedPreKeyId != null -> {
                device.signedPreKey?.takeIf { it.id == signedPreKeyId }
                    ?: error("Signed prekey not found: $signedPreKeyId")
            }

            else -> device.signedPreKey
                ?: error("Missing signed prekey on roster for deviceId=$deviceId")
        }
        require(crypto.verifyDetached(device.signingKey, signedPreKey.publicKey.bytes, signedPreKey.signature)) {
            "failed to verify signed prekey signature"
        }
        return PeerPreKeyBundle(identityKey = device.encryptionKey, signedPreKey = signedPreKey)
    }

    override suspend fun getCurrentLocalSignedPreKey(): LocalSignedPreKey = local.signedPreKey

    override suspend fun resolveLocalSignedPreKey(id: SignedPreKeyId): LocalSignedPreKey {
        require(id == local.signedPreKey.id) { "Signed prekey not found: $id" }
        return local.signedPreKey
    }

    override suspend fun getAllActiveDeviceIds(): List<PeerId> =
        getAllPeers().filter { getDeviceStatus(it) != IdentityStatus.BANNED }

    override suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> =
        accountIds.flatMap { getAllPeerDevicesForAccount(it) }


    override suspend fun getDeclaredTorEndpoint(deviceId: PeerId): TorEndpoint? = resolveTorEndpointForDevice(deviceId)
}

internal data class E2eeRouterTestStack(
    val peer: TestPeerIdentity,
    val identity: E2eeIdentityResolverForRouter,
    val protection: EnvelopeProtections,
)

internal fun buildE2eeRouterStack(
    local: TestPeerIdentity,
    remote: TestPeerIdentity,
    peersByAccount: Map<AccountId, List<PeerId>>,
    torByPeer: MutableMap<PeerId, TorEndpoint>,
    clock: Clock = FakeClock(epochSeconds(10_000L)),
    crypto: CryptoProvider = DefaultCryptoProvider(),
    bootstrapKeySource: BootstrapKeySource = BootstrapKeySource { null },
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
        localIdentity = identity,
        localKeys = identity,
        peerKeys = identity,
        opkRepository = InMemoryOpkRepository(crypto),
        clock = clock,
        cryptoLimits = MutableStateFlow(testCryptoLimits()),
        sessionConfig = MutableStateFlow(
            CryptoSessionConfig()
        )
    )
    val signatureProvider = DefaultSignatureProvider(identity, identity, crypto)
    val protection = DefaultEnvelopeProtections(
        signal = SignedAndEncryptedWebRtcSignalProtection(signatureProvider, sessionManager, crypto),
        message = SignedAndEncryptedMessageProtection(signatureProvider, sessionManager, crypto),
        system = SignedSystemProtection(signatureProvider, crypto),
        bootstrap = BootstrapProtection(crypto, bootstrapKeySource),
        file = PassthroughFileProtection(),
    )
    return E2eeRouterTestStack(
        peer = local,
        identity = identity,
        protection = protection,
    )
}

internal class FakeFrontierSnapshotProvider : FrontierSnapshotProvider {
    override suspend fun latestRoomFrontiers(account: AccountId): List<Pair<RoomId, List<Uuid>>> = emptyList()
}

internal fun testRoutingContext(
    identity: FakeIdentityResolverForRouter,
    tor: TorTransport,
    webRtc: RecordingWebRtcTransport,
    clock: Clock,
    routerConfig: RouterConfig,
    protections: EnvelopeProtections,
    fileChunkSize: Int = 220 * 1024,
): RoutingContext {
    val peerLinks = PeerLinks(identity, webRtc, tor)
    return RoutingContext(
        localIdentity = identity,
        localDeviceId = identity.localDeviceId,
        devices = identity,
        torTransport = tor,
        webRtcTransport = webRtc,
        peerLinks = peerLinks,
        transportLimits = MutableStateFlow(testTransportLimits()),
        fileLimits = MutableStateFlow(FileLimits(chunkSizeBytes = fileChunkSize, fetchFrameBytes = fileChunkSize)),
        envelopeProtections = protections,
        clock = clock,
        routerConfig = MutableStateFlow(routerConfig),
    )
}

internal fun testRoutingContext(
    identity: E2eeIdentityResolverForRouter,
    tor: TorTransport,
    webRtc: RecordingWebRtcTransport,
    clock: Clock,
    routerConfig: RouterConfig,
    protections: EnvelopeProtections,
    fileChunkSize: Int = 220 * 1024,
): RoutingContext {
    val peerLinks = PeerLinks(identity, webRtc, tor)
    return RoutingContext(
        localIdentity = identity,
        localDeviceId = identity.localDeviceId,
        devices = identity,
        torTransport = tor,
        webRtcTransport = webRtc,
        peerLinks = peerLinks,
        transportLimits = MutableStateFlow(testTransportLimits()),
        fileLimits = MutableStateFlow(FileLimits(chunkSizeBytes = fileChunkSize, fetchFrameBytes = fileChunkSize)),
        envelopeProtections = protections,
        clock = clock,
        routerConfig = MutableStateFlow(routerConfig),
    )
}

internal fun e2eeRouterUnderTest(
    stack: E2eeRouterTestStack,
    tor: RecordingTorTransport,
    webRtc: RecordingWebRtcTransport = RecordingWebRtcTransport(),
    dedup: PacketDeduplicator = InMemoryPacketDeduplicator(),
    outbox: PacketOutbox = TrackingPacketOutbox(),
    clock: Clock = FakeClock(epochSeconds(10_000L)),
    routerConfig: RouterConfig = RouterConfig(),
    syncPayloadProvider: SyncPayloadProvider = FakeSyncPayloadProvider(),
    filePorts: FakeFilePorts = RefusingFilePorts(),
    fileIncomingStore: FileIncomingTransferStore = InMemoryFileIncomingTransferStore(),
    filePushStore: FilePushStore = InMemoryFilePushStore(),
    fileRelayStore: FileRelayStore = InMemoryFileRelayStore(),
    fileRequestAttemptStore: FileRequestAttemptStore = InMemoryFileRequestAttemptStore(),
    plaintextSource: FilePlaintextSource = FakeFilePlaintextSource(),
    fileChunkSize: kotlinx.coroutines.flow.StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(220 * 1024),
): DefaultRouter {
    val ctx = testRoutingContext(
        identity = stack.identity,
        tor = tor,
        webRtc = webRtc,
        clock = clock,
        routerConfig = routerConfig,
        protections = stack.protection,
        fileChunkSize = fileChunkSize.value,
    )
    return DefaultRouter(
        ctx = ctx,
        routes = stack.identity,
        packetDeduplicator = dedup,
        packetOutbox = outbox,
        peerAvailabilityStore = FakePeerAvailabilityStore(),
        syncAttemptStore = InMemorySyncAttemptStore(),
        syncPayloadProvider = syncPayloadProvider,
        frontierSnapshotProvider = FakeFrontierSnapshotProvider(),
        fileIngest = filePorts,
        fileRequests = filePorts,
        filePlaintext = plaintextSource,
        fileIncomingStore = fileIncomingStore,
        filePushStore = filePushStore,
        fileRelayStore = fileRelayStore,
        fileRequestAttemptStore = fileRequestAttemptStore,
    )
}

internal fun outboxProcessorUnderTest(
    tor: TorTransport = RecordingTorTransport(),
    webRtc: RecordingWebRtcTransport = RecordingWebRtcTransport(),
    identity: FakeIdentityResolverForRouter,
    outbox: PacketOutbox = TrackingPacketOutbox(),
    clock: Clock = FakeClock(epochSeconds(10_000L)),
    routerConfig: RouterConfig = RouterConfig(),
    relayCopies: (OutboxTag.Kind) -> RelayCopies? = { null },
): OutboxProcessor {
    val ctx = testRoutingContext(
        identity = identity,
        tor = tor,
        webRtc = webRtc,
        clock = clock,
        routerConfig = routerConfig,
        protections = PassthroughFakeEnvelopeProtectionService(),
        fileChunkSize = 220 * 1024,
    )
    val peerAvailabilityRegistry = PeerAvailabilityRegistry(
        clock = clock,
        routerConfig = MutableStateFlow(routerConfig),
        store = FakePeerAvailabilityStore(),
    )
    val linkKeeper = LinkKeeper(
        name = "Tor-test",
        sessions = ctx.peerLinks.tor,
        targets = { emptyList() },
        ready = kotlinx.coroutines.flow.flowOf(false),
        config = MutableStateFlow(routerConfig.torLinkKeeperConfig),
        clock = clock,
    )
    return OutboxProcessor(
        ctx = ctx,
        dispatcher = EnvelopeDispatcher(ctx, identity),
        transportPolicy = LinkPreferencePolicy(MutableStateFlow(routerConfig)),
        packetOutbox = outbox,
        maxIdlePoll = MutableStateFlow(routerConfig.retryLoopMaxIdlePoll),
        peerAvailabilityRegistry = peerAvailabilityRegistry,
        linkKeeper = linkKeeper,
        relayCopies = relayCopies,
    )
}

internal fun defaultRouterUnderTest(
    tor: TorTransport = RecordingTorTransport(),
    webRtc: RecordingWebRtcTransport = RecordingWebRtcTransport(),
    identity: FakeIdentityResolverForRouter,
    dedup: PacketDeduplicator = InMemoryPacketDeduplicator(),
    outbox: PacketOutbox = TrackingPacketOutbox(),
    clock: Clock = FakeClock(epochSeconds(10_000L)),
    routerConfig: RouterConfig = RouterConfig(),
    envelopeProtections: EnvelopeProtections = PassthroughFakeEnvelopeProtectionService(),
    syncPayloadProvider: SyncPayloadProvider = FakeSyncPayloadProvider(),
    filePorts: FakeFilePorts = RefusingFilePorts(),
    fileIncomingStore: FileIncomingTransferStore = InMemoryFileIncomingTransferStore(),
    filePushStore: FilePushStore = InMemoryFilePushStore(),
    fileRelayStore: FileRelayStore = InMemoryFileRelayStore(),
    fileRequestAttemptStore: FileRequestAttemptStore = InMemoryFileRequestAttemptStore(),
    plaintextSource: FilePlaintextSource = FakeFilePlaintextSource(),
    fileChunkSize: kotlinx.coroutines.flow.StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(220 * 1024),
): DefaultRouter {
    val ctx = testRoutingContext(
        identity = identity,
        tor = tor,
        webRtc = webRtc,
        clock = clock,
        routerConfig = routerConfig,
        protections = envelopeProtections,
        fileChunkSize = fileChunkSize.value,
    )
    return DefaultRouter(
        ctx = ctx,
        routes = identity,
        packetDeduplicator = dedup,
        packetOutbox = outbox,
        peerAvailabilityStore = FakePeerAvailabilityStore(),
        syncAttemptStore = InMemorySyncAttemptStore(),
        syncPayloadProvider = syncPayloadProvider,
        frontierSnapshotProvider = FakeFrontierSnapshotProvider(),
        fileIngest = filePorts,
        fileRequests = filePorts,
        filePlaintext = plaintextSource,
        fileIncomingStore = fileIncomingStore,
        filePushStore = filePushStore,
        fileRelayStore = fileRelayStore,
        fileRequestAttemptStore = fileRequestAttemptStore,
    )
}
