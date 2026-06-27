package org.yapyap.backend.crypto.e2ee

import org.yapyap.backend.db.CryptoSessionStore
import org.yapyap.backend.db.SessionStatus
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.time.EpochSecondsProvider
import org.yapyap.backend.time.SystemEpochSecondsProvider

interface CryptoHousekeeping {
    suspend fun run(nowEpochSeconds: Long? = null)
}

class DefaultCryptoHousekeeping(
    private val sessionStore: CryptoSessionStore,
    private val oneTimePreKeyStore: OneTimePreKeyStore,
    private val sessionConfig: CryptoSessionConfig = CryptoSessionConfig(),
    private val timeProvider: EpochSecondsProvider = SystemEpochSecondsProvider,
    private val logger: AppLogger = NoopAppLogger,
) : CryptoHousekeeping {

    override suspend fun run(nowEpochSeconds: Long?) {
        val now = nowEpochSeconds ?: timeProvider.nowEpochSeconds()
        try {
            val prunedOpkIds = oneTimePreKeyStore.pruneExpiredOffers(
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

    val pruneCutoff = nowEpochSeconds - sessionConfig.supersededRetentionSeconds
    for (record in sessions) {
        if (record.meta.status == SessionStatus.SUPERSEDED &&
            record.meta.updatedAtEpochSeconds < pruneCutoff
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
