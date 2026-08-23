package org.yapyap.crypto.e2ee.maintenance

import org.yapyap.crypto.e2ee.CryptoSessionConfig
import org.yapyap.crypto.e2ee.session.SessionStatus
import org.yapyap.persistence.crypto.CryptoSessionStore
import org.yapyap.persistence.key.OpkRepository
import org.yapyap.protocol.PeerId
import org.yapyap.time.EpochProvider
import org.yapyap.time.SystemEpochProvider

class CryptoMaintenance(
    private val sessionStore: CryptoSessionStore,
    private val opkRepository: OpkRepository,
    private val sessionConfig: CryptoSessionConfig,
    private val timeProvider: EpochProvider = SystemEpochProvider,
) {

    suspend fun run() {
        val now = timeProvider.nowEpochSeconds()
        val prunedOpkIds = opkRepository.pruneExpiredOffers(
            cutoffEpochSeconds = now - sessionConfig.offeredOpkRetentionSeconds,
        )
        if (prunedOpkIds.isNotEmpty()) {
            sessionStore.clearOfferedOpkIds(prunedOpkIds, updatedAtEpochSeconds = now)
        }
        for (peerDeviceId in sessionStore.listPeerDeviceIds()) {
            maintainPeerSessions(
                sessionStore = sessionStore,
                sessionConfig = sessionConfig,
                peerDeviceId = peerDeviceId,
                nowEpochSeconds = now,
            )
        }
    }
}

internal suspend fun maintainPeerSessions(
    sessionStore: CryptoSessionStore,
    sessionConfig: CryptoSessionConfig,
    peerDeviceId: PeerId,
    nowEpochSeconds: Long,
) {
    val sessions = sessionStore.listByPeer(peerDeviceId)
    val idleCutoff = nowEpochSeconds - sessionConfig.canonicalIdleSupersedeSeconds
    for (record in sessions) {
        if (record.canonical &&
            record.meta.status == SessionStatus.ACTIVE &&
            record.meta.updatedAtEpochSeconds < idleCutoff
        ) {
            sessionStore.markSuperseded(
                peerDeviceId,
                record.sessionEpoch,
                record.meta.role,
                record.meta.sessionGeneration,
                nowEpochSeconds,
            )
        }
    }

    val pendingEpoch2Cutoff = nowEpochSeconds - sessionConfig.pendingEpoch2RetentionSeconds
    val supersededPruneCutoff = nowEpochSeconds - sessionConfig.supersededRetentionSeconds
    for (record in sessions) {
        if (record.sessionEpoch == 2 &&
            record.meta.status == SessionStatus.PENDING &&
            record.meta.updatedAtEpochSeconds < pendingEpoch2Cutoff
        ) {
            sessionStore.deleteSession(
                peerDeviceId,
                record.sessionEpoch,
                record.meta.role,
                record.meta.sessionGeneration,
            )
            continue
        }
        if (record.meta.status == SessionStatus.SUPERSEDED &&
            record.meta.updatedAtEpochSeconds < supersededPruneCutoff
        ) {
            sessionStore.deleteSession(
                peerDeviceId,
                record.sessionEpoch,
                record.meta.role,
                record.meta.sessionGeneration,
            )
        }
    }
}
