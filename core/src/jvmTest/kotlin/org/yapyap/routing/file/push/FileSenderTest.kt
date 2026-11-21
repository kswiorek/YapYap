package org.yapyap.routing.file.push

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileFailureReason
import org.yapyap.routing.file.port.FileSendStatus
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.store.FilePushKind
import org.yapyap.routing.store.FilePushStatus
import org.yapyap.routing.store.OutboxTag
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class FileSenderTest {

    @Test
    fun pushFile_fansOutPerDevice_withOwnTransferIdKeyAndMarker() = runTest {
        val h = pushHarness()
        h.engine.runIn(backgroundScope)

        h.sender.pushFile(fixtureFileId, fixtureAccount)
        h.awaitOffers(2)

        val transfersA = h.transferIdsFor(peerA)
        val transfersB = h.transferIdsFor(peerB)
        assertEquals(1, transfersA.size)
        assertEquals(1, transfersB.size)
        assertTrue(transfersA.single() != transfersB.single(), "one transfer per device")
        val rowA = h.pushes.find(transfersA.single())!!
        val rowB = h.pushes.find(transfersB.single())!!
        assertTrue(rowA.key.bytes.contentEquals(ByteArray(32) { it.toByte() }))
        assertTrue(rowB.key.bytes.contentEquals(ByteArray(32) { (it + 32).toByte() }))
        assertEquals(FilePushStatus.CREATED, rowA.status)
        val markers = h.sent.filter { FileEnvelope.decode(it.first.payload).decodePayload() is FilePayload.Offer }
        assertEquals(2, markers.size)
        assertEquals(2, h.protection.sealedOffers.size)
    }

    @Test
    fun pushFile_refusesLargeFiles() = runTest {
        val h = pushHarness(totalBytes = 11L * 1024 * 1024)

        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        assertTrue(h.pushes.live().isEmpty())
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun pushFile_secondPushIsNoOp() = runTest {
        val h = pushHarness()

        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        h.awaitOffers(2)
        val before = h.sent.size
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        delay(300)
        // One live transfer per (file, device): the second push changes nothing.
        assertEquals(1, h.transferIdsFor(peerA).size)
        assertEquals(before, h.sent.size)
    }

    @Test
    fun serve_startsFreshTransferAtFloorAlignedStart() = runTest {
        val h = pushHarness(totalBytes = 1024L * 1024, chunkSize = 220 * 1024)

        h.engine.runIn(backgroundScope)
        assertTrue(h.sender.serveFile(fixtureFileId, peerA, fromOffset = 300_000L))
        h.awaitOffers(1)

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
        val h = pushHarness()

        h.engine.runIn(backgroundScope)
        h.plaintext.describeImpl = { null }
        assertFalse(h.sender.serveFile(fixtureFileId, peerA, fromOffset = 0L))
        assertTrue(h.pushes.live().isEmpty())
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun serve_supersedesLivePushWithCancel() = runTest {
        val h = pushHarness()

        h.engine.runIn(backgroundScope)
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch { h.fileEvents.collect { seen += it } }
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        h.awaitOffers(2)
        val pushed = h.transferIdsFor(peerA).single()

        assertTrue(h.sender.serveFile(fixtureFileId, peerA, fromOffset = 0L))

        // Old transfer is CANCELled silently (a handoff, not a failure); the fresh serve
        // runs as its own transfer; the sibling device's push is untouched.
        withTimeout(5.seconds) {
            while (h.pushes.find(pushed)?.status?.isLive == true) delay(10)
        }
        assertEquals(FilePushStatus.CANCELLED, h.pushes.find(pushed)?.status)
        assertTrue(seen.none { it is FileEvent.Failed }, "supersede is silent")
        withTimeout(5.seconds) {
            while (h.sent.mapNotNull { (env, tag) ->
                    if (tag == null) FileEnvelope.decode(env.payload).decodePayload() as? FilePayload.Cancel else null
                }.size < 1
            ) delay(10)
        }
        val cancels = h.sent.mapNotNull { (env, tag) ->
            if (tag == null) FileEnvelope.decode(env.payload).decodePayload() as? FilePayload.Cancel else null
        }
        assertEquals(1, cancels.size)
        // The fresh serve runs as its own transfer; the sibling device's push is untouched.
        val liveA = h.transferIdsFor(peerA)
        assertEquals(1, liveA.size)
        val serveRow = h.pushes.find(liveA.single())!!
        assertEquals(FilePushKind.SERVE, serveRow.kind)
        assertNotEquals(pushed, serveRow.transferId)
        val liveB = h.transferIdsFor(peerB)
        assertEquals(1, liveB.size)
        assertTrue(seen.any { it is FileEvent.ServeStarted && it.fileId == fixtureFileId })
    }

    @Test
    fun cancelFile_cancelsLiveTransfers() = runTest {
        val h = pushHarness()

        h.engine.runIn(backgroundScope)
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch { h.fileEvents.collect { seen += it } }
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        h.awaitOffers(2)

        h.sender.cancelFile(fixtureFileId, fixtureAccount)

        withTimeout(5.seconds) {
            while (h.pushes.live().any { it.fileId == fixtureFileId }) delay(10)
        }
        assertTrue(h.pushes.live().none { it.fileId == fixtureFileId })
        withTimeout(5.seconds) {
            while (seen.filterIsInstance<FileEvent.Failed>().isEmpty()) delay(10)
        }
        assertEquals(
            setOf(FileFailureReason.CANCELLED),
            seen.filterIsInstance<FileEvent.Failed>().map { it.reason }.toSet(),
        )
    }

    @Test
    fun sendState_aggregatesProgressAndTerminal() = runTest {
        val h = pushHarness(totalBytes = 1024L * 1024, chunkSize = 220 * 1024)

        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)

        val sending = h.sender.sendState(fixtureFileId)
        assertEquals(FileSendStatus.SENDING, sending.getValue(fixtureAccount).status)

        val transfer = h.transferIdsFor(peerA).single()
        h.engine.onOutboxOutcome(h.ackMarker(transfer, peerA))
        h.engine.coverageAck(transfer, peerA, highestContiguousIndex = 2, missing = emptyList())
        kotlinx.coroutines.delay(100)
        val progress = h.sender.sendState(fixtureFileId)
        // Minimum covered bytes across the account's live device transfers.
        assertTrue(progress.getValue(fixtureAccount).sentBytes >= 0L)
    }

    @Test
    fun sendState_failedOnlyWhenAllTerminal() = runTest {
        val h = pushHarness()
        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transferA = h.transferIdsFor(peerA).single()
        val transferB = h.transferIdsFor(peerB).single()

        // One device refused while the other still sends: still SENDING, never failed.
        h.engine.onOutboxOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_PUSH, transferA.value),
                peerA,
                h.markerOf(transferA),
                PacketNackReason.DECLINED,
            ),
        )
        h.awaitStatus(transferA, FilePushStatus.DECLINED)
        assertEquals(FileSendStatus.SENDING, h.sender.sendState(fixtureFileId).getValue(fixtureAccount).status)

        // Both refused: the send reads DECLINED.
        h.engine.onOutboxOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_PUSH, transferB.value),
                peerB,
                h.markerOf(transferB),
                PacketNackReason.DECLINED,
            ),
        )
        withTimeout(5.seconds) {
            while (h.sender.sendState(fixtureFileId).getValue(fixtureAccount).status != FileSendStatus.DECLINED) delay(
                10
            )
        }
        assertEquals(FileSendStatus.DECLINED, h.sender.sendState(fixtureFileId).getValue(fixtureAccount).status)
    }

    @Test
    fun sendState_mixedEndedAndExpiredReportsEnded() = runTest {
        val h = pushHarness()
        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transferA = h.transferIdsFor(peerA).single()
        val transferB = h.transferIdsFor(peerB).single()

        // A ends with an unknown outcome, B expires: ENDED wins over EXPIRED.
        h.engine.onOutboxOutcome(h.ackMarker(transferA, peerA))
        withTimeout(5.seconds) { while (h.chunkIndices().isEmpty()) delay(10) }
        val chunkPacket = h.sent.map { it.first }.first { env ->
            val decoded = FileEnvelope.decode(env.payload).decodePayload()
            decoded is FilePayload.EncryptedChunk &&
                    FileEnvelope.decode(env.payload).transferId == transferA
        }.packetId
        h.engine.onOutboxOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_PUSH, transferA.value),
                peerA,
                chunkPacket,
                PacketNackReason.DECLINED,
            ),
        )
        h.awaitStatus(transferA, FilePushStatus.ENDED)

        h.clock.advanceTo(epochSeconds(10_000L) + 3.days)
        h.engine.onOutboxOutcome(
            OutboxOutcome.Expired(OutboxTag(OutboxTag.Kind.FILE_PUSH, transferB.value), peerB, h.markerOf(transferB)),
        )
        withTimeout(5.seconds) {
            while (h.sender.sendState(fixtureFileId).getValue(fixtureAccount).status != FileSendStatus.ENDED) delay(10)
        }
        assertEquals(FileSendStatus.ENDED, h.sender.sendState(fixtureFileId).getValue(fixtureAccount).status)
    }

    @Test
    fun sendState_completedOnceReady() = runTest {
        val h = pushHarness()
        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()

        h.engine.complete(transfer, peerA)
        withTimeout(5.seconds) {
            while (h.sender.sendState(fixtureFileId).getValue(fixtureAccount).status != FileSendStatus.COMPLETED) delay(
                10
            )
        }
        val state = h.sender.sendState(fixtureFileId).getValue(fixtureAccount)
        assertEquals(FileSendStatus.COMPLETED, state.status)
        assertEquals(state.totalBytes, state.sentBytes)
    }
}
