package org.yapyap.orchestrator.file

import org.yapyap.crypto.identity.LocalIdentity
import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.orchestrator.store.RoomRepository
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.RoomMemberStatus
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.file.port.FileCoverage
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.file.port.FileServeGate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * What routing asks the orchestrator about a file it requests or serves (design §9): held
 * coverage (from the receive engine), candidate accounts, total bytes, and the serve gate. Reads
 * only, so it is built before the router. The router never addresses a device through these
 * ports: candidates are accounts, and serving is gated per account, like sync.
 */
internal class DefaultFileRequestSource(
    private val messages: MessageRepository,
    private val rooms: RoomRepository,
    private val receives: FileReceiveReader,
    private val descriptors: FileDescriptorReader,
    private val localIdentity: LocalIdentity,
    private val coverage: FileCoverageReader,
) : FileRequestSource, FileServeGate {
    override suspend fun heldCoverage(fileId: FileId): FileCoverage? = coverage.heldCoverage(fileId)

    override suspend fun requestCandidates(fileId: FileId): List<AccountId> {
        val messageIds = try {
            messages.findMessageIdsByFileId(fileId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return emptyList()
        }
        val senders = LinkedHashSet<AccountId>()
        for (messageId in messageIds) {
            val stored = try {
                messages.findById(messageId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            } ?: continue
            val text = stored.payload as? MessagePayload.Text ?: continue
            senders += text.senderAccountId
        }
        val own = try {
            localIdentity.getLocalAccountId()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        // Senders' devices first, then own-account siblings (room members later).
        val ordered = senders.toMutableList()
        if (own != null) {
            ordered.remove(own)
            ordered.add(own)
        }
        return ordered
    }

    override suspend fun canServe(fileId: FileId, requester: AccountId): Boolean {
        val descriptor = try {
            descriptors.findById(fileId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return false
        if (descriptor.status != FileDescriptorStatus.HELD) return false
        // No size gate: small REQUESTs for large files are DECLINED downstream by
        // the pusher (never served by transfer), while fetch serves any size here.
        val messageIds = try {
            messages.findMessageIdsByFileId(fileId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return false
        }
        for (messageId in messageIds) {
            if (isVisibleTo(messageId, requester)) return true
        }
        return false
    }

    override suspend fun requestTotalBytes(fileId: FileId): Long? {
        // Attachments first (works before any receive row exists), then the row.
        messages.findFileAttachment(fileId)?.let { return it.totalBytes }
        return try {
            receives.find(fileId)?.totalBytes
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    /** Serve gate, same rule as sync's membership gate: ACTIVE sees everything in the room. */
    private suspend fun isVisibleTo(messageId: Uuid, requester: AccountId): Boolean {
        val stored = try {
            messages.findById(messageId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return false
        val text = stored.payload as? MessagePayload.Text ?: return false
        val member = try {
            rooms.memberRowOf(text.roomId, requester)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return false
        if (member.status == RoomMemberStatus.ACTIVE) return true
        val removal = member.removalNodeId ?: return false
        if (member.status != RoomMemberStatus.REMOVED) return false
        // Member-era history only: the message must be the removal node or one of its ancestors.
        if (messageId == removal) return true
        val visited = HashSet<Uuid>()
        val queue = ArrayDeque<Uuid>()
        queue.add(removal)
        var steps = 0
        while (queue.isNotEmpty() && steps++ < 512) {
            val id = queue.removeFirst()
            if (!visited.add(id)) continue
            if (id == messageId) return true
            val parents = try {
                messages.findParents(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return false
            }
            queue.addAll(parents)
        }
        return false
    }
}

/** First attachment referencing [fileId] across its messages, or null. */
internal suspend fun MessageRepository.findFileAttachment(fileId: FileId): MessagePayload.Attachment? {
    val messageIds = try {
        findMessageIdsByFileId(fileId)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        return null
    }
    for (messageId in messageIds) {
        val stored = try {
            findById(messageId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: continue
        val text = stored.payload as? MessagePayload.Text ?: continue
        text.attachments.firstOrNull { it.fileId == fileId }?.let { return it }
    }
    return null
}
