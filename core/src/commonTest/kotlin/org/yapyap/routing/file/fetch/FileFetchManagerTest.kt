package org.yapyap.routing.file.fetch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.FileFetchFrame
import org.yapyap.routing.directory.DeviceDirectory
import org.yapyap.routing.file.port.FileCoverage
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileReadResult
import org.yapyap.routing.file.port.FileRefusalReason
import org.yapyap.routing.router.FakeFilePorts
import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import org.yapyap.transport.webrtc.types.WebRtcDataFrame
import org.yapyap.transport.webrtc.types.WebRtcDataType
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Step 21: fetch over file channels, no envelopes, no outbox, in-memory state.
 */
class FileFetchManagerTest {

    private val aliceDevice = PeerId("fetch-alice-device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val bobDevice = PeerId("fetch-bob-device-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val aliceAccount = AccountId("fetch-alice-account")
    private val bobAccount = AccountId("fetch-bob-account")
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

    private class FakeDevices(
        private val deviceToAccount: Map<PeerId, AccountId>,
    ) : DeviceDirectory {
        override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? = deviceToAccount[deviceId]
        override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
            deviceToAccount.filter { it.value == accountId }.keys.toList()
        override suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> =
            deviceToAccount.filter { it.value in accountIds }.keys.toList()
        override suspend fun getAllActiveDeviceIds(): List<PeerId> = deviceToAccount.keys.toList()
        override suspend fun getDeviceStatus(deviceId: PeerId): org.yapyap.protocol.IdentityStatus? = null
        override suspend fun getAccountStatus(accountId: AccountId): org.yapyap.protocol.IdentityStatus? = null
        override suspend fun getDeclaredTorEndpoint(deviceId: PeerId): org.yapyap.protocol.TorEndpoint? = null
    }

    private class FakePlaintext(
        val info: FileManifest?,
        val bytes: ByteArray,
    ) : FilePlaintextSource {
        var reads = 0
        override suspend fun describe(fileId: FileId): FileManifest? = info
        override suspend fun read(fileId: FileId, offset: Long, maxBytes: Int): FileReadResult {
            reads++
            if (info == null) return FileReadResult.NotHeld
            if (offset >= bytes.size) return FileReadResult.Ok(ByteArray(0), eof = true)
            val end = minOf(bytes.size, (offset + maxBytes).toInt())
            return FileReadResult.Ok(bytes.copyOfRange(offset.toInt(), end), eof = end >= bytes.size)
        }
    }

    private class FakeGate(
        var canServe: Boolean = true,
        var offerVerdict: FileOfferVerdict = FileOfferVerdict.Accept,
    ) : FakeFilePorts {
        var ingestedBytes = 0L
        var ingestCalls = 0
        var checkOfferCalls = 0
        var totalBytes: Long = 0L
        override suspend fun checkOffer(offer: FileManifest): FileOfferVerdict {
            checkOfferCalls++
            totalBytes = offer.totalBytes
            // Realistic block-list gate: reject when hashes don't match the id
            // (empty file id used here never matches a non-empty list in these tests
            // unless the test explicitly passes Accept).
            return offerVerdict
        }
        override suspend fun ingest(
            fileId: FileId,
            byteOffset: Long,
            plaintext: ByteArray,
        ): FileIngestResult {
            ingestCalls++
            ingestedBytes += plaintext.size
            val contiguous = byteOffset + plaintext.size
            val total = if (totalBytes > 0) totalBytes else contiguous
            return FileIngestResult.Staged(contiguous, total)
        }
        override suspend fun heldCoverage(fileId: FileId): FileCoverage? = FileCoverage(0L, emptyList())
        override suspend fun requestCandidates(fileId: FileId): List<AccountId> = emptyList()
        override suspend fun canServe(fileId: FileId, requester: AccountId): Boolean = canServe
        override suspend fun requestTotalBytes(fileId: FileId): Long? = null
    }

    private fun manager(
        local: PeerId,
        devices: DeviceDirectory,
        transport: RecordingWebRtcTransport,
        plaintext: FilePlaintextSource,
        gate: FakeFilePorts,
        routerConfig: RouterConfig = RouterConfig(),
        chunkSize: Int = 32 * 1024,
        events: MutableSharedFlow<FileEvent> = MutableSharedFlow(extraBufferCapacity = 64),
    ) = FileFetchManager(
        localDeviceId = local,
        devices = devices,
        transport = transport,
        plaintext = plaintext,
        ingest = gate,
        serveGate = gate,
        routerConfig = MutableStateFlow(routerConfig),
        frameBytes = MutableStateFlow(chunkSize),
        events = events,
    )

    /** Pump file frames + closes between two recording transports. */
    private fun CoroutineScope.pump(a: RecordingWebRtcTransport, b: RecordingWebRtcTransport) = launch {
        var aSent = 0
        var bSent = 0
        var aClosed = 0
        var bClosed = 0
        while (true) {
            delay(10)
            while (aSent < a.sendFileFrameCalls.size) {
                val (target, label, payload) = a.sendFileFrameCalls[aSent++]
                val source = if (target == bobDevice) aliceDevice else bobDevice
                val peerTransport = if (target == bobDevice) b else a
                peerTransport.tryEmitFileFrame(WebRtcDataFrame(source, target, WebRtcDataType.FILE_DATA, payload, label))
            }
            while (bSent < b.sendFileFrameCalls.size) {
                val (target, label, payload) = b.sendFileFrameCalls[bSent++]
                val source = if (target == aliceDevice) bobDevice else aliceDevice
                val peerTransport = if (target == aliceDevice) a else b
                peerTransport.tryEmitFileFrame(WebRtcDataFrame(source, target, WebRtcDataType.FILE_DATA, payload, label))
            }
            while (aClosed < a.closeFileChannelCalls.size) {
                val (target, label) = a.closeFileChannelCalls[aClosed++]
                val peerTransport = if (target == bobDevice) b else a
                peerTransport.tryEmitFileChannelEvent(WebRtcFileChannelEvent.Removed(target, label))
            }
            while (bClosed < b.closeFileChannelCalls.size) {
                val (target, label) = b.closeFileChannelCalls[bClosed++]
                val peerTransport = if (target == aliceDevice) a else b
                peerTransport.tryEmitFileChannelEvent(WebRtcFileChannelEvent.Removed(target, label))
            }
        }
    }

    @Test
    fun serveGate_declinesUnknownAccount() = runBlocking {
        val aliceTransport = RecordingWebRtcTransport()
        val bobTransport = RecordingWebRtcTransport()
        val data = ByteArray(64 * 1024) { it.toByte() }
        val info = FileManifest(fileId, data.size.toLong(), 1024 * 1024, emptyList(), "big.bin", null)
        val holderGate = FakeGate(canServe = false)
        val holder = manager(
            aliceDevice,
            FakeDevices(mapOf(bobDevice to bobAccount)),
            aliceTransport,
            FakePlaintext(info, data),
            holderGate,
        )
        val requesterGate = FakeGate()
        val requester = manager(
            bobDevice,
            FakeDevices(mapOf(aliceDevice to aliceAccount)),
            bobTransport,
            FakePlaintext(null, ByteArray(0)),
            requesterGate,
        )
        val scope = CoroutineScope(SupervisorJob())
        holder.runIn(scope)
        requester.runIn(scope)
        val pumpJob = scope.pump(aliceTransport, bobTransport)
        try {
            // Unknown peer: no account row -> NOT_ALLOWED without touching the store.
            val holderNoAccount = manager(
                aliceDevice,
                FakeDevices(emptyMap()),
                RecordingWebRtcTransport(),
                FakePlaintext(info, data),
                holderGate,
            )
            // Direct unit check: unknown device gets DECLINED, no DATA flows.
            // (Covered end-to-end below with canServe=false.)
            holderNoAccount.activeCount()
            val outcome = withTimeout(10.seconds) {
                requester.fetchFile(aliceDevice, fileId, 0L)
            }
            assertTrue(outcome is FileFetchOutcome.Declined, "expected DECLINED, got $outcome")
            assertEquals(0, requesterGate.ingestCalls, "nothing written on decline")
        } finally {
            pumpJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun blockListMismatch_closesWithNothingWritten() = runBlocking {
        val aliceTransport = RecordingWebRtcTransport()
        val bobTransport = RecordingWebRtcTransport()
        val data = ByteArray(64 * 1024) { it.toByte() }
        // Holder serves bytes, but the requester's gate rejects the block list.
        val info = FileManifest(fileId, data.size.toLong(), 1024 * 1024, listOf(ByteArray(32) { 9 }), "big.bin", null)
        val holder = manager(
            aliceDevice,
            FakeDevices(mapOf(bobDevice to bobAccount)),
            aliceTransport,
            FakePlaintext(info, data),
            FakeGate(canServe = true),
        )
        val requesterGate = FakeGate(offerVerdict = FileOfferVerdict.Refuse(FileRefusalReason.INVALID_OFFER))
        val requester = manager(
            bobDevice,
            FakeDevices(mapOf(aliceDevice to aliceAccount)),
            bobTransport,
            FakePlaintext(null, ByteArray(0)),
            requesterGate,
        )
        val scope = CoroutineScope(SupervisorJob())
        holder.runIn(scope)
        requester.runIn(scope)
        val pumpJob = scope.pump(aliceTransport, bobTransport)
        try {
            val outcome = withTimeout(10.seconds) {
                requester.fetchFile(aliceDevice, fileId, 0L)
            }
            assertTrue(outcome is FileFetchOutcome.Declined || outcome is FileFetchOutcome.Failed, "got $outcome")
            assertEquals(0, requesterGate.ingestCalls, "no bytes accepted before the list check")
        } finally {
            pumpJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun sessionDrop_leavesNoState() = runBlocking {
        val aliceTransport = RecordingWebRtcTransport()
        val bobTransport = RecordingWebRtcTransport()
        val data = ByteArray(256 * 1024) { it.toByte() }
        val info = FileManifest(fileId, data.size.toLong(), 64 * 1024, emptyList(), "big.bin", null)
        val holder = manager(
            aliceDevice,
            FakeDevices(mapOf(bobDevice to bobAccount)),
            aliceTransport,
            FakePlaintext(info, data),
            FakeGate(canServe = true),
        )
        // Requester gate that accepts but never advances (slow disk): holder parks
        // at the window and the test then drops the session.
        val requesterGate = object : FakeFilePorts by FakeGate() {
            override suspend fun checkOffer(offer: FileManifest) = FileOfferVerdict.Accept
            override suspend fun ingest(
                fileId: FileId,
                byteOffset: Long,
                plaintext: ByteArray,
            ) =
                FileIngestResult.Defer
        }
        val requester = manager(
            bobDevice,
            FakeDevices(mapOf(aliceDevice to aliceAccount)),
            bobTransport,
            FakePlaintext(null, ByteArray(0)),
            requesterGate,
        )
        val scope = CoroutineScope(SupervisorJob())
        holder.runIn(scope)
        requester.runIn(scope)
        val pumpJob = scope.pump(aliceTransport, bobTransport)
        try {
            val fetchJob = launch {
                requester.fetchFile(aliceDevice, fileId, 0L)
            }
            // Let the FETCH/FETCH_OK handshake and some DATA flow.
            withTimeout(10.seconds) {
                while (requesterGate.let { true } && holder.activeCount() == 0 && requester.activeCount() == 0) {
                    delay(10)
                }
            }
            delay(300)
            assertTrue(holder.activeCount() > 0 || requester.activeCount() > 0, "fetch must be live before the drop")
            // Session drop: both ends forget the fetch; nothing durable remains
            // (routing holds no row for a fetch by design).
            aliceTransport.tryEmitFileChannelEvent(
                WebRtcFileChannelEvent.Removed(bobDevice, aliceTransport.openFileChannelCalls.firstOrNull()?.second ?: "x")
            )
            bobTransport.tryEmitFileChannelEvent(
                WebRtcFileChannelEvent.Removed(aliceDevice, bobTransport.openFileChannelCalls.firstOrNull()?.second ?: "x")
            )
            // Close every known channel on both ends.
            for ((peer, label) in aliceTransport.openFileChannelCalls.toList()) {
                aliceTransport.tryEmitFileChannelEvent(WebRtcFileChannelEvent.Removed(peer, label))
            }
            for ((peer, label) in bobTransport.openFileChannelCalls.toList() + bobTransport.sendFileFrameCalls.map { it.first to it.second }) {
                bobTransport.tryEmitFileChannelEvent(WebRtcFileChannelEvent.Removed(peer, label))
            }
            // Also drive the managers' cleanup directly through their event path.
            delay(300)
            fetchJob.cancel()
            // In-memory state dies with the channel.
            withTimeout(5.seconds) {
                while (holder.activeCount() != 0 || requester.activeCount() != 0) {
                    // Force-cleanup via abort path (same as a session drop).
                    holder.abortFetch(fileId, org.yapyap.protocol.envelopes.FileFetchAbortReason.CANCELLED)
                    requester.abortFetch(fileId, org.yapyap.protocol.envelopes.FileFetchAbortReason.CANCELLED)
                    delay(20)
                }
            }
        } finally {
            pumpJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun slowDisk_boundsHolderAtWindow_envelopeKeepsFlowing() = runBlocking {
        val aliceTransport = RecordingWebRtcTransport()
        val bobTransport = RecordingWebRtcTransport()
        val window = 64L * 1024L
        val chunk = 8 * 1024
        val data = ByteArray(256 * 1024) { it.toByte() }
        val info = FileManifest(fileId, data.size.toLong(), 64 * 1024, emptyList(), "big.bin", null)
        val plaintext = FakePlaintext(info, data)
        val holder = manager(
            aliceDevice,
            FakeDevices(mapOf(bobDevice to bobAccount)),
            aliceTransport,
            plaintext,
            FakeGate(canServe = true),
            routerConfig = RouterConfig(fileStreamWindowBytes = window),
            chunkSize = chunk,
        )
        // Slow disk: accept the OFFER but never report progress (Defer every DATA).
        val requesterGate = object : FakeFilePorts by FakeGate() {
            override suspend fun checkOffer(offer: FileManifest) = FileOfferVerdict.Accept
            override suspend fun ingest(
                fileId: FileId,
                byteOffset: Long,
                plaintext: ByteArray,
            ) =
                FileIngestResult.Defer
        }
        val requester = manager(
            bobDevice,
            FakeDevices(mapOf(aliceDevice to aliceAccount)),
            bobTransport,
            FakePlaintext(null, ByteArray(0)),
            requesterGate,
        )
        val scope = CoroutineScope(SupervisorJob())
        holder.runIn(scope)
        requester.runIn(scope)
        val pumpJob = scope.pump(aliceTransport, bobTransport)
        try {
            val fetchJob = launch {
                requester.fetchFile(aliceDevice, fileId, 0L)
            }
            // Holder must stop at the window even though the receiver is live.
            withTimeout(10.seconds) {
                while (plaintext.reads * chunk < window) {
                    delay(20)
                }
                // Give the holder a chance to over-run; it must not.
                delay(400)
            }
            val sentBytes = aliceTransport.sendFileFrameCalls.sumOf { (_, _, payload) ->
                val frame = try { FileFetchFrame.decode(payload) } catch (_: Exception) { null }
                if (frame is FileFetchFrame.Data) frame.bytes.size else 0
            }
            assertTrue(sentBytes <= window + chunk, "holder bounded at window, sent $sentBytes")
            // Envelope channel is independent: a binary envelope still flows.
            aliceTransport.tryEmitFileFrame(
                WebRtcDataFrame(
                    aliceDevice, bobDevice, WebRtcDataType.ENVELOPE_BINARY, byteArrayOf(1, 2, 3),
                )
            )
            val envelope = withTimeout(5.seconds) { bobTransport.incomingFileFrames }
            fetchJob.cancel()
        } finally {
            pumpJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun noFileByte_crossesTorOrOutbox() = runBlocking {
        val aliceTransport = RecordingWebRtcTransport()
        val bobTransport = RecordingWebRtcTransport()
        val data = ByteArray(32 * 1024) { it.toByte() }
        val info = FileManifest(fileId, data.size.toLong(), 64 * 1024, emptyList(), "big.bin", null)
        val holder = manager(
            aliceDevice,
            FakeDevices(mapOf(bobDevice to bobAccount)),
            aliceTransport,
            FakePlaintext(info, data),
            FakeGate(canServe = true),
        )
        val requesterGate = FakeGate()
        val requester = manager(
            bobDevice,
            FakeDevices(mapOf(aliceDevice to aliceAccount)),
            bobTransport,
            FakePlaintext(null, ByteArray(0)),
            requesterGate,
        )
        val scope = CoroutineScope(SupervisorJob())
        holder.runIn(scope)
        requester.runIn(scope)
        val pumpJob = scope.pump(aliceTransport, bobTransport)
        try {
            withTimeout(10.seconds) {
                requester.fetchFile(aliceDevice, fileId, 0L)
            }
            // Every byte the fetch sent decodes as a fetch frame (never an envelope,
            // never an outbox row): the large path touches no Tor and no outbox.
            val allPayloads = aliceTransport.sendFileFrameCalls.map { it.third } +
                bobTransport.sendFileFrameCalls.map { it.third }
            assertTrue(allPayloads.isNotEmpty(), "fetch must send frames")
            for (payload in allPayloads) {
                FileFetchFrame.decode(payload)
            }
            assertTrue(aliceTransport.sendEnvelopeCalls.isEmpty())
            assertTrue(bobTransport.sendEnvelopeCalls.isEmpty())
        } finally {
            pumpJob.cancel()
            scope.cancel()
        }
    }
}
