package org.yapyap.orchestrator.runtime.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.yapyap.orchestrator.IdentityPayload
import org.yapyap.orchestrator.onboarding.BootstrapSessionStore
import org.yapyap.routing.router.BootstrapIntroEvent
import org.yapyap.routing.router.Router

/**
 * Scaffolding stub for the sprint-4 onboarding handshake. The router delivers authenticated
 * [BootstrapIntroEvent]s here; persisting the sponsor's provisional rows and triggering the
 * global-room range sync land with the onboarding implementation.
 */
internal class DefaultOnboardingService(
    private val router: Router,
    private val sessionStore: BootstrapSessionStore,
) : OnboardingService {

    private val _state = MutableStateFlow(OnboardingState.IDLE)
    override val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private var collectJob: Job? = null

    fun start(scope: CoroutineScope) {
        // TODO(sprint 4 onboarding): drive IDLE -> AWAITING_INTRO -> SYNCING -> COMPLETE and burn
        // the one-time secret at COMPLETE (onboarding state machine).
        collectJob = scope.launch {
            router.bootstrapIntros.collect { onBootstrapIntro(it) }
        }
    }

    fun stop() {
        collectJob?.cancel()
    }

    override suspend fun onBootstrapIntro(event: BootstrapIntroEvent) {
        // TODO(sprint 4 onboarding): insert the sponsor's provisional account + device + GLOBAL
        // membership rows (insert-only, FK order, provisional flag) — requires the devices.provisional
        // migration and an insert-only IdentityKeyRepository method — then requestRangeSync(GLOBAL,
        // 0..event.payload.dagHeadLamport).
        TODO("sprint 4 onboarding: provisional rows + global range sync not yet implemented")
    }

    override suspend fun sponsorNewcomer(sharedSecret: ByteArray, newcomerIdentity: IdentityPayload) {
        // TODO(sprint 4 onboarding): sessionStore.setActiveSecret(sharedSecret), insert the newcomer's
        // peer rows, append AddAccount + AddDevice to the global DAG, then router.sendBootstrapIntro.
        TODO("sprint 4 onboarding: sponsor flow not yet implemented")
    }
}