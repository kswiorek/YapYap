package org.yapyap.backend.crypto.e2ee

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.backend.crypto.CryptoProvider
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityResolver
import org.yapyap.backend.db.CryptoSessionMeta
import org.yapyap.backend.db.CryptoSessionRecord
import org.yapyap.backend.db.CryptoSessionStore
import org.yapyap.backend.db.SessionRole
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.time.EpochSecondsProvider
import org.yapyap.backend.time.SystemEpochSecondsProvider

class DefaultCryptoSessionManager(
    private val crypto: CryptoProvider,
    private val x3dh: X3dhHandshake,
    private val sessionStore: CryptoSessionStore,
    private val identityResolver: IdentityResolver,
    private val oneTimePreKeyStore: OneTimePreKeyStore,
    private val timeProvider: EpochSecondsProvider = SystemEpochSecondsProvider,
    private val upgradePolicy: SessionUpgradePolicy = SessionUpgradePolicy.NEVER,
    private val logger: AppLogger = NoopAppLogger,
) : CryptoSessionManager {

    private val peerLocks = PeerLockRegistry()

    override suspend fun encryptMessage(
        remoteDeviceId: PeerId,
        bytes: ByteArray,
    ): SessionWireFrame = peerLocks.withPeerLock(remoteDeviceId) {
        val epoch = sessionStore.latestEncryptEpoch(remoteDeviceId) ?: 1
        var loaded = loadCanonicalSession(remoteDeviceId, epoch)
        var outerHandshake: X3dhWireInfo? = null

        if (loaded == null) {
            require(epoch == 1) {
                "epoch-2 session must exist before encrypt for peer=$remoteDeviceId"
            }
            loaded = bootstrapEpoch1Initiator(remoteDeviceId)
            outerHandshake = buildOutboundWire(
                peerDeviceId = remoteDeviceId,
                meta = loaded.meta,
                epoch = 1,
                mode = X3dhMode.THREE_DH,
            )
        } else if (shouldAttachOutboundWire(loaded, epoch)) {
            outerHandshake = buildOutboundWire(
                peerDeviceId = remoteDeviceId,
                meta = loaded.meta,
                epoch = epoch,
                mode = if (epoch == 1) X3dhMode.THREE_DH else X3dhMode.FOUR_DH,
                oneTimePreKeyId = loaded.meta.handshakeOpkId,
            )
        }

        val innerToEncrypt = maybeAttachOpkOffer(remoteDeviceId, epoch, loaded.meta, bytes)
        if (innerToEncrypt is RatchetInnerPlaintext.WithControl &&
            innerToEncrypt.control is InnerSessionControl.OpkOffer
        ) {
            val opkId = innerToEncrypt.control.opkId
            loaded.meta = loaded.meta.copy(offeredOpkId = opkId)
        }

        val ratchet = loaded.session.encrypt(innerToEncrypt.encode())
        persist(remoteDeviceId, epoch, loaded.session, loaded.meta)

        SessionWireFrame(
            sessionEpoch = epoch,
            outerHandshake = outerHandshake,
            ratchet = ratchet,
        )
    }

    override suspend fun decryptMessage(
        remoteDeviceId: PeerId,
        frame: SessionWireFrame,
    ): ByteArray = peerLocks.withPeerLock(remoteDeviceId) {
        val canonicalRecord = sessionStore.loadCanonical(remoteDeviceId, frame.sessionEpoch)

        if (shouldBootstrapFromInboundHandshake(canonicalRecord)) {
            decryptFromInboundHandshake(remoteDeviceId, frame, canonicalRecord)?.let { return@withPeerLock it }
        }

        decryptWithExistingSession(remoteDeviceId, frame)
    }

    /**
     * Simultaneous-init tie-break: the peer with the lower device id is the canonical responder;
     * the higher id keeps the canonical initiator session when both sides sent first.
     */
    private suspend fun inboundResponderSessionIsCanonical(peerDeviceId: PeerId): Boolean =
        peerDeviceId.id > identityResolver.getLocalDeviceId().id

    /**
     * Bootstrap from [SessionWireFrame.outerHandshake] on first contact, or during simultaneous
     * initiation when the local initiator has sent but not yet received on that epoch.
     */
    private fun shouldBootstrapFromInboundHandshake(canonicalRecord: CryptoSessionRecord?): Boolean {
        if (canonicalRecord == null) {
            return true
        }
        if (canonicalRecord.meta.role != SessionRole.INITIATOR) {
            return false
        }
        return canonicalRecord.ratchetState.recvMessageNumber == 0
    }

    private suspend fun decryptFromInboundHandshake(
        remoteDeviceId: PeerId,
        frame: SessionWireFrame,
        canonicalRecord: CryptoSessionRecord?,
    ): ByteArray? {
        val bootstrapped = try {
            bootstrapFromFrame(remoteDeviceId, frame)
        } catch (_: CryptoSessionException.HandshakeRequired) {
            return null
        }

        val inner = decryptRatchet(bootstrapped.session, frame.ratchet)

        val responderIsCanonical = inboundResponderSessionIsCanonical(remoteDeviceId)
        if (responderIsCanonical && canonicalRecord != null) {
            sessionStore.setCanonical(remoteDeviceId, frame.sessionEpoch, SessionRole.INITIATOR, canonical = false)
        }
        persist(
            remoteDeviceId,
            frame.sessionEpoch,
            bootstrapped.session,
            bootstrapped.meta,
            canonical = responderIsCanonical || canonicalRecord == null,
        )
        if (frame.sessionEpoch == 2) {
            clearEpoch1OpkOffer(remoteDeviceId)
        }
        maybeUpgradeToEpoch2(remoteDeviceId, inner)
        return inner.bytes
    }

    private suspend fun clearEpoch1OpkOffer(peerDeviceId: PeerId) {
        val epoch1 = sessionStore.loadCanonical(peerDeviceId, sessionEpoch = 1) ?: return
        if (epoch1.meta.offeredOpkId == null) {
            return
        }
        val now = timeProvider.nowEpochSeconds()
        val updatedMeta = epoch1.meta.copy(
            offeredOpkId = null,
            updatedAtEpochSeconds = now,
        )
        persist(
            peerDeviceId,
            sessionEpoch = 1,
            DoubleRatchetSession.fromState(crypto, epoch1.ratchetState),
            updatedMeta,
            canonical = epoch1.canonical,
        )
    }

    private suspend fun decryptWithExistingSession(
        remoteDeviceId: PeerId,
        frame: SessionWireFrame,
    ): ByteArray {
        val records = sessionStore.loadSessions(remoteDeviceId, frame.sessionEpoch)
        records.find { it.ratchetState.remoteDhPublicKey.contentEquals(frame.ratchet.dhPublicKey) }
            ?.let { return decryptAndPersist(remoteDeviceId, frame, it) }

        for (record in records.sortedBy { it.meta.status }) {
            try {
                return decryptAndPersist(remoteDeviceId, frame, record)
            } catch (_: Exception) {
                // try next candidate session for this epoch
            }
        }
        throw CryptoSessionException.NoSession(remoteDeviceId, frame.sessionEpoch)
    }

    private suspend fun decryptAndPersist(
        remoteDeviceId: PeerId,
        frame: SessionWireFrame,
        record: CryptoSessionRecord,
    ): ByteArray {
        val loaded = LoadedSession(
            session = DoubleRatchetSession.fromState(crypto, record.ratchetState),
            meta = record.meta,
        )
        val inner = decryptRatchet(loaded.session, frame.ratchet)
        persist(remoteDeviceId, frame.sessionEpoch, loaded.session, loaded.meta, canonical = record.canonical)
        maybeUpgradeToEpoch2(remoteDeviceId, inner)
        return inner.bytes
    }

    private suspend fun decryptRatchet(
        session: DoubleRatchetSession,
        ratchet: RatchetCiphertext,
    ): RatchetInnerPlaintext {
        val plaintext = session.decrypt(ratchet)
        return RatchetInnerPlaintext.decode(plaintext)
    }

    private suspend fun maybeUpgradeToEpoch2(remoteDeviceId: PeerId, inner: RatchetInnerPlaintext) {
        if (inner is RatchetInnerPlaintext.WithControl && inner.control is InnerSessionControl.OpkOffer) {
            createEpoch2AsInitiator(remoteDeviceId, inner.control)
        }
    }

    private suspend fun bootstrapEpoch1Initiator(peerDeviceId: PeerId): LoadedSession {
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
        val now = timeProvider.nowEpochSeconds()
        val meta = CryptoSessionMeta(
            role = SessionRole.INITIATOR,
            x3dhMode = X3dhMode.THREE_DH,
            handshakeSpkId = remote.signedPreKeyId,
            initiatorEphemeralPrivateKey = result.ephemeralKeyPair.privateKey,
            initiatorEphemeralPublicKey = result.ephemeralKeyPair.publicKey,
            createdAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
        )
        persist(peerDeviceId, sessionEpoch = 1, session, meta)
        return LoadedSession(session, meta)
    }

    private suspend fun bootstrapFromFrame(peerDeviceId: PeerId, frame: SessionWireFrame): LoadedSession {
        val wire = frame.outerHandshake ?: throw CryptoSessionException.HandshakeRequired(peerDeviceId)
        return when (frame.sessionEpoch) {
            1 -> bootstrapEpoch1Responder(peerDeviceId, wire)
            2 -> bootstrapEpoch2Responder(peerDeviceId, wire)
            else -> error("unsupported session epoch: ${frame.sessionEpoch}")
        }
    }

    private suspend fun bootstrapEpoch1Responder(peerDeviceId: PeerId, wire: X3dhWireInfo): LoadedSession {
        require(wire.mode == X3dhMode.THREE_DH) { "expected THREE_DH for epoch 1 bootstrap" }
        val localSpk = identityResolver.resolveLocalSignedPreKey(wire.signedPreKeyId)
        val remoteIk = identityResolver.resolvePeerIdentityRecord(peerDeviceId)?.encryption?.publicKey
            ?: error("Missing peer identity encryption key for peer=$peerDeviceId")
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
            createdAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
        )
        return LoadedSession(session, meta)
    }

    private suspend fun bootstrapEpoch2Responder(peerDeviceId: PeerId, wire: X3dhWireInfo): LoadedSession {
        require(wire.mode == X3dhMode.FOUR_DH) { "expected FOUR_DH for epoch 2 bootstrap" }
        val epoch1 = sessionStore.loadCanonical(peerDeviceId, sessionEpoch = 1)
            ?: throw CryptoSessionException.NoSession(peerDeviceId, sessionEpoch = 1)
        val offeredOpkId = epoch1.meta.offeredOpkId
            ?: throw CryptoSessionException.MissingOfferedOpk(peerDeviceId)
        val opkId = wire.oneTimePreKeyId ?: offeredOpkId
        val opk = oneTimePreKeyStore.consume(opkId)
            ?: throw CryptoSessionException.OpkConsumeFailed(opkId)
        val localSpk = identityResolver.resolveLocalSignedPreKey(wire.signedPreKeyId)
        val remoteIk = identityResolver.resolvePeerIdentityRecord(peerDeviceId)?.encryption?.publicKey
            ?: error("Missing peer identity encryption key for peer=$peerDeviceId")
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
            createdAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
        )
        return LoadedSession(session, meta)
    }

    private suspend fun createEpoch2AsInitiator(peerDeviceId: PeerId, offer: InnerSessionControl.OpkOffer) {
        if (sessionStore.loadCanonical(peerDeviceId, sessionEpoch = 2) != null) {
            return
        }
        val epoch1 = sessionStore.loadCanonical(peerDeviceId, sessionEpoch = 1)
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
        val now = timeProvider.nowEpochSeconds()
        val meta = CryptoSessionMeta(
            role = SessionRole.INITIATOR,
            x3dhMode = X3dhMode.FOUR_DH,
            handshakeSpkId = remote.signedPreKeyId,
            handshakeOpkId = offer.opkId,
            initiatorEphemeralPrivateKey = ephemeral.privateKey,
            initiatorEphemeralPublicKey = ephemeral.publicKey,
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

    private suspend fun maybeAttachOpkOffer(
        peerDeviceId: PeerId,
        epoch: Int,
        meta: CryptoSessionMeta,
        inner: ByteArray,
    ): RatchetInnerPlaintext {
        if (upgradePolicy != SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY) {
            return RatchetInnerPlaintext.Payload(inner)
        }
        if (epoch != 1 || sessionStore.latestEncryptEpoch(peerDeviceId) == 2) {
            return RatchetInnerPlaintext.Payload(inner)
        }
        if (meta.offeredOpkId != null || meta.role != SessionRole.RESPONDER) {
            return RatchetInnerPlaintext.Payload(inner)
        }
        val snapshot = sessionStore.loadCanonical(peerDeviceId, sessionEpoch = 1)?.ratchetState
        if (snapshot != null && snapshot.sendMessageNumber > 0) {
            return RatchetInnerPlaintext.Payload(inner)
        }
        val opk = oneTimePreKeyStore.allocate()
        return RatchetInnerPlaintext.WithControl(
            inner,
            InnerSessionControl.OpkOffer(
                opkId = opk.keyId,
                opkPublicKey = opk.publicKey,
            ),
        )
    }

    private fun shouldAttachOutboundWire(loaded: LoadedSession, epoch: Int): Boolean {
        if (loaded.meta.role != SessionRole.INITIATOR) {
            return false
        }
        val recvMessageNumber = loaded.session.snapshot().recvMessageNumber
        return recvMessageNumber == 0 && (epoch == 1 || epoch == 2)
    }

    private fun buildOutboundWire(
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
            mode = mode,
            oneTimePreKeyId = oneTimePreKeyId,
        )
    }

    private suspend fun loadCanonicalSession(peerDeviceId: PeerId, sessionEpoch: Int): LoadedSession? {
        val record = sessionStore.loadCanonical(peerDeviceId, sessionEpoch) ?: return null
        return LoadedSession(
            session = DoubleRatchetSession.fromState(crypto, record.ratchetState),
            meta = record.meta,
        )
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

    private data class LoadedSession(
        val session: DoubleRatchetSession,
        var meta: CryptoSessionMeta,
    )

    private class PeerLockRegistry {
        private val registryMutex = Mutex()
        private val locks = mutableMapOf<String, Mutex>()

        suspend fun <T> withPeerLock(peerDeviceId: PeerId, block: suspend () -> T): T {
            val mutex = registryMutex.withLock {
                locks.getOrPut(peerDeviceId.id) { Mutex() }
            }
            return mutex.withLock { block() }
        }
    }
}

sealed class CryptoSessionException(message: String) : Exception(message) {
    class NoSession(peerDeviceId: PeerId, sessionEpoch: Int) :
        CryptoSessionException("No crypto session for peer=$peerDeviceId epoch=$sessionEpoch")

    class HandshakeRequired(peerDeviceId: PeerId) :
        CryptoSessionException("Handshake required for peer=$peerDeviceId")

    class MissingInitiatorEphemeral(peerDeviceId: PeerId) :
        CryptoSessionException("Missing initiator ephemeral key for peer=$peerDeviceId")

    class MissingOfferedOpk(peerDeviceId: PeerId) :
        CryptoSessionException("Missing offered OPK for peer=$peerDeviceId")

    class OpkConsumeFailed(opkId: String) :
        CryptoSessionException("Failed to consume one-time prekey id=$opkId")
}
