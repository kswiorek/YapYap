package org.yapyap.routing.file.large

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.LargeFileAbortReason
import org.yapyap.protocol.LargeFileFrame
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.routing.file.LargeFileResult
import org.yapyap.routing.file.port.FileAppendResult
import org.yapyap.routing.file.port.FileCoverage
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileIngestResult
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.port.FileOfferVerdict
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileRefusalReason
import org.yapyap.routing.file.port.PlaintextRead
import org.yapyap.routing.file.port.PlaintextStream
import org.yapyap.routing.router.DeviceDirectory
import org.yapyap.routing.router.FakeFilePorts
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.RouterConfig
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import org.yapyap.transport.webrtc.types.FileChannelId
import org.yapyap.transport.webrtc.types.WebRtcFileChannelEvent
import org.yapyap.transport.webrtc.types.WebRtcFileFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Focused unit tests for [LargeFileService]: serve gating, block-list checks,
 * session-drop cleanup, slow-disk backpressure, and frame purity.
 */
class LargeFileServiceTest {

    private val aliceDevice = PeerId("fetch-alice-device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val bobDevice = PeerId("fetch-bob-device-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val aliceAccount = AccountId("fetch-alice-account")
    private val bobAccount = AccountId("fetch-bob-account")
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val now = epochSeconds(10_000L)

    private class FakeDevices(
        private val deviceToAccount: Map<PeerId, AccountId>,
    ) : DeviceDirectory {
        override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? = deviceToAccount[deviceId]
        override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
            deviceToAccount.filter { it.value == accountId }.keys.toList()

        override suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> =
            deviceToAccount.filter { it.value in accountIds }.keys.toList()

        override suspend fun getAllActiveDeviceIds(): List<PeerId> = deviceToAccount.keys.toList()
        override suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus? =
            if (deviceId in deviceToAccount) IdentityStatus.ACTIVE else null

        override suspend fun getAccountStatus(accountId: AccountId): IdentityStatus? = null
        override suspend fun getDeclaredTorEndpoint(deviceId: PeerId): TorEndpoint? = null
    }

    private class BytePlaintext(
        val info: FileManifest?,
        val bytes: ByteArray,
        var sourceLostAt: Long? = null,
    ) : FilePlaintextSource {
        var opens = 0
        override suspend fun describe(fileId: FileId): FileManifest? = info
        override suspend fun open(fileId: FileId, fromOffset: Long): PlaintextStream? {
            opens++
            if (info == null) return null
            if (fromOffset > bytes.size) return null
            return object : PlaintextStream {
                var pos = fromOffset
                override suspend fun read(maxBytes: Int): PlaintextRead {
                    sourceLostAt?.let { loseAt ->
                        if (pos >= loseAt) return PlaintextRead.SourceLost(0)
                    }
                    if (pos >= bytes.size) return PlaintextRead.Eof
                    val end = minOf(bytes.size.toLong(), pos + maxBytes)
                    val slice = bytes.copyOfRange(pos.toInt(), end.toInt())
                    pos = end
                    return PlaintextRead.Bytes(slice)
                }

                override fun close() = Unit
            }
        }
    }

    private class TestGate(
        var canServe: Boolean = true,
        var offerVerdict: FileOfferVerdict = FileOfferVerdict.Accept,
        var totalBytes: Long = 0L,
        var appendImpl: (suspend (offset: Long, bytes: ByteArray) -> FileAppendResult)? = null,
    ) : FakeFilePorts {
        var appendCalls = 0
        var ingestedBytes = 0L
        var checkOfferCalls = 0
        override suspend fun checkOffer(offer: FileManifest): FileOfferVerdict {
            checkOfferCalls++
            totalBytes = offer.totalBytes
            return offerVerdict
        }

        override suspend fun ingest(
            fileId: FileId,
            byteOffset: Long,
            plaintext: ByteArray,
        ): FileIngestResult {
            val contiguous = byteOffset + plaintext.size
            val total = if (totalBytes > 0) totalBytes else contiguous
            return FileIngestResult.Staged(contiguous, total)
        }

        override suspend fun append(fileId: FileId, offset: Long, bytes: ByteArray): FileAppendResult {
            appendCalls++
            ingestedBytes += bytes.size
            appendImpl?.let { return it(offset, bytes) }
            return if (offset + bytes.size >= totalBytes) FileAppendResult.Ready
            else FileAppendResult.Appended(offset + bytes.size)
        }

        override suspend fun heldCoverage(fileId: FileId): FileCoverage? = FileCoverage(0L, emptyList())
        override suspend fun requestCandidates(fileId: FileId): List<AccountId> = emptyList()
        override suspend fun canServe(fileId: FileId, requester: AccountId): Boolean = canServe
        override suspend fun requestTotalBytes(fileId: FileId): Long? = null
    }

    private fun service(
        local: PeerId,
        devices: DeviceDirectory,
        transport: RecordingWebRtcTransport,
        plaintext: FilePlaintextSource,
        gate: FakeFilePorts,
        routerConfig: RouterConfig = RouterConfig(),
        chunkSize: Int = 32 * 1024,
        events: MutableSharedFlow<FileEvent> = MutableSharedFlow(extraBufferCapacity = 64),
        clock: FakeClock = FakeClock(now),
    ) = LargeFileService(
        devices = devices,
        transport = transport,
        plaintext = plaintext,
        ingest = gate,
        requests = gate,
        routerConfig = MutableStateFlow(routerConfig),
        fileLimits = MutableStateFlow(
            FileLimits(
                chunkSizeBytes = chunkSize,
                largeFileFrameBytes = chunkSize,
                maxSealBytes = 64 * 1024
            )
        ),
        events = events,
        clock = clock,
    )

    /** File-frame + close pump between two recording transports with label translation. */
    private fun CoroutineScope.pump(
        aLocal: PeerId,
        a: RecordingWebRtcTransport,
        bLocal: PeerId,
        b: RecordingWebRtcTransport,
    ) = launch {
        var aSent = 0
        var bSent = 0
        var aClosed = 0
        var bClosed = 0
        while (true) {
            delay(10.milliseconds)
            val aSends = a.sendFileFrameCalls.toList()
            while (aSent < aSends.size) {
                val (channel, payload) = aSends[aSent++]
                val forB = WebRtcFileFrame(FileChannelId(peer = aLocal, label = channel.label), payload)
                while (!b.tryEmitFileFrame(forB)) delay(5.milliseconds)
            }
            val bSends = b.sendFileFrameCalls.toList()
            while (bSent < bSends.size) {
                val (channel, payload) = bSends[bSent++]
                val forA = WebRtcFileFrame(FileChannelId(peer = bLocal, label = channel.label), payload)
                while (!a.tryEmitFileFrame(forA)) delay(5.milliseconds)
            }
            val aCloses = a.closeFileChannelCalls.toList()
            while (aClosed < aCloses.size) {
                val channel = aCloses[aClosed++]
                b.tryEmitFileChannelEvent(
                    WebRtcFileChannelEvent.Removed(FileChannelId(peer = aLocal, label = channel.label))
                )
            }
            val bCloses = b.closeFileChannelCalls.toList()
            while (bClosed < bCloses.size) {
                val channel = bCloses[bClosed++]
                a.tryEmitFileChannelEvent(
                    WebRtcFileChannelEvent.Removed(FileChannelId(peer = bLocal, label = channel.label))
                )
            }
        }
    }

    @Test
    fun serveGate_declinesUnknownAccount() = runBlocking {
        val aliceTransport = RecordingWebRtcTransport()
        val bobTransport = RecordingWebRtcTransport()
        val data = ByteArray(64 * 1024) { it.toByte() }
        val info = FileManifest(fileId, data.size.toLong(), 1024 * 1024, emptyList(), "big.bin", null)
        val holderGate = TestGate(canServe = false)
        val holder = service(
            aliceDevice,
            FakeDevices(emptyMap()),
            aliceTransport,
            BytePlaintext(info, data),
            holderGate,
        )
        val requesterGate = TestGate(totalBytes = data.size.toLong())
        val requester = service(
            bobDevice,
            FakeDevices(mapOf(aliceDevice to aliceAccount)),
            bobTransport,
            BytePlaintext(null, ByteArray(0)),
            requesterGate,
        )
        val scope = CoroutineScope(SupervisorJob())
        holder.runIn(scope)
        requester.runIn(scope)
        val pumpJob = scope.pump(aliceDevice, aliceTransport, bobDevice, bobTransport)
        try {
            val outcome = withTimeout(10.seconds) {
                requester.pull(aliceDevice, fileId, 0L)
            }
            assertEquals(LargeFileResult.DECLINED, outcome, "unknown account must decline")
            assertEquals(0, requesterGate.appendCalls, "nothing written on decline")
        } finally {
            pumpJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun blockListMismatch_declinesWithNothingWritten() = runBlocking {
        val aliceTransport = RecordingWebRtcTransport()
        val bobTransport = RecordingWebRtcTransport()
        val data = ByteArray(64 * 1024) { it.toByte() }
        val info = FileManifest(fileId, data.size.toLong(), 1024 * 1024, listOf(ByteArray(32) { 9 }), "big.bin", null)
        val holder = service(
            aliceDevice,
            FakeDevices(mapOf(bobDevice to bobAccount)),
            aliceTransport,
            BytePlaintext(info, data),
            TestGate(canServe = true),
        )
        val requesterGate = TestGate(
            offerVerdict = FileOfferVerdict.Refuse(FileRefusalReason.INVALID_OFFER),
            totalBytes = data.size.toLong(),
        )
        val requester = service(
            bobDevice,
            FakeDevices(mapOf(aliceDevice to aliceAccount)),
            bobTransport,
            BytePlaintext(null, ByteArray(0)),
            requesterGate,
        )
        val scope = CoroutineScope(SupervisorJob())
        holder.runIn(scope)
        requester.runIn(scope)
        // The pump carries both directions.
        val pumpJob = scope.pump(aliceDevice, aliceTransport, bobDevice, bobTransport)
        try {
            val outcome = withTimeout(10.seconds) {
                requester.pull(aliceDevice, fileId, 0L)
            }
            assertEquals(LargeFileResult.DECLINED, outcome, "bad block list must decline, got $outcome")
            assertEquals(0, requesterGate.appendCalls, "no bytes accepted before the list check")
        } finally {
            pumpJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun localRefusalsAtPullOk_areNeverAHolderDecline() = runBlocking {
        // Our own outcomes at PULL_OK must not blacklist the holder: only its DECLINED
        // (or a block list that fails the fileId) counts as a decline.
        val cases = listOf(
            FileOfferVerdict.Defer to LargeFileResult.UNANSWERED,
            FileOfferVerdict.Refuse(FileRefusalReason.NO_SPACE) to LargeFileResult.NO_SPACE,
            FileOfferVerdict.Refuse(FileRefusalReason.NOT_RETRYABLE) to LargeFileResult.ENDED,
        )
        for ((verdict, expected) in cases) {
            val aliceTransport = RecordingWebRtcTransport()
            val bobTransport = RecordingWebRtcTransport()
            val data = ByteArray(64 * 1024) { it.toByte() }
            val info =
                FileManifest(fileId, data.size.toLong(), 1024 * 1024, listOf(ByteArray(32) { 9 }), "big.bin", null)
            val holder = service(
                aliceDevice,
                FakeDevices(mapOf(bobDevice to bobAccount)),
                aliceTransport,
                BytePlaintext(info, data),
                TestGate(canServe = true),
            )
            val requesterGate = TestGate(offerVerdict = verdict, totalBytes = data.size.toLong())
            val requester = service(
                bobDevice,
                FakeDevices(mapOf(aliceDevice to aliceAccount)),
                bobTransport,
                BytePlaintext(null, ByteArray(0)),
                requesterGate,
            )
            val scope = CoroutineScope(SupervisorJob())
            holder.runIn(scope)
            requester.runIn(scope)
            val pumpJob = scope.pump(aliceDevice, aliceTransport, bobDevice, bobTransport)
            try {
                val outcome = withTimeout(10.seconds) { requester.pull(aliceDevice, fileId, 0L) }
                assertEquals(expected, outcome, "verdict $verdict")
                assertEquals(0, requesterGate.appendCalls, "verdict $verdict: no bytes accepted")
            } finally {
                pumpJob.cancel()
                scope.cancel()
            }
        }
    }

    @Test
    fun sessionDrop_leavesNoState() = runBlocking {
        val aliceTransport = RecordingWebRtcTransport()
        val bobTransport = RecordingWebRtcTransport()
        val data = ByteArray(256 * 1024) { it.toByte() }
        val info = FileManifest(fileId, data.size.toLong(), 64 * 1024, emptyList(), "big.bin", null)
        val holder = service(
            aliceDevice,
            FakeDevices(mapOf(bobDevice to bobAccount)),
            aliceTransport,
            BytePlaintext(info, data),
            TestGate(canServe = true),
        )
        val requesterGate = TestGate(totalBytes = data.size.toLong()).apply {
            var calls = 0
            appendImpl = { offset, bytes ->
                calls++
                // Stall the first ~500ms so the drop lands mid-fetch, then complete
                // normally so the close can drain and the fetch can return.
                if (calls <= 5) FileAppendResult.Defer
                else if (offset + bytes.size >= data.size.toLong()) FileAppendResult.Ready
                else FileAppendResult.Appended(offset + bytes.size)
            }
        }
        val requester = service(
            bobDevice,
            FakeDevices(mapOf(aliceDevice to aliceAccount)),
            bobTransport,
            BytePlaintext(null, ByteArray(0)),
            requesterGate,
        )
        val scope = CoroutineScope(SupervisorJob())
        holder.runIn(scope)
        requester.runIn(scope)
        val pumpJob = scope.pump(aliceDevice, aliceTransport, bobDevice, bobTransport)
        try {
            var result: LargeFileResult? = null
            val fetchJob = launch {
                result = requester.pull(aliceDevice, fileId, 0L)
            }
            withTimeout(10.seconds) {
                while (holder.activeCount() == 0 && requester.activeCount() == 0) delay(10.milliseconds)
            }
            // Drop while stalled on Defer (the stall keeps the 256KB fetch live);
            // the close drains once the defers expire and the fetch returns.
            delay(50.milliseconds)
            assertTrue(holder.activeCount() > 0 || requester.activeCount() > 0, "fetch must be live before the drop")
            for (ch in bobTransport.openedChannels.toList()) {
                bobTransport.tryEmitFileChannelEvent(WebRtcFileChannelEvent.Removed(ch))
                aliceTransport.tryEmitFileChannelEvent(
                    WebRtcFileChannelEvent.Removed(FileChannelId(peer = bobDevice, label = ch.label))
                )
            }
            for (ch in bobTransport.sendFileFrameCalls.map { it.first }.toList()) {
                bobTransport.tryEmitFileChannelEvent(WebRtcFileChannelEvent.Removed(ch))
            }
            // No abort: the close alone must end the fetch and clear both sides.
            withTimeout(5.seconds) {
                fetchJob.join()
            }
            withTimeout(5.seconds) {
                while (holder.activeCount() != 0 || requester.activeCount() != 0) delay(20.milliseconds)
            }
            assertEquals(0, holder.activeCount())
            assertEquals(0, requester.activeCount())
        } finally {
            pumpJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun slowDisk_boundsHolderAtWindow() = runBlocking {
        val aliceTransport = RecordingWebRtcTransport()
        val bobTransport = RecordingWebRtcTransport()
        val window = 64L * 1024L
        val chunk = 8 * 1024
        val data = ByteArray(256 * 1024) { it.toByte() }
        val info = FileManifest(fileId, data.size.toLong(), 64 * 1024, emptyList(), "big.bin", null)
        val plaintext = BytePlaintext(info, data)
        val holder = service(
            aliceDevice,
            FakeDevices(mapOf(bobDevice to bobAccount)),
            aliceTransport,
            plaintext,
            TestGate(canServe = true),
            routerConfig = RouterConfig(largeFileStreamWindowBytes = window),
            chunkSize = chunk,
        )
        var appends = 0
        val requesterGate = TestGate(totalBytes = data.size.toLong()).apply {
            appendImpl = { _, _ ->
                appends++
                // First DATA stalls (slow disk), then bytes are taken without moving P,
                // so the holder's window never advances and it parks at window+chunk.
                if (appends == 1) FileAppendResult.Defer
                else FileAppendResult.Appended(0L)
            }
        }
        val requester = service(
            bobDevice,
            FakeDevices(mapOf(aliceDevice to aliceAccount)),
            bobTransport,
            BytePlaintext(null, ByteArray(0)),
            requesterGate,
        )
        val scope = CoroutineScope(SupervisorJob())
        holder.runIn(scope)
        requester.runIn(scope)
        val pumpJob = scope.pump(aliceDevice, aliceTransport, bobDevice, bobTransport)
        try {
            val fetchJob = launch {
                requester.pull(aliceDevice, fileId, 0L)
            }
            fun sentBytes() = aliceTransport.sendFileFrameCalls.toList().sumOf { (_, payload) ->
                val frame = try {
                    LargeFileFrame.decode(payload)
                } catch (_: Exception) {
                    null
                }
                if (frame is LargeFileFrame.Data) frame.bytes.size else 0
            }
            // The holder must actually fill its window (a holder that sent nothing would pass a
            // bound alone), then stay within it while P does not move.
            withTimeout(10.seconds) { while (sentBytes() < window) delay(20.milliseconds) }
            delay(400.milliseconds)
            val sent = sentBytes()
            assertTrue(sent <= window + chunk, "holder bounded at window, sent $sent")
            // The holder parks via the callback-backed backpressure path, not polling.
            assertTrue(aliceTransport.awaitBufferedCalls.isNotEmpty(), "holder used awaitBufferedBelow")
            fetchJob.cancel()
        } finally {
            pumpJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun allPayloads_decodeAsFetchFrames() = runBlocking {
        val aliceTransport = RecordingWebRtcTransport()
        val bobTransport = RecordingWebRtcTransport()
        val data = ByteArray(32 * 1024) { it.toByte() }
        val info = FileManifest(fileId, data.size.toLong(), 64 * 1024, emptyList(), "big.bin", null)
        val holder = service(
            aliceDevice,
            FakeDevices(mapOf(bobDevice to bobAccount)),
            aliceTransport,
            BytePlaintext(info, data),
            TestGate(canServe = true),
        )
        val requesterGate = TestGate(totalBytes = data.size.toLong())
        val requester = service(
            bobDevice,
            FakeDevices(mapOf(aliceDevice to aliceAccount)),
            bobTransport,
            BytePlaintext(null, ByteArray(0)),
            requesterGate,
        )
        val scope = CoroutineScope(SupervisorJob())
        holder.runIn(scope)
        requester.runIn(scope)
        val pumpJob = scope.pump(aliceDevice, aliceTransport, bobDevice, bobTransport)
        try {
            withTimeout(10.seconds) {
                requester.pull(aliceDevice, fileId, 0L)
            }
            val allPayloads = aliceTransport.sendFileFrameCalls.map { it.second } +
                    bobTransport.sendFileFrameCalls.map { it.second }
            assertTrue(allPayloads.isNotEmpty(), "fetch must send frames")
            for (payload in allPayloads) {
                LargeFileFrame.decode(payload)
            }
            assertTrue(aliceTransport.sendEnvelopeCalls.isEmpty())
            assertTrue(bobTransport.sendEnvelopeCalls.isEmpty())
        } finally {
            pumpJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun appendNoSpace_abortsAndEndsWithoutBlacklisting() = runBlocking {
        val aliceTransport = RecordingWebRtcTransport()
        val bobTransport = RecordingWebRtcTransport()
        val data = ByteArray(64 * 1024) { it.toByte() }
        val info = FileManifest(fileId, data.size.toLong(), 64 * 1024, emptyList(), "big.bin", null)
        val holder = service(
            aliceDevice,
            FakeDevices(mapOf(bobDevice to bobAccount)),
            aliceTransport,
            BytePlaintext(info, data),
            TestGate(canServe = true),
        )
        val requesterGate = TestGate(
            totalBytes = data.size.toLong(),
            appendImpl = { _, _ -> FileAppendResult.NoSpace },
        )
        val requester = service(
            bobDevice,
            FakeDevices(mapOf(aliceDevice to aliceAccount)),
            bobTransport,
            BytePlaintext(null, ByteArray(0)),
            requesterGate,
        )
        val scope = CoroutineScope(SupervisorJob())
        holder.runIn(scope)
        requester.runIn(scope)
        val pumpJob = scope.pump(aliceDevice, aliceTransport, bobDevice, bobTransport)
        try {
            val result = withTimeout(10.seconds) {
                requester.pull(aliceDevice, fileId, 0L)
            }
            assertEquals(LargeFileResult.NO_SPACE, result)
            // Local refusal never counts against the holder: no declined outcome, just an ABORT.
            val aborts = bobTransport.sendFileFrameCalls.mapNotNull { (_, payload) ->
                try {
                    LargeFileFrame.decode(payload) as? LargeFileFrame.Abort
                } catch (_: Exception) {
                    null
                }
            }
            assertTrue(aborts.any { it.reason == LargeFileAbortReason.NO_SPACE }, "ABORT NO_SPACE sent")
        } finally {
            pumpJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun appendMismatch_abortsAndReturnsMismatch() = runBlocking {
        val aliceTransport = RecordingWebRtcTransport()
        val bobTransport = RecordingWebRtcTransport()
        val data = ByteArray(64 * 1024) { it.toByte() }
        val info = FileManifest(fileId, data.size.toLong(), 64 * 1024, emptyList(), "big.bin", null)
        val holder = service(
            aliceDevice,
            FakeDevices(mapOf(bobDevice to bobAccount)),
            aliceTransport,
            BytePlaintext(info, data),
            TestGate(canServe = true),
        )
        val requesterGate = TestGate(
            totalBytes = data.size.toLong(),
            appendImpl = { _, _ -> FileAppendResult.Mismatch(0) },
        )
        val requester = service(
            bobDevice,
            FakeDevices(mapOf(aliceDevice to aliceAccount)),
            bobTransport,
            BytePlaintext(null, ByteArray(0)),
            requesterGate,
        )
        val scope = CoroutineScope(SupervisorJob())
        holder.runIn(scope)
        requester.runIn(scope)
        val pumpJob = scope.pump(aliceDevice, aliceTransport, bobDevice, bobTransport)
        try {
            val result = withTimeout(10.seconds) {
                requester.pull(aliceDevice, fileId, 0L)
            }
            assertEquals(LargeFileResult.MISMATCH, result)
        } finally {
            pumpJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun appendDefer_suspendsWithoutDroppingFrames() = runBlocking {
        val aliceTransport = RecordingWebRtcTransport()
        val bobTransport = RecordingWebRtcTransport()
        val data = ByteArray(64 * 1024) { it.toByte() }
        val info = FileManifest(fileId, data.size.toLong(), 64 * 1024, emptyList(), "big.bin", null)
        val holder = service(
            aliceDevice,
            FakeDevices(mapOf(bobDevice to bobAccount)),
            aliceTransport,
            BytePlaintext(info, data),
            TestGate(canServe = true),
        )
        var defers = 0
        val requesterGate = TestGate(
            totalBytes = data.size.toLong(),
            appendImpl = { offset, bytes ->
                if (defers < 3) {
                    defers++
                    FileAppendResult.Defer
                } else if (offset + bytes.size >= data.size) FileAppendResult.Ready
                else FileAppendResult.Appended(offset + bytes.size)
            },
        )
        val requester = service(
            bobDevice,
            FakeDevices(mapOf(aliceDevice to aliceAccount)),
            bobTransport,
            BytePlaintext(null, ByteArray(0)),
            requesterGate,
        )
        val scope = CoroutineScope(SupervisorJob())
        holder.runIn(scope)
        requester.runIn(scope)
        val pumpJob = scope.pump(aliceDevice, aliceTransport, bobDevice, bobTransport)
        try {
            val result = withTimeout(10.seconds) {
                requester.pull(aliceDevice, fileId, 0L)
            }
            assertEquals(LargeFileResult.SATISFIED, result)
            assertTrue(defers >= 3, "deferred frames retried, none dropped")
            // Retries re-append the same bytes, so the counted total includes them.
            assertTrue(requesterGate.ingestedBytes >= data.size.toLong())
        } finally {
            pumpJob.cancel()
            scope.cancel()
        }
    }

    /** Holder and requester services wired through the pump, for one fetch of [data]. */
    private suspend fun fetchWith(
        requesterGate: TestGate,
        data: ByteArray = ByteArray(64 * 1024) { it.toByte() },
        block: suspend (requester: LargeFileService) -> Unit,
    ) {
        val aliceTransport = RecordingWebRtcTransport()
        val bobTransport = RecordingWebRtcTransport()
        val info = FileManifest(fileId, data.size.toLong(), 64 * 1024, emptyList(), "big.bin", null)
        val holder = service(
            aliceDevice, FakeDevices(mapOf(bobDevice to bobAccount)), aliceTransport, BytePlaintext(info, data),
            TestGate(canServe = true),
        )
        val requester = service(
            bobDevice, FakeDevices(mapOf(aliceDevice to aliceAccount)), bobTransport, BytePlaintext(null, ByteArray(0)),
            requesterGate,
        )
        val scope = CoroutineScope(SupervisorJob())
        holder.runIn(scope)
        requester.runIn(scope)
        val pumpJob = scope.pump(aliceDevice, aliceTransport, bobDevice, bobTransport)
        try {
            block(requester)
        } finally {
            pumpJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun appendAfterTheReceiveEnded_endsWithoutBlame() = runBlocking {
        val gate = TestGate(totalBytes = 64 * 1024L, appendImpl = { _, _ -> FileAppendResult.Ended })
        fetchWith(gate) { requester ->
            assertEquals(LargeFileResult.ENDED, withTimeout(10.seconds) { requester.pull(aliceDevice, fileId, 0L) })
        }
    }

    @Test
    fun endlessDefer_endsWhenTheFetchIsAborted() = runBlocking {
        val gate = TestGate(totalBytes = 64 * 1024L, appendImpl = { _, _ -> FileAppendResult.Defer })
        fetchWith(gate) { requester ->
            coroutineScope {
                val result = async { requester.pull(aliceDevice, fileId, 0L) }
                withTimeout(10.seconds) { while (gate.appendCalls == 0) delay(10.milliseconds) }
                requester.abortPull(fileId, LargeFileAbortReason.CANCELLED)
                // The retry loop sees the abort; the fetch is bounded even while the engine defers.
                assertEquals(LargeFileResult.UNANSWERED, withTimeout(10.seconds) { result.await() })
            }
        }
    }

    @Test
    fun holderOverrunningTheWindow_isCutOff() = runBlocking {
        val window = 64 * 1024L
        val frame = 32 * 1024
        val bobTransport = RecordingWebRtcTransport()
        // P never moves, so the window never opens past its first 64 KiB.
        val gate = TestGate(totalBytes = 1024 * 1024L, appendImpl = { _, _ -> FileAppendResult.Appended(0L) })
        val requester = service(
            bobDevice, FakeDevices(mapOf(aliceDevice to aliceAccount)), bobTransport, BytePlaintext(null, ByteArray(0)),
            gate, routerConfig = RouterConfig(largeFileStreamWindowBytes = window),
        )
        val scope = CoroutineScope(SupervisorJob())
        requester.runIn(scope)
        try {
            coroutineScope {
                val result = async { requester.pull(aliceDevice, fileId, 0L) }
                val channel = withTimeout(10.seconds) {
                    while (bobTransport.sendFileFrameCalls.isEmpty()) delay(10.milliseconds)
                    bobTransport.sendFileFrameCalls.first().first
                }
                val ok = LargeFileFrame.PullOk(1024 * 1024L, 1024 * 1024, emptyList(), "big.bin", null)
                assertTrue(bobTransport.tryEmitFileFrame(WebRtcFileFrame(channel, ok.encode())))
                // A rogue holder streams the whole file regardless of PROGRESS.
                repeat(32) {
                    while (!bobTransport.tryEmitFileFrame(
                            WebRtcFileFrame(
                                channel,
                                LargeFileFrame.Data(ByteArray(frame)).encode()
                            )
                        )
                    ) {
                        delay(5.milliseconds)
                    }
                }
                assertEquals(LargeFileResult.UNANSWERED, withTimeout(10.seconds) { result.await() })
                assertTrue(channel in bobTransport.closeFileChannelCalls, "the overrun channel is closed")
                assertTrue(
                    gate.ingestedBytes <= window + frame,
                    "nothing past the window reaches the engine, got ${gate.ingestedBytes}",
                )
            }
        } finally {
            scope.cancel()
        }
    }
}
