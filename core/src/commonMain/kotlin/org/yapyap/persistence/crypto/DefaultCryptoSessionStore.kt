package org.yapyap.persistence.crypto

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.crypto.e2ee.session.*
import org.yapyap.persistence.SelectCryptoSessionByPeerAndEpochWithKeys
import org.yapyap.persistence.SelectCryptoSessionsByPeerWithKeys
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.PeerId
import kotlin.time.Instant

class DefaultCryptoSessionStore(
    private val database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : CryptoSessionStore {

    private val queries get() = database.cryptoQueries

    override suspend fun loadActiveCanonical(peerDeviceId: PeerId, sessionEpoch: Int): CryptoSessionRecord? =
        loadSessions(peerDeviceId, sessionEpoch)
            .firstOrNull { it.canonical && it.meta.status == SessionStatus.ACTIVE }

    override suspend fun loadSessions(peerDeviceId: PeerId, sessionEpoch: Int): List<CryptoSessionRecord> =
        withContext(dbDispatcher) {
            queries.selectCryptoSessionByPeerAndEpochWithKeys(
                peer_device_id = peerDeviceId,
                session_epoch = sessionEpoch.toLong(),
            ).executeAsList().map { it.toSessionJoin() }.toRecords(peerDeviceId)
        }

    override suspend fun save(record: CryptoSessionRecord) {
        withContext(dbDispatcher) {
            if (record.canonical &&
                (record.meta.status == SessionStatus.ACTIVE || record.meta.status == SessionStatus.PENDING)
            ) {
                demoteOtherCanonicalSessions(
                    record.peerDeviceId,
                    record.sessionEpoch,
                    exceptRole = record.meta.role,
                    exceptGeneration = record.meta.sessionGeneration,
                )
            }
            val ratchet = record.ratchetState
            val meta = record.meta
            val sid = sessionId(record.peerDeviceId, record.sessionEpoch, record.meta.role, meta.sessionGeneration)
            require(ratchet.skippedMessageKeys.size <= CryptoWireLimits.MAX_SKIPPED_KEYS_COUNT) {
                "skipped message keys ${ratchet.skippedMessageKeys.size} exceeds max ${CryptoWireLimits.MAX_SKIPPED_KEYS_COUNT}"
            }
            database.transaction {
                queries.insertOrReplaceCryptoSession(
                    session_id = sid,
                    peer_device_id = record.peerDeviceId,
                    canonical = record.canonical,
                    session_epoch = record.sessionEpoch.toLong(),
                    session_generation = meta.sessionGeneration.toLong(),
                    root_key = ratchet.rootKey,
                    send_chain_key = ratchet.sendChainKey,
                    recv_chain_key = ratchet.recvChainKey,
                    send_message_number = ratchet.sendMessageNumber.toLong(),
                    recv_message_number = ratchet.recvMessageNumber.toLong(),
                    previous_send_chain_length = ratchet.previousSendChainLength.toLong(),
                    local_dh_private_key = ratchet.localDhPrivateKey,
                    local_dh_public_key = ratchet.localDhPublicKey,
                    remote_dh_pub_key = ratchet.remoteDhPublicKey,
                    role = meta.role,
                    x3dh_mode = meta.x3dhMode,
                    handshake_spk_id = meta.handshakeSpkId,
                    handshake_opk_id = meta.handshakeOpkId,
                    initiator_ephemeral_private_key = meta.initiatorEphemeralPrivateKey,
                    initiator_ephemeral_public_key = meta.initiatorEphemeralPublicKey,
                    offered_opk_id = meta.offeredOpkId,
                    status = meta.status,
                    created_at_epoch_seconds = meta.createdAt,
                    updated_at_epoch_seconds = meta.updatedAt,
                )
                queries.deleteCryptoSessionSkippedKeysBySession(session_id = sid)
                for ((keyId, messageKey) in ratchet.skippedMessageKeys) {
                    queries.insertCryptoSessionSkippedKey(
                        session_id = sid,
                        dh_public_key = keyId.dhPublicKey,
                        message_number = keyId.messageNumber.toLong(),
                        message_key = messageKey,
                    )
                }
            }
            CryptoSessionCanonicalInvariant.ensure(record.peerDeviceId, record.sessionEpoch, this@DefaultCryptoSessionStore)
        }
    }

    override suspend fun setCanonical(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
        sessionGeneration: Int,
        canonical: Boolean,
    ) {
        withContext(dbDispatcher) {
            if (canonical) {
                demoteOtherCanonicalSessions(
                    peerDeviceId,
                    sessionEpoch,
                    exceptRole = role,
                    exceptGeneration = sessionGeneration,
                )
            }
            queries.setCanonicalByPeerEpochRoleAndGeneration(
                canonical = canonical,
                peer_device_id = peerDeviceId,
                session_epoch = sessionEpoch.toLong(),
                role = role,
                session_generation = sessionGeneration.toLong(),
            )
        }
    }

    private suspend fun demoteOtherCanonicalSessions(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        exceptRole: SessionRole,
        exceptGeneration: Int,
    ) {
        withContext(dbDispatcher) {
            for (session in loadSessions(peerDeviceId, sessionEpoch)) {
                if ((session.meta.role != exceptRole || session.meta.sessionGeneration != exceptGeneration) &&
                    session.canonical
                ) {
                    queries.setCanonicalByPeerEpochRoleAndGeneration(
                        canonical = false,
                        peer_device_id = peerDeviceId,
                        session_epoch = sessionEpoch.toLong(),
                        role = session.meta.role,
                        session_generation = session.meta.sessionGeneration.toLong(),
                    )
                }
            }
        }
    }

    override suspend fun latestEncryptEpoch(peerDeviceId: PeerId): Int? =
        withContext(dbDispatcher) {
            queries
                .selectMaxSessionEpochByPeer(peer_device_id = peerDeviceId)
                .executeAsOneOrNull()
                ?.max_epoch
                ?.toInt()
        }

    override suspend fun latestGeneration(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
    ): Int? =
        loadSessions(peerDeviceId, sessionEpoch)
            .filter { it.meta.role == role }
            .maxOfOrNull { it.meta.sessionGeneration }

    override suspend fun listByPeer(peerDeviceId: PeerId): List<CryptoSessionRecord> =
        withContext(dbDispatcher) {
            queries.selectCryptoSessionsByPeerWithKeys(peer_device_id = peerDeviceId)
                .executeAsList()
                .map { it.toSessionJoin() }
                .toRecords(peerDeviceId)
        }

    override suspend fun markSuperseded(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
        sessionGeneration: Int,
        updatedAt: Instant,
    ) {
        withContext(dbDispatcher) {
            queries.markCryptoSessionSupersededByRoleAndGeneration(
                status = SessionStatus.SUPERSEDED,
                updated_at_epoch_seconds = updatedAt,
                peer_device_id = peerDeviceId,
                session_epoch = sessionEpoch.toLong(),
                role = role,
                session_generation = sessionGeneration.toLong(),
            )
        }
    }

    override suspend fun markEpochSuperseded(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        updatedAt: Instant,
    ) {
        withContext(dbDispatcher) {
            queries.markCryptoSessionSuperseded(
                status = SessionStatus.SUPERSEDED,
                updated_at_epoch_seconds = updatedAt,
                peer_device_id = peerDeviceId,
                session_epoch = sessionEpoch.toLong(),
            )
        }
    }

    override suspend fun deleteSession(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
        sessionGeneration: Int,
    ) {
        withContext(dbDispatcher) {
            queries.deleteCryptoSessionByPeerEpochRoleAndGeneration(
                peer_device_id = peerDeviceId,
                session_epoch = sessionEpoch.toLong(),
                role = role,
                session_generation = sessionGeneration.toLong(),
            )
        }
    }

    override suspend fun listPeerDeviceIds(): List<PeerId> =
        withContext(dbDispatcher) {
            queries.selectDistinctPeerDeviceIds()
                .executeAsList()
        }

    override suspend fun clearOfferedOpkIds(opkIds: Collection<String>, updatedAt: Instant) {
        withContext(dbDispatcher) {
            for (opkId in opkIds) {
                queries.clearOfferedOpkId(
                    updated_at_epoch_seconds = updatedAt,
                    offered_opk_id = opkId,
                )
            }
        }
    }

    private data class SessionJoin(
        val sessionId: String,
        val canonical: Boolean,
        val sessionEpoch: Long,
        val sessionGeneration: Long,
        val rootKey: ByteArray,
        val sendChainKey: ByteArray?,
        val recvChainKey: ByteArray?,
        val sendMessageNumber: Long,
        val recvMessageNumber: Long,
        val previousSendChainLength: Long,
        val localDhPrivateKey: ByteArray,
        val localDhPublicKey: ByteArray,
        val remoteDhPublicKey: ByteArray?,
        val role: SessionRole,
        val x3dhMode: X3dhMode,
        val handshakeSpkId: String,
        val handshakeOpkId: String?,
        val initiatorEphemeralPrivateKey: ByteArray?,
        val initiatorEphemeralPublicKey: ByteArray?,
        val offeredOpkId: String?,
        val status: SessionStatus,
        val createdAt: Instant,
        val updatedAt: Instant,
        val skippedDhPublicKey: ByteArray?,
        val skippedMessageNumber: Long?,
        val skippedMessageKey: ByteArray?,
    )

    private fun SelectCryptoSessionByPeerAndEpochWithKeys.toSessionJoin(): SessionJoin = SessionJoin(
        sessionId = session_id,
        canonical = canonical,
        sessionEpoch = session_epoch,
        sessionGeneration = session_generation,
        rootKey = root_key,
        sendChainKey = send_chain_key,
        recvChainKey = recv_chain_key,
        sendMessageNumber = send_message_number,
        recvMessageNumber = recv_message_number,
        previousSendChainLength = previous_send_chain_length,
        localDhPrivateKey = local_dh_private_key,
        localDhPublicKey = local_dh_public_key,
        remoteDhPublicKey = remote_dh_pub_key,
        role = role,
        x3dhMode = x3dh_mode,
        handshakeSpkId = handshake_spk_id,
        handshakeOpkId = handshake_opk_id,
        initiatorEphemeralPrivateKey = initiator_ephemeral_private_key,
        initiatorEphemeralPublicKey = initiator_ephemeral_public_key,
        offeredOpkId = offered_opk_id,
        status = status,
        createdAt = created_at_epoch_seconds,
        updatedAt = updated_at_epoch_seconds,
        skippedDhPublicKey = skipped_dh_public_key,
        skippedMessageNumber = skipped_message_number,
        skippedMessageKey = skipped_message_key,
    )

    private fun SelectCryptoSessionsByPeerWithKeys.toSessionJoin(): SessionJoin = SessionJoin(
        sessionId = session_id,
        canonical = canonical,
        sessionEpoch = session_epoch,
        sessionGeneration = session_generation,
        rootKey = root_key,
        sendChainKey = send_chain_key,
        recvChainKey = recv_chain_key,
        sendMessageNumber = send_message_number,
        recvMessageNumber = recv_message_number,
        previousSendChainLength = previous_send_chain_length,
        localDhPrivateKey = local_dh_private_key,
        localDhPublicKey = local_dh_public_key,
        remoteDhPublicKey = remote_dh_pub_key,
        role = role,
        x3dhMode = x3dh_mode,
        handshakeSpkId = handshake_spk_id,
        handshakeOpkId = handshake_opk_id,
        initiatorEphemeralPrivateKey = initiator_ephemeral_private_key,
        initiatorEphemeralPublicKey = initiator_ephemeral_public_key,
        offeredOpkId = offered_opk_id,
        status = status,
        createdAt = created_at_epoch_seconds,
        updatedAt = updated_at_epoch_seconds,
        skippedDhPublicKey = skipped_dh_public_key,
        skippedMessageNumber = skipped_message_number,
        skippedMessageKey = skipped_message_key,
    )

    private fun List<SessionJoin>.toRecords(peerDeviceId: PeerId): List<CryptoSessionRecord> {
        val grouped = LinkedHashMap<String, MutableList<SessionJoin>>()
        for (row in this) {
            grouped.getOrPut(row.sessionId) { mutableListOf() }.add(row)
        }
        return grouped.values.map { it.toRecord(peerDeviceId) }
    }

    private fun List<SessionJoin>.toRecord(peerDeviceId: PeerId): CryptoSessionRecord {
        val first = this[0]
        val skippedMessageKeys = LinkedHashMap<RatchetSkippedKeyId, ByteArray>()
        for (row in this) {
            val messageNumber = row.skippedMessageNumber ?: continue
            skippedMessageKeys[RatchetSkippedKeyId(row.skippedDhPublicKey!!, messageNumber.toInt())] =
                row.skippedMessageKey!!
        }
        return CryptoSessionRecord(
            peerDeviceId = peerDeviceId,
            sessionEpoch = first.sessionEpoch.toInt(),
            canonical = first.canonical,
            ratchetState = RatchetSessionState(
                rootKey = first.rootKey.copyOf(),
                sendChainKey = first.sendChainKey?.copyOf(),
                recvChainKey = first.recvChainKey?.copyOf(),
                sendMessageNumber = first.sendMessageNumber.toInt(),
                recvMessageNumber = first.recvMessageNumber.toInt(),
                previousSendChainLength = first.previousSendChainLength.toInt(),
                localDhPrivateKey = first.localDhPrivateKey.copyOf(),
                localDhPublicKey = first.localDhPublicKey.copyOf(),
                remoteDhPublicKey = first.remoteDhPublicKey?.copyOf(),
                skippedMessageKeys = skippedMessageKeys.mapValues { (_, value) -> value.copyOf() },
            ),
            meta = CryptoSessionMeta(
                role = first.role,
                x3dhMode = first.x3dhMode,
                handshakeSpkId = first.handshakeSpkId,
                handshakeOpkId = first.handshakeOpkId,
                initiatorEphemeralPrivateKey = first.initiatorEphemeralPrivateKey?.copyOf(),
                initiatorEphemeralPublicKey = first.initiatorEphemeralPublicKey?.copyOf(),
                offeredOpkId = first.offeredOpkId,
                status = first.status,
                sessionGeneration = first.sessionGeneration.toInt(),
                createdAt = first.createdAt,
                updatedAt = first.updatedAt,
            ),
        )
    }

    private fun sessionId(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        sessionRole: SessionRole,
        sessionGeneration: Int,
    ): String = "${peerDeviceId.id}#$sessionEpoch#${sessionRole.name}#g$sessionGeneration"
}
