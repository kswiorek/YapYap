package org.yapyap.backend.crypto

import org.yapyap.backend.db.CryptoSessionRecord
import org.yapyap.backend.db.CryptoSessionStore
import org.yapyap.backend.db.SessionRole
import org.yapyap.backend.db.SessionStatus
import org.yapyap.backend.protocol.PeerId

/** In-memory [CryptoSessionStore] for unit tests. */
internal class MapBackedCryptoSessionStore : CryptoSessionStore {
    private val records = mutableMapOf<SessionKey, CryptoSessionRecord>()

    override suspend fun loadCanonical(peerDeviceId: PeerId, sessionEpoch: Int): CryptoSessionRecord? =
        records.values
            .firstOrNull {
                it.peerDeviceId == peerDeviceId &&
                    it.sessionEpoch == sessionEpoch &&
                    it.canonical
            }
            ?.let { copyRecord(it) }

    override suspend fun loadSessions(peerDeviceId: PeerId, sessionEpoch: Int): List<CryptoSessionRecord> =
        records.values
            .filter { it.peerDeviceId == peerDeviceId && it.sessionEpoch == sessionEpoch }
            .map { copyRecord(it) }

    override suspend fun save(record: CryptoSessionRecord) {
        val key = SessionKey(record.peerDeviceId.id, record.sessionEpoch, record.meta.role)
        records[key] = copyRecord(record)
    }

    override suspend fun setCanonical(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
        canonical: Boolean,
    ) {
        val key = SessionKey(peerDeviceId.id, sessionEpoch, role)
        val existing = records[key] ?: return
        records[key] = existing.copy(canonical = canonical)
    }

    override suspend fun latestEncryptEpoch(peerDeviceId: PeerId): Int? =
        records.values
            .filter { it.peerDeviceId == peerDeviceId }
            .maxOfOrNull { it.sessionEpoch }

    override suspend fun listByPeer(peerDeviceId: PeerId): List<CryptoSessionRecord> =
        records.values
            .filter { it.peerDeviceId == peerDeviceId }
            .map { copyRecord(it) }
            .sortedWith(compareBy({ it.sessionEpoch }, { it.meta.role.name }))

    override suspend fun markSuperseded(peerDeviceId: PeerId, sessionEpoch: Int) {
        for ((key, record) in records) {
            if (record.peerDeviceId == peerDeviceId && record.sessionEpoch == sessionEpoch) {
                records[key] = record.copy(meta = record.meta.copy(status = SessionStatus.SUPERSEDED))
            }
        }
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

    private data class SessionKey(val peerId: String, val epoch: Int, val role: SessionRole)
}
