package org.yapyap.persistence.crypto

import org.yapyap.crypto.e2ee.CryptoSessionRecord
import org.yapyap.crypto.e2ee.SessionRole
import org.yapyap.crypto.e2ee.SessionStatus
import org.yapyap.protocol.PeerId

interface CryptoSessionStore {
    /** Canonical [SessionStatus.ACTIVE] session for encrypt and session orchestration. */
    suspend fun loadActiveCanonical(peerDeviceId: PeerId, sessionEpoch: Int): CryptoSessionRecord?

    suspend fun loadSessions(peerDeviceId: PeerId, sessionEpoch: Int): List<CryptoSessionRecord>

    suspend fun save(record: CryptoSessionRecord)

    suspend fun setCanonical(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
        sessionGeneration: Int,
        canonical: Boolean,
    )

    /** Highest [SessionStatus.ACTIVE] epoch to use for new outbound encrypt. */
    suspend fun latestEncryptEpoch(peerDeviceId: PeerId): Int?

    suspend fun latestGeneration(peerDeviceId: PeerId, sessionEpoch: Int, role: SessionRole): Int?

    suspend fun listByPeer(peerDeviceId: PeerId): List<CryptoSessionRecord>

    suspend fun markSuperseded(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
        sessionGeneration: Int,
        updatedAtEpochSeconds: Long,
    )

    /** Marks every session row for the peer epoch superseded (e.g. epoch 1 after epoch 2 upgrade). */
    suspend fun markEpochSuperseded(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        updatedAtEpochSeconds: Long,
    )

    suspend fun deleteSession(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
        sessionGeneration: Int,
    )

    suspend fun listPeerDeviceIds(): List<PeerId>

    suspend fun clearOfferedOpkIds(opkIds: Collection<String>, updatedAtEpochSeconds: Long)
}

internal object CryptoSessionCanonicalInvariant {

    suspend fun ensure(peerDeviceId: PeerId, sessionEpoch: Int, store: CryptoSessionStore) {
        val sessions = store.loadSessions(peerDeviceId, sessionEpoch)
        val active = sessions.filter { it.meta.status == SessionStatus.ACTIVE }
        if (active.isEmpty()) {
            return
        }

        val canonicalActive = active.filter { it.canonical }
        when {
            canonicalActive.size == 1 -> return
            canonicalActive.size > 1 -> {
                val keepRole = preferredRole(canonicalActive)
                for (session in canonicalActive) {
                    if (session.meta.role != keepRole) {
                        store.setCanonical(
                            peerDeviceId,
                            sessionEpoch,
                            session.meta.role,
                            session.meta.sessionGeneration,
                            canonical = false,
                        )
                    }
                }
            }
            else -> {
                val promoteRole = preferredRole(active)
                val promoteSession = active.first { it.meta.role == promoteRole }
                store.setCanonical(
                    peerDeviceId,
                    sessionEpoch,
                    promoteRole,
                    promoteSession.meta.sessionGeneration,
                    canonical = true,
                )
            }
        }
    }

    private fun preferredRole(sessions: List<CryptoSessionRecord>): SessionRole {
        return sessions.firstOrNull { it.meta.role == SessionRole.INITIATOR }?.meta?.role
            ?: sessions.first().meta.role
    }
}