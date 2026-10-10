package org.yapyap.testfixtures

import org.yapyap.crypto.identity.*
import org.yapyap.crypto.signature.AuthorshipOutcome
import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.persistence.db.IdentityStatus
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.db.RoomMemberStatus
import org.yapyap.persistence.db.VerificationState
import org.yapyap.persistence.messaging.*
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomType
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.MessagePayload
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Shared pure-Kotlin in-memory repositories and fakes for commonTest.
 *
 * Extracted from [org.yapyap.orchestrator.dag.DefaultDagEngineTest] so the sync,
 * messaging and dag tests can reuse them instead of duplicating private copies.
 */

class FakeMessageRepository : MessageRepository {
    val byId = mutableMapOf<Uuid, MessageRow>()
    private val parentIds = mutableMapOf<Uuid, MutableList<Uuid>>()

    /** Wired by tests exercising the removal-boundary render queries. */
    var roomRepository: FakeRoomRepository? = null

    override suspend fun insert(
        payload: MessagePayload,
        isOrphaned: Boolean,
        ancestryComplete: Boolean,
        verificationState: VerificationState,
    ): Boolean {
        if (byId.containsKey(payload.messageId)) {
            // INSERT OR IGNORE semantics — duplicated key is a no-op.
            return true
        }
        byId[payload.messageId] = MessageRow(payload, isOrphaned, verificationState, ancestryComplete)
        return true
    }

    override suspend fun findById(messageId: Uuid): MessageRow? = byId[messageId]

    override suspend fun findRoomFrontier(roomId: RoomId): List<MessageRow> {
        // Mirror selectRoomFrontier: VERIFIED-chainable messages no VERIFIED-chainable
        // message references as a parent. PENDING tips are never advertised nor built on.
        val chainable = byId.values.filter {
            it.payload.roomId == roomId && it.ancestryComplete && it.verificationState == VerificationState.VERIFIED
        }
        val referenced = chainable
            .flatMap { child -> parentIds[child.payload.messageId].orEmpty() }
            .toSet()
        return chainable.filter { it.payload.messageId !in referenced }
    }

    override suspend fun findParents(messageId: Uuid): List<Uuid> =
        parentIds[messageId].orEmpty().toList()

    override suspend fun insertParent(messageId: Uuid, parentId: Uuid) {
        parentIds.getOrPut(messageId) { mutableListOf() }.add(parentId)
    }

    override suspend fun findChildrenInRoom(parentId: Uuid, roomId: RoomId): List<MessageRow> =
        byId.values.filter { row ->
            row.payload.roomId == roomId && parentId in parentIds[row.payload.messageId].orEmpty()
        }

    override suspend fun findMessagesInRoomPageDesc(
        roomId: RoomId,
        limit: Int,
        cursor: MessageCursor?
    ): List<MessageRow> {
        val all = byId.values
            .filter { it.payload.roomId == roomId && it.verificationState != VerificationState.REJECTED }
            .sortedWith(
                compareByDescending<MessageRow> { it.payload.createdAt }
                    .thenByDescending { it.payload.messageId }
            )
        val filtered = if (cursor == null) {
            all
        } else {
            all.filter { row ->
                val rowCreated = row.payload.createdAt
                val rowId = row.payload.messageId
                rowCreated < cursor.createdAt ||
                        (rowCreated == cursor.createdAt && rowId < cursor.messageId)
            }
        }
        return filtered.take(limit)
    }

    override suspend fun findAllInRoom(roomId: RoomId): List<MessageRow> =
        byId.values
            .filter { it.payload.roomId == roomId }
            .sortedWith(
                compareByDescending<MessageRow> { it.payload.createdAt }
                    .thenByDescending { it.payload.messageId }
            )

    override suspend fun renderableMessagesInRoom(
        roomId: RoomId,
        limit: Int,
        cursor: MessageCursor?,
    ): List<MessageRow> {
        val all = byId.values
            .filter { row ->
                row.payload.roomId == roomId &&
                        row.verificationState != VerificationState.REJECTED &&
                        isVisible(roomId, row.payload.senderAccountId, row.payload.messageId)
            }
            .sortedWith(
                compareByDescending<MessageRow> { it.payload.createdAt }
                    .thenByDescending { it.payload.messageId }
            )
        val filtered = if (cursor == null) {
            all
        } else {
            all.filter { row ->
                val rowCreated = row.payload.createdAt
                val rowId = row.payload.messageId
                rowCreated < cursor.createdAt ||
                        (rowCreated == cursor.createdAt && rowId < cursor.messageId)
            }
        }
        return filtered.take(limit)
    }

    override suspend fun isRenderable(roomId: RoomId, messageId: Uuid): Boolean {
        val row = byId[messageId] ?: return false
        if (row.payload.roomId != roomId) return false
        if (row.verificationState == VerificationState.REJECTED) return false
        return isVisible(roomId, row.payload.senderAccountId, messageId)
    }

    private suspend fun isVisible(roomId: RoomId, sender: AccountId, messageId: Uuid): Boolean {
        // Mirror the SQL visibility predicate: an ACTIVE row renders; a REMOVED
        // row renders only inside its removal node's ancestor closure (the member
        // era); no row never renders. The closure walks stored parent edges from
        // the removal node — orphans from removed authors are unreachable from a
        // chainable root, so no explicit orphan check is needed.
        val rooms = roomRepository
            ?: error("FakeMessageRepository.roomRepository must be wired for renderable queries")
        val member = rooms.memberRowOf(roomId, sender) ?: return false
        if (member.status == RoomMemberStatus.ACTIVE) return true
        val removalNode = member.removalNodeId ?: return false
        val seen = HashSet<Uuid>()
        val queue = ArrayDeque<Uuid>()
        seen.add(removalNode)
        queue.add(removalNode)
        while (queue.isNotEmpty()) {
            for (parent in parentIds[queue.removeFirst()].orEmpty()) {
                if (seen.add(parent)) queue.add(parent)
            }
        }
        return messageId in seen
    }

    override suspend fun hasMessages(roomId: RoomId): Boolean =
        byId.values.any { it.payload.roomId == roomId }

    override suspend fun findFoldableInRoom(roomId: RoomId): List<MessageRow> =
        // Mirror selectFoldableInRoom: VERIFIED ∧ stored flag, all payload types.
        byId.values.filter {
            it.payload.roomId == roomId &&
                    it.ancestryComplete &&
                    it.verificationState == VerificationState.VERIFIED
        }

    override suspend fun updateOrphanedFlag(messageId: Uuid, isOrphaned: Boolean) {
        val row = byId[messageId] ?: return
        byId[messageId] = row.copy(isOrphaned = isOrphaned)
    }

    override suspend fun updateAncestryComplete(messageId: Uuid, complete: Boolean) {
        val row = byId[messageId] ?: return
        byId[messageId] = row.copy(ancestryComplete = complete)
    }

    override suspend fun updateVerificationState(messageId: Uuid, state: VerificationState) {
        val row = byId[messageId] ?: return
        byId[messageId] = row.copy(verificationState = state)
    }

    override suspend fun findPendingByAuthor(deviceId: PeerId): List<MessageRow> =
        byId.values
            .filter { it.payload.authorDeviceId == deviceId && it.verificationState == VerificationState.PENDING }
            .toList()

    override suspend fun findAllPending(): List<MessageRow> =
        byId.values.filter { it.verificationState == VerificationState.PENDING }.toList()
}

/**
 * In-memory [RoomRepository]. Members are provided via the [members] map
 * (roomId -> accountIds, all ACTIVE); defaults to empty when not supplied.
 * Mirrors the SQL semantics: [membersOfRoom] is ACTIVE-only, [removeMember]
 * flips to REMOVED and retains the row, [addMember] is an upsert.
 */
class FakeRoomRepository(
    private val members: Map<RoomId, List<AccountId>> = emptyMap(),
    private val devicesByAccount: Map<AccountId, List<PeerId>> = emptyMap(),
) : RoomRepository {
    private data class Cell(
        var role: RoomMemberRole,
        var status: RoomMemberStatus,
        var removalNodeId: Uuid? = null,
    )

    private val cells: MutableMap<Pair<RoomId, AccountId>, Cell> =
        members.flatMap { (room, accounts) ->
            accounts.map { (room to it) to Cell(RoomMemberRole.MEMBER, RoomMemberStatus.ACTIVE) }
        }.toMap().toMutableMap()
    private val roomsFound = mutableSetOf<RoomId>()
    private val roomCells = mutableMapOf<RoomId, RoomRecord>()

    override suspend fun membersOfRoom(roomId: RoomId): List<AccountId> =
        cells.filter { (key, cell) -> key.first == roomId && cell.status == RoomMemberStatus.ACTIVE }
            .map { it.key.second }

    override suspend fun memberStatusesOfRoom(roomId: RoomId): List<RoomMemberRecord> =
        cells.filter { (key, _) -> key.first == roomId }
            .map { (key, cell) -> RoomMemberRecord(key.second, cell.role, cell.status, cell.removalNodeId) }

    override suspend fun memberRowOf(roomId: RoomId, accountId: AccountId): RoomMemberRecord? =
        cells[roomId to accountId]
            ?.let { RoomMemberRecord(accountId, it.role, it.status, it.removalNodeId) }

    override suspend fun roomsOfPeer(peerId: PeerId): List<RoomId> {
        // Mirror selectRoomsOfPeer: REMOVED rows never grant sync access.
        // (roomsFound rows have no membership row yet — pre-fold rooms seed the
        // rooms table via ensureRoomExists, so they stay visible here.)
        val activeRooms = cells
            .filter { (_, cell) -> cell.status == RoomMemberStatus.ACTIVE }
            .map { it.key.first }
            .toSet()
        val allRooms = activeRooms + roomsFound
        if (devicesByAccount.isEmpty()) return allRooms.toList()
        val account = devicesByAccount.entries.find { peerId in it.value }?.key ?: return emptyList()
        return allRooms.filter { room ->
            cells[room to account]?.status == RoomMemberStatus.ACTIVE
        }
    }

    override suspend fun ensureRoomExists(roomId: RoomId, type: RoomType, name: String) {
        roomsFound.add(roomId)
        roomCells.getOrPut(roomId) { RoomRecord(roomId, null, type, name) }
    }

    override suspend fun upsertMember(
        roomId: RoomId,
        accountId: AccountId,
        role: RoomMemberRole,
        status: RoomMemberStatus,
        removalNodeId: Uuid?,
    ) {
        // Mirror INSERT OR REPLACE (upsert, no duplicates).
        cells[roomId to accountId] = Cell(role, status, removalNodeId)
    }

    override suspend fun removeMember(roomId: RoomId, accountId: AccountId) {
        // Mirror the SQL UPDATE: flip to REMOVED, retain the row (removal-boundary source).
        cells[roomId to accountId]?.status = RoomMemberStatus.REMOVED
    }

    override suspend fun allChatRoomIds(): List<RoomId> =
        (cells.keys.map { it.first } + roomsFound).filter { it != RoomId.GLOBAL }.toSet().toList()

    override suspend fun roomOf(roomId: RoomId): RoomRecord? = roomCells[roomId]

    override suspend fun mergeRoomFromGenesis(roomId: RoomId, name: String, type: RoomType, spaceId: String?) {
        roomsFound.add(roomId)
        roomCells[roomId] = RoomRecord(roomId, spaceId, type, name)
    }

    override suspend fun removeRoomMembersNotIn(roomId: RoomId, keep: Collection<AccountId>) {
        cells.keys.removeAll { (r, a) -> r == roomId && a !in keep }
    }
}

class FakeCausalHoldRepository(
    private val messageRepo: FakeMessageRepository,
) : CausalHoldRepository {
    private val rows = mutableListOf<Gap>()

    override suspend fun insert(gapId: Uuid, missingPrevId: Uuid, orphanedMessageId: Uuid, detectedTimestamp: Instant) {
        rows.add(Gap(gapId, missingPrevId, orphanedMessageId, detectedTimestamp))
    }

    override suspend fun findByMissingPrevId(missingPrevId: Uuid): List<Gap> =
        rows.filter { it.missingPrevId == missingPrevId }

    override suspend fun findByRoom(roomId: RoomId): List<Gap> =
        // Mirror the SQL JOIN: a causal_hold row belongs to the room of its orphaned message.
        rows.filter { row ->
            val orphan = messageRepo.findById(row.orphanedMessageId)
            orphan?.payload?.roomId == roomId
        }

    override suspend fun findAll(): List<Gap> = rows.toList()

    override suspend fun countByOrphan(orphanedMessageId: Uuid): Long =
        rows.count { it.orphanedMessageId == orphanedMessageId }.toLong()

    override suspend fun deleteByMissingPrevId(missingPrevId: Uuid) {
        rows.removeAll { it.missingPrevId == missingPrevId }
    }

    override suspend fun deleteByOrphanedMessageId(orphanedMessageId: Uuid) {
        rows.removeAll { it.orphanedMessageId == orphanedMessageId }
    }
}

class FakeIdentityResolver(
    private val localAccountId: AccountId,
    private val localDeviceId: PeerId,
    private val accountByDevice: Map<PeerId, AccountId> = emptyMap(),
    private val accountStatuses: Map<AccountId, IdentityStatus> = emptyMap(),
    private val localOwner: Boolean = false,
    private val localAdmin: Boolean = false,
) : IdentityResolver {
    override suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = error("not used")
    override suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord = error("not used")
    override suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus = IdentityStatus.ACTIVE
    override suspend fun getAccountStatus(accountId: AccountId): IdentityStatus? =
        accountStatuses[accountId]
    override suspend fun isLocalAccountAdmin(): Boolean = localAdmin
    override suspend fun isLocalAccountOwner(): Boolean = localOwner
    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalDeviceId(): PeerId = localDeviceId
    override suspend fun getLocalAccountId(): AccountId = localAccountId
    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord = error("not used")
    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint = error("not used")
    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> = error("not used")
    override suspend fun getAllPeers(): List<PeerId> = error("not used")
    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? =
        accountByDevice[deviceId]
    override suspend fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) = error("not used")
    override suspend fun resolvePeerX3dhRemoteKeys(deviceId: PeerId, signedPreKeyId: String?) = error("not used")
    override suspend fun getCurrentLocalSignedPreKey(): SignedPreKeyRecord = error("not used")
    override suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): SignedPreKeyRecord = error("not used")
}

class FakeSignatureProvider : SignatureProvider {
    override suspend fun sign(message: ByteArray): ByteArray = byteArrayOf(0x01, 0x02, 0x03)

    override suspend fun verify(deviceId: PeerId, message: ByteArray, signature: ByteArray): Boolean = true

    override suspend fun verifyMessageAuthorship(
        accountId: AccountId,
        authorDeviceId: PeerId,
        signedBytes: ByteArray,
        signature: ByteArray,
    ): Boolean = true
}

class FakeRejectingSignatureProvider : SignatureProvider {
    override suspend fun sign(message: ByteArray): ByteArray = byteArrayOf(0x01, 0x02, 0x03)

    override suspend fun verify(deviceId: PeerId, message: ByteArray, signature: ByteArray): Boolean = false

    override suspend fun verifyMessageAuthorship(
        accountId: AccountId,
        authorDeviceId: PeerId,
        signedBytes: ByteArray,
        signature: ByteArray,
    ): Boolean = false
}

class FakeUnknownAuthorSignatureProvider : SignatureProvider {
    override suspend fun sign(message: ByteArray): ByteArray = byteArrayOf(0x01, 0x02, 0x03)

    override suspend fun verify(deviceId: PeerId, message: ByteArray, signature: ByteArray): Boolean = false

    override suspend fun verifyMessageAuthorship(
        accountId: AccountId,
        authorDeviceId: PeerId,
        signedBytes: ByteArray,
        signature: ByteArray,
    ): Boolean = false

    override suspend fun classifyMessageAuthorship(
        accountId: AccountId,
        authorDeviceId: PeerId,
        signedBytes: ByteArray,
        signature: ByteArray?,
    ): AuthorshipOutcome = AuthorshipOutcome.UNKNOWN_AUTHOR
}
