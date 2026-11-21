package org.yapyap.orchestrator.file

import org.yapyap.crypto.identity.LocalIdentity
import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.orchestrator.sync.RoomVisibility
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.file.port.FileCoverage
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.file.port.FileServeGate

/**
 * What routing asks the orchestrator about a file it requests or serves (design §9): held
 * coverage (from the receive engine), candidate accounts, total bytes, and the serve gate. Reads
 * only, so it is built before the router. Errors propagate: routing's callers already handle
 * them (the candidate core logs and backs off; the fetch maps unexpected failures to
 * unanswered). No catch-and-default here.
 */
internal class DefaultFileRequestSource(
    private val messages: MessageRepository,
    private val visibility: RoomVisibility,
    private val receives: FileReceiveReader,
    private val descriptors: FileDescriptorReader,
    private val localIdentity: LocalIdentity,
    private val coverage: FileCoverageReader,
) : FileRequestSource, FileServeGate {
    override suspend fun heldCoverage(fileId: FileId): FileCoverage? = coverage.heldCoverage(fileId)

    override suspend fun requestCandidates(fileId: FileId): List<AccountId> {
        val messageIds = messages.findMessageIdsByFileId(fileId)
        val senders = LinkedHashSet<AccountId>()
        for (messageId in messageIds) {
            val stored = messages.findById(messageId) ?: continue
            val text = stored.payload as? MessagePayload.Text ?: continue
            senders += text.senderAccountId
        }
        val own = localIdentity.getLocalAccountId()
        val ordered = senders.toMutableList()
        ordered.remove(own)
        ordered.add(own)
        return ordered
    }

    override suspend fun canServe(fileId: FileId, requester: AccountId): Boolean {
        val descriptor = descriptors.findById(fileId) ?: return false
        if (descriptor.status != FileDescriptorStatus.HELD) return false
        val messageIds = messages.findMessageIdsByFileId(fileId)
        for (messageId in messageIds) {
            if (visibility.isVisible(messageId, requester)) return true
        }
        return false
    }

    override suspend fun requestTotalBytes(fileId: FileId): Long? {
        messages.findFileAttachment(fileId)?.let { return it.totalBytes }
        return receives.find(fileId)?.totalBytes
    }
}

/** First attachment referencing [fileId] across its messages, or null. */
internal suspend fun MessageRepository.findFileAttachment(fileId: FileId): MessagePayload.Attachment? {
    val messageIds = findMessageIdsByFileId(fileId)
    for (messageId in messageIds) {
        val stored = findById(messageId) ?: continue
        val text = stored.payload as? MessagePayload.Text ?: continue
        text.attachments.firstOrNull { it.fileId == fileId }?.let { return it }
    }
    return null
}
