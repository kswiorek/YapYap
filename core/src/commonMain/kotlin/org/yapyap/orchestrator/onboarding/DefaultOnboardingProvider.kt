package org.yapyap.orchestrator.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.yapyap.orchestrator.sync.SyncCoordinator
import org.yapyap.protocol.envelopes.Intro
import org.yapyap.routing.router.Router

/**
 * Scaffolding stub for the sprint-4 newcomer onboarding handshake. Consumes authenticated [Intro]s
 * from the router (the RecoveryRequest flavour belongs to the standing [RecoveryResponder]), seeds
 * the sponsor's provisional identity rows, and triggers the global-room range sync; the one-time
 * secret is burned at COMPLETE.
 *
 * Runs in every mode — headless relays have no runtime yet still need to onboard as newcomers. The
 * GUI observes progress via [state]; the sponsor (QR-scan) side is the runtime service's job.
 */
internal class DefaultOnboardingProvider(
    private val router: Router,
    private val sessionStore: BootstrapSessionStore,
    private val syncCoordinator: SyncCoordinator,
) : OnboardingProvider {

    private val _state = MutableStateFlow(OnboardingState.IDLE)
    override val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private var collectJob: Job? = null

    override fun start(scope: CoroutineScope) {
        // TODO(sprint 4 onboarding): drive IDLE -> AWAITING_INTRO -> SYNCING -> COMPLETE and burn
        // the one-time secret at COMPLETE (onboarding state machine). Subscribe before any intro can
        // arrive; on a restart mid-onboarding (persisted secret) an intro could race this boot.
        collectJob = scope.launch {
            router.bootstrapPackets.collect { event ->
                val intro = event.payload as? Intro ?: return@collect
                onBootstrapIntro(intro)
            }
        }
    }

    override suspend fun stop() {
        collectJob?.cancel()
    }

    private suspend fun onBootstrapIntro(intro: Intro) {
        // TODO(sprint 4 onboarding): insert the sponsor's provisional account + device + GLOBAL
        // membership rows (insert-only, FK order, provisional flag — requires the devices.provisional
        // migration and an insert-only IdentityKeyRepository method), then
        // syncCoordinator.requestRangeSync(GLOBAL, intro.dagHeadLamport). COMPLETE + burn land once
        // the global-room fold carries the local device's own Add event.
        TODO("sprint 4 onboarding: provisional rows + global range sync not yet implemented")
    }
}
