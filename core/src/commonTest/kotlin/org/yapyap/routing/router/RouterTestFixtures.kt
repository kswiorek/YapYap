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
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protection.envelope.HopProtection
import org.yapyap.protection.envelope.MessageProtection
import org.yapyap.protection.envelope.RatchetFileProtection
import org.yapyap.protection.envelope.RatchetMessageProtection
import org.yapyap.protection.envelope.RatchetSealer
import org.yapyap.protection.envelope.RatchetWebRtcSignalProtection
import org.yapyap.protection.envelope.SignedHopProtection
import org.yapyap.protection.envelope.WebRtcSignalProtection
import org.yapyap.protection.service.EnvelopeProtections
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.SealKey
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.FileRequest
import org.yapyap.protocol.envelopes.FileSeal
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SyncPayload
import org.yapyap.protocol.envelopes.UnsignedEnvelope
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.protocol.envelopes.WebRtcSignalKind
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.SignedPreKeyId
import org.yapyap.routing.candidate.CandidateAttemptStore
import org.yapyap.routing.candidate.CandidateRow
import org.yapyap.routing.candidate.CandidateWaiting
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
import org.yapyap.routing.policy.LinkPreferencePolicy
import org.yapyap.routing.policy.NoRelaySelection
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.presence.FakePeerAvailabilityStore
import org.yapyap.routing.presence.PeerAvailabilityRegistry
import org.yapyap.routing.store.AdmitResult
import org.yapyap.routing.store.DueOutboxRow
import org.yapyap.routing.store.FileIncomingStore
import org.yapyap.routing.store.FilePullAttempt
import org.yapyap.routing.store.FilePullAttemptStore
import org.yapyap.routing.store.FilePullClass
import org.yapyap.routing.store.FileSealStatus
import org.yapyap.routing.store.FileSealStore
import org.yapyap.routing.store.FileTransferStatus
import org.yapyap.routing.store.FileTransferStore
import org.yapyap.routing.store.IncomingSeal
import org.yapyap.routing.store.IncomingTransfer
import org.yapyap.routing.store.OutboxEntry
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.OutgoingSeal
import org.yapyap.routing.store.OutgoingTransfer
import org.yapyap.routing.store.PacketDeduplicator
import org.yapyap.routing.store.PacketOutbox
import org.yapyap.routing.store.RelayBackup
import org.yapyap.routing.store.RelayCoverage
import org.yapyap.routing.store.RelayHop
import org.yapyap.routing.store.RetryClass
import org.yapyap.routing.store.SyncAttempt
import org.yapyap.routing.store.SyncAttemptStore
import org.yapyap.routing.store.relayReservedBytes
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

/** Hop layer that signs with a zeroed signature and verifies everything. */
internal class FakeHopProtection(
    private val crypto: CryptoProvider = DefaultCryptoProvider(),
) : HopProtection {
    override suspend fun sign(envelope: UnsignedEnvelope): BinaryEnvelope =
        envelope.signed(ByteArray(BinaryEnvelope.SIGNATURE_BYTES))

    override suspend fun verify(envelope: BinaryEnvelope): Boolean = true

    override suspend fun contentId(content: ByteArray): ContentId = ContentId(crypto.sha256(content))
}

/** Plaintext message content: encode/decode with the real codec, no crypto. */
internal class FakeMessageProtection : MessageProtection {
    override suspend fun seal(payload: MessagePayload, finalTarget: PeerId): ByteArray = payload.encode()

    override suspend fun open(content: ByteArray, origin: PeerId): MessagePayload = MessagePayload.decode(content)
}

/** Plaintext signal content: the payload rides in the clear. */
internal class FakeWebRtcSignalProtection : WebRtcSignalProtection {
    override suspend fun seal(signal: WebRtcSignal): ByteArray = signal.payload

    override suspend fun open(
        content: ByteArray,
        kind: WebRtcSignalKind,
        source: PeerId,
        target: PeerId,
    ): WebRtcSignal = WebRtcSignal(kind = kind, source = source, target = target, payload = content)
}

/**
 * No real crypto — seals/REQUESTs ride as their encodings, chunk "ciphertext" is the
 * plaintext plus [FileProtection.CHUNK_OVERHEAD_BYTES] of padding so every size a relay
 * or receiver checks matches the real protection's.
 */
internal class FakeFileProtection : FileProtection {
    override suspend fun seal(seal: FileSeal, target: PeerId): ByteArray = seal.encode()

    override suspend fun openSeal(content: ByteArray, origin: PeerId): FileSeal = FileSeal.decode(content)

    override suspend fun encryptChunk(
        sealId: ContentId,
        key: SealKey,
        offset: Long,
        plaintext: ByteArray,
    ): ByteArray = plaintext + ByteArray(FileProtection.CHUNK_OVERHEAD_BYTES)

    override suspend fun decryptChunk(
        sealId: ContentId,
        key: SealKey,
        offset: Long,
        ciphertext: ByteArray,
    ): ByteArray =
        ciphertext.copyOfRange(0, ciphertext.size - FileProtection.CHUNK_OVERHEAD_BYTES)

    override suspend fun sealRequest(request: FileRequest, target: PeerId): ByteArray = request.encode()

    override suspend fun openRequest(content: ByteArray, source: PeerId): FileRequest =
        FileRequest.decode(content)

    override fun generateSealKey(): SealKey = SealKey(ByteArray(32))
}

internal fun passthroughProtections(
    crypto: CryptoProvider = DefaultCryptoProvider(),
    file: FileProtection = FakeFileProtection(),
    keySource: BootstrapKeySource = BootstrapKeySource { null },
): EnvelopeProtections = EnvelopeProtections(
    hop = FakeHopProtection(crypto),
    signal = FakeWebRtcSignalProtection(),
    message = FakeMessageProtection(),
    bootstrap = BootstrapProtection(crypto, keySource),
    file = file,
)

internal fun PassthroughFakeEnvelopeProtectionService(
    crypto: CryptoProvider = DefaultCryptoProvider(),
    fileProtection: FileProtection = FakeFileProtection(),
): EnvelopeProtections = passthroughProtections(crypto, fileProtection)

/**
 * Wraps [delegate]'s message protection and tracks how many [MessageProtection.seal] calls
 * overlap in time. Used to verify multi-peer fan-out runs concurrently rather than sequentially.
 */
internal class ConcurrencyTrackingEnvelopeProtectionService(
    delegate: EnvelopeProtections,
    private val protectDelayMillis: Long = 200,
) {
    private val protectStatsMutex = Mutex()
    private var activeProtects = 0
    var maxConcurrentProtects = 0
        private set

    private inner class TrackingMessageProtection(
        private val inner: MessageProtection,
    ) : MessageProtection {
        override suspend fun seal(payload: MessagePayload, finalTarget: PeerId): ByteArray {
            protectStatsMutex.withLock {
                activeProtects++
                if (activeProtects > maxConcurrentProtects) {
                    maxConcurrentProtects = activeProtects
                }
            }
            try {
                delay(protectDelayMillis.milliseconds)
                return inner.seal(payload, finalTarget)
            } finally {
                protectStatsMutex.withLock {
                    activeProtects--
                }
            }
        }

        override suspend fun open(content: ByteArray, origin: PeerId): MessagePayload = inner.open(content, origin)
    }

    val protections: EnvelopeProtections = EnvelopeProtections(
        hop = delegate.hop,
        signal = delegate.signal,
        message = TrackingMessageProtection(delegate.message),
        bootstrap = delegate.bootstrap,
        file = delegate.file,
    )
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
        var attempts: Int = 0,
        var nextAttemptAt: Instant = Instant.DISTANT_PAST,
        val declined: MutableSet<PeerId> = mutableSetOf()
    )

    private val rows = linkedMapOf<Uuid, Row>()
    private val mutex = Mutex()

    override suspend fun start(syncId: Uuid, firstAttemptAt: Instant) = mutex.withLock {
        if (syncId !in rows) rows[syncId] = Row(0, firstAttemptAt)
        Unit
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

    override fun candidateStore(): CandidateAttemptStore<Uuid> = object : CandidateAttemptStore<Uuid> {
        override suspend fun start(id: Uuid, firstAttemptAt: Instant) =
            this@InMemorySyncAttemptStore.start(id, firstAttemptAt)

        override suspend fun drop(id: Uuid) = this@InMemorySyncAttemptStore.drop(id)

        override suspend fun earliestDueAt(): Instant? = this@InMemorySyncAttemptStore.earliestDueAt()

        override suspend fun findDue(now: Instant, limit: Int): List<CandidateRow<Uuid>> =
            this@InMemorySyncAttemptStore.findDue(now, limit)
                .map { CandidateRow(it.syncId, it.attempts, it.declinedDevices) }

        override suspend fun recordAttempt(id: Uuid, nextAttemptAt: Instant) =
            this@InMemorySyncAttemptStore.recordAttempt(id, nextAttemptAt)

        override suspend fun postpone(id: Uuid, nextAttemptAt: Instant) =
            this@InMemorySyncAttemptStore.postpone(id, nextAttemptAt)

        override suspend fun advanceToNow(ids: Collection<Uuid>, now: Instant) =
            this@InMemorySyncAttemptStore.advanceToNow(ids, now)

        override suspend fun declinedDevices(id: Uuid): Set<PeerId> =
            this@InMemorySyncAttemptStore.declinedDevices(id)

        override suspend fun addDeclined(id: Uuid, device: PeerId) =
            this@InMemorySyncAttemptStore.addDeclined(id, device)

        override suspend fun removeDeclined(id: Uuid, devices: Collection<PeerId>) =
            this@InMemorySyncAttemptStore.removeDeclined(id, devices)

        override suspend fun setWaiting(id: Uuid, device: PeerId, until: Instant) = Unit

        override suspend fun clearWaiting(id: Uuid) = Unit
    }

    fun contains(syncId: Uuid): Boolean = rows.containsKey(syncId)
    fun attemptsOf(syncId: Uuid): Int? = rows[syncId]?.attempts
    fun nextAttemptAt(syncId: Uuid): Instant? = rows[syncId]?.nextAttemptAt
}

/** In-memory [FileSealStore], mirroring the SQL semantics. */
internal class InMemoryFileSealStore : FileSealStore {
    private val rows = linkedMapOf<ContentId, OutgoingSeal>()
    private val claimed = mutableSetOf<ContentId>()
    private val mutex = Mutex()

    override suspend fun insert(seal: OutgoingSeal) = mutex.withLock {
        rows[seal.sealId] = seal
    }

    override suspend fun find(sealId: ContentId): OutgoingSeal? = mutex.withLock { rows[sealId] }

    override suspend fun byFile(fileId: FileId): List<OutgoingSeal> = mutex.withLock {
        rows.values.filter { it.fileId == fileId }
    }

    override suspend fun byFileAndTarget(fileId: FileId, target: PeerId): List<OutgoingSeal> = mutex.withLock {
        rows.values.filter { it.fileId == fileId && it.targetDevice == target }
    }

    override suspend fun live(): List<OutgoingSeal> = mutex.withLock {
        rows.values.filter { it.status.isLive }
    }

    override suspend fun expiredAt(now: Instant): List<OutgoingSeal> = mutex.withLock {
        rows.values.filter { it.expiresAt <= now }
    }

    override suspend fun setStatus(sealId: ContentId, status: FileSealStatus) = mutex.withLock {
        rows[sealId]?.let {
            rows[sealId] = OutgoingSeal(
                sealId = it.sealId,
                fileId = it.fileId,
                targetDevice = it.targetDevice,
                kind = it.kind,
                sealBytes = it.sealBytes,
                key = it.key,
                totalBytes = it.totalBytes,
                status = status,
                createdAt = it.createdAt,
                expiresAt = it.expiresAt,
            )
        }
        Unit
    }

    override suspend fun claimDeposit(sealId: ContentId, now: Instant): Boolean = mutex.withLock {
        if (sealId in claimed) false else {
            claimed += sealId
            true
        }
    }

    override suspend fun delete(sealId: ContentId) {
        mutex.withLock {
            rows.remove(sealId)
            claimed.remove(sealId)
        }
    }

    fun all(): List<OutgoingSeal> = rows.values.toList()
}

/** In-memory [FileTransferStore], mirroring the SQL semantics. */
internal class InMemoryFileTransferStore : FileTransferStore {
    private val rows = linkedMapOf<TransferId, OutgoingTransfer>()
    private val chunks = linkedMapOf<Pair<TransferId, Int>, Uuid>()
    private val mutex = Mutex()

    override suspend fun insert(transfer: OutgoingTransfer) = mutex.withLock {
        rows[transfer.transferId] = transfer
    }

    override suspend fun find(transferId: TransferId): OutgoingTransfer? = mutex.withLock { rows[transferId] }

    override suspend fun live(): List<OutgoingTransfer> = mutex.withLock {
        rows.values.filter { it.status.isLive }
    }

    override suspend fun bySeal(sealId: ContentId): List<OutgoingTransfer> = mutex.withLock {
        rows.values.filter { it.sealId == sealId }
    }

    override suspend fun bySourceTransfer(sourceTransferId: TransferId): List<OutgoingTransfer> = mutex.withLock {
        rows.values.filter { it.sourceTransferId == sourceTransferId }
    }

    override suspend fun setStatus(transferId: TransferId, status: FileTransferStatus) = mutex.withLock {
        rows[transferId]?.let { rows[transferId] = it.copy(status = status) }
        Unit
    }

    override suspend fun setOffer(transferId: TransferId, packetId: Uuid) = mutex.withLock {
        rows[transferId]?.let { rows[transferId] = it.copy(offerPacketId = packetId) }
        Unit
    }

    override suspend fun setProgress(transferId: TransferId, nextChunkIndex: Int, contiguousAcked: Int) =
        mutex.withLock {
            rows[transferId]?.let { rows[transferId] = it.copy(nextChunkIndex = nextChunkIndex, contiguousAcked = contiguousAcked) }
            Unit
        }

    override suspend fun recordChunk(transferId: TransferId, index: Int, packetId: Uuid) = mutex.withLock {
        chunks[transferId to index] = packetId
        Unit
    }

    override suspend fun chunks(transferId: TransferId): Map<Int, Uuid> = mutex.withLock {
        chunks.entries.filter { it.key.first == transferId }.associate { it.key.second to it.value }
    }

    override suspend fun chunkByPacket(packetId: Uuid): Pair<TransferId, Int>? = mutex.withLock {
        chunks.entries.firstOrNull { it.value == packetId }?.let { it.key.first to it.key.second }
    }

    override suspend fun deleteChunk(packetId: Uuid) = mutex.withLock {
        chunks.entries.firstOrNull { it.value == packetId }?.let { chunks.remove(it.key) }
        Unit
    }

    override suspend fun deleteChunks(transferId: TransferId) = mutex.withLock {
        chunks.keys.removeAll { it.first == transferId }
        Unit
    }

    override suspend fun delete(transferId: TransferId) = mutex.withLock {
        rows.remove(transferId)
        chunks.keys.removeAll { it.first == transferId }
        Unit
    }

    fun all(): List<OutgoingTransfer> = rows.values.toList()
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

/** In-memory [FilePullAttemptStore], mirroring the SQL semantics. */
internal class InMemoryFilePullAttemptStore : FilePullAttemptStore {
    private data class Row(
        var attempts: Int = 0,
        var nextAttemptAt: Instant = Instant.DISTANT_PAST,
        var waitingDevice: PeerId? = null,
        var waitingUntil: Instant? = null,
        var attemptId: Uuid? = null,
        var pullClass: FilePullClass = FilePullClass.SMALL,
        val declined: MutableSet<PeerId> = mutableSetOf(),
    )

    private val rows = linkedMapOf<FileId, Row>()
    private val mutex = Mutex()

    override suspend fun start(fileId: FileId, pullClass: FilePullClass, firstAttemptAt: Instant) =
        mutex.withLock {
            if (fileId !in rows) rows[fileId] = Row(nextAttemptAt = firstAttemptAt, pullClass = pullClass)
            Unit
        }

    override suspend fun drop(fileId: FileId) {
        mutex.withLock { rows.remove(fileId) }
    }

    override suspend fun earliestDueAt(pullClass: FilePullClass): Instant? = mutex.withLock {
        rows.values.filter { it.pullClass == pullClass }.minOfOrNull { it.nextAttemptAt }
    }

    override suspend fun findDue(
        now: Instant,
        limit: Int,
        pullClass: FilePullClass,
    ): List<FilePullAttempt> = mutex.withLock {
        rows.filterValues { it.nextAttemptAt <= now && it.pullClass == pullClass }
            .entries.sortedBy { it.value.nextAttemptAt }.take(limit).map { (id, row) -> row.toAttempt(id) }
    }

    override suspend fun findById(fileId: FileId): FilePullAttempt? = mutex.withLock {
        rows[fileId]?.toAttempt(fileId)
    }

    override suspend fun findByAttemptId(attemptId: Uuid): FilePullAttempt? = mutex.withLock {
        rows.entries.firstOrNull { it.value.attemptId == attemptId }?.let { (id, row) -> row.toAttempt(id) }
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

    override suspend fun setWaiting(fileId: FileId, device: PeerId, until: Instant, attemptId: Uuid) =
        mutex.withLock {
            rows[fileId]?.let {
                it.waitingDevice = device
                it.waitingUntil = until
                it.attemptId = attemptId
            }
            Unit
        }

    override suspend fun clearWaiting(fileId: FileId) = mutex.withLock {
        rows[fileId]?.let {
            it.waitingDevice = null
            it.waitingUntil = null
            it.attemptId = null
        }
        Unit
    }

    override suspend fun setAttempt(fileId: FileId, attemptId: Uuid) = mutex.withLock {
        rows[fileId]?.attemptId = attemptId
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

    override suspend fun idsOf(pullClass: FilePullClass): List<FileId> = mutex.withLock {
        rows.filterValues { it.pullClass == pullClass }.keys.toList()
    }

    fun nextAttemptAt(fileId: FileId): Instant? = rows[fileId]?.nextAttemptAt

    override fun candidateStore(pullClass: FilePullClass): CandidateAttemptStore<FileId> =
        object : CandidateAttemptStore<FileId> {
            override suspend fun start(id: FileId, firstAttemptAt: Instant) =
                this@InMemoryFilePullAttemptStore.start(id, pullClass, firstAttemptAt)

            override suspend fun drop(id: FileId) = this@InMemoryFilePullAttemptStore.drop(id)

            override suspend fun earliestDueAt(): Instant? =
                this@InMemoryFilePullAttemptStore.earliestDueAt(pullClass)

            override suspend fun findDue(now: Instant, limit: Int): List<CandidateRow<FileId>> =
                this@InMemoryFilePullAttemptStore.findDue(now, limit, pullClass).map { row ->
                    val waiting = if (pullClass == FilePullClass.SMALL) row.waitingState() else null
                    CandidateRow(row.fileId, row.attempts, row.declinedDevices, waiting)
                }

            override suspend fun recordAttempt(id: FileId, nextAttemptAt: Instant) =
                this@InMemoryFilePullAttemptStore.recordAttempt(id, nextAttemptAt)

            override suspend fun postpone(id: FileId, nextAttemptAt: Instant) =
                this@InMemoryFilePullAttemptStore.postpone(id, nextAttemptAt)

            override suspend fun advanceToNow(ids: Collection<FileId>, now: Instant) =
                this@InMemoryFilePullAttemptStore.advanceToNow(ids, now)

            override suspend fun declinedDevices(id: FileId): Set<PeerId> =
                this@InMemoryFilePullAttemptStore.declinedDevices(id)

            override suspend fun addDeclined(id: FileId, device: PeerId) =
                this@InMemoryFilePullAttemptStore.addDeclined(id, device)

            override suspend fun removeDeclined(id: FileId, devices: Collection<PeerId>) =
                this@InMemoryFilePullAttemptStore.removeDeclined(id, devices)

            override suspend fun setWaiting(id: FileId, device: PeerId, until: Instant) {
                if (pullClass != FilePullClass.SMALL) return
                val attemptId = this@InMemoryFilePullAttemptStore.findById(id)?.attemptId ?: return
                this@InMemoryFilePullAttemptStore.setWaiting(id, device, until, attemptId)
            }

            override suspend fun clearWaiting(id: FileId) {
                if (pullClass == FilePullClass.SMALL) this@InMemoryFilePullAttemptStore.clearWaiting(id)
            }

            private fun FilePullAttempt.waitingState(): CandidateWaiting? {
                val device = waitingDevice ?: return null
                val until = waitingUntil ?: return null
                return CandidateWaiting(device, until)
            }
        }

    private fun Row.toAttempt(id: FileId) = FilePullAttempt(
        fileId = id,
        attempts = attempts,
        declinedDevices = declined.toSet(),
        waitingDevice = waitingDevice,
        waitingUntil = waitingUntil,
        attemptId = attemptId,
        pullClass = pullClass,
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

/** In-memory [FileIncomingStore], mirroring the SQL semantics. */
internal class InMemoryFileIncomingStore : FileIncomingStore {
    private val seals = linkedMapOf<ContentId, IncomingSeal>()
    private val transfers = linkedMapOf<TransferId, IncomingTransfer>()
    private val relaySeals = linkedMapOf<TransferId, Pair<ByteArray, Instant>>()
    private val relayChunks = linkedMapOf<Pair<TransferId, Int>, Pair<ByteArray, Instant>>()
    private val mutex = Mutex()

    override suspend fun insertSeal(seal: IncomingSeal) = mutex.withLock {
        seals[seal.sealId] = seal
    }

    override suspend fun findSeal(sealId: ContentId): IncomingSeal? = mutex.withLock { seals[sealId] }

    override suspend fun sealsByFile(fileId: FileId): List<IncomingSeal> = mutex.withLock {
        seals.values.filter { it.seal.fileId == fileId }
    }

    override suspend fun insertTransfer(transfer: IncomingTransfer): Boolean = mutex.withLock {
        if (transfer.transferId in transfers) false else {
            transfers[transfer.transferId] = transfer
            true
        }
    }

    override suspend fun findTransfer(transferId: TransferId): IncomingTransfer? = mutex.withLock {
        transfers[transferId]
    }

    override suspend fun transfersByFile(fileId: FileId): List<IncomingTransfer> = mutex.withLock {
        val sealIds = seals.values.filter { it.seal.fileId == fileId }.map { it.sealId }.toSet()
        transfers.values.filter { it.sealId in sealIds }
    }

    override suspend fun deleteTransfer(transferId: TransferId) = mutex.withLock {
        removeTransfer(transferId)
    }

    override suspend fun pruneExpired(now: Instant) = mutex.withLock {
        transfers.entries.removeAll { it.value.expiresAt <= now && it.key !in relaySeals }
        seals.entries.removeAll { it.value.expiresAt <= now }
        Unit
    }

    override suspend fun admitRelay(
        transfer: IncomingTransfer,
        sealBytes: ByteArray,
        budget: Long,
        now: Instant,
    ): AdmitResult = mutex.withLock {
        val needed = relayReservedBytes(transfer.shape, sealBytes.size)
        val hops = relayHopsLocked()
        var used = hops.sumOf { it.reservedBytes }
        val victims = mutableListOf<TransferId>()
        for (hop in hops) {
            if (used + needed <= budget) break
            victims += hop.transfer.transferId
            used -= hop.reservedBytes
        }
        if (used + needed > budget) return@withLock AdmitResult(false, emptyList())
        victims.forEach { removeTransfer(it) }
        if (transfer.transferId !in transfers) {
            transfers[transfer.transferId] = transfer
            relaySeals[transfer.transferId] = sealBytes to now
        }
        AdmitResult(true, victims)
    }

    override suspend fun relayHops(): List<RelayHop> = mutex.withLock { relayHopsLocked() }

    override suspend fun relayHop(transferId: TransferId): RelayHop? = mutex.withLock { relayHopLocked(transferId) }

    override suspend fun relaySealBytes(transferId: TransferId): ByteArray? = mutex.withLock {
        relaySeals[transferId]?.first
    }

    override suspend fun storeRelayChunk(
        transferId: TransferId,
        index: Int,
        ciphertext: ByteArray,
        now: Instant,
    ): RelayCoverage? = mutex.withLock {
        val hop = relayHopLocked(transferId) ?: return@withLock null
        relayChunks.getOrPut(transferId to index) { ciphertext to now }
        val stored = relayChunks.keys.filter { it.first == transferId }.map { it.second }.sorted()
        val contiguous = stored.withIndex().takeWhile { (position, index) -> index == position }.count()
        RelayCoverage(contiguous - 1, stored.size >= hop.transfer.shape.chunkCount)
    }

    override suspend fun relayChunk(transferId: TransferId, index: Int): ByteArray? = mutex.withLock {
        relayChunks[transferId to index]?.first
    }

    private fun relayHopsLocked(): List<RelayHop> =
        relaySeals.keys.mapNotNull { relayHopLocked(it) }
            .sortedWith(compareBy({ it.lastStoredAt }, { it.transfer.transferId.value }))

    private fun relayHopLocked(transferId: TransferId): RelayHop? {
        val transfer = transfers[transferId] ?: return null
        val (sealBytes, sealStoredAt) = relaySeals[transferId] ?: return null
        val chunks = relayChunks.filterKeys { it.first == transferId }.values
        return RelayHop(
            transfer = transfer,
            sealSize = sealBytes.size,
            storedChunks = chunks.size,
            lastStoredAt = chunks.maxOfOrNull { it.second } ?: sealStoredAt,
        )
    }

    /** The hop and, like the SQL cascade, whatever a relay holds of it. */
    private fun removeTransfer(transferId: TransferId) {
        transfers.remove(transferId)
        relaySeals.remove(transferId)
        relayChunks.keys.removeAll { it.first == transferId }
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
    val ratchet = RatchetSealer(sessionManager)
    val protection = EnvelopeProtections(
        hop = SignedHopProtection(signatureProvider, crypto),
        signal = RatchetWebRtcSignalProtection(ratchet),
        message = RatchetMessageProtection(ratchet),
        bootstrap = BootstrapProtection(crypto, bootstrapKeySource),
        file = RatchetFileProtection(ratchet, crypto),
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
        fileLimits = MutableStateFlow(
            FileLimits(
                chunkSizeBytes = fileChunkSize,
                largeFileFrameBytes = fileChunkSize,
                maxSealBytes = 64 * 1024
            )
        ),
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
        fileLimits = MutableStateFlow(
            FileLimits(
                chunkSizeBytes = fileChunkSize,
                largeFileFrameBytes = fileChunkSize,
                maxSealBytes = 64 * 1024
            )
        ),
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
    fileSealStore: FileSealStore = InMemoryFileSealStore(),
    fileTransferStore: FileTransferStore = InMemoryFileTransferStore(),
    fileIncomingStore: FileIncomingStore = InMemoryFileIncomingStore(),
    fileRequestAttemptStore: FilePullAttemptStore = InMemoryFilePullAttemptStore(),
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
        fileSealStore = fileSealStore,
        fileTransferStore = fileTransferStore,
        fileIncomingStore = fileIncomingStore,
        filePullAttemptStore = fileRequestAttemptStore,
    )
}

internal fun outboxProcessorUnderTest(
    tor: TorTransport = RecordingTorTransport(),
    webRtc: RecordingWebRtcTransport = RecordingWebRtcTransport(),
    identity: FakeIdentityResolverForRouter,
    outbox: PacketOutbox = TrackingPacketOutbox(),
    clock: Clock = FakeClock(epochSeconds(10_000L)),
    routerConfig: RouterConfig = RouterConfig(),
    relaySelection: RelaySelectionPolicy = NoRelaySelection,
): OutboxProcessor {
    val ctx = testRoutingContext(
        identity = identity,
        tor = tor,
        webRtc = webRtc,
        clock = clock,
        routerConfig = routerConfig,
        protections = passthroughProtections(),
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
        relaySelection = relaySelection,
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
    envelopeProtections: EnvelopeProtections = passthroughProtections(),
    syncPayloadProvider: SyncPayloadProvider = FakeSyncPayloadProvider(),
    filePorts: FakeFilePorts = RefusingFilePorts(),
    fileSealStore: FileSealStore = InMemoryFileSealStore(),
    fileTransferStore: FileTransferStore = InMemoryFileTransferStore(),
    fileIncomingStore: FileIncomingStore = InMemoryFileIncomingStore(),
    fileRequestAttemptStore: FilePullAttemptStore = InMemoryFilePullAttemptStore(),
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
        fileSealStore = fileSealStore,
        fileTransferStore = fileTransferStore,
        fileIncomingStore = fileIncomingStore,
        filePullAttemptStore = fileRequestAttemptStore,
    )
}
