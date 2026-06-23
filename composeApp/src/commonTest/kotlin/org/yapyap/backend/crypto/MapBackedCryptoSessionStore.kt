package org.yapyap.backend.crypto

import org.yapyap.backend.db.CryptoSessionRecord
import org.yapyap.backend.db.CryptoSessionStore
import org.yapyap.backend.db.SessionStatus
import org.yapyap.backend.protocol.PeerId

/** In-memory [CryptoSessionStore] for unit tests. */
internal class MapBackedCryptoSessionStore : CryptoSessionStore {
    private val records = mutableMapOf<PeerEpoch, CryptoSessionRecord>()

    override suspend fun load(peerDeviceId: PeerId, sessionEpoch: Int): CryptoSessionRecord? =
        records[PeerEpoch(peerDeviceId.id, sessionEpoch)]?.let { copyRecord(it) }

    override suspend fun save(record: CryptoSessionRecord) {
        records[PeerEpoch(record.peerDeviceId.id, record.sessionEpoch)] = copyRecord(record)
    }

    override suspend fun latestEncryptEpoch(peerDeviceId: PeerId): Int? =
        records.keys
            .filter { it.peerId == peerDeviceId.id }
            .maxOfOrNull { it.epoch }

    override suspend fun listByPeer(peerDeviceId: PeerId): List<CryptoSessionRecord> =
        records.values
            .filter { it.peerDeviceId == peerDeviceId }
            .map { copyRecord(it) }
            .sortedBy { it.sessionEpoch }

    override suspend fun markSuperseded(peerDeviceId: PeerId, sessionEpoch: Int) {
        val key = PeerEpoch(peerDeviceId.id, sessionEpoch)
        val existing = records[key] ?: return
        records[key] = existing.copy(meta = existing.meta.copy(status = SessionStatus.SUPERSEDED))
    }

    private fun copyRecord(record: CryptoSessionRecord): CryptoSessionRecord =
        record.copy(
            ratchetState = record.ratchetState.copy(
                rootKey = record.ratchetState.rootKey.copyOf(),
                sendChainKey = record.ratchetState.sendChainKey?.copyOf(),
                recvChainKey = record.ratchetState.recvChainKey?.copyOf(),
                localDhPrivateKey = record.ratchetState.localDhPrivateKey.copyOf(),
                localDhPublicKey = record.ratchetState.localDhPublicKey.copyOf(),
                remoteDhPublicKey = record.ratchetState.remoteDhPublicKey?.copyOf(),
                skippedMessageKeys = record.ratchetState.skippedMessageKeys
                    .mapValues { (_, value) -> value.copyOf() },
            ),
            meta = record.meta.copy(
                initiatorEphemeralPrivateKey = record.meta.initiatorEphemeralPrivateKey?.copyOf(),
                initiatorEphemeralPublicKey = record.meta.initiatorEphemeralPublicKey?.copyOf(),
            ),
        )

    private data class PeerEpoch(val peerId: String, val epoch: Int)
}
