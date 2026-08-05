package org.yapyap.testfixtures

import org.yapyap.crypto.identity.*
import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.persistence.messaging.*
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.time.EpochSecondsProvider
import kotlin.uuid.Uuid

/**
 * Shared pure-Kotlin in-memory repositories and fakes for commonTest.
 *
 * Extracted from [org.yapyap.orchestrator.dag.DefaultDagEngineTest] so the sync,
 * messaging and dag tests can reuse them instead of duplicating private copies.
 */

class MutableEpochSecondsProvider(var t: Long) : EpochSecondsProvider {
    override fun nowEpochSeconds(): Long = t
}

class FakeMessageRepository : MessageRepository {
    val byId = mutableMapOf<Uuid, MessageRow>()

    override suspend fun insert(
        payload: MessagePayload,
        isOrphaned: Boolean,
    ): Boolean {
        if (byId.containsKey(payload.messageId)) {
            // INSERT OR IGNORE semantics — duplicated key is a no-op.
            return true
        }
        byId[payload.messageId] = MessageRow(payload, isOrphaned)
        return true
    }

    override suspend fun findById(messageId: Uuid): MessageRow? = byId[messageId]

    override suspend fun findRoomTail(roomId: String): MessageRow? =
        byId.values
            .filter { it.payload.roomId == roomId }
            .maxWithOrNull(
                compareBy<MessageRow> { it.payload.lamportClock }
                    .thenBy { it.payload.createdAtEpochSeconds }
                    .thenBy { it.payload.messageId }
            )

    override suspend fun findMessagesInRoomPageDesc(
        roomId: String,
        limit: Int,
        cursor: MessageCursor?
    ): List<MessageRow> {
        val all = byId.values
            .filter { it.payload.roomId == roomId }
            .sortedWith(
                compareByDescending<MessageRow> { it.payload.createdAtEpochSeconds }
                    .thenByDescending { it.payload.lamportClock }
                    .thenByDescending { it.payload.messageId }
            )
        val filtered = if (cursor == null) {
            all
        } else {
            all.filter { row ->
                val rowCreated = row.payload.createdAtEpochSeconds
                val rowLamport = row.payload.lamportClock
                val rowId = row.payload.messageId
                rowCreated < cursor.createdAtEpochSeconds ||
                        (rowCreated == cursor.createdAtEpochSeconds && rowLamport < cursor.lamportClock) ||
                        (rowCreated == cursor.createdAtEpochSeconds && rowLamport == cursor.lamportClock && cursor.messageId.let { rowId < it })
            }
        }
        return filtered.take(limit)
    }

    override suspend fun findAllInRoom(roomId: String): List<MessageRow> =
        byId.values
            .filter { it.payload.roomId == roomId }
            .sortedWith(
                compareByDescending<MessageRow> { it.payload.createdAtEpochSeconds }
                    .thenByDescending { it.payload.lamportClock }
                    .thenByDescending { it.payload.messageId }
            )

    override suspend fun maxLamportInRoom(roomId: String): Long? =
        byId.values
            .filter { it.payload.roomId == roomId }
            .maxOfOrNull { it.payload.lamportClock }

    override suspend fun updateOrphanedFlag(messageId: Uuid, isOrphaned: Boolean) {
        val row = byId[messageId] ?: return
        byId[messageId] = row.copy(isOrphaned = isOrphaned)
    }

    override suspend fun isOrphanAtLamport(roomId: String, lamport: Long): Boolean =
        byId.values.any { it.payload.roomId == roomId && it.payload.lamportClock == lamport && it.isOrphaned }

    override suspend fun maxLamportBelow(roomId: String, lamport: Long): Long? =
        byId.values
            .filter { it.payload.roomId == roomId && it.payload.lamportClock < lamport }
            .maxOfOrNull { it.payload.lamportClock }

    override suspend fun findMessagesInLamportRange(
        roomId: String,
        lowerInclusive: Long,
        upperInclusive: Long,
        limit: Int,
    ): List<MessageRow> =
        byId.values
            .filter { it.payload.roomId == roomId }
            .filter { it.payload.lamportClock in lowerInclusive..upperInclusive }
            .sortedWith(
                compareBy<MessageRow> { it.payload.lamportClock }
                    .thenBy { it.payload.createdAtEpochSeconds }
                    .thenBy { it.payload.messageId }
            )
            .take(limit)

    override suspend fun countAtLamport(roomId: String, lamport: Long): Long =
        byId.values.count { it.payload.roomId == roomId && it.payload.lamportClock == lamport }.toLong()
}

/**
 * In-memory [RoomRepository]. Members are provided via the [members] map
 * (roomId -> accountIds); defaults to empty when not supplied.
 */
class FakeRoomRepository(
    private val members: Map<String, List<AccountId>> = emptyMap(),
) : RoomRepository {
    private val seqs = mutableMapOf<String, Long>()

    override suspend fun membersOfRoom(roomId: String): List<AccountId> =
        members[roomId].orEmpty()

    override suspend fun updateLocalSeq(roomId: String, seqN: Long) {
        seqs[roomId] = seqN
    }

    override suspend fun getLocalSeq(roomId: String): Long? = seqs[roomId]
}

class FakeCausalHoldRepository(
    private val messageRepo: FakeMessageRepository,
) : CausalHoldRepository {
    private val rows = mutableListOf<CausalHoldRow>()

    override suspend fun insert(gapId: Uuid, missingPrevId: Uuid, orphanedMessageId: Uuid, detectedTimestamp: Long) {
        rows.add(CausalHoldRow(gapId, missingPrevId, orphanedMessageId, detectedTimestamp))
    }

    override suspend fun findByMissingPrevId(missingPrevId: Uuid): List<CausalHoldRow> =
        rows.filter { it.missingPrevId == missingPrevId }

    override suspend fun findByRoom(roomId: String): List<CausalHoldRow> =
        // Mirror the SQL JOIN: a causal_hold row belongs to the room of its orphaned message.
        rows.filter { row ->
            val orphan = messageRepo.findById(row.orphanedMessageId)
            orphan?.payload?.roomId == roomId
        }

    override suspend fun findAll(): List<CausalHoldRow> = rows.toList()

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
    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalDeviceId(): PeerId = localDeviceId
    override suspend fun getLocalAccountId(): AccountId = localAccountId
    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord = error("not used")
    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint = error("not used")
    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> = error("not used")
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
