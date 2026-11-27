package org.yapyap.routing.file.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FileHeader
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.ackOffer
import org.yapyap.routing.file.allTransfers
import org.yapyap.routing.file.awaitOffers
import org.yapyap.routing.file.awaitStatus
import org.yapyap.routing.file.chunkIndices
import org.yapyap.routing.file.fixtureAccount
import org.yapyap.routing.file.fixtureFileId
import org.yapyap.routing.file.offerPacketOf
import org.yapyap.routing.file.peerA
import org.yapyap.routing.file.peerB
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileFailureReason
import org.yapyap.routing.file.relay1
import org.yapyap.routing.file.relay2
import org.yapyap.routing.file.sealTransferHarness
import org.yapyap.routing.file.sent
import org.yapyap.routing.file.transfersFor
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.store.FileTransferStatus
import org.yapyap.routing.store.OutboxTag
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** The transfer engine over a seal registry: offers, windows, outcomes and completion. */
class TransferEngineTest {

    @Test
    fun offerAck_startsWindow_countingQueuedRows() = runTest {
        val h = sealTransferHarness(totalBytes = 1024L * 1024, chunkSize = 220 * 1024)

        h.engine.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        h.awaitOffers(2)
        val transfer = h.transfersFor(peerA).single()

        h.engine.onOutcome(h.ackOffer(transfer.transferId, peerA))
        withTimeout(5.seconds) { while (h.chunkIndices().size < 5) delay(10.milliseconds) }

        val row = h.transferStore.find(transfer.transferId)!!
        assertEquals(FileTransferStatus.STREAMING, row.status)
        assertEquals(listOf(0, 1, 2, 3, 4), h.chunkIndices().sorted())
        assertEquals(5, row.nextChunkIndex)
        assertEquals(-1, row.contiguousAcked)
    }

    @Test
    fun coverageAck_movesResume_notWindow() = runTest {
        // More chunks than the window (16), so a coverage-driven window would show.
        val h = sealTransferHarness(totalBytes = 32L * 220 * 1024)

        h.engine.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        val transfer = h.transfersFor(peerA).single()
        h.engine.onOutcome(h.ackOffer(transfer.transferId, peerA))
        withTimeout(5.seconds) { while (h.chunkIndices().size < 16) delay(10.milliseconds) }

        // ACK one chunk's outbox row (transport ACK), then the coverage ACK.
        val chunkPackets = h.transferStore.chunks(transfer.transferId)
        val firstPacket = chunkPackets.getValue(0)
        h.engine.onOutcome(
            OutboxOutcome.Acked(
                OutboxTag(OutboxTag.Kind.FILE_TRANSFER, transfer.transferId.value),
                peerA,
                firstPacket,
            ),
        )
        delay(100.milliseconds)
        val queuedBefore = h.chunkIndices().size
        h.engine.onAck(transfer.transferId, peerA, highestContiguousIndex = 2)
        withTimeout(5.seconds) {
            while (h.transferStore.find(transfer.transferId)?.contiguousAcked != 2) delay(10.milliseconds)
        }
        delay(100.milliseconds)

        val row = h.transferStore.find(transfer.transferId)!!
        assertEquals(2, row.contiguousAcked)
        // Coverage moves the resume point, never the window: the transport ACK freed one
        // slot (refilled with the next index), coverage alone queued nothing.
        assertEquals(queuedBefore, h.chunkIndices().size, "coverage alone queued more chunks")
        val indices = h.chunkIndices()
        assertEquals(indices.toSet().size, indices.size, "no chunk index enqueued twice")
    }

    @Test
    fun restart_rebuildsQueued_withoutNewSeals() = runTest {
        val h = sealTransferHarness(totalBytes = 3L * 1024 * 1024, chunkSize = 100 * 1024)

        h.engine.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        val transfer = h.transfersFor(peerB).single()
        h.engine.onOutcome(h.ackOffer(transfer.transferId, peerB))
        withTimeout(5.seconds) { while (h.chunkIndices().size < 16) delay(10.milliseconds) }
        val sealsBefore = h.sealStore.byFile(fixtureFileId).size

        // Restart: new engine over copies of the same rows. The outbox is a table, so its
        // rows survive the restart; the engine rebuilds `queued` from them plus the chunk
        // mappings and never re-seals (only the registry makes seals, and it isn't rerun).
        val h2 = sealTransferHarness(totalBytes = 3L * 1024 * 1024, chunkSize = 100 * 1024)
        for (seal in h.sealStore.byFile(fixtureFileId)) h2.sealStore.insert(seal)
        for (row in h.allTransfers()) h2.transferStore.insert(row)
        for ((index, packetId) in h.transferStore.chunks(transfer.transferId)) {
            h2.transferStore.recordChunk(transfer.transferId, index, packetId)
        }
        for ((env, tag) in h.sent()) {
            val header = FileHeader.decode(env.routingHeader)
            val retry = if (header is FileHeader.Chunk) {
                org.yapyap.routing.store.RetryClass.FLAT
            } else {
                org.yapyap.routing.store.RetryClass.STANDARD
            }
            h2.outbox.enqueue(env, h2.clock.now(), tag = tag, retryClass = retry)
        }
        val replayedChunks = h2.chunkIndices().size
        h2.engine.runIn(backgroundScope)
        // A full rebuilt window sends nothing new and makes no seals.
        delay(300.milliseconds)
        assertEquals(replayedChunks, h2.chunkIndices().size, "a full rebuilt window sends nothing new")
        assertEquals(sealsBefore, h2.sealStore.byFile(fixtureFileId).size, "no new seals on restart")

        // Draining two rows refills the window forward: exactly the next indices go out,
        // never anything below the resume point.
        val queuedPackets = h2.transferStore.chunks(transfer.transferId)
        val ackTag = OutboxTag(OutboxTag.Kind.FILE_TRANSFER, transfer.transferId.value)
        h2.engine.onOutcome(OutboxOutcome.Acked(ackTag, peerB, queuedPackets.getValue(0)))
        h2.engine.onOutcome(OutboxOutcome.Acked(ackTag, peerB, queuedPackets.getValue(1)))
        withTimeout(5.seconds) {
            while (h2.chunkIndices().size < replayedChunks + 2) delay(10.milliseconds)
        }
        val fresh = h2.chunkIndices().drop(replayedChunks)
        assertEquals(listOf(16, 17), fresh.sorted(), "the window refills forward after restart")
    }

    @Test
    fun targetDeclined_onOffer_isTerminalForThatDevice() = runTest {
        val h = sealTransferHarness()

        val seen = mutableListOf<FileEvent>()
        h.engine.runIn(backgroundScope)
        backgroundScope.launch(Dispatchers.Unconfined) { h.seals.runIn(backgroundScope) }
        backgroundScope.launch(Dispatchers.Unconfined) { h.fileEvents.collect { seen += it } }
        h.seals.push(fixtureFileId, fixtureAccount)
        val transfer = h.transfersFor(peerA).single()

        h.engine.onOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_TRANSFER, transfer.transferId.value),
                peerA,
                h.offerPacketOf(transfer.transferId),
                PacketNackReason.DECLINED,
            ),
        )
        h.awaitStatus(transfer.transferId, FileTransferStatus.DECLINED)

        assertTrue(h.sent().none { (env, _) ->
            (FileHeader.decode(env.routingHeader) as? FileHeader.OfTransfer)?.transferId == transfer.transferId
        }, "rows dropped")
        // The sibling device is still sending: no Failed yet (account-level gating).
        assertTrue(seen.filterIsInstance<FileEvent.Failed>().isEmpty())

        // The sibling refusing too ends the account send: exactly one Failed.
        val sibling = h.transfersFor(peerB).single()
        h.engine.onOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_TRANSFER, sibling.transferId.value),
                peerB,
                h.offerPacketOf(sibling.transferId),
                PacketNackReason.DECLINED,
            ),
        )
        withTimeout(5.seconds) {
            while (seen.filterIsInstance<FileEvent.Failed>().isEmpty()) delay(10.milliseconds)
        }
        val failed = seen.filterIsInstance<FileEvent.Failed>()
        assertEquals(1, failed.size)
        assertEquals(FileFailureReason.DECLINED, failed.single().reason)
    }

    @Test
    fun declinedChunk_endsTransferQuietly() = runTest {
        val h = sealTransferHarness()

        h.engine.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        val transfer = h.transfersFor(peerA).single()
        h.engine.onOutcome(h.ackOffer(transfer.transferId, peerA))
        withTimeout(5.seconds) { while (h.chunkIndices().isEmpty()) delay(10.milliseconds) }
        val chunkPacket = h.sent().map { it.first }.first { env ->
            val header = FileHeader.decode(env.routingHeader)
            header is FileHeader.Chunk && header.transferId == transfer.transferId
        }.packetId

        h.engine.onOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_TRANSFER, transfer.transferId.value),
                peerA,
                chunkPacket,
                PacketNackReason.DECLINED,
            ),
        )
        h.awaitStatus(transfer.transferId, FileTransferStatus.ENDED)

        assertEquals(FileTransferStatus.ENDED, h.transferStore.find(transfer.transferId)?.status)
        assertTrue(h.sent().none { (env, _) ->
            (FileHeader.decode(env.routingHeader) as? FileHeader.OfTransfer)?.transferId == transfer.transferId
        })
    }

    @Test
    fun otherTerminalNack_endsTransfer() = runTest {
        val h = sealTransferHarness()

        h.engine.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        val transfer = h.transfersFor(peerA).single()
        h.engine.onOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_TRANSFER, transfer.transferId.value),
                peerA,
                h.offerPacketOf(transfer.transferId),
                PacketNackReason.PERMANENT_PROTECTION_FAILED,
            ),
        )
        h.awaitStatus(transfer.transferId, FileTransferStatus.ENDED)
        assertEquals(FileTransferStatus.ENDED, h.transferStore.find(transfer.transferId)?.status)
    }

    @Test
    fun complete_resolvesOnce_liveTransfersCancelled() = runTest {
        val relays = object : RelaySelectionPolicy {
            override suspend fun selectRelays(targetDevice: PeerId) = listOf(relay1, relay2)
        }
        val h = sealTransferHarness(relaySelection = relays)

        val seen = mutableListOf<FileEvent>()
        h.engine.runIn(backgroundScope)
        h.seals.runIn(backgroundScope)
        backgroundScope.launch(Dispatchers.Unconfined) { h.fileEvents.collect { seen += it } }
        h.seals.push(fixtureFileId, fixtureAccount)
        val transfer = h.transfersFor(peerA).single()
        h.engine.onOutcome(h.ackOffer(transfer.transferId, peerA))
        withTimeout(5.seconds) { while (h.chunkIndices().isEmpty()) delay(10.milliseconds) }
        // No link to the target: the tick reports the direct transfer gone and one deposit
        // round carries the same seal to both relays.
        withTimeout(30.seconds) {
            while (h.transfersFor(relay1).isEmpty() || h.transfersFor(relay2).isEmpty()) delay(50.milliseconds)
        }
        // The targets got the file and COMPLETE their seals (seal-level, by seal id).
        val sealA = h.sealStore.byFileAndTarget(fixtureFileId, peerA).single().sealId
        val sealB = h.sealStore.byFileAndTarget(fixtureFileId, peerB).single().sealId
        h.seals.onComplete(sealA, peerA)
        h.seals.onComplete(sealB, peerB)
        withTimeout(5.seconds) {
            while (h.sealStore.find(sealA)?.status !=
                org.yapyap.routing.store.FileSealStatus.DELIVERED
            ) delay(10.milliseconds)
        }
        withTimeout(5.seconds) {
            while (h.allTransfers().any { it.status.isLive }) delay(10.milliseconds)
        }
        // Its COMPLETE arriving again changes nothing: DELIVERED once overall.
        h.seals.onComplete(sealA, peerA)
        delay(200.milliseconds)
        assertEquals(1, seen.filterIsInstance<FileEvent.OutgoingComplete>().size)
    }

    @Test
    fun completeFromARelay_isIgnored() = runTest {
        val relays = object : RelaySelectionPolicy {
            override suspend fun selectRelays(targetDevice: PeerId) = listOf(relay1)
        }
        val h = sealTransferHarness(relaySelection = relays)

        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { h.fileEvents.collect { seen += it } }
        // Running the engine makes the liveness check below meaningful: a wrongly accepted
        // COMPLETE would cancel the transfer through the session, not just leave the row alone.
        h.engine.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        val transfer = h.transfersFor(peerA).single()
        val sealId = h.sealStore.byFileAndTarget(fixtureFileId, peerA).single().sealId
        // Only the seal's target device authors COMPLETE: a relay cannot claim delivery.
        h.seals.onComplete(sealId, relay1)
        delay(200.milliseconds)
        assertEquals(true, h.transferStore.find(transfer.transferId)?.status?.isLive)
        assertTrue(seen.none { it is FileEvent.OutgoingComplete })
    }

    @Test
    fun cancelledByPeer_endsTransfer() = runTest {
        val relays = object : RelaySelectionPolicy {
            override suspend fun selectRelays(targetDevice: PeerId) = listOf(relay1)
        }
        val h = sealTransferHarness(relaySelection = relays)

        h.engine.runIn(backgroundScope)
        h.seals.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        withTimeout(30.seconds) { while (h.transfersFor(relay1).isEmpty()) delay(50.milliseconds) }
        val deposits = h.transfersFor(relay1)
        // One deposit per device seal; cancel each (the relay's copy is forgotten, the seal settles).
        for ((transferId) in deposits) {
            h.engine.onCancelled(transferId, relay1, FileCancelReason.ABORT)
        }
        withTimeout(5.seconds) {
            while (deposits.any { h.transferStore.find(it.transferId)?.status?.isLive == true }) delay(10.milliseconds)
        }
    }

    @Test
    fun cancelledFromWrongSource_isIgnored() = runTest {
        val h = sealTransferHarness()

        h.engine.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        val transfer = h.transfersFor(peerA).single()

        // A CANCEL whose source is not the transfer's peer is ignored.
        h.engine.onCancelled(transfer.transferId, relay1, FileCancelReason.ABORT)
        delay(200.milliseconds)

        assertEquals(true, h.transferStore.find(transfer.transferId)?.status?.isLive)
    }

    @Test
    fun fiftyConcurrentCoverageAcks_neverDuplicateChunk() = runTest {
        val h = sealTransferHarness(totalBytes = 10L * 1024 * 1024, chunkSize = 220 * 1024)

        h.engine.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        val transfer = h.transfersFor(peerA).single()
        h.engine.onOutcome(h.ackOffer(transfer.transferId, peerA))
        withTimeout(5.seconds) { while (h.chunkIndices().size < 16) delay(10.milliseconds) }

        repeat(50) { i ->
            launch { h.engine.onAck(transfer.transferId, peerA, highestContiguousIndex = i % 5) }
        }
        delay(500.milliseconds)

        val indices = h.chunkIndices()
        assertEquals(indices.toSet().size, indices.size, "50 concurrent ACKs never enqueue a chunk twice")
    }

    @Test
    fun expiredTransfer_isTerminal() = runTest {
        val h = sealTransferHarness()

        h.engine.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        val transfer = h.transfersFor(peerA).single()
        // The offer must be out before the TTL passes: the engine would otherwise
        // expire the row itself (correct behavior — an unoffered transfer never starts).
        val offer = h.offerPacketOf(transfer.transferId)
        h.clock.advanceTo(epochSeconds(10_000L) + 3.days)
        h.engine.onOutcome(
            OutboxOutcome.Expired(OutboxTag(OutboxTag.Kind.FILE_TRANSFER, transfer.transferId.value), peerA, offer),
        )
        h.awaitStatus(transfer.transferId, FileTransferStatus.EXPIRED)
        assertEquals(FileTransferStatus.EXPIRED, h.transferStore.find(transfer.transferId)?.status)
    }

    @Test
    fun complete_fromWrongSource_isIgnored() = runTest {
        val h = sealTransferHarness()
        h.seals.push(fixtureFileId, fixtureAccount)
        val sealId = h.sealStore.byFileAndTarget(fixtureFileId, peerA).single().sealId

        // A COMPLETE from anyone but the seal's target device is ignored.
        h.seals.onComplete(sealId, relay1)
        delay(200.milliseconds)

        assertEquals(true, h.sealStore.find(sealId)?.status?.isLive)
    }

    @Test
    fun expiredOffer_reArmsWhileLive() = runTest {
        val h = sealTransferHarness()
        h.engine.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        val transfer = h.transfersFor(peerA).single()
        val oldOffer = h.offerPacketOf(transfer.transferId)

        // The offer's outbox row expired before its TTL: the engine re-arms the same
        // transfer from its own state, never as a fresh transfer.
        h.engine.onOutcome(
            OutboxOutcome.Expired(OutboxTag(OutboxTag.Kind.FILE_TRANSFER, transfer.transferId.value), peerA, oldOffer),
        )
        withTimeout(5.seconds) {
            while (h.transferStore.find(transfer.transferId)?.offerPacketId == oldOffer) delay(10.milliseconds)
        }

        val row = h.transferStore.find(transfer.transferId)!!
        assertEquals(FileTransferStatus.OFFERED, row.status)
        assertTrue(row.offerPacketId != null && row.offerPacketId != oldOffer)
    }

    @Test
    fun complete_emittedOncePerAccount() = runTest {
        val h = sealTransferHarness()
        // Unconfined subscription: the collector subscribes synchronously at launch, before
        // the synchronous seals.onComplete calls below emit. A test-scheduler collector would
        // only start on the first yield, racing the buffered events.
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { h.fileEvents.collect { seen += it } }
        h.seals.push(fixtureFileId, fixtureAccount)
        val sealA = h.sealStore.byFileAndTarget(fixtureFileId, peerA).single().sealId
        val sealB = h.sealStore.byFileAndTarget(fixtureFileId, peerB).single().sealId

        // Both devices complete: exactly one OutgoingComplete per account.
        h.seals.onComplete(sealA, peerA)
        h.seals.onComplete(sealB, peerB)

        assertEquals(1, seen.filterIsInstance<FileEvent.OutgoingComplete>().size)
    }

    @Test
    fun resendAfterComplete_makesFreshSeal_completionStaysSingle() = runTest {
        val h = sealTransferHarness()
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { h.fileEvents.collect { seen += it } }
        h.seals.push(fixtureFileId, fixtureAccount)
        val first = h.transfersFor(peerA).single()
        val firstSeal = h.sealStore.byFileAndTarget(fixtureFileId, peerA).single().sealId
        h.seals.onComplete(firstSeal, peerA)

        // The same file sent again: the finished seal is history and the new push seals
        // fresh (one seal per push, never re-made for the same push).
        h.seals.push(fixtureFileId, fixtureAccount)
        val second = h.transfersFor(peerA).single { it.transferId != first.transferId }
        assertNotEquals(first.transferId, second.transferId, "a resend seals fresh")
        val secondSeal = h.sealStore.byFileAndTarget(fixtureFileId, peerA).single { it.sealId != firstSeal }.sealId
        h.seals.onComplete(secondSeal, peerA)

        assertEquals(1, seen.filterIsInstance<FileEvent.OutgoingComplete>().size, "one per account")
    }

    @Test
    fun unknownTransfer_outcomeIgnored() = runTest {
        val h = sealTransferHarness()

        h.engine.onOutcome(
            OutboxOutcome.Acked(OutboxTag(OutboxTag.Kind.FILE_TRANSFER, Uuid.random()), peerA, Uuid.random()),
        )
        h.engine.onAck(TransferId(Uuid.random()), peerA, 0)
        h.engine.onCancelled(TransferId(Uuid.random()), peerA, FileCancelReason.ABORT)
        assertTrue(h.allTransfers().isEmpty())
    }
}
