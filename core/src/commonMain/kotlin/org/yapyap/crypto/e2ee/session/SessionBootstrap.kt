package org.yapyap.crypto.e2ee.session

import org.yapyap.crypto.e2ee.CryptoSessionException
import org.yapyap.crypto.keys.EncryptionPublicKey
import org.yapyap.crypto.keys.OneTimePreKeyId
import org.yapyap.crypto.keys.SignedPreKeyId
import org.yapyap.crypto.identity.LocalKeys
import org.yapyap.crypto.identity.PeerKeys
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.keys.EncryptionKeyPair
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.crypto.store.CryptoSessionStore
import org.yapyap.crypto.store.OpkRepository
import org.yapyap.protocol.PeerId
import kotlin.time.Clock

internal data class LoadedSession(
    val session: DoubleRatchetSession,
    var meta: CryptoSessionMeta,
)

internal class SessionBootstrap(
    private val crypto: CryptoProvider,
    private val x3dh: X3dhHandshake,
    private val sessionStore: CryptoSessionStore,
    private val localKeys: LocalKeys,
    private val peerKeys: PeerKeys,
    private val opkRepository: OpkRepository,
    private val clock: Clock,
) {

    suspend fun bootstrapEpoch1Initiator(peerDeviceId: PeerId, sessionGeneration: Int): LoadedSession {
        val remote = peerKeys.resolvePeerPreKeyBundle(peerDeviceId)
        val ephemeral = crypto.generateEncryptionKeyPair()
        val result = x3dh.initiatorCompute3Dh(
            localIdentityKey = localKeys.localDeviceEncryptionKey(),
            remote = remote,
            ephemeral = ephemeral,
        )
        val session = DoubleRatchetSession.createInitiator(crypto, result.ratchetBootstrap)
        zeroizeInitiatorEphemeralMaterial(ephemeral, result)
        val now = clock.now()
        val meta = CryptoSessionMeta(
            role = SessionRole.INITIATOR,
            x3dhMode = X3dhMode.THREE_DH,
            handshakeSpkId = remote.signedPreKey.id.value,
            initiatorEphemeralPublicKey = result.ephemeralKeyPair.publicKey.bytes,
            sessionGeneration = sessionGeneration,
            createdAt = now,
            updatedAt = now,
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
        val remote = peerKeys.resolvePeerPreKeyBundle(
            peerDeviceId,
            signedPreKeyId = SignedPreKeyId(epoch1.meta.handshakeSpkId),
        )
        val ephemeral = crypto.generateEncryptionKeyPair()
        val result = x3dh.initiatorCompute4Dh(
            localIdentityKey = localKeys.localDeviceEncryptionKey(),
            remote = remote,
            ephemeral = ephemeral,
            oneTimePreKeyPublicKey = EncryptionPublicKey(offer.opkPublicKey),
            oneTimePreKeyId = offer.opkId,
        )
        val session = DoubleRatchetSession.createInitiator(crypto, result.ratchetBootstrap)
        zeroizeInitiatorEphemeralMaterial(ephemeral, result)
        val now = clock.now()
        val meta = CryptoSessionMeta(
            role = SessionRole.INITIATOR,
            x3dhMode = X3dhMode.FOUR_DH,
            handshakeSpkId = remote.signedPreKey.id.value,
            handshakeOpkId = offer.opkId,
            initiatorEphemeralPublicKey = result.ephemeralKeyPair.publicKey.bytes,
            status = SessionStatus.PENDING,
            createdAt = now,
            updatedAt = now,
        )
        persist(peerDeviceId, sessionEpoch = 2, session, meta)
        AppLog.debug(
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
        val localSpk = localKeys.resolveLocalSignedPreKey(SignedPreKeyId(wire.signedPreKeyId))
        val remoteIk = peerKeys.resolvePeerIdentity(peerDeviceId).encryptionKey
        val result = x3dh.responderCompute3Dh(
            localIdentityKey = localKeys.localDeviceEncryptionKey(),
            localSignedPreKey = localSpk,
            remoteIdentityKey = remoteIk,
            wire = wire,
        )
        val session = DoubleRatchetSession.createResponder(crypto, result.ratchetBootstrap)
        val now = clock.now()
        val meta = CryptoSessionMeta(
            role = SessionRole.RESPONDER,
            x3dhMode = X3dhMode.THREE_DH,
            handshakeSpkId = localSpk.id.value,
            initiatorEphemeralPublicKey = wire.ephemeralPublicKey,
            sessionGeneration = wire.sessionGeneration,
            createdAt = now,
            updatedAt = now,
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
        val opk = opkRepository.consume(OneTimePreKeyId(opkId))
            ?: throw CryptoSessionException.OpkConsumeFailed(opkId)
        val localSpk = localKeys.resolveLocalSignedPreKey(SignedPreKeyId(wire.signedPreKeyId))
        val remoteIk = peerKeys.resolvePeerIdentity(peerDeviceId).encryptionKey
        val result = x3dh.responderCompute4Dh(
            localIdentityKey = localKeys.localDeviceEncryptionKey(),
            localSignedPreKey = localSpk,
            oneTimePreKey = opk,
            remoteIdentityKey = remoteIk,
            wire = wire,
        )
        val session = DoubleRatchetSession.createResponder(crypto, result.ratchetBootstrap)
        val now = clock.now()
        val meta = CryptoSessionMeta(
            role = SessionRole.RESPONDER,
            x3dhMode = X3dhMode.FOUR_DH,
            handshakeSpkId = localSpk.id.value,
            handshakeOpkId = opk.id.value,
            sessionGeneration = wire.sessionGeneration,
            createdAt = now,
            updatedAt = now,
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
        val now = clock.now()
        sessionStore.save(
            CryptoSessionRecord(
                peerDeviceId = peerDeviceId,
                sessionEpoch = sessionEpoch,
                ratchetState = session.snapshot(),
                meta = meta.copy(updatedAt = now),
                canonical = canonical,
            ),
        )
    }

    private fun zeroizeInitiatorEphemeralMaterial(
        ephemeral: EncryptionKeyPair,
        result: X3dhInitiatorResult,
    ) {
        ephemeral.privateKey.bytes.fill(0)
        result.ephemeralKeyPair.privateKey.bytes.fill(0)
    }
}
