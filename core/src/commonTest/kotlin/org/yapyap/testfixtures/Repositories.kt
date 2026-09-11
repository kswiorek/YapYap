package org.yapyap.testfixtures

import org.yapyap.crypto.identity.*
import org.yapyap.crypto.signature.AuthorshipOutcome
import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.db.RoomType
import org.yapyap.persistence.db.VerificationState
import org.yapyap.persistence.messaging.*
import org.yapyap.protocol.PeerId
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
        // Mirror selectRoomFrontier: chainable messages no chainable message references as a parent.
        val chainable = byId.values.filter {
            it.payload.roomId == roomId && it.ancestryComplete && it.verificationState != VerificationState.REJECTED
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

    override suspend fun hasMessages(roomId: RoomId): Boolean =
        byId.values.any { it.payload.roomId == roomId }

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
 * (roomId -> accountIds); defaults to empty when not supplied.
 */
class FakeRoomRepository(
    private val members: Map<RoomId, List<AccountId>> = emptyMap(),
) : RoomRepository {
    private val memberLists: MutableMap<RoomId, MutableList<AccountId>> =
        members.mapValues { it.value.toMutableList() }.toMutableMap()
    private val roomsFound = mutableSetOf<RoomId>()

    override suspend fun membersOfRoom(roomId: RoomId): List<AccountId> =
        memberLists[roomId].orEmpty()

    override suspend fun roomsOfPeer(peerId: PeerId): List<RoomId> =
        (memberLists.keys + roomsFound).toList()

    override suspend fun ensureRoomExists(roomId: RoomId, type: RoomType, name: String) {
        roomsFound.add(roomId)
    }

    override suspend fun addMember(roomId: RoomId, accountId: AccountId, role: RoomMemberRole) {
        memberLists.getOrPut(roomId) { mutableListOf() }.add(accountId)
    }

    override suspend fun removeMember(roomId: RoomId, accountId: AccountId) {
        memberLists[roomId]?.remove(accountId)
    }
}

class FakeCausalHoldRepository(
    private val messageRepo: FakeMessageRepository,
) : CausalHoldRepository {
    private val rows = mutableListOf<CausalHoldRow>()

    override suspend fun insert(gapId: Uuid, missingPrevId: Uuid, orphanedMessageId: Uuid, detectedTimestamp: Instant) {
        rows.add(CausalHoldRow(gapId, missingPrevId, orphanedMessageId, detectedTimestamp))
    }

    override suspend fun findByMissingPrevId(missingPrevId: Uuid): List<CausalHoldRow> =
        rows.filter { it.missingPrevId == missingPrevId }

    override suspend fun findByRoom(roomId: RoomId): List<CausalHoldRow> =
        // Mirror the SQL JOIN: a causal_hold row belongs to the room of its orphaned message.
        rows.filter { row ->
            val orphan = messageRepo.findById(row.orphanedMessageId)
            orphan?.payload?.roomId == roomId
        }

    override suspend fun findAll(): List<CausalHoldRow> = rows.toList()

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
) : IdentityResolver {
    override suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = error("not used")
    override suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord = error("not used")
    override suspend fun isLocalAccountAdmin(): Boolean = error("not used")
    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalDeviceId(): PeerId = localDeviceId
    override suspend fun getLocalAccountId(): AccountId = localAccountId
    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord = error("not used")
    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint = error("not used")
    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> = error("not used")
    override suspend fun getAllPeers(): List<PeerId> = error("not used")
    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? = error("not used")
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
