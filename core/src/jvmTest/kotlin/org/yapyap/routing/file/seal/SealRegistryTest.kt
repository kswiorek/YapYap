package org.yapyap.routing.file.seal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.FileSeal
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.IdentityFileProtection
import org.yapyap.routing.file.ackOffer
import org.yapyap.routing.file.allTransfers
import org.yapyap.routing.file.awaitOffers
import org.yapyap.routing.file.awaitStatus
import org.yapyap.routing.file.chunkIndices
import org.yapyap.routing.file.fixtureAccount
import org.yapyap.routing.file.fixtureFileId
import org.yapyap.routing.file.offerHeaders
import org.yapyap.routing.file.offerPacketOf
import org.yapyap.routing.file.onOutcome
import org.yapyap.routing.file.peerA
import org.yapyap.routing.file.peerB
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileFailureReason
import org.yapyap.routing.file.port.FileSendStatus
import org.yapyap.routing.file.sealTransferHarness
import org.yapyap.routing.file.sent
import org.yapyap.routing.file.transfersFor
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.RouterConfig
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

@OptIn(ExperimentalCoroutinesApi::class)
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
        val h = sealTransferHarness(totalBytes = RouterConfig().smallFileMaxBytes + 1)
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
    fun pushFile_overlappingPushes_makeOneSealPerDevice() = runTest {
        val protection = GatedFileProtection()
        val h = sealTransferHarness(fileProtection = protection)
        val sealA = protection.park(peerA)
        val sealB = protection.park(peerB)

        val first = launch { h.seals.push(fixtureFileId, fixtureAccount) }
        // The first push is inside its execute phase: its (file, device) pairs are marked in
        // flight, but no seal row has landed yet, so the durable live check cannot see it.
        withTimeout(5.seconds) {
            sealA.awaitEntered()
            sealB.awaitEntered()
        }

        val second = launch { h.seals.push(fixtureFileId, fixtureAccount) }
        // The overlapping push plans nothing and finishes while the first still seals.
        withTimeout(5.seconds) { second.join() }
        assertTrue(first.isActive, "the first push is still sealing")
        assertEquals(0, h.sealStore.byFile(fixtureFileId).size, "the first push's seals have not landed yet")

        sealA.release()
        sealB.release()
        first.join()
        h.awaitOffers(2)

        assertEquals(2, h.sealStore.byFile(fixtureFileId).size)
        for (device in listOf(peerA, peerB)) {
            val seals = h.sealStore.byFileAndTarget(fixtureFileId, device)
            assertEquals(1, seals.size, "one seal per (file, device)")
            assertEquals(FileSealKind.PUSH, seals.single().kind)
            assertEquals(1, h.transfersFor(device).size)
        }
    }

    @Test
    fun serve_overlappingServes_makeOneSealAndDeclineTheSecond() = runTest {
        val protection = GatedFileProtection()
        val h = sealTransferHarness(fileProtection = protection)
        val sealA = protection.park(peerA)

        val first = launch { assertTrue(h.seals.serve(fixtureFileId, peerA, fromOffset = 0L)) }
        withTimeout(5.seconds) { sealA.awaitEntered() }

        // Same pair, already in flight: the REQUEST is declined instead of starting a second seal.
        assertFalse(
            withTimeout(5.seconds) { h.seals.serve(fixtureFileId, peerA, fromOffset = 0L) },
            "a serve already in flight declines",
        )

        sealA.release()
        first.join()
        h.awaitOffers(1)

        val seals = h.sealStore.byFileAndTarget(fixtureFileId, peerA)
        assertEquals(1, seals.size, "one serve seal for the device")
        assertEquals(FileSealKind.SERVE, seals.single().kind)
        assertEquals(1, h.transfersFor(peerA).size)
    }

    @Test
    fun serve_whileSameDevicePushInFlight_declinesAndCreatesNoSeal() = runTest {
        val protection = GatedFileProtection()
        val h = sealTransferHarness(fileProtection = protection)
        val pushSealA = protection.park(peerA)

        val push = launch { h.seals.push(fixtureFileId, fixtureAccount) }
        withTimeout(5.seconds) { pushSealA.awaitEntered() }

        // The push's seal for the device is in flight: the REQUEST is declined and makes nothing.
        assertFalse(
            withTimeout(5.seconds) { h.seals.serve(fixtureFileId, peerA, fromOffset = 0L) },
            "a push in flight for the device declines the serve",
        )
        assertEquals(0, h.sealStore.byFileAndTarget(fixtureFileId, peerA).size, "the serve made no seal")

        pushSealA.release()
        withTimeout(5.seconds) { push.join() }

        val seals = h.sealStore.byFileAndTarget(fixtureFileId, peerA)
        assertEquals(1, seals.size, "one PUSH seal for the device")
        assertEquals(FileSealKind.PUSH, seals.single().kind)
    }

    @Test
    fun pushFile_outerCleanupKeepsTheLeaseOfALaterServe() = runTest {
        val protection = GatedFileProtection()
        val h = sealTransferHarness(fileProtection = protection)
        val pushSealA = protection.park(peerA)
        val pushSealB = protection.park(peerB)

        val push = launch { h.seals.push(fixtureFileId, fixtureAccount) }
        withTimeout(5.seconds) {
            pushSealA.awaitEntered()
            pushSealB.awaitEntered()
        }

        // A's push seal lands, so the push releases A's mark while B is still parked.
        // runCurrent() drains the work the gate release queued, so the mark is free before the serve.
        pushSealA.release()
        runCurrent()
        assertEquals(1, h.sealStore.byFileAndTarget(fixtureFileId, peerA).size, "A's push seal landed")

        // A serve now takes A's mark for itself and parks inside its own seal.
        val serveSealA = protection.park(peerA)
        val serve = launch { assertTrue(h.seals.serve(fixtureFileId, peerA, fromOffset = 0L)) }
        withTimeout(5.seconds) { serveSealA.awaitEntered() }

        // The push's outer cleanup runs when B lands; it must not release the serve's mark for A.
        pushSealB.release()
        withTimeout(5.seconds) { push.join() }
        assertFalse(
            withTimeout(5.seconds) { h.seals.serve(fixtureFileId, peerA, fromOffset = 0L) },
            "the serve in flight still owns the device",
        )

        serveSealA.release()
        withTimeout(5.seconds) { serve.join() }

        val seals = h.sealStore.byFileAndTarget(fixtureFileId, peerA)
        assertEquals(2, seals.size, "one PUSH seal and one SERVE seal for the device")
        assertEquals(1, seals.count { it.kind == FileSealKind.PUSH })
        assertEquals(1, seals.count { it.kind == FileSealKind.SERVE })
        assertEquals(1, h.sealStore.byFileAndTarget(fixtureFileId, peerB).size)
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
                OutboxTag.FileTransfer(transferA.transferId),
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
                OutboxTag.FileTransfer(transferB.transferId),
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
                OutboxTag.FileTransfer(transferA.transferId),
                peerA,
                chunkPacket,
                PacketNackReason.DECLINED,
            ),
        )
        h.awaitStatus(transferA.transferId, FileTransferStatus.ENDED)

        h.clock.advanceTo(h.clock.now() + 3.days)
        h.engine.onOutcome(
            OutboxOutcome.Expired(
                OutboxTag.FileTransfer(transferB.transferId),
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

/**
 * File protection that parks `seal` calls on gates a test arms with [park]: the n-th call for a
 * device waits for the n-th gate armed for it, so several seals of one file can be held at once and
 * released one by one. Calls with no armed gate proceed. Nothing here sleeps.
 */
private class GatedFileProtection(
    private val delegate: FileProtection = IdentityFileProtection(),
) : FileProtection by delegate {
    /** One armed gate: the next `seal` call for the device it was armed for waits until [release]. */
    class Gate internal constructor() {
        private val entered = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        /** Completes once a `seal` call is parked on this gate. */
        suspend fun awaitEntered() = entered.await()

        /** Lets the parked `seal` call proceed. */
        fun release() {
            released.complete(Unit)
        }

        internal suspend fun pass() {
            entered.complete(Unit)
            released.await()
        }
    }

    private val gates = mutableMapOf<PeerId, ArrayDeque<Gate>>()

    /** Arms a gate for the next `seal` call of [device]. */
    fun park(device: PeerId): Gate = Gate().also { gates.getOrPut(device) { ArrayDeque() }.addLast(it) }

    override suspend fun seal(seal: FileSeal, target: PeerId): ByteArray {
        val gate = gates[target]?.removeFirstOrNull()
        if (gate != null) gate.pass()
        return delegate.seal(seal, target)
    }
}
