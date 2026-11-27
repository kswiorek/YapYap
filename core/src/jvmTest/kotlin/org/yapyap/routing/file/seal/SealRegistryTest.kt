package org.yapyap.routing.file.seal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.ackOffer
import org.yapyap.routing.file.allTransfers
import org.yapyap.routing.file.awaitOffers
import org.yapyap.routing.file.awaitStatus
import org.yapyap.routing.file.chunkIndices
import org.yapyap.routing.file.fixtureAccount
import org.yapyap.routing.file.fixtureFileId
import org.yapyap.routing.file.offerHeaders
import org.yapyap.routing.file.offerPacketOf
import org.yapyap.routing.file.peerA
import org.yapyap.routing.file.peerB
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileFailureReason
import org.yapyap.routing.file.port.FileSendStatus
import org.yapyap.routing.file.sealTransferHarness
import org.yapyap.routing.file.sent
import org.yapyap.routing.file.transfersFor
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.store.FileSealKind
import org.yapyap.routing.store.FileTransferStatus
import org.yapyap.routing.store.OutboxTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SealRegistryTest {

    @Test
    fun pushFile_fansOutPerDevice_withOwnTransferIdAndMarker() = runTest {
        val h = sealTransferHarness()
        h.seals.push(fixtureFileId, fixtureAccount)
        h.awaitOffers(2)

        val transfersA = h.transfersFor(peerA)
        val transfersB = h.transfersFor(peerB)
        assertEquals(1, transfersA.size)
        assertEquals(1, transfersB.size)
        assertTrue(transfersA.single().transferId != transfersB.single().transferId, "one transfer per device")
        assertEquals(FileTransferStatus.OFFERED, transfersA.single().status)
        val markers = h.offerHeaders()
        assertEquals(2, markers.size)
        // One seal per (file, target device).
        assertEquals(2, h.sealStore.byFile(fixtureFileId).size)
    }

    @Test
    fun pushFile_refusesLargeFiles() = runTest {
        val h = sealTransferHarness(totalBytes = SMALL_FILE_MAX_BYTES + 1)
        h.seals.push(fixtureFileId, fixtureAccount)
        assertTrue(h.allTransfers().isEmpty())
        assertTrue(h.sent().isEmpty())
    }

    @Test
    fun pushFile_secondPushIsNoOp() = runTest {
        val h = sealTransferHarness()
        h.seals.push(fixtureFileId, fixtureAccount)
        h.awaitOffers(2)
        val before = h.sent().size
        h.seals.push(fixtureFileId, fixtureAccount)
        delay(300.milliseconds)
        // One live seal per (file, device): the second push changes nothing.
        assertEquals(1, h.transfersFor(peerA).size)
        assertEquals(before, h.sent().size)
    }

    @Test
    fun serve_startsFreshTransferAtFloorAlignedStart() = runTest {
        val h = sealTransferHarness(totalBytes = 1024L * 1024, chunkSize = 220 * 1024)

        assertTrue(h.seals.serve(fixtureFileId, peerA, fromOffset = 300_000L))
        h.awaitOffers(1)

        val row = h.allTransfers().single { it.peer == peerA }
        val seal = h.sealStore.byFileAndTarget(fixtureFileId, peerA).single { it.kind == FileSealKind.SERVE }
        // floor(300_000 / 220*1024) * 220*1024 = 225280
        assertEquals(225280L, row.shape.byteFrom)
        assertEquals(0, row.nextChunkIndex)
        assertEquals(-1, row.contiguousAcked)
        val offer = h.offerHeaders().single()
        assertEquals(225280L, offer.shape.byteFrom)
        assertEquals(FileTransferStatus.OFFERED, row.status)
        assertEquals(seal.sealId, row.sealId)
    }

    @Test
    fun serve_unknownFile_returnsFalseWithoutRow() = runTest {
        val h = sealTransferHarness()
        h.plaintext.describeImpl = { null }
        assertFalse(h.seals.serve(fixtureFileId, peerA, fromOffset = 0L))
        assertTrue(h.allTransfers().isEmpty())
        assertTrue(h.sent().isEmpty())
    }

    @Test
    fun serve_supersedesLivePushWithCancel() = runTest {
        val h = sealTransferHarness()

        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { h.fileEvents.collect { seen += it } }
        h.engine.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        h.awaitOffers(2)
        val pushed = h.transfersFor(peerA).single()

        assertTrue(h.seals.serve(fixtureFileId, peerA, fromOffset = 0L))

        // Old transfer is cancelled silently (a handoff, not a failure); the fresh serve
        // runs as its own transfer; the sibling device's push is untouched.
        withTimeout(5.seconds) {
            while (h.transferStore.find(pushed.transferId)?.status?.isLive == true) delay(10.milliseconds)
        }
        assertEquals(FileTransferStatus.CANCELLED, h.transferStore.find(pushed.transferId)?.status)
        assertTrue(seen.none { it is FileEvent.Failed }, "supersede is silent")
        val liveA = h.transfersFor(peerA)
        assertEquals(1, liveA.size)
        val serveRow = h.transferStore.find(liveA.single().transferId)!!
        assertNotEquals(pushed.transferId, serveRow.transferId)
        val liveB = h.transfersFor(peerB)
        assertEquals(1, liveB.size)
    }

    @Test
    fun cancelFile_cancelsLiveTransfers() = runTest {
        val h = sealTransferHarness()

        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { h.fileEvents.collect { seen += it } }
        h.engine.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        h.awaitOffers(2)

        h.seals.cancel(fixtureFileId, fixtureAccount)

        withTimeout(5.seconds) {
            while (h.allTransfers().any { it.status.isLive }) {
                delay(10.milliseconds)
            }
        }
        assertTrue(h.allTransfers().none { it.status.isLive })
        withTimeout(5.seconds) {
            while (seen.filterIsInstance<FileEvent.Failed>().isEmpty()) {
                delay(10.milliseconds)
            }
        }
        assertEquals(
            setOf(FileFailureReason.CANCELLED),
            seen.filterIsInstance<FileEvent.Failed>().map { it.reason }.toSet(),
        )
    }

    @Test
    fun sendState_aggregatesProgressAndTerminal() = runTest {
        val h = sealTransferHarness(totalBytes = 1024L * 1024, chunkSize = 220 * 1024)
        h.engine.runIn(backgroundScope)

        h.seals.push(fixtureFileId, fixtureAccount)

        val sending = h.seals.sendState(fixtureFileId)
        assertEquals(FileSendStatus.SENDING, sending.getValue(fixtureAccount).status)

        // Progress is the least any device has covered, so both devices must ACK before the
        // account's sentBytes leaves the starting zero.
        for (device in listOf(peerA, peerB)) {
            val transfer = h.transfersFor(device).single()
            h.engine.onOutcome(h.ackOffer(transfer.transferId, device))
            h.engine.onAck(transfer.transferId, device, highestContiguousIndex = 2)
        }
        val expectedBytes = 3L * h.chunkSize
        withTimeout(5.seconds) {
            while (h.seals.sendState(fixtureFileId).getValue(fixtureAccount).sentBytes < expectedBytes) {
                delay(10.milliseconds)
            }
        }
        val progress = h.seals.sendState(fixtureFileId).getValue(fixtureAccount)
        assertEquals(FileSendStatus.SENDING, progress.status)
        // Chunks 0..2 covered: exactly three chunks from the chunk boundary.
        assertEquals(expectedBytes, progress.sentBytes)
    }

    @Test
    fun sendState_failedOnlyWhenAllTerminal() = runTest {
        val h = sealTransferHarness()
        h.seals.runIn(backgroundScope)
        h.engine.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        val transferA = h.transfersFor(peerA).single()
        val transferB = h.transfersFor(peerB).single()

        // One device refused while the other still sends: still SENDING, never failed.
        h.engine.onOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_TRANSFER, transferA.transferId.value),
                peerA,
                h.offerPacketOf(transferA.transferId),
                PacketNackReason.DECLINED,
            ),
        )
        h.awaitStatus(transferA.transferId, FileTransferStatus.DECLINED)
        assertEquals(FileSendStatus.SENDING, h.seals.sendState(fixtureFileId).getValue(fixtureAccount).status)

        // Both refused: the send reads DECLINED.
        h.engine.onOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_TRANSFER, transferB.transferId.value),
                peerB,
                h.offerPacketOf(transferB.transferId),
                PacketNackReason.DECLINED,
            ),
        )
        withTimeout(5.seconds) {
            while (h.seals.sendState(fixtureFileId).getValue(fixtureAccount).status != FileSendStatus.DECLINED) delay(
                10
            )
        }
        assertEquals(FileSendStatus.DECLINED, h.seals.sendState(fixtureFileId).getValue(fixtureAccount).status)
    }

    @Test
    fun sendState_mixedEndedAndExpiredReportsEnded() = runTest {
        val h = sealTransferHarness()
        h.seals.runIn(backgroundScope)
        h.engine.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        val transferA = h.transfersFor(peerA).single()
        val transferB = h.transfersFor(peerB).single()

        // A ends with an unknown outcome, B expires: ENDED wins over EXPIRED.
        h.engine.onOutcome(h.ackOffer(transferA.transferId, peerA))
        withTimeout(5.seconds) { while (h.chunkIndices().isEmpty()) delay(10.milliseconds) }
        val chunkPacket = h.sent().map { it.first }.first { env ->
            val decoded = FileHeader.decode(env.routingHeader)
            decoded is FileHeader.Chunk && decoded.transferId == transferA.transferId
        }.packetId
        h.engine.onOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_TRANSFER, transferA.transferId.value),
                peerA,
                chunkPacket,
                PacketNackReason.DECLINED,
            ),
        )
        h.awaitStatus(transferA.transferId, FileTransferStatus.ENDED)

        h.clock.advanceTo(h.clock.now() + 3.days)
        h.engine.onOutcome(
            OutboxOutcome.Expired(
                OutboxTag(OutboxTag.Kind.FILE_TRANSFER, transferB.transferId.value),
                peerB,
                h.offerPacketOf(transferB.transferId)
            ),
        )
        withTimeout(5.seconds) {
            while (h.seals.sendState(fixtureFileId)
                    .getValue(fixtureAccount).status != FileSendStatus.ENDED
            ) delay(10.milliseconds)
        }
        assertEquals(FileSendStatus.ENDED, h.seals.sendState(fixtureFileId).getValue(fixtureAccount).status)
    }

    @Test
    fun sendState_completedOnceReady() = runTest {
        val h = sealTransferHarness()
        h.seals.push(fixtureFileId, fixtureAccount)
        val sealA = h.sealStore.byFileAndTarget(fixtureFileId, peerA).single().sealId
        val sealB = h.sealStore.byFileAndTarget(fixtureFileId, peerB).single().sealId

        h.seals.onComplete(sealA, peerA)
        h.seals.onComplete(sealB, peerB)
        withTimeout(5.seconds) {
            while (h.seals.sendState(fixtureFileId).getValue(fixtureAccount).status != FileSendStatus.COMPLETED) delay(
                10
            )
        }
        val state = h.seals.sendState(fixtureFileId).getValue(fixtureAccount)
        assertEquals(FileSendStatus.COMPLETED, state.status)
        assertEquals(state.totalBytes, state.sentBytes)
    }
}
