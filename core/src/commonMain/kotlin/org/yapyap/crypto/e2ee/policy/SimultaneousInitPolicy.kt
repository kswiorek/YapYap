package org.yapyap.crypto.e2ee.policy

import org.yapyap.crypto.e2ee.session.CryptoSessionRecord
import org.yapyap.crypto.e2ee.session.LoadedSession
import org.yapyap.crypto.e2ee.session.SessionRole
import org.yapyap.crypto.e2ee.session.SessionWireFrame
import org.yapyap.persistence.crypto.CryptoSessionStore
import org.yapyap.protocol.PeerId
import kotlin.time.Clock

internal object SimultaneousInitPolicy {

    /**
     * Simultaneous-init tie-break: the peer with the lower device id is the canonical responder;
     * the higher id keeps the canonical initiator session when both sides sent first.
     */
    fun inboundResponderSessionIsCanonical(localDeviceId: PeerId, peerDeviceId: PeerId): Boolean =
        peerDeviceId.id > localDeviceId.id

    /**
     * Bootstrap from [SessionWireFrame.outerHandshake] on first contact, or during simultaneous
     * initiation when the local initiator has sent but not yet received on that epoch.
     */
    fun shouldBootstrapFromInboundHandshake(
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

    fun shouldAttachOutboundWire(loaded: LoadedSession, epoch: Int): Boolean {
        if (loaded.meta.role != SessionRole.INITIATOR) {
            return false
        }
        val recvMessageNumber = loaded.session.snapshot().recvMessageNumber
        return recvMessageNumber == 0 && (epoch == 1 || epoch == 2)
    }

    suspend fun handleInboundGenerationReset(
        sessionStore: CryptoSessionStore,
        clock: Clock,
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
            clock.now(),
        )
        if (frame.sessionEpoch == 1) {
            sessionStore.markEpochSuperseded(
                remoteDeviceId,
                sessionEpoch = 2,
                updatedAt = clock.now(),
            )
        }
    }
}
