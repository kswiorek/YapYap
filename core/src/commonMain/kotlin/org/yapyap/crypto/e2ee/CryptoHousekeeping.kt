package org.yapyap.crypto.e2ee

import org.yapyap.persistence.crypto.CryptoSessionStore
import org.yapyap.persistence.crypto.SessionStatus
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.persistence.key.OpkRepository
import org.yapyap.protocol.PeerId
import org.yapyap.time.EpochSecondsProvider
import org.yapyap.time.SystemEpochSecondsProvider

interface CryptoHousekeeping {
    suspend fun run(nowEpochSeconds: Long? = null)
}

class DefaultCryptoHousekeeping(
    private val sessionStore: CryptoSessionStore,
    private val opkRepository: OpkRepository,
    private val sessionConfig: CryptoSessionConfig = CryptoSessionConfig(),
    private val timeProvider: EpochSecondsProvider = SystemEpochSecondsProvider,
    private val logger: AppLogger = NoopAppLogger,
) : CryptoHousekeeping {

    override suspend fun run(nowEpochSeconds: Long?) {
        val now = nowEpochSeconds ?: timeProvider.nowEpochSeconds()
        try {
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
        } catch (error: Exception) {
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.CRYPTO_MAINTENANCE_FAILED,
                message = "Crypto housekeeping failed",
                throwable = error,
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
