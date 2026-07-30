package org.yapyap.crypto.e2ee

import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.identity.LocalOneTimePreKey
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.crypto.CryptoSessionStore
import org.yapyap.persistence.key.OpkRepository
import org.yapyap.protocol.PeerId
import org.yapyap.time.EpochSecondsProvider

internal class Epoch2Upgrade(
    private val crypto: CryptoProvider,
    private val sessionStore: CryptoSessionStore,
    private val identityResolver: IdentityResolver,
    private val opkRepository: OpkRepository,
    private val sessionBootstrap: SessionBootstrap,
    private val timeProvider: EpochSecondsProvider,
    private val upgradePolicy: SessionUpgradePolicy,
) {

    fun isEpoch2OpkBootstrapFailure(frame: SessionWireFrame, error: Exception): Boolean {
        if (frame.sessionEpoch != 2) {
            return false
        }
        return when (error) {
            is CryptoSessionException.HandshakeRequired,
            is CryptoSessionException.HandshakeMismatch,
            is CryptoSessionException.MissingOfferedOpk,
            is CryptoSessionException.OpkConsumeFailed,
            -> true
            else -> false
        }
    }

    suspend fun maybeAttachOpkOffer(
        peerDeviceId: PeerId,
        epoch: Int,
        loaded: LoadedSession,
        inner: ByteArray,
    ): RatchetInnerPlaintext {
        if (upgradePolicy != SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY) {
            return RatchetInnerPlaintext.Payload(inner)
        }
        if (epoch != 1 || sessionStore.latestEncryptEpoch(peerDeviceId) == 2) {
            return RatchetInnerPlaintext.Payload(inner)
        }
        if (loaded.meta.role != SessionRole.RESPONDER) {
            return RatchetInnerPlaintext.Payload(inner)
        }

        return try {
            val offered = resolveOfferedOpk(loaded.meta)
            val initiatorEphemeral = loaded.meta.initiatorEphemeralPublicKey
                ?: return RatchetInnerPlaintext.Payload(inner)
            val sessionBinding = OpkOfferBinding.compute(
                crypto = crypto,
                localDeviceId = identityResolver.getLocalDeviceId(),
                peerDeviceId = peerDeviceId,
                sessionEpoch = 1,
                sessionGeneration = loaded.meta.sessionGeneration,
                handshakeSpkId = loaded.meta.handshakeSpkId,
                initiatorEphemeralPublicKey = initiatorEphemeral,
            )
            RatchetInnerPlaintext.WithControl(
                inner,
                InnerSessionControl.OpkOffer(
                    sessionEpoch = 1,
                    sessionGeneration = loaded.meta.sessionGeneration,
                    opkId = offered.keyId,
                    opkPublicKey = offered.publicKey,
                    sessionBinding = sessionBinding,
                ),
            )
        } catch (error: Exception) {
            AppLog.debug(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_OPENED,
                message = "Skipped OPK offer; continuing on epoch-1 3-DH",
                fields = mapOf(
                    "peerDeviceId" to peerDeviceId,
                    "reason" to (error.message ?: error::class.simpleName.orEmpty()),
                ),
            )
            RatchetInnerPlaintext.Payload(inner)
        }
    }

    suspend fun maybeUpgradeToEpoch2(
        remoteDeviceId: PeerId,
        frame: SessionWireFrame,
        record: CryptoSessionRecord,
        inner: RatchetInnerPlaintext,
    ) {
        if (inner !is RatchetInnerPlaintext.WithControl || inner.control !is InnerSessionControl.OpkOffer) {
            return
        }
        if (record.meta.role != SessionRole.INITIATOR || frame.sessionEpoch != 1) {
            return
        }
        if (!record.canonical || record.meta.status != SessionStatus.ACTIVE) {
            return
        }
        val canonicalEpoch1 = sessionStore.loadActiveCanonical(remoteDeviceId, sessionEpoch = 1) ?: return
        if (canonicalEpoch1.meta.sessionGeneration != record.meta.sessionGeneration) {
            return
        }
        if (frame.sessionGeneration != record.meta.sessionGeneration) {
            return
        }

        val offer = inner.control
        if (offer.sessionEpoch != 1 || offer.sessionGeneration != record.meta.sessionGeneration) {
            return
        }
        val initiatorEphemeral = record.meta.initiatorEphemeralPublicKey ?: return

        val expectedBinding = OpkOfferBinding.compute(
            crypto = crypto,
            localDeviceId = identityResolver.getLocalDeviceId(),
            peerDeviceId = remoteDeviceId,
            sessionEpoch = 1,
            sessionGeneration = record.meta.sessionGeneration,
            handshakeSpkId = record.meta.handshakeSpkId,
            initiatorEphemeralPublicKey = initiatorEphemeral,
        )
        if (!expectedBinding.contentEquals(offer.sessionBinding)) {
            AppLog.debug(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_OPENED,
                message = "Ignored OPK offer with invalid session binding",
                fields = mapOf("peerDeviceId" to remoteDeviceId, "opkId" to offer.opkId),
            )
            return
        }

        sessionBootstrap.createEpoch2AsInitiator(remoteDeviceId, offer)
    }

    suspend fun onEpoch2Confirmed(peerDeviceId: PeerId) {
        clearEpoch1OpkOffer(peerDeviceId)
        val hasActiveEpoch1 = sessionStore.loadSessions(peerDeviceId, sessionEpoch = 1)
            .any { it.meta.status == SessionStatus.ACTIVE }
        if (hasActiveEpoch1) {
            sessionStore.markEpochSuperseded(
                peerDeviceId,
                sessionEpoch = 1,
                updatedAtEpochSeconds = timeProvider.nowEpochSeconds(),
            )
        }
    }

    suspend fun maybePromoteEpoch2ForEncrypt(
        peerDeviceId: PeerId,
        frame: SessionWireFrame,
        hadPendingEpoch2BeforeDecrypt: Boolean,
    ) {
        if (frame.sessionEpoch != 1 || !hadPendingEpoch2BeforeDecrypt) {
            return
        }
        promotePendingEpoch2ForEncrypt(peerDeviceId)
    }

    private suspend fun clearEpoch1OpkOffer(peerDeviceId: PeerId) {
        val epoch1 = sessionStore.loadActiveCanonical(peerDeviceId, sessionEpoch = 1) ?: return
        if (epoch1.meta.offeredOpkId == null) {
            return
        }
        val now = timeProvider.nowEpochSeconds()
        val updatedMeta = epoch1.meta.copy(
            offeredOpkId = null,
            updatedAtEpochSeconds = now,
        )
        sessionStore.save(
            epoch1.copy(
                ratchetState = epoch1.ratchetState,
                meta = updatedMeta,
            ),
        )
    }

    private suspend fun promotePendingEpoch2ForEncrypt(peerDeviceId: PeerId) {
        val pending = sessionBootstrap.loadPendingEpoch2Initiator(peerDeviceId) ?: return
        val now = timeProvider.nowEpochSeconds()
        sessionStore.save(
            pending.copy(
                meta = pending.meta.copy(
                    status = SessionStatus.ACTIVE,
                    updatedAtEpochSeconds = now,
                ),
            ),
        )
        AppLog.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Promoted pending epoch-2 session for outbound encrypt",
            fields = mapOf("peerDeviceId" to peerDeviceId),
        )
    }

    private suspend fun resolveOfferedOpk(meta: CryptoSessionMeta): LocalOneTimePreKey {
        meta.offeredOpkId?.let { offeredOpkId ->
            opkRepository.loadOffered(offeredOpkId)?.let { return it }
        }
        val opk = opkRepository.allocate()
        opkRepository.markOffered(opk.keyId)
        return opk
    }
}
