package org.yapyap.crypto.e2ee

import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.EncryptionKeyPair
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.crypto.CryptoSessionStore
import org.yapyap.persistence.key.OpkRepository
import org.yapyap.protocol.PeerId
import org.yapyap.time.EpochSecondsProvider

internal data class LoadedSession(
    val session: DoubleRatchetSession,
    var meta: CryptoSessionMeta,
)

internal class SessionBootstrap(
    private val crypto: CryptoProvider,
    private val x3dh: X3dhHandshake,
    private val sessionStore: CryptoSessionStore,
    private val identityResolver: IdentityResolver,
    private val opkRepository: OpkRepository,
    private val timeProvider: EpochSecondsProvider,
    private val logger: AppLogger,
) {

    suspend fun bootstrapEpoch1Initiator(peerDeviceId: PeerId, sessionGeneration: Int): LoadedSession {
        val remote = identityResolver.resolvePeerX3dhRemoteKeys(peerDeviceId)
        val localIkPrivate = identityResolver.getLocalDevicePrivateKey(IdentityKeyPurpose.ENCRYPTION)
        val localIkPublic = identityResolver.getLocalDeviceIdentityRecord().encryption.publicKey
        val ephemeral = crypto.generateEncryptionKeyPair()
        val result = x3dh.initiatorCompute3Dh(
            local = X3dhLocalInitiatorKeys(
                identityEncryptionPrivateKey = localIkPrivate,
                identityEncryptionPublicKey = localIkPublic,
            ),
            remote = remote,
            ephemeral = ephemeral,
        )
        val session = DoubleRatchetSession.createInitiator(crypto, result.ratchetBootstrap)
        zeroizeInitiatorEphemeralMaterial(ephemeral, result)
        val now = timeProvider.nowEpochSeconds()
        val meta = CryptoSessionMeta(
            role = SessionRole.INITIATOR,
            x3dhMode = X3dhMode.THREE_DH,
            handshakeSpkId = remote.signedPreKeyId,
            initiatorEphemeralPublicKey = result.ephemeralKeyPair.publicKey,
            sessionGeneration = sessionGeneration,
            createdAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
        )
        persist(peerDeviceId, sessionEpoch = 1, session, meta)
        return LoadedSession(session, meta)
    }

    suspend fun bootstrapFromFrame(peerDeviceId: PeerId, frame: SessionWireFrame): LoadedSession {
        val wire = frame.outerHandshake ?: throw CryptoSessionException.HandshakeRequired(peerDeviceId)
        if (frame.sessionEpoch != wire.sessionEpoch) {
            throw CryptoSessionException.HandshakeMismatch(
                "sessionEpoch mismatch: frame=${frame.sessionEpoch}, wire=${wire.sessionEpoch}",
            )
        }
        if (frame.sessionGeneration != wire.sessionGeneration) {
            throw CryptoSessionException.HandshakeMismatch(
                "sessionGeneration mismatch: frame=${frame.sessionGeneration}, wire=${wire.sessionGeneration}",
            )
        }
        return when (frame.sessionEpoch) {
            1 -> bootstrapEpoch1Responder(peerDeviceId, wire)
            2 -> bootstrapEpoch2Responder(peerDeviceId, wire)
            else -> throw CryptoSessionException.HandshakeMismatch(
                "unsupported session epoch: ${frame.sessionEpoch}",
            )
        }
    }

    suspend fun createEpoch2AsInitiator(peerDeviceId: PeerId, offer: InnerSessionControl.OpkOffer) {
        if (sessionStore.loadActiveCanonical(peerDeviceId, sessionEpoch = 2) != null) {
            return
        }
        if (loadPendingEpoch2Initiator(peerDeviceId) != null) {
            return
        }
        val epoch1 = sessionStore.loadActiveCanonical(peerDeviceId, sessionEpoch = 1)
            ?: throw CryptoSessionException.NoSession(peerDeviceId, sessionEpoch = 1)
        val remote = identityResolver.resolvePeerX3dhRemoteKeys(
            peerDeviceId,
            signedPreKeyId = epoch1.meta.handshakeSpkId,
        )
        val localIkPrivate = identityResolver.getLocalDevicePrivateKey(IdentityKeyPurpose.ENCRYPTION)
        val localIkPublic = identityResolver.getLocalDeviceIdentityRecord().encryption.publicKey
        val ephemeral = crypto.generateEncryptionKeyPair()
        val result = x3dh.initiatorCompute4Dh(
            local = X3dhLocalInitiatorKeys(
                identityEncryptionPrivateKey = localIkPrivate,
                identityEncryptionPublicKey = localIkPublic,
            ),
            remote = remote,
            ephemeral = ephemeral,
            oneTimePreKeyPublicKey = offer.opkPublicKey,
            oneTimePreKeyId = offer.opkId,
        )
        val session = DoubleRatchetSession.createInitiator(crypto, result.ratchetBootstrap)
        zeroizeInitiatorEphemeralMaterial(ephemeral, result)
        val now = timeProvider.nowEpochSeconds()
        val meta = CryptoSessionMeta(
            role = SessionRole.INITIATOR,
            x3dhMode = X3dhMode.FOUR_DH,
            handshakeSpkId = remote.signedPreKeyId,
            handshakeOpkId = offer.opkId,
            initiatorEphemeralPublicKey = result.ephemeralKeyPair.publicKey,
            status = SessionStatus.PENDING,
            createdAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
        )
        persist(peerDeviceId, sessionEpoch = 2, session, meta)
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Created epoch-2 crypto session from OPK offer",
            fields = mapOf("peerDeviceId" to peerDeviceId, "opkId" to offer.opkId),
        )
    }

    fun buildOutboundWire(
        peerDeviceId: PeerId,
        meta: CryptoSessionMeta,
        epoch: Int,
        mode: X3dhMode,
        oneTimePreKeyId: String? = null,
    ): X3dhWireInfo {
        val ephemeralPublic = meta.initiatorEphemeralPublicKey
            ?: throw CryptoSessionException.MissingInitiatorEphemeral(peerDeviceId)
        return X3dhWireInfo(
            ephemeralPublicKey = ephemeralPublic,
            signedPreKeyId = meta.handshakeSpkId,
            sessionEpoch = epoch,
            sessionGeneration = meta.sessionGeneration,
            mode = mode,
            oneTimePreKeyId = oneTimePreKeyId,
        )
    }

    suspend fun loadPendingEpoch2Initiator(peerDeviceId: PeerId): CryptoSessionRecord? =
        sessionStore.loadSessions(peerDeviceId, sessionEpoch = 2)
            .firstOrNull {
                it.canonical &&
                    it.meta.status == SessionStatus.PENDING &&
                    it.meta.role == SessionRole.INITIATOR
            }

    private suspend fun bootstrapEpoch1Responder(peerDeviceId: PeerId, wire: X3dhWireInfo): LoadedSession {
        if (wire.mode != X3dhMode.THREE_DH) {
            throw CryptoSessionException.HandshakeMismatch("expected THREE_DH for epoch 1 bootstrap")
        }
        val localSpk = identityResolver.resolveLocalSignedPreKey(wire.signedPreKeyId)
        val remoteIk = identityResolver.resolvePeerIdentityRecord(peerDeviceId).encryption.publicKey
        val result = x3dh.responderCompute3Dh(
            local = X3dhLocalResponderKeys(
                identityEncryptionPrivateKey = identityResolver.getLocalDevicePrivateKey(IdentityKeyPurpose.ENCRYPTION),
                identityEncryptionPublicKey = identityResolver.getLocalDeviceIdentityRecord().encryption.publicKey,
                signedPreKeyPrivateKey = localSpk.privateKey!!,
                signedPreKeyPublicKey = localSpk.publicKey,
                signedPreKeyId = localSpk.keyId,
            ),
            remoteIdentityEncryptionPublicKey = remoteIk,
            wire = wire,
        )
        val session = DoubleRatchetSession.createResponder(crypto, result.ratchetBootstrap)
        val now = timeProvider.nowEpochSeconds()
        val meta = CryptoSessionMeta(
            role = SessionRole.RESPONDER,
            x3dhMode = X3dhMode.THREE_DH,
            handshakeSpkId = localSpk.keyId,
            initiatorEphemeralPublicKey = wire.ephemeralPublicKey,
            sessionGeneration = wire.sessionGeneration,
            createdAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
        )
        return LoadedSession(session, meta)
    }

    private suspend fun bootstrapEpoch2Responder(peerDeviceId: PeerId, wire: X3dhWireInfo): LoadedSession {
        if (wire.mode != X3dhMode.FOUR_DH) {
            throw CryptoSessionException.HandshakeMismatch("expected FOUR_DH for epoch 2 bootstrap")
        }
        val epoch1 = sessionStore.loadActiveCanonical(peerDeviceId, sessionEpoch = 1)
            ?: throw CryptoSessionException.NoSession(peerDeviceId, sessionEpoch = 1)
        val offeredOpkId = epoch1.meta.offeredOpkId
            ?: throw CryptoSessionException.MissingOfferedOpk(peerDeviceId)
        if (wire.signedPreKeyId != epoch1.meta.handshakeSpkId) {
            throw CryptoSessionException.HandshakeMismatch(
                "signedPreKeyId mismatch with epoch-1 session: wire=${wire.signedPreKeyId}, epoch1=${epoch1.meta.handshakeSpkId}",
            )
        }
        val opkId = wire.oneTimePreKeyId ?: offeredOpkId
        val opk = opkRepository.consume(opkId)
            ?: throw CryptoSessionException.OpkConsumeFailed(opkId)
        val localSpk = identityResolver.resolveLocalSignedPreKey(wire.signedPreKeyId)
        val remoteIk = identityResolver.resolvePeerIdentityRecord(peerDeviceId).encryption.publicKey
        val result = x3dh.responderCompute4Dh(
            local = X3dhLocalResponderKeys(
                identityEncryptionPrivateKey = identityResolver.getLocalDevicePrivateKey(IdentityKeyPurpose.ENCRYPTION),
                identityEncryptionPublicKey = identityResolver.getLocalDeviceIdentityRecord().encryption.publicKey,
                signedPreKeyPrivateKey = localSpk.privateKey!!,
                signedPreKeyPublicKey = localSpk.publicKey,
                signedPreKeyId = localSpk.keyId,
            ),
            oneTimePreKeyPrivateKey = opk.privateKey,
            oneTimePreKeyId = opk.keyId,
            remoteIdentityEncryptionPublicKey = remoteIk,
            wire = wire,
        )
        val session = DoubleRatchetSession.createResponder(crypto, result.ratchetBootstrap)
        val now = timeProvider.nowEpochSeconds()
        val meta = CryptoSessionMeta(
            role = SessionRole.RESPONDER,
            x3dhMode = X3dhMode.FOUR_DH,
            handshakeSpkId = localSpk.keyId,
            handshakeOpkId = opk.keyId,
            sessionGeneration = wire.sessionGeneration,
            createdAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
        )
        return LoadedSession(session, meta)
    }

    private suspend fun persist(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        session: DoubleRatchetSession,
        meta: CryptoSessionMeta,
        canonical: Boolean = true,
    ) {
        val now = timeProvider.nowEpochSeconds()
        sessionStore.save(
            CryptoSessionRecord(
                peerDeviceId = peerDeviceId,
                sessionEpoch = sessionEpoch,
                ratchetState = session.snapshot(),
                meta = meta.copy(updatedAtEpochSeconds = now),
                canonical = canonical,
            ),
        )
    }

    private fun zeroizeInitiatorEphemeralMaterial(
        ephemeral: EncryptionKeyPair,
        result: X3dhInitiatorResult,
    ) {
        ephemeral.privateKey.fill(0)
        result.ephemeralKeyPair.privateKey.fill(0)
    }
}
