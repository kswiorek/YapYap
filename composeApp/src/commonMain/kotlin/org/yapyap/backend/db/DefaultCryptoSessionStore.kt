package org.yapyap.backend.db

import org.yapyap.backend.crypto.e2ee.RatchetSessionState
import org.yapyap.backend.protocol.PeerId

class DefaultCryptoSessionStore(
    private val database: YapYapDatabase,
) : CryptoSessionStore {

    private val queries get() = database.cryptoQueries

    override suspend fun load(peerDeviceId: PeerId, sessionEpoch: Int): CryptoSessionRecord? =
        queries
            .selectCryptoSessionByPeerAndEpoch(
                peer_device_id = peerDeviceId.id,
                session_epoch = sessionEpoch.toLong(),
            )
            .executeAsOneOrNull()
            ?.toRecord(peerDeviceId)

    override suspend fun save(record: CryptoSessionRecord) {
        val ratchet = record.ratchetState
        val meta = record.meta
        queries.insertOrReplaceCryptoSession(
            session_id = sessionId(record.peerDeviceId, record.sessionEpoch),
            peer_device_id = record.peerDeviceId.id,
            session_epoch = record.sessionEpoch.toLong(),
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
    }

    override suspend fun latestEncryptEpoch(peerDeviceId: PeerId): Int? =
        queries
            .selectMaxSessionEpochByPeer(peer_device_id = peerDeviceId.id)
            .executeAsOneOrNull()
            ?.max_epoch
            ?.toInt()

    override suspend fun listByPeer(peerDeviceId: PeerId): List<CryptoSessionRecord> =
        queries
            .selectCryptoSessionsByPeer(peer_device_id = peerDeviceId.id)
            .executeAsList()
            .map { it.toRecord(peerDeviceId) }

    override suspend fun markSuperseded(peerDeviceId: PeerId, sessionEpoch: Int) {
        queries.markCryptoSessionSuperseded(
            status = SessionStatus.SUPERSEDED,
            peer_device_id = peerDeviceId.id,
            session_epoch = sessionEpoch.toLong(),
        )
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
                createdAtEpochSeconds = created_at_epoch_seconds,
                updatedAtEpochSeconds = updated_at_epoch_seconds,
            ),
        )
    }

    private fun sessionId(peerDeviceId: PeerId, sessionEpoch: Int): String =
        "${peerDeviceId.id}#$sessionEpoch"
}
