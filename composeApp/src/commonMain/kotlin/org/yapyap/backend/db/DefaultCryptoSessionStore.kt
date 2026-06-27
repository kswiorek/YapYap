package org.yapyap.backend.db

import org.yapyap.backend.crypto.e2ee.RatchetSessionState
import org.yapyap.backend.protocol.PeerId

class DefaultCryptoSessionStore(
    private val database: YapYapDatabase,
) : CryptoSessionStore {

    private val queries get() = database.cryptoQueries

    override suspend fun loadActiveCanonical(peerDeviceId: PeerId, sessionEpoch: Int): CryptoSessionRecord? =
        queries
            .selectActiveCanonicalCryptoSessionByPeerAndEpoch(
                peer_device_id = peerDeviceId.id,
                session_epoch = sessionEpoch.toLong(),
            )
            .executeAsOneOrNull()?.toRecord(peerDeviceId)

    override suspend fun loadSessions(peerDeviceId: PeerId, sessionEpoch: Int): List<CryptoSessionRecord> =
        queries.selectCryptoSessionByPeerAndEpoch(
            peer_device_id = peerDeviceId.id,
            session_epoch = sessionEpoch.toLong(),
        ).executeAsList().map { it.toRecord(peerDeviceId) }

    override suspend fun save(record: CryptoSessionRecord) {
        if (record.canonical && record.meta.status == SessionStatus.ACTIVE) {
            demoteOtherCanonicalSessions(
                record.peerDeviceId,
                record.sessionEpoch,
                exceptRole = record.meta.role,
                exceptGeneration = record.meta.sessionGeneration,
            )
        }
        val ratchet = record.ratchetState
        val meta = record.meta
        queries.insertOrReplaceCryptoSession(
            session_id = sessionId(record.peerDeviceId, record.sessionEpoch, record.meta.role, meta.sessionGeneration),
            peer_device_id = record.peerDeviceId.id,
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
            skipped_message_keys = RatchetSkippedKeysCodec.encode(ratchet.skippedMessageKeys),
            role = meta.role,
            x3dh_mode = meta.x3dhMode,
            handshake_spk_id = meta.handshakeSpkId,
            handshake_opk_id = meta.handshakeOpkId,
            initiator_ephemeral_private_key = meta.initiatorEphemeralPrivateKey,
            initiator_ephemeral_public_key = meta.initiatorEphemeralPublicKey,
            offered_opk_id = meta.offeredOpkId,
            status = meta.status,
            created_at_epoch_seconds = meta.createdAtEpochSeconds,
            updated_at_epoch_seconds = meta.updatedAtEpochSeconds,
        )
        CryptoSessionCanonicalInvariant.ensure(record.peerDeviceId, record.sessionEpoch, this)
    }

    override suspend fun setCanonical(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
        sessionGeneration: Int,
        canonical: Boolean,
    ) {
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
            peer_device_id = peerDeviceId.id,
            session_epoch = sessionEpoch.toLong(),
            role = role,
            session_generation = sessionGeneration.toLong(),
        )
    }

    private suspend fun demoteOtherCanonicalSessions(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        exceptRole: SessionRole,
        exceptGeneration: Int,
    ) {
        for (session in loadSessions(peerDeviceId, sessionEpoch)) {
            if ((session.meta.role != exceptRole || session.meta.sessionGeneration != exceptGeneration) &&
                session.canonical
            ) {
                queries.setCanonicalByPeerEpochRoleAndGeneration(
                    canonical = false,
                    peer_device_id = peerDeviceId.id,
                    session_epoch = sessionEpoch.toLong(),
                    role = session.meta.role,
                    session_generation = session.meta.sessionGeneration.toLong(),
                )
            }
        }
    }

    override suspend fun latestEncryptEpoch(peerDeviceId: PeerId): Int? =
        queries
            .selectMaxSessionEpochByPeer(peer_device_id = peerDeviceId.id)
            .executeAsOneOrNull()
            ?.max_epoch
            ?.toInt()

    override suspend fun latestGeneration(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
    ): Int? =
        loadSessions(peerDeviceId, sessionEpoch)
            .filter { it.meta.role == role }
            .maxOfOrNull { it.meta.sessionGeneration }

    override suspend fun listByPeer(peerDeviceId: PeerId): List<CryptoSessionRecord> =
        queries
            .selectCryptoSessionsByPeer(peer_device_id = peerDeviceId.id)
            .executeAsList()
            .map { it.toRecord(peerDeviceId) }

    override suspend fun markSuperseded(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
        sessionGeneration: Int,
        updatedAtEpochSeconds: Long,
    ) {
        queries.markCryptoSessionSupersededByRoleAndGeneration(
            status = SessionStatus.SUPERSEDED,
            updated_at_epoch_seconds = updatedAtEpochSeconds,
            peer_device_id = peerDeviceId.id,
            session_epoch = sessionEpoch.toLong(),
            role = role,
            session_generation = sessionGeneration.toLong(),
        )
    }

    override suspend fun markEpochSuperseded(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        updatedAtEpochSeconds: Long,
    ) {
        queries.markCryptoSessionSuperseded(
            status = SessionStatus.SUPERSEDED,
            updated_at_epoch_seconds = updatedAtEpochSeconds,
            peer_device_id = peerDeviceId.id,
            session_epoch = sessionEpoch.toLong(),
        )
    }

    override suspend fun deleteSession(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
        sessionGeneration: Int,
    ) {
        queries.deleteCryptoSessionByPeerEpochRoleAndGeneration(
            peer_device_id = peerDeviceId.id,
            session_epoch = sessionEpoch.toLong(),
            role = role,
            session_generation = sessionGeneration.toLong(),
        )
    }

    override suspend fun listPeerDeviceIds(): List<PeerId> =
        queries.selectDistinctPeerDeviceIds()
            .executeAsList()
            .map(::PeerId)

    override suspend fun clearOfferedOpkIds(opkIds: Collection<String>, updatedAtEpochSeconds: Long) {
        for (opkId in opkIds) {
            queries.clearOfferedOpkId(
                updated_at_epoch_seconds = updatedAtEpochSeconds,
                offered_opk_id = opkId,
            )
        }
    }

    private fun Crypto_sessions.toRecord(peerDeviceId: PeerId): CryptoSessionRecord {
        val skipped = RatchetSkippedKeysCodec.decode(skipped_message_keys)
        return CryptoSessionRecord(
            peerDeviceId = peerDeviceId,
            sessionEpoch = session_epoch.toInt(),
            ratchetState = RatchetSessionState(
                rootKey = root_key.copyOf(),
                sendChainKey = send_chain_key?.copyOf(),
                recvChainKey = recv_chain_key?.copyOf(),
                sendMessageNumber = send_message_number.toInt(),
                recvMessageNumber = recv_message_number.toInt(),
                previousSendChainLength = previous_send_chain_length.toInt(),
                localDhPrivateKey = local_dh_private_key.copyOf(),
                localDhPublicKey = local_dh_public_key.copyOf(),
                remoteDhPublicKey = remote_dh_pub_key?.copyOf(),
                skippedMessageKeys = skipped.mapValues { (_, value) -> value.copyOf() },
            ),
            meta = CryptoSessionMeta(
                role = role,
                x3dhMode = x3dh_mode,
                handshakeSpkId = handshake_spk_id,
                handshakeOpkId = handshake_opk_id,
                initiatorEphemeralPrivateKey = initiator_ephemeral_private_key?.copyOf(),
                initiatorEphemeralPublicKey = initiator_ephemeral_public_key?.copyOf(),
                offeredOpkId = offered_opk_id,
                status = status,
                sessionGeneration = session_generation.toInt(),
                createdAtEpochSeconds = created_at_epoch_seconds,
                updatedAtEpochSeconds = updated_at_epoch_seconds,
            ),
            canonical = canonical,
        )
    }

    private fun sessionId(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        sessionRole: SessionRole,
        sessionGeneration: Int,
    ): String = "${peerDeviceId.id}#$sessionEpoch#${sessionRole.name}#g$sessionGeneration"
}
