package org.yapyap.routing.file.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileFailureReason
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.store.FilePushKind
import org.yapyap.routing.store.FilePushStatus
import org.yapyap.routing.store.OutboxTag
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class FilePushEngineTest {

    @Test
    fun markerAck_startsWindow_countingQueuedRows() = runTest {
        val h = pushHarness(totalBytes = 1024L * 1024, chunkSize = 220 * 1024)

        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        h.awaitOffers(2)
        val transfer = h.transferIdsFor(peerA).single()
        val before = h.sent.size

        h.engine.onOutboxOutcome(h.ackMarker(transfer, peerA))
        withTimeout(5.seconds) { while (h.chunkIndices().size < 5) delay(10) }

        val row = h.pushes.find(transfer)!!
        assertEquals(FilePushStatus.IN_FLIGHT, row.status)
        assertEquals(listOf(0, 1, 2, 3, 4), h.chunkIndices())
        assertEquals(before + 5, h.sent.size)
        assertEquals(5, h.pushes.find(transfer)!!.nextChunkIndex)
    }

    @Test
    fun coverageAck_movesResume_notWindow() = runTest {
        val h = pushHarness()

        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        h.engine.onOutboxOutcome(h.ackMarker(transfer, peerA))
        withTimeout(5.seconds) { while (h.chunkIndices().size < 5) delay(10) }

        // ACK the first chunk's outbox row (transport ACK), then the coverage ACK.
        val firstChunk = h.sent.map { it.first }
            .first { FileEnvelope.decode(it.payload).decodePayload() is FilePayload.EncryptedChunk }.packetId
        h.engine.onOutboxOutcome(
            OutboxOutcome.Acked(
                OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer.value),
                peerA,
                firstChunk
            )
        )
        h.engine.coverageAck(transfer, peerA, contiguousChunks = 2, missing = emptyList())
        withTimeout(5.seconds) {
            while (h.pushes.find(transfer)?.contiguousAcked != 2) delay(10)
        }

        val row = h.pushes.find(transfer)!!
        assertEquals(2, row.contiguousAcked)
        // The window still counts queued rows: no new chunk is forced by coverage alone
        // beyond refilling the window as rows drain.
        assertTrue(h.chunkIndices().toSet().size == h.chunkIndices().size, "no chunk index enqueued twice")
    }

    @Test
    fun restart_rebuildsQueued_withoutNewSeals() = runTest {
        val h = pushHarness(totalBytes = 3L * 1024 * 1024, chunkSize = 100 * 1024)

        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerB).single()
        h.engine.onOutboxOutcome(h.ackMarker(transfer, peerB))
        withTimeout(5.seconds) { while (h.chunkIndices().size < 16) delay(10) }
        val sealsBefore = h.protection.encryptChunkCalls

        // Restart: new engine over the same store. The outbox is a DB table, so its rows
        // survive the restart; the actor rebuilds `queued` from them plus file_push_chunks
        // and never re-seals.
        val h2 = pushHarness(totalBytes = 3L * 1024 * 1024, chunkSize = 100 * 1024)
        for (row in h.pushes.live()) h2.pushes.insert(row)
        // Replay the chunk table and the still-queued outbox rows.
        for ((index, packetId) in h.pushes.chunksByTransfer(transfer)) {
            h2.pushes.recordChunk(transfer, index, packetId)
        }
        for ((env, tag) in h.sent) {
            h2.sent += env to tag
            val decoded = FileEnvelope.decode(env.payload).decodePayload() as? FilePayload.EncryptedChunk
            if (decoded != null) h2.packetIndex[env.packetId] = decoded.chunkIndex
        }
        val replayed = h2.sent.size
        val replayedChunks = h2.chunkIndices().size
        h2.protection.encryptChunkCalls = sealsBefore
        h2.engine.runIn(backgroundScope)
        // With a full rebuilt window no chunk goes out again and nothing is re-sealed.
        // (The unacked peerA marker is re-armed — a retry, not a re-seal of chunks.)
        delay(300)
        assertEquals(replayedChunks, h2.chunkIndices().size, "a full rebuilt window sends no chunk new")
        assertEquals(sealsBefore, h2.protection.encryptChunkCalls, "no new seals on restart")

        // Draining two rows refills the window forward: exactly the next indices go out,
        // never anything below the resume point.
        val queuedPackets = h2.pushes.chunksByTransfer(transfer)
        val ackTag = OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer.value)
        h2.engine.onOutboxOutcome(OutboxOutcome.Acked(ackTag, peerB, queuedPackets.getValue(0)))
        h2.engine.onOutboxOutcome(OutboxOutcome.Acked(ackTag, peerB, queuedPackets.getValue(1)))
        withTimeout(5.seconds) {
            while (h2.chunkIndices().size < replayedChunks + 2) delay(10)
        }
        val fresh = h2.chunkIndices().drop(replayedChunks)
        assertEquals(listOf(16, 17), fresh.sorted(), "the window refills forward after restart")
    }

    @Test
    fun targetDeclined_onMarker_isTerminal() = runTest {
        val h = pushHarness()

        h.engine.runIn(backgroundScope)
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch { h.fileEvents.collect { seen += it } }
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()

        h.engine.onOutboxOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer.value),
                peerA,
                h.markerOf(transfer),
                PacketNackReason.DECLINED,
            ),
        )
        h.awaitStatus(transfer, FilePushStatus.DECLINED)

        assertTrue(h.sent.none { FileEnvelope.decode(it.first.payload).transferId == transfer }, "rows dropped")
        // The sibling device is still sending: no Failed yet (account-level gating).
        assertTrue(seen.filterIsInstance<FileEvent.Failed>().isEmpty())

        // The sibling refusing too ends the account send: exactly one Failed.
        val sibling = h.transferIdsFor(peerB).single()
        h.engine.onOutboxOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_PUSH, sibling.value),
                peerB,
                h.markerOf(sibling),
                PacketNackReason.DECLINED,
            ),
        )
        withTimeout(5.seconds) {
            while (seen.filterIsInstance<FileEvent.Failed>().isEmpty()) delay(10)
        }
        val failed = seen.filterIsInstance<FileEvent.Failed>()
        assertEquals(1, failed.size)
        assertEquals(FileFailureReason.DECLINED, failed.single().reason)
    }

    @Test
    fun declinedChunk_endsTransferQuietly() = runTest {
        val h = pushHarness()

        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        h.engine.onOutboxOutcome(h.ackMarker(transfer, peerA))
        withTimeout(5.seconds) { while (h.chunkIndices().isEmpty()) delay(10) }
        val chunkPacket = h.sent.map { it.first }.first { env ->
            val decoded = FileEnvelope.decode(env.payload).decodePayload()
            decoded is FilePayload.EncryptedChunk &&
                    FileEnvelope.decode(env.payload).transferId == transfer
        }.packetId

        h.engine.onOutboxOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer.value),
                peerA,
                chunkPacket,
                PacketNackReason.DECLINED
            ),
        )
        h.awaitStatus(transfer, FilePushStatus.ENDED)

        assertEquals(FilePushStatus.ENDED, h.pushes.find(transfer)?.status)
        assertTrue(h.sent.none { FileEnvelope.decode(it.first.payload).transferId == transfer })
    }

    @Test
    fun otherTerminalNack_endsTransfer() = runTest {
        val h = pushHarness()

        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        h.engine.onOutboxOutcome(
            OutboxOutcome.Nacked(
                OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer.value),
                peerA,
                h.markerOf(transfer),
                PacketNackReason.PERMANENT_PROTECTION_FAILED,
            ),
        )
        h.awaitStatus(transfer, FilePushStatus.ENDED)
        assertEquals(FilePushStatus.ENDED, h.pushes.find(transfer)?.status)
    }

    @Test
    fun complete_resolvesOnce_depositsCancelled() = runTest {
        val relays = object : RelaySelectionPolicy {
            override suspend fun selectRelays(targetDevice: org.yapyap.protocol.PeerId) = listOf(relay1, relay2)
        }
        val h = pushHarness(relaySelection = relays)

        h.engine.runIn(backgroundScope)
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch { h.fileEvents.collect { seen += it } }
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        h.engine.onOutboxOutcome(h.ackMarker(transfer, peerA))
        withTimeout(5.seconds) { while (h.chunkIndices().isEmpty()) delay(10) }
        // Deposit round once, then COMPLETE the deposit first.
        h.engine.depositRemainder(transfer)
        withTimeout(5.seconds) { while (h.pushes.live().count { it.kind == FilePushKind.DEPOSIT } < 2) delay(10) }
        val deposits = h.pushes.live().filter { it.kind == FilePushKind.DEPOSIT }
        h.engine.complete(deposits.first().transferId, relay1)
        withTimeout(5.seconds) {
            while (h.pushes.find(deposits.first().transferId)?.status?.isLive == true) delay(10)
        }
        // The deposit's COMPLETE before the direct one gives COMPLETED once overall.
        h.engine.complete(transfer, peerA)
        h.awaitStatus(transfer, FilePushStatus.COMPLETED)
        delay(200)
        assertEquals(1, seen.filterIsInstance<FileEvent.OutgoingComplete>().size)
    }

    @Test
    fun completeForDepositFromTarget_resolvesAuthoringRow() = runTest {
        val relays = object : RelaySelectionPolicy {
            override suspend fun selectRelays(targetDevice: org.yapyap.protocol.PeerId) = listOf(relay1)
        }
        val h = pushHarness(relaySelection = relays)

        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        h.engine.depositRemainder(transfer)
        withTimeout(5.seconds) {
            while (h.pushes.live().none { it.kind == FilePushKind.DEPOSIT }) delay(10)
        }
        val deposit = h.pushes.live().single { it.kind == FilePushKind.DEPOSIT }
        // A COMPLETE that names a deposit transferId but comes from the ultimate target
        // resolves the authoring row (the target addresses every transfer it knows by
        // (file, device), never by relay-internal ids).
        h.engine.complete(deposit.transferId, peerA)
        h.awaitStatus(transfer, FilePushStatus.COMPLETED)
        assertEquals(FilePushStatus.COMPLETED, h.pushes.find(transfer)?.status)
    }

    @Test
    fun cancelFromRelay_isIgnored() = runTest {
        val h = pushHarness()

        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()

        // A CANCEL whose source is a relay (not the row's target) is ignored.
        h.engine.cancelled(transfer, relay1, FileCancelReason.ABORT)
        delay(200)

        assertTrue(h.pushes.find(transfer)?.status?.isLive == true)
    }

    @Test
    fun fiftyConcurrentCoverageAcks_neverDuplicateChunk() = runTest {
        val h = pushHarness(totalBytes = 10L * 1024 * 1024, chunkSize = 220 * 1024)

        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        h.engine.onOutboxOutcome(h.ackMarker(transfer, peerA))
        withTimeout(5.seconds) { while (h.chunkIndices().size < 16) delay(10) }

        repeat(50) { i ->
            launch { h.engine.coverageAck(transfer, peerA, contiguousChunks = i % 5, missing = emptyList()) }
        }
        delay(500)

        val indices = h.chunkIndices()
        assertEquals(indices.toSet().size, indices.size, "50 concurrent ACKs never enqueue a chunk twice")
    }

    @Test
    fun expiredTransfer_isTerminal() = runTest {
        val h = pushHarness()

        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        // The marker must be out before the TTL passes: the engine would otherwise
        // expire the row itself (correct behavior — an unoffered transfer never starts).
        val marker = h.markerOf(transfer)
        h.clock.advanceTo(epochSeconds(10_000L) + 3.days)
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch { h.fileEvents.collect { seen += it } }
        h.engine.onOutboxOutcome(
            OutboxOutcome.Expired(OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer.value), peerA, marker),
        )
        h.awaitStatus(transfer, FilePushStatus.EXPIRED)
        assertEquals(FilePushStatus.EXPIRED, h.pushes.find(transfer)?.status)
    }

    @Test
    fun complete_fromWrongSource_isIgnored() = runTest {
        val h = pushHarness()
        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()

        // A COMPLETE from anyone but the row's target device is ignored, never as a refusal.
        h.engine.complete(transfer, relay1)
        delay(200)

        assertTrue(h.pushes.find(transfer)?.status?.isLive == true)
        assertTrue(h.sent.none { FileEnvelope.decode(it.first.payload).decodePayload() is FilePayload.Complete })
    }

    @Test
    fun expiredMarker_reArmsWhileLive() = runTest {
        val h = pushHarness()
        h.engine.runIn(backgroundScope)
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transfer = h.transferIdsFor(peerA).single()
        val oldMarker = h.markerOf(transfer)
        val countBefore = h.sent.size

        // The marker's outbox row expired before its TTL: the actor re-arms the same
        // marker from its own state (design §6.1), never as a fresh transfer.
        h.engine.onOutboxOutcome(
            OutboxOutcome.Expired(OutboxTag(OutboxTag.Kind.FILE_PUSH, transfer.value), peerA, oldMarker),
        )
        withTimeout(5.seconds) {
            while (h.pushes.find(transfer)?.markerPacketId == oldMarker) delay(10)
        }

        val row = h.pushes.find(transfer)!!
        assertEquals(FilePushStatus.CREATED, row.status)
        assertTrue(row.markerPacketId != null && row.markerPacketId != oldMarker)
        assertEquals(countBefore + 1, h.sent.size)
    }

    @Test
    fun complete_emittedOncePerAccount() = runTest {
        val h = pushHarness()
        h.engine.runIn(backgroundScope)
        // Unconfined subscription: the collector subscribes synchronously at launch, before
        // the synchronous engine.complete calls below emit. A test-scheduler collector would
        // only start on the first yield, racing the buffered events.
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { h.fileEvents.collect { seen += it } }
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val transferA = h.transferIdsFor(peerA).single()
        val transferB = h.transferIdsFor(peerB).single()

        // Both devices complete: exactly one OutgoingComplete per account.
        h.engine.complete(transferA, peerA)
        h.awaitStatus(transferA, FilePushStatus.COMPLETED)
        h.engine.complete(transferB, peerB)
        h.awaitStatus(transferB, FilePushStatus.COMPLETED)

        assertEquals(1, seen.filterIsInstance<FileEvent.OutgoingComplete>().size)
    }

    @Test
    fun resendAfterComplete_reportsCompleteAgain() = runTest {
        val h = pushHarness()
        h.engine.runIn(backgroundScope)
        val seen = mutableListOf<FileEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { h.fileEvents.collect { seen += it } }
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val first = h.transferIdsFor(peerA).single()
        h.engine.complete(first, peerA)
        h.awaitStatus(first, FilePushStatus.COMPLETED)

        // The same file (same content id) sent again: the finished transfer of the pair is
        // history and must not swallow the new send's completion.
        h.sender.pushFile(fixtureFileId, fixtureAccount)
        val second = h.transferIdsFor(peerA).single { it != first }
        assertNull(h.pushes.find(first), "the earlier finished transfer of the pair is gone")
        h.engine.complete(second, peerA)
        h.awaitStatus(second, FilePushStatus.COMPLETED)

        assertEquals(2, seen.filterIsInstance<FileEvent.OutgoingComplete>().size, "one per send")
    }

    @Test
    fun unknownTransfer_outcomeIgnored() = runTest {
        val h = pushHarness()

        h.engine.runIn(backgroundScope)
        h.engine.onOutboxOutcome(
            OutboxOutcome.Acked(OutboxTag(OutboxTag.Kind.FILE_PUSH, Uuid.random()), peerA, Uuid.random()),
        )
        h.engine.complete(org.yapyap.protocol.TransferId(Uuid.random()), peerA)
        assertTrue(h.pushes.live().isEmpty())
    }
}
