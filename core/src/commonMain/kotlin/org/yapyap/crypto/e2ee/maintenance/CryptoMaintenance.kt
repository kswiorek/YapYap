package org.yapyap.crypto.e2ee.maintenance

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.e2ee.CryptoSessionConfig
import org.yapyap.crypto.e2ee.session.SessionStatus
import org.yapyap.persistence.crypto.CryptoSessionStore
import org.yapyap.persistence.key.OpkRepository
import org.yapyap.protocol.PeerId
import kotlin.time.Clock
import kotlin.time.Instant

class CryptoMaintenance(
    private val sessionStore: CryptoSessionStore,
    private val opkRepository: OpkRepository,
    private val sessionConfig: StateFlow<CryptoSessionConfig>,
    private val clock: Clock = Clock.System,
) {

    suspend fun run() {
        val now = clock.now()
        val prunedOpkIds = opkRepository.pruneExpiredOffers(
            cutoff = now - sessionConfig.value.offeredOpkRetention,
        )
        if (prunedOpkIds.isNotEmpty()) {
            sessionStore.clearOfferedOpkIds(prunedOpkIds, updatedAt = now)
        }
        for (peerDeviceId in sessionStore.listPeerDeviceIds()) {
            maintainPeerSessions(
                sessionStore = sessionStore,
                sessionConfig = sessionConfig.value,
                peerDeviceId = peerDeviceId,
                now = now,
            )
        }
    }
}

internal suspend fun maintainPeerSessions(
    sessionStore: CryptoSessionStore,
    sessionConfig: CryptoSessionConfig,
    peerDeviceId: PeerId,
    now: Instant,
) {
    val sessions = sessionStore.listByPeer(peerDeviceId)
    val idleCutoff = now - sessionConfig.canonicalIdleSupersede
    for (record in sessions) {
        if (record.canonical &&
            record.meta.status == SessionStatus.ACTIVE &&
            record.meta.updatedAt < idleCutoff
        ) {
            sessionStore.markSuperseded(
                peerDeviceId,
                record.sessionEpoch,
                record.meta.role,
                record.meta.sessionGeneration,
                now,
            )
        }
    }

    val pendingEpoch2Cutoff = now - sessionConfig.pendingEpoch2Retention
    val supersededPruneCutoff = now - sessionConfig.supersededRetention
    for (record in sessions) {
        if (record.sessionEpoch == 2 &&
            record.meta.status == SessionStatus.PENDING &&
            record.meta.updatedAt < pendingEpoch2Cutoff
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
            record.meta.updatedAt < supersededPruneCutoff
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
