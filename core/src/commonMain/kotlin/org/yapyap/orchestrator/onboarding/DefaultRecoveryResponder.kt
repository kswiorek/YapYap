package org.yapyap.orchestrator.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.yapyap.persistence.key.BootstrapSessionStore
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.envelopes.RecoveryRequest
import org.yapyap.routing.router.Router

/**
 * Scaffolding stub for the sprint-4 recovery responder. Consumes authenticated
 * [RecoveryRequest]s from the router (the INTRO flavour belongs to the newcomer-side
 * [OnboardingProvider]); the relay flow is deliberately left as TODO until the details are settled.
 */
internal class DefaultRecoveryResponder(
    private val router: Router,
    private val sessionStore: BootstrapSessionStore,
    private val identityKeyRepository: IdentityKeyRepository,
    private val roomRepository: RoomRepository,
) : RecoveryResponder {

    private var collectJob: Job? = null

    override fun start(scope: CoroutineScope) {
        collectJob = scope.launch {
            router.bootstrapPackets.collect { event ->
                val request = event.payload as? RecoveryRequest ?: return@collect
                onRecoveryRequest(request)
            }
        }
    }

    override suspend fun stop() {
        collectJob?.cancel()
    }

    private suspend fun onRecoveryRequest(request: RecoveryRequest) {
        // TODO(sprint 4 recovery): iron out the flow details before implementing:
        //   - refuse while this node's own onboarding is active (sessionStore.introKey() != null) —
        //     a mid-onboarding node is not a good sync seed;
        //   - check the requester's account exists and is ACTIVE (identityKeyRepository) — a
        //     REMOVED/BANNED account must not re-enter via recovery;
        //   - append AddDevice(key_signature = request.accountSignature) bound to the requester's
        //     account and fold immediately — the chain-derived device row then makes the newcomer's
        //     sync requests verifiable, and the intro's dagHead includes its own AddDevice;
        //   - reply with an Intro payload protected under request.sharedSecret (AEAD), carrying this
        //     node's account + device + onion + the updated GLOBAL dagHead, sent to the requester's
        //     deviceId (roomRepository for the dagHead; router.sendBootstrap for the send).
        TODO("sprint 4 recovery: recovery responder flow not yet implemented")
    }
}