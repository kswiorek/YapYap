package org.yapyap.protocol.envelopes

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

class MessageAttachmentsTest {
    private val authorDeviceId = PeerId("author-device")
    private val authorAccountId = AccountId("author-account")

    private fun text(
        attachments: List<DagPayload.Attachment> = emptyList(),
        text: String = "hello",
    ) = DagPayload.Text(
        messageId = Uuid.random(),
        roomId = RoomId(Uuid.random()),
        senderAccountId = authorAccountId,
        authorDeviceId = authorDeviceId,
        prevIds = emptyList(),
        createdAt = epochSeconds(1_700_000_042L),
        text = text,
        attachments = attachments,
        authorSignature = byteArrayOf(0x01, 0x02, 0x03),
    )

    private fun attachment(i: Int) = DagPayload.Attachment(
        fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
        fileName = "file-$i.bin",
        mimeType = "application/octet-stream",
        totalBytes = 100L + i,
    )

    @Test
    fun roundTrip_zeroAttachments() {
        val original = text(emptyList())
        assertEquals(original, DagPayload.Text.decode(original.encode()))
    }

    @Test
    fun roundTrip_oneAttachment() {
        val original = text(listOf(attachment(0)))
        assertEquals(original, DagPayload.Text.decode(original.encode()))
    }

    @Test
    fun roundTrip_maxAttachments() {
        val list = (0 until DagPayload.Attachment.MAX_ATTACHMENTS).map { attachment(it) }
        val original = text(list)
        assertEquals(original, DagPayload.Text.decode(original.encode()))
    }

    @Test
    fun rejects_tooManyAttachments() {
        val list = (0..DagPayload.Attachment.MAX_ATTACHMENTS).map { attachment(it % 16) }
        assertFailsWith<IllegalArgumentException> { text(list) }
    }

    @Test
    fun signature_coversAttachments() = runTest {
        val crypto = DefaultCryptoProvider()
        val keys = crypto.generateSigningKeyPair()
        val base = text(emptyList())
        val withAttachment = text(listOf(attachment(0)))
        // Same header/text, different attachments -> different signing bytes.
        assertFalse(base.encodeForAuthorSigning().contentEquals(withAttachment.encodeForAuthorSigning()))
    }

    @Test
    fun rejects_version2Payload() {
        val original = text(emptyList())
        val bytes = original.encode().copyOf()
        // Header version is the first byte.
        bytes[0] = 2
        assertFailsWith<IllegalArgumentException> {
            MessagePayload.decode(bytes)
        }
    }

    @Test
    fun attachment_rejectsBlankFileName() {
        assertFailsWith<IllegalArgumentException> {
            DagPayload.Attachment(
                fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                fileName = "",
                mimeType = null,
                totalBytes = 1,
            )
        }
    }
}
