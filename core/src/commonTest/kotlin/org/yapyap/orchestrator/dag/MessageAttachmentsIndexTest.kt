package org.yapyap.orchestrator.dag

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.MessageId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.testfixtures.FakeCausalHoldRepository
import org.yapyap.testfixtures.FakeIdentityResolver
import org.yapyap.testfixtures.FakeMessageRepository
import org.yapyap.testfixtures.FakeRoomRepository
import org.yapyap.testfixtures.FakeSignatureProvider
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MessageAttachmentsIndexTest {
    private val testAccount = AccountId("test-account")
    private val testDeviceId = PeerId("test-devicetest-devicetest-devicetest-devicetest-device00")
    private val roomId = RoomId(Uuid.random())

    private fun engine(
        messageRepo: FakeMessageRepository,
    ): DefaultDagEngine {
        val roomRepo = FakeRoomRepository()
        // Wire renderable-query visibility (FakeMessageRepository delegates to room repo).
        messageRepo.roomRepository = roomRepo
        return DefaultDagEngine(
            messageRepository = messageRepo,
            causalHoldRepository = FakeCausalHoldRepository(messageRepo),
            roomRepository = roomRepo,
            localIdentity = FakeIdentityResolver(testAccount, testDeviceId),
            signatureProvider = FakeSignatureProvider(),
            cryptoProvider = DefaultCryptoProvider(),
            clock = object : kotlin.time.Clock {
                override fun now(): Instant = epochSeconds(1_000_000L)
            },
        )
    }

    private fun attachment(i: Int) = DagPayload.Attachment(
        fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b8" + "0123456789abcdef"[i % 16] + "0123456789abcdef"[(i / 16) % 16]),
        fileName = "file-$i.bin",
        mimeType = null,
        totalBytes = 10L + i,
    )

    @Test
    fun append_writesOneIndexRowPerAttachment() = runTest {
        val repo = FakeMessageRepository()
        val dag = engine(repo)
        val payload = dag.append(roomId, MessageDraft.Text("hi", listOf(attachment(0), attachment(1))))
        val text = payload as DagPayload.Text
        assertEquals(2, text.attachments.size)
        for (attachment in text.attachments) {
            assertEquals(listOf(payload.messageId), repo.findMessageIdsByFileId(attachment.fileId))
        }
    }

    @Test
    fun orphanArrival_writesIndexRows() = runTest {
        val repo = FakeMessageRepository()
        val dag = engine(repo)
        val orphan = DagPayload.Text(
            messageId = MessageId(Uuid.random()),
            roomId = roomId,
            senderAccountId = testAccount,
            authorDeviceId = testDeviceId,
            prevIds = listOf(MessageId(Uuid.random())),
            createdAt = epochSeconds(1_000_000L),
            text = "orphan",
            attachments = listOf(attachment(0)),
            authorSignature = byteArrayOf(0x01),
        )
        val result = dag.ingest(orphan)
        assertTrue(result is IngestResult.BecameOrphan)
        assertEquals(listOf(orphan.messageId), repo.findMessageIdsByFileId(orphan.attachments[0].fileId))
    }

    @Test
    fun duplicateIngest_keepsOneRowPerPair() = runTest {
        val repo = FakeMessageRepository()
        val dag = engine(repo)
        val payload = dag.append(roomId, MessageDraft.Text("hi", listOf(attachment(0))))
        val text = payload as DagPayload.Text
        // Duplicate ingest of the same message: early dedup, no second index row.
        dag.ingest(text)
        dag.ingest(text)
        val fileId = text.attachments[0].fileId
        assertEquals(listOf(payload.messageId), repo.findMessageIdsByFileId(fileId))
    }
}
