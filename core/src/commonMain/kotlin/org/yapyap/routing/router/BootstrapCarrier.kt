package org.yapyap.routing.router

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapPayload
import kotlin.uuid.Uuid

/** Bootstrap-family packets: onboarding intros and account-recovery requests. */
internal interface BootstrapCarrier {
    /**
     * Hot stream of authenticated bootstrap-family packets (payload variants dispatch
     * consumer-side). Each event has passed its kind's authentication (AEAD intro gate /
     * account-signature recovery check); consuming is an orchestrator concern: INTRO → newcomer
     * onboarding provider, RECOVERY_REQUEST → recovery responder.
     */
    val bootstrapPackets: Flow<BootstrapPacketEvent>

    /**
     * Send a bootstrap-family packet: protect inside the router, queue through the outbox with
     * a short lifetime, cleared on the peer's ACK.
     *
     * @param targetEndpoint out-of-band endpoint override for targets with no local devices row.
     * @param sharedSecret sender's in-memory one-time secret, required for INTRO (used once,
     *   never persisted). Null for RECOVERY_REQUEST.
     * @return the queued packet's id, as later reported in [MessageCarrier.deliveries]
     *   ([DeliverySubject.Bootstrap]).
     */
    suspend fun sendBootstrap(
        payload: BootstrapPayload,
        target: PeerId,
        targetEndpoint: TorEndpoint? = null,
        sharedSecret: ByteArray? = null,
    ): Uuid
}
