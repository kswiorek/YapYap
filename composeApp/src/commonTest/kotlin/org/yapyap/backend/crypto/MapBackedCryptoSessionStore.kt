package org.yapyap.backend.crypto

import org.yapyap.backend.db.CryptoSessionCanonicalInvariant
import org.yapyap.backend.db.CryptoSessionRecord
import org.yapyap.backend.db.CryptoSessionStore
import org.yapyap.backend.db.SessionRole
import org.yapyap.backend.db.SessionStatus
import org.yapyap.backend.protocol.PeerId

/** In-memory [CryptoSessionStore] for unit tests. */
internal class MapBackedCryptoSessionStore : CryptoSessionStore {
    private val records = mutableMapOf<SessionKey, CryptoSessionRecord>()

    override suspend fun loadActiveCanonical(peerDeviceId: PeerId, sessionEpoch: Int): CryptoSessionRecord? =
        records.values
            .firstOrNull {
                it.peerDeviceId == peerDeviceId &&
                    it.sessionEpoch == sessionEpoch &&
                    it.canonical &&
                    it.meta.status == SessionStatus.ACTIVE
            }
            ?.let { copyRecord(it) }

    override suspend fun loadSessions(peerDeviceId: PeerId, sessionEpoch: Int): List<CryptoSessionRecord> =
        records.values
            .filter { it.peerDeviceId == peerDeviceId && it.sessionEpoch == sessionEpoch }
            .sortedWith(compareBy({ it.meta.sessionGeneration }, { it.meta.role.name }))
            .map { copyRecord(it) }

    override suspend fun save(record: CryptoSessionRecord) {
        if (record.canonical && record.meta.status == SessionStatus.ACTIVE) {
            demoteOtherCanonicalSessions(
                record.peerDeviceId,
                record.sessionEpoch,
                exceptRole = record.meta.role,
                exceptGeneration = record.meta.sessionGeneration,
            )
        }
        val key = sessionKey(record)
        records[key] = copyRecord(record)
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
        val key = SessionKey(peerDeviceId.id, sessionEpoch, role, sessionGeneration)
        val existing = records[key] ?: return
        records[key] = existing.copy(canonical = canonical)
    }

    private fun demoteOtherCanonicalSessions(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        exceptRole: SessionRole,
        exceptGeneration: Int,
    ) {
        for ((key, session) in records) {
            if (session.peerDeviceId == peerDeviceId &&
                session.sessionEpoch == sessionEpoch &&
                (session.meta.role != exceptRole || session.meta.sessionGeneration != exceptGeneration) &&
                session.canonical
            ) {
                records[key] = session.copy(canonical = false)
            }
        }
    }

    override suspend fun latestEncryptEpoch(peerDeviceId: PeerId): Int? =
        records.values
            .filter { it.peerDeviceId == peerDeviceId }
            .maxOfOrNull { it.sessionEpoch }

    override suspend fun latestGeneration(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
    ): Int? =
        records.values
            .filter {
                it.peerDeviceId == peerDeviceId &&
                    it.sessionEpoch == sessionEpoch &&
                    it.meta.role == role
            }
            .maxOfOrNull { it.meta.sessionGeneration }

    override suspend fun listByPeer(peerDeviceId: PeerId): List<CryptoSessionRecord> =
        records.values
            .filter { it.peerDeviceId == peerDeviceId }
            .map { copyRecord(it) }
            .sortedWith(compareBy({ it.sessionEpoch }, { it.meta.sessionGeneration }, { it.meta.role.name }))

    override suspend fun markSuperseded(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
        sessionGeneration: Int,
        updatedAtEpochSeconds: Long,
    ) {
        val key = SessionKey(peerDeviceId.id, sessionEpoch, role, sessionGeneration)
        val record = records[key] ?: return
        records[key] = record.copy(
            meta = record.meta.copy(
                status = SessionStatus.SUPERSEDED,
                updatedAtEpochSeconds = updatedAtEpochSeconds,
            ),
        )
    }

    override suspend fun markEpochSuperseded(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        updatedAtEpochSeconds: Long,
    ) {
        for ((key, record) in records) {
            if (record.peerDeviceId == peerDeviceId && record.sessionEpoch == sessionEpoch) {
                records[key] = record.copy(
                    meta = record.meta.copy(
                        status = SessionStatus.SUPERSEDED,
                        updatedAtEpochSeconds = updatedAtEpochSeconds,
                    ),
                )
            }
        }
    }

    override suspend fun deleteSession(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
        sessionGeneration: Int,
    ) {
        records.remove(SessionKey(peerDeviceId.id, sessionEpoch, role, sessionGeneration))
    }

    private fun sessionKey(record: CryptoSessionRecord): SessionKey =
        SessionKey(
            peerId = record.peerDeviceId.id,
            epoch = record.sessionEpoch,
            role = record.meta.role,
            generation = record.meta.sessionGeneration,
        )

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

    private data class SessionKey(
        val peerId: String,
        val epoch: Int,
        val role: SessionRole,
        val generation: Int,
    )
}
