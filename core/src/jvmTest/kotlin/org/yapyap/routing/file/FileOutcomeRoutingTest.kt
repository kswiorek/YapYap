package org.yapyap.routing.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileFailureReason
import org.yapyap.routing.file.port.FileManifest
import org.yapyap.routing.file.push.FileOutbox
import org.yapyap.routing.file.push.FilePushEngine
import org.yapyap.routing.file.push.testIncomingTransfers
import org.yapyap.routing.policy.NoRelaySelection
import org.yapyap.routing.router.FakeFilePlaintextSource
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.store.DefaultFilePushStore
import org.yapyap.routing.store.DefaultFileRelayStore
import org.yapyap.routing.store.FilePush
import org.yapyap.routing.store.FilePushKind
import org.yapyap.routing.store.FilePushStatus
import org.yapyap.routing.store.FileRelayStatus
import org.yapyap.routing.store.FileRelayTransfer
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.RelayBackup
import org.yapyap.routing.store.RetryClass
import org.yapyap.sync.buildSyncRoutingStack
import org.yapyap.sync.testDeviceIdentity
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * R2.3 outcome routing: a `FILE_PUSH` outcome reaches only the push engine, a `FILE_FORWARD`
 * outcome only the relay forwarder. Each side ignores the other's tag without touching its
 * store or the outbox; the matching-kind paths still act (chunk DECLINED ends the push,
 * forward DECLINED drops the relay transfer).
 */
class FileOutcomeRoutingTest {

    private val localDevice = PeerId("outcome-local-device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val targetDevice = PeerId("outcome-target-device-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val targetAccount = AccountId("outcome-target-account")
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val now = epochSeconds(10_000L)

    private class RecordingFileOutbox : FileOutbox {
        data class Enqueued(val envelope: BinaryEnvelope, val tag: OutboxTag)

        val enqueues = mutableListOf<Enqueued>()
        val deletes = mutableListOf<OutboxTag>()

        override suspend fun enqueue(
            envelope: BinaryEnvelope,
            tag: OutboxTag,
            relayBackup: RelayBackup,
            retryClass: RetryClass,
        ) {
            enqueues += Enqueued(envelope, tag)
        }

        override suspend fun enqueueDirect(envelope: BinaryEnvelope) = error("not used")
        override suspend fun deleteByTag(tag: OutboxTag) {
            deletes += tag
        }

        override suspend fun packetIdsByTag(tag: OutboxTag): List<Uuid> = emptyList()
    }

    private fun manifest() = FileManifest(
        fileId = fileId,
        totalBytes = 10_000L,
        blockSize = 1024 * 1024,
        blockHashes = listOf(ByteArray(32) { 7 }),
        fileName = "photo.jpg",
        mimeType = "image/jpeg",
    )

    private fun pushRow(transferId: TransferId, markerPacketId: Uuid) = FilePush(
        transferId = transferId,
        fileId = fileId,
        targetDevice = targetDevice,
        viaRelay = null,
        kind = FilePushKind.DIRECT,
        totalBytes = 10_000L,
        key = ByteArray(32) { it.toByte() },
        chunkSizeBytes = 32 * 1024,
        startIndex = 0,
        nextChunkIndex = 1,
        contiguousAcked = -1,
        markerPacketId = markerPacketId,
        status = FilePushStatus.IN_FLIGHT,
        createdAt = now,
        expiresAt = now + 2.days,
    )

    private fun relayRow(transferId: TransferId) = FileRelayTransfer(
        transferId = transferId,
        sourceDevice = targetDevice,
        targetDevice = targetDevice,
        chunkSizeBytes = 32 * 1024,
        totalBytes = 10_000L,
        startIndex = 0,
        offerPayload = byteArrayOf(9),
        status = FileRelayStatus.WHOLE,
        reservedBytes = 100L,
        markerPacketId = null,
        createdAt = now,
        lastChunkAt = now,
        expiresAt = now + 2.days,
    )

    @Test
    fun pusher_ignoresForwardOutcome_butEndsOnPushChunkDecline() = runBlocking {
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(targetAccount to listOf(targetDevice)),
            torByPeer = mutableMapOf(targetDevice to TorEndpoint("peer.onion", 80)),
            clock = FakeClock(now),
        )
        val pushes = DefaultFilePushStore(openMemoryDatabase().database, Dispatchers.Unconfined)
        val outbox = RecordingFileOutbox()
        val events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
        val plaintext = FakeFilePlaintextSource()
        plaintext.describeImpl = { manifest() }
        val engine = FilePushEngine(
            ctx = stack.ctx,
            plaintext = plaintext,
            pushes = pushes,
            outbox = outbox,
            events = events,
            relaySelection = NoRelaySelection,
            relay = DefaultFileRelayStore(openMemoryDatabase().database, Dispatchers.Unconfined),
            incoming = testIncomingTransfers(stack.ctx.clock),
        )
        val seen = mutableListOf<FileEvent>()
        val collect = launch { events.collect { seen += it } }
        var engineJob: Job? = null
        try {
            val transferId = TransferId(Uuid.random())
            val marker = Uuid.random()
            pushes.insert(pushRow(transferId, marker))

            // A forward outcome for a live push row: ignored, nothing touched.
            engine.onOutboxOutcome(
                OutboxOutcome.Acked(
                    OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value),
                    targetDevice,
                    Uuid.random()
                )
            )
            assertEquals(
                pushRow(transferId, marker),
                pushes.find(transferId),
                "wrong-kind outcome leaves the push row alone"
            )
            assertTrue(
                outbox.enqueues.isEmpty() && outbox.deletes.isEmpty(),
                "wrong-kind outcome touches no outbox rows"
            )
            assertNull(withTimeoutOrNull(100.milliseconds) { events.first() }, "wrong-kind outcome emits nothing")

            // The matching kind still acts (on a running engine): a chunk DECLINED ends the
            // transfer quietly.
            engineJob = engine.runIn(this)
            val chunkPacket = Uuid.random()
            engine.onOutboxOutcome(
                OutboxOutcome.Nacked(
                    OutboxTag(OutboxTag.Kind.FILE_PUSH, transferId.value),
                    targetDevice,
                    chunkPacket,
                    PacketNackReason.DECLINED,
                )
            )
            withTimeout(5.seconds) {
                while (pushes.find(transferId)?.status?.isLive == true) delay(10.milliseconds)
            }
            assertEquals(FilePushStatus.ENDED, pushes.find(transferId)?.status)
            assertEquals(listOf(OutboxTag(OutboxTag.Kind.FILE_PUSH, transferId.value)), outbox.deletes)
            delay(100.milliseconds)
            assertTrue(
                seen.any { it is FileEvent.Failed && it.fileId == fileId && it.reason == FileFailureReason.ENDED },
                "a chunk DECLINED ends the transfer quietly, never as a refusal",
            )
        } finally {
            engineJob?.cancel()
            collect.cancel()
        }
    }

    @Test
    fun forwarder_ignoresPushOutcome_butDropsOnForwardDecline() = runBlocking {
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(targetAccount to listOf(targetDevice)),
            torByPeer = mutableMapOf(targetDevice to TorEndpoint("peer.onion", 80)),
            clock = FakeClock(now),
        )
        val relay = DefaultFileRelayStore(openMemoryDatabase().database, Dispatchers.Unconfined)
        val outbox = RecordingFileOutbox()
        val events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 64)
        val engine = FilePushEngine(
            ctx = stack.ctx,
            plaintext = FakeFilePlaintextSource(),
            pushes = DefaultFilePushStore(openMemoryDatabase().database, Dispatchers.Unconfined),
            outbox = outbox,
            events = events,
            relaySelection = NoRelaySelection,
            relay = relay,
            incoming = testIncomingTransfers(stack.ctx.clock),
        )

        val transferId = TransferId(Uuid.random())
        relay.insert(relayRow(transferId))

        // A push outcome for a live relay row: ignored, nothing touched.
        engine.onForwardOutcome(
            OutboxOutcome.Acked(
                OutboxTag(OutboxTag.Kind.FILE_PUSH, transferId.value),
                targetDevice,
                Uuid.random()
            )
        )
        assertEquals(relayRow(transferId), relay.find(transferId), "wrong-kind outcome leaves the relay row alone")
        assertTrue(outbox.enqueues.isEmpty() && outbox.deletes.isEmpty(), "wrong-kind outcome touches no outbox rows")

        // The matching kind still acts: a DECLINED forward is dropped, never re-offered.
        // The running engine's sweep starts the forward, which sends its marker.
        val engineJob = engine.runIn(this)
        try {
            withTimeout(5.seconds) { while (outbox.enqueues.isEmpty()) delay(10) }
            val markerPacket = outbox.enqueues.single().envelope.packetId
            engine.onForwardOutcome(
                OutboxOutcome.Nacked(
                    OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value),
                    targetDevice,
                    markerPacket,
                    PacketNackReason.DECLINED,
                )
            )
            withTimeout(5.seconds) { while (relay.find(transferId) != null) delay(10) }
            assertNull(relay.find(transferId), "a refused forward is dropped")
            assertEquals(listOf(OutboxTag(OutboxTag.Kind.FILE_FORWARD, transferId.value)), outbox.deletes)
        } finally {
            engineJob.cancel()
        }
    }
}
