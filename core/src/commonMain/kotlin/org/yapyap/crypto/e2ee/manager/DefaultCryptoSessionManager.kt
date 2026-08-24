package org.yapyap.crypto.e2ee.manager

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.e2ee.CryptoSessionConfig
import org.yapyap.crypto.e2ee.CryptoSessionException
import org.yapyap.crypto.e2ee.policy.SimultaneousInitPolicy
import org.yapyap.crypto.e2ee.session.*
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.crypto.CryptoSessionStore
import org.yapyap.persistence.key.OpkRepository
import org.yapyap.protocol.PeerId
import org.yapyap.time.EpochProvider
import org.yapyap.time.SystemEpochProvider

class DefaultCryptoSessionManager(
    private val crypto: CryptoProvider,
    private val x3dh: X3dhHandshake,
    private val sessionStore: CryptoSessionStore,
    private val identityResolver: IdentityResolver,
    private val opkRepository: OpkRepository,
    private val cryptoLimits: StateFlow<CryptoLimits>,
    private val timeProvider: EpochProvider = SystemEpochProvider,
    private val upgradePolicy: SessionUpgradePolicy = SessionUpgradePolicy.NEVER,
    private val sessionConfig: StateFlow<CryptoSessionConfig>,
) : CryptoSessionManager {

    private val codec = CryptoWireCodec(cryptoLimits)

    private val peerLocks = PeerLockRegistry()

    private val sessionBootstrap = SessionBootstrap(
        crypto = crypto,
        x3dh = x3dh,
        sessionStore = sessionStore,
        identityResolver = identityResolver,
        opkRepository = opkRepository,
        timeProvider = timeProvider,
    )

    private val epoch2Upgrade = Epoch2Upgrade(
        crypto = crypto,
        sessionStore = sessionStore,
        identityResolver = identityResolver,
        opkRepository = opkRepository,
        sessionBootstrap = sessionBootstrap,
        timeProvider = timeProvider,
        upgradePolicy = upgradePolicy,
    )

    override suspend fun encryptMessage(
        remoteDeviceId: PeerId,
        bytes: ByteArray,
    ): ByteArray = peerLocks.withPeerLock(remoteDeviceId) {
        codec.encode(encryptMessageUnderLock(remoteDeviceId, bytes))
    }

    override suspend fun decryptMessage(
        remoteDeviceId: PeerId,
        frameBytes: ByteArray,
    ): ByteArray {
        val frame = codec.decodeSessionWireFrame(frameBytes)
        return peerLocks.withPeerLock(remoteDeviceId) {
            decryptMessageUnderLock(remoteDeviceId, frame)
        }
    }

    private suspend fun encryptMessageUnderLock(
        remoteDeviceId: PeerId,
        bytes: ByteArray,
    ): SessionWireFrame {
        require(bytes.size <= cryptoLimits.value.maxInnerPlaintextBytes) {
            "inner plaintext size ${bytes.size} exceeds max ${cryptoLimits.value.maxInnerPlaintextBytes}"
        }
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
            loaded = sessionBootstrap.bootstrapEpoch1Initiator(remoteDeviceId, generation)
            outerHandshake = sessionBootstrap.buildOutboundWire(
                peerDeviceId = remoteDeviceId,
                meta = loaded.meta,
                epoch = 1,
                mode = X3dhMode.THREE_DH,
            )
        } else if (SimultaneousInitPolicy.shouldAttachOutboundWire(loaded, epoch)) {
            outerHandshake = sessionBootstrap.buildOutboundWire(
                peerDeviceId = remoteDeviceId,
                meta = loaded.meta,
                epoch = epoch,
                mode = if (epoch == 1) X3dhMode.THREE_DH else X3dhMode.FOUR_DH,
                oneTimePreKeyId = loaded.meta.handshakeOpkId,
            )
        }

        val innerToEncrypt = epoch2Upgrade.maybeAttachOpkOffer(remoteDeviceId, epoch, loaded, bytes)
        if (innerToEncrypt is RatchetInnerPlaintext.WithControl &&
            innerToEncrypt.control is InnerSessionControl.OpkOffer
        ) {
            loaded.meta = loaded.meta.copy(offeredOpkId = innerToEncrypt.control.opkId)
        }

        val ratchet = loaded.session.encrypt(codec.encode(innerToEncrypt))
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
        val hadPendingEpoch2 = sessionBootstrap.loadPendingEpoch2Initiator(remoteDeviceId) != null

        if (SimultaneousInitPolicy.shouldBootstrapFromInboundHandshake(frame, canonicalRecord)) {
            decryptFromInboundHandshake(remoteDeviceId, frame, canonicalRecord, hadPendingEpoch2)?.let { return it }
        }

        return decryptWithExistingSession(remoteDeviceId, frame, hadPendingEpoch2)
    }

    private suspend fun decryptFromInboundHandshake(
        remoteDeviceId: PeerId,
        frame: SessionWireFrame,
        canonicalRecord: CryptoSessionRecord?,
        hadPendingEpoch2: Boolean,
    ): ByteArray? {
        val bootstrapped = try {
            sessionBootstrap.bootstrapFromFrame(remoteDeviceId, frame)
        } catch (error: Exception) {
            if (!epoch2Upgrade.isEpoch2OpkBootstrapFailure(frame, error)) {
                throw error
            }
            AppLog.debug(
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

        SimultaneousInitPolicy.handleInboundGenerationReset(
            sessionStore = sessionStore,
            timeProvider = timeProvider,
            remoteDeviceId = remoteDeviceId,
            frame = frame,
            canonicalRecord = canonicalRecord,
        )

        val localDeviceId = identityResolver.getLocalDeviceId()
        val responderIsCanonical = SimultaneousInitPolicy.inboundResponderSessionIsCanonical(
            localDeviceId,
            remoteDeviceId,
        )
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
            if (sessionConfig.value.supersedeRogueSessionsAfterSimultaneousInit) {
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
            sessionConfig.value.supersedeRogueSessionsAfterSimultaneousInit
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
            epoch2Upgrade.onEpoch2Confirmed(remoteDeviceId)
        }
        epoch2Upgrade.maybeUpgradeToEpoch2(
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
        epoch2Upgrade.maybePromoteEpoch2ForEncrypt(remoteDeviceId, frame, hadPendingEpoch2)
        return inner.bytes
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
            val loaded = LoadedSession(
                session = DoubleRatchetSession.fromState(crypto, record.ratchetState),
                meta = record.meta,
            )
            val inner = tryDecryptRatchet(loaded.session, frame.ratchet) ?: continue
            return finalizeDecrypt(remoteDeviceId, frame, record, loaded, inner, hadPendingEpoch2)
        }
        throw CryptoSessionException.NoSession(remoteDeviceId, frame.sessionEpoch)
    }

    private suspend fun tryDecryptRatchet(
        session: DoubleRatchetSession,
        ratchet: RatchetCiphertext,
    ): RatchetInnerPlaintext? {
        val plaintext = try {
            session.decrypt(ratchet)
        } catch (error: Throwable) {
            if (!isCandidateMismatch(error)) {
                throw error
            }
            return null
        }
        return codec.decodeRatchetInnerPlaintext(plaintext)
    }

    private fun isCandidateMismatch(error: Throwable): Boolean =
        when (error) {
            is CryptoSessionException.Replay,
            is CryptoSessionException.SupersededDhChain,
            is CryptoSessionException.MessageSkipExceeded,
            is CryptoSessionException.DecryptionFailed,
                -> true
            else -> false
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
        return finalizeDecrypt(remoteDeviceId, frame, record, loaded, inner, hadPendingEpoch2)
    }

    private suspend fun finalizeDecrypt(
        remoteDeviceId: PeerId,
        frame: SessionWireFrame,
        record: CryptoSessionRecord,
        loaded: LoadedSession,
        inner: RatchetInnerPlaintext,
        hadPendingEpoch2: Boolean,
    ): ByteArray {
        persist(remoteDeviceId, frame.sessionEpoch, loaded.session, loaded.meta, canonical = record.canonical)
        if (frame.sessionEpoch == 2) {
            epoch2Upgrade.onEpoch2Confirmed(remoteDeviceId)
        }
        epoch2Upgrade.maybeUpgradeToEpoch2(
            remoteDeviceId = remoteDeviceId,
            frame = frame,
            record = record.copy(ratchetState = loaded.session.snapshot()),
            inner = inner,
        )
        epoch2Upgrade.maybePromoteEpoch2ForEncrypt(remoteDeviceId, frame, hadPendingEpoch2)
        return inner.bytes
    }

    private suspend fun decryptRatchet(
        session: DoubleRatchetSession,
        ratchet: RatchetCiphertext,
    ): RatchetInnerPlaintext {
        val plaintext = session.decrypt(ratchet)
        return codec.decodeRatchetInnerPlaintext(plaintext)
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
