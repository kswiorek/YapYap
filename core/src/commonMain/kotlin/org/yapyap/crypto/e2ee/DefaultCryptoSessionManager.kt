package org.yapyap.crypto.e2ee

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.identity.LocalOneTimePreKey
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.EncryptionKeyPair
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.persistence.crypto.CryptoSessionStore
import org.yapyap.persistence.key.OpkRepository
import org.yapyap.protocol.PeerId
import org.yapyap.time.EpochSecondsProvider
import org.yapyap.time.SystemEpochSecondsProvider

class DefaultCryptoSessionManager(
    private val crypto: CryptoProvider,
    private val x3dh: X3dhHandshake,
    private val sessionStore: CryptoSessionStore,
    private val identityResolver: IdentityResolver,
    private val opkRepository: OpkRepository,
    private val timeProvider: EpochSecondsProvider = SystemEpochSecondsProvider,
    private val upgradePolicy: SessionUpgradePolicy = SessionUpgradePolicy.NEVER,
    private val sessionConfig: CryptoSessionConfig = CryptoSessionConfig(),
    private val logger: AppLogger = NoopAppLogger,
) : CryptoSessionManager {

    private val peerLocks = PeerLockRegistry()

    override suspend fun encryptMessage(
        remoteDeviceId: PeerId,
        bytes: ByteArray,
    ): SessionWireFrame = peerLocks.withPeerLock(remoteDeviceId) {
        encryptMessageUnderLock(remoteDeviceId, bytes)
    }

    override suspend fun decryptMessage(
        remoteDeviceId: PeerId,
        frame: SessionWireFrame,
    ): ByteArray = peerLocks.withPeerLock(remoteDeviceId) {
        decryptMessageUnderLock(remoteDeviceId, frame)
    }

    private suspend fun encryptMessageUnderLock(
        remoteDeviceId: PeerId,
        bytes: ByteArray,
    ): SessionWireFrame {
        CryptoWireLimits.requireInnerPlaintextSize(bytes.size)
        val epoch = sessionStore.latestEncryptEpoch(remoteDeviceId) ?: 1
        var loaded = loadCanonicalSession(remoteDeviceId, epoch)
        var outerHandshake: X3dhWireInfo? = null

        if (loaded == null) {
            require(epoch == 1) {
                "epoch-2 session must exist before encrypt for peer=$remoteDeviceId"
            }
            val generation = nextSessionGeneration(remoteDeviceId, epoch, SessionRole.INITIATOR)
            if (generation > 1) {
                sessionStore.markEpochSuperseded(
                    remoteDeviceId,
                    sessionEpoch = 2,
                    updatedAtEpochSeconds = timeProvider.nowEpochSeconds(),
                )
            }
            loaded = bootstrapEpoch1Initiator(remoteDeviceId, generation)
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

        val innerToEncrypt = maybeAttachOpkOffer(remoteDeviceId, epoch, loaded, bytes)
        if (innerToEncrypt is RatchetInnerPlaintext.WithControl &&
            innerToEncrypt.control is InnerSessionControl.OpkOffer
        ) {
            loaded.meta = loaded.meta.copy(offeredOpkId = innerToEncrypt.control.opkId)
        }

        val ratchet = loaded.session.encrypt(innerToEncrypt.encode())
        persist(remoteDeviceId, epoch, loaded.session, loaded.meta)

        return SessionWireFrame(
            sessionEpoch = epoch,
            sessionGeneration = loaded.meta.sessionGeneration,
            outerHandshake = outerHandshake,
            ratchet = ratchet,
        )
    }

    private suspend fun decryptMessageUnderLock(
        remoteDeviceId: PeerId,
        frame: SessionWireFrame,
    ): ByteArray {
        val canonicalRecord = sessionStore.loadActiveCanonical(remoteDeviceId, frame.sessionEpoch)
        val hadPendingEpoch2 = loadPendingEpoch2Initiator(remoteDeviceId) != null

        if (shouldBootstrapFromInboundHandshake(frame, canonicalRecord)) {
            decryptFromInboundHandshake(remoteDeviceId, frame, canonicalRecord, hadPendingEpoch2)?.let { return it }
        }

        return decryptWithExistingSession(remoteDeviceId, frame, hadPendingEpoch2)
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
    private fun shouldBootstrapFromInboundHandshake(
        frame: SessionWireFrame,
        canonicalRecord: CryptoSessionRecord?,
    ): Boolean {
        if (frame.outerHandshake == null) {
            return false
        }
        if (canonicalRecord == null) {
            return true
        }
        if (frame.sessionGeneration > canonicalRecord.meta.sessionGeneration) {
            return true
        }
        if (canonicalRecord.meta.role != SessionRole.INITIATOR) {
            return false
        }
        return canonicalRecord.meta.sessionGeneration == frame.sessionGeneration &&
            canonicalRecord.ratchetState.recvMessageNumber == 0
    }

    private suspend fun handleInboundGenerationReset(
        remoteDeviceId: PeerId,
        frame: SessionWireFrame,
        canonicalRecord: CryptoSessionRecord?,
    ) {
        if (canonicalRecord == null) {
            return
        }
        if (frame.sessionGeneration <= canonicalRecord.meta.sessionGeneration) {
            return
        }
        sessionStore.markSuperseded(
            remoteDeviceId,
            frame.sessionEpoch,
            canonicalRecord.meta.role,
            canonicalRecord.meta.sessionGeneration,
            timeProvider.nowEpochSeconds(),
        )
        if (frame.sessionEpoch == 1) {
            sessionStore.markEpochSuperseded(
                remoteDeviceId,
                sessionEpoch = 2,
                updatedAtEpochSeconds = timeProvider.nowEpochSeconds(),
            )
        }
    }

    private suspend fun decryptFromInboundHandshake(
        remoteDeviceId: PeerId,
        frame: SessionWireFrame,
        canonicalRecord: CryptoSessionRecord?,
        hadPendingEpoch2: Boolean,
    ): ByteArray? {
        val bootstrapped = try {
            bootstrapFromFrame(remoteDeviceId, frame)
        } catch (error: Exception) {
            if (!isEpoch2OpkBootstrapFailure(frame, error)) {
                throw error
            }
            logger.debug(
                component = LogComponent.CRYPTO,
                event = LogEvent.EPOCH_2_BOOTSTRAP_FAIL,
                message = "Deferred epoch-2 bootstrap; continuing on epoch-1",
                fields = mapOf(
                    "peerDeviceId" to remoteDeviceId,
                    "reason" to (error.message ?: error::class.simpleName.orEmpty()),
                ),
            )
            return null
        }

        val inner = decryptRatchet(bootstrapped.session, frame.ratchet)

        handleInboundGenerationReset(remoteDeviceId, frame, canonicalRecord)

        val responderIsCanonical = inboundResponderSessionIsCanonical(remoteDeviceId)
        if (responderIsCanonical && canonicalRecord != null &&
            canonicalRecord.meta.sessionGeneration == frame.sessionGeneration
        ) {
            sessionStore.setCanonical(
                remoteDeviceId,
                frame.sessionEpoch,
                SessionRole.INITIATOR,
                canonicalRecord.meta.sessionGeneration,
                canonical = false,
            )
            if (sessionConfig.supersedeRogueSessionsAfterSimultaneousInit) {
                sessionStore.markSuperseded(
                    remoteDeviceId,
                    frame.sessionEpoch,
                    SessionRole.INITIATOR,
                    canonicalRecord.meta.sessionGeneration,
                    timeProvider.nowEpochSeconds(),
                )
            }
        }
        persist(
            remoteDeviceId,
            frame.sessionEpoch,
            bootstrapped.session,
            bootstrapped.meta,
            canonical = responderIsCanonical || canonicalRecord == null,
        )
        if (!responderIsCanonical && canonicalRecord != null &&
            canonicalRecord.meta.sessionGeneration == frame.sessionGeneration &&
            sessionConfig.supersedeRogueSessionsAfterSimultaneousInit
        ) {
            sessionStore.markSuperseded(
                remoteDeviceId,
                frame.sessionEpoch,
                SessionRole.RESPONDER,
                frame.sessionGeneration,
                timeProvider.nowEpochSeconds(),
            )
        }
        if (frame.sessionEpoch == 2) {
            onEpoch2Confirmed(remoteDeviceId)
        }
        maybeUpgradeToEpoch2(
            remoteDeviceId = remoteDeviceId,
            frame = frame,
            record = CryptoSessionRecord(
                peerDeviceId = remoteDeviceId,
                sessionEpoch = frame.sessionEpoch,
                ratchetState = bootstrapped.session.snapshot(),
                meta = bootstrapped.meta,
                canonical = responderIsCanonical || canonicalRecord == null,
            ),
            inner = inner,
        )
        maybePromoteEpoch2ForEncrypt(remoteDeviceId, frame, hadPendingEpoch2)
        return inner.bytes
    }

    private suspend fun onEpoch2Confirmed(peerDeviceId: PeerId) {
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
        hadPendingEpoch2: Boolean,
    ): ByteArray {
        val records = sessionStore.loadSessions(remoteDeviceId, frame.sessionEpoch)
            .filter { it.meta.sessionGeneration == frame.sessionGeneration }
        records.find { it.ratchetState.remoteDhPublicKey.contentEquals(frame.ratchet.dhPublicKey) }
            ?.let { return decryptAndPersist(remoteDeviceId, frame, it, hadPendingEpoch2) }

        for (record in records.sortedBy { it.meta.status }) {
            try {
                return decryptAndPersist(remoteDeviceId, frame, record, hadPendingEpoch2)
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
        hadPendingEpoch2: Boolean,
    ): ByteArray {
        val loaded = LoadedSession(
            session = DoubleRatchetSession.fromState(crypto, record.ratchetState),
            meta = record.meta,
        )
        val inner = decryptRatchet(loaded.session, frame.ratchet)
        persist(remoteDeviceId, frame.sessionEpoch, loaded.session, loaded.meta, canonical = record.canonical)
        if (frame.sessionEpoch == 2) {
            onEpoch2Confirmed(remoteDeviceId)
        }
        maybeUpgradeToEpoch2(
            remoteDeviceId = remoteDeviceId,
            frame = frame,
            record = record.copy(ratchetState = loaded.session.snapshot()),
            inner = inner,
        )
        maybePromoteEpoch2ForEncrypt(remoteDeviceId, frame, hadPendingEpoch2)
        return inner.bytes
    }

    private suspend fun decryptRatchet(
        session: DoubleRatchetSession,
        ratchet: RatchetCiphertext,
    ): RatchetInnerPlaintext {
        val plaintext = session.decrypt(ratchet)
        return RatchetInnerPlaintext.decode(plaintext)
    }

    private suspend fun maybeUpgradeToEpoch2(
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
            logger.debug(
                component = LogComponent.CRYPTO,
                event = LogEvent.ENVELOPE_OPENED,
                message = "Ignored OPK offer with invalid session binding",
                fields = mapOf("peerDeviceId" to remoteDeviceId, "opkId" to offer.opkId),
            )
            return
        }

        createEpoch2AsInitiator(remoteDeviceId, offer)
    }

    private suspend fun bootstrapEpoch1Initiator(peerDeviceId: PeerId, sessionGeneration: Int): LoadedSession {
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

    private suspend fun bootstrapFromFrame(peerDeviceId: PeerId, frame: SessionWireFrame): LoadedSession {
        val wire = frame.outerHandshake ?: throw CryptoSessionException.HandshakeRequired(peerDeviceId)
        require(frame.sessionEpoch == wire.sessionEpoch) {
            "sessionEpoch mismatch: frame=${frame.sessionEpoch}, wire=${wire.sessionEpoch}"
        }
        require(frame.sessionGeneration == wire.sessionGeneration) {
            "sessionGeneration mismatch: frame=${frame.sessionGeneration}, wire=${wire.sessionGeneration}"
        }
        return when (frame.sessionEpoch) {
            1 -> bootstrapEpoch1Responder(peerDeviceId, wire)
            2 -> bootstrapEpoch2Responder(peerDeviceId, wire)
            else -> error("unsupported session epoch: ${frame.sessionEpoch}")
        }
    }

    private suspend fun bootstrapEpoch1Responder(peerDeviceId: PeerId, wire: X3dhWireInfo): LoadedSession {
        require(wire.mode == X3dhMode.THREE_DH) { "expected THREE_DH for epoch 1 bootstrap" }
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
        require(wire.mode == X3dhMode.FOUR_DH) { "expected FOUR_DH for epoch 2 bootstrap" }
        val epoch1 = sessionStore.loadActiveCanonical(peerDeviceId, sessionEpoch = 1)
            ?: throw CryptoSessionException.NoSession(peerDeviceId, sessionEpoch = 1)
        val offeredOpkId = epoch1.meta.offeredOpkId
            ?: throw CryptoSessionException.MissingOfferedOpk(peerDeviceId)
        require(wire.signedPreKeyId == epoch1.meta.handshakeSpkId) {
            "signedPreKeyId mismatch with epoch-1 session: wire=${wire.signedPreKeyId}, epoch1=${epoch1.meta.handshakeSpkId}"
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

    private suspend fun createEpoch2AsInitiator(peerDeviceId: PeerId, offer: InnerSessionControl.OpkOffer) {
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

    private suspend fun maybeAttachOpkOffer(
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
            logger.debug(
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

    private suspend fun resolveOfferedOpk(meta: CryptoSessionMeta): LocalOneTimePreKey {
        meta.offeredOpkId?.let { offeredOpkId ->
            opkRepository.loadOffered(offeredOpkId)?.let { return it }
        }
        val opk = opkRepository.allocate()
        opkRepository.markOffered(opk.keyId)
        return opk
    }

    private suspend fun loadPendingEpoch2Initiator(peerDeviceId: PeerId): CryptoSessionRecord? =
        sessionStore.loadSessions(peerDeviceId, sessionEpoch = 2)
            .firstOrNull {
                it.canonical &&
                    it.meta.status == SessionStatus.PENDING &&
                    it.meta.role == SessionRole.INITIATOR
            }

    private suspend fun maybePromoteEpoch2ForEncrypt(
        peerDeviceId: PeerId,
        frame: SessionWireFrame,
        hadPendingEpoch2BeforeDecrypt: Boolean,
    ) {
        if (frame.sessionEpoch != 1 || !hadPendingEpoch2BeforeDecrypt) {
            return
        }
        promotePendingEpoch2ForEncrypt(peerDeviceId)
    }

    private suspend fun promotePendingEpoch2ForEncrypt(peerDeviceId: PeerId) {
        val pending = loadPendingEpoch2Initiator(peerDeviceId) ?: return
        val now = timeProvider.nowEpochSeconds()
        sessionStore.save(
            pending.copy(
                meta = pending.meta.copy(
                    status = SessionStatus.ACTIVE,
                    updatedAtEpochSeconds = now,
                ),
            ),
        )
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_OPENED,
            message = "Promoted pending epoch-2 session for outbound encrypt",
            fields = mapOf("peerDeviceId" to peerDeviceId),
        )
    }

    private fun isEpoch2OpkBootstrapFailure(frame: SessionWireFrame, error: Exception): Boolean {
        if (frame.sessionEpoch != 2) {
            return false
        }
        return when (error) {
            is CryptoSessionException.HandshakeRequired,
            is CryptoSessionException.MissingOfferedOpk,
            is CryptoSessionException.OpkConsumeFailed,
            -> true
            is IllegalArgumentException -> error.message?.contains("signedPreKeyId mismatch") == true
            else -> false
        }
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
            sessionGeneration = meta.sessionGeneration,
            mode = mode,
            oneTimePreKeyId = oneTimePreKeyId,
        )
    }

    private suspend fun nextSessionGeneration(
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        role: SessionRole,
    ): Int = (sessionStore.latestGeneration(peerDeviceId, sessionEpoch, role) ?: 0) + 1

    private suspend fun loadCanonicalSession(peerDeviceId: PeerId, sessionEpoch: Int): LoadedSession? {
        val record = sessionStore.loadActiveCanonical(peerDeviceId, sessionEpoch) ?: return null
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

    private fun zeroizeInitiatorEphemeralMaterial(
        ephemeral: EncryptionKeyPair,
        result: X3dhInitiatorResult,
    ) {
        ephemeral.privateKey.fill(0)
        result.ephemeralKeyPair.privateKey.fill(0)
    }

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
