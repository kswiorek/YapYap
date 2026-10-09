package org.yapyap.orchestrator.runtime.account

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.orchestrator.dag.DagException
import org.yapyap.orchestrator.fold.global.GlobalEventProjector
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.orchestrator.runtime.globalevent.GlobalEventOutcome
import org.yapyap.orchestrator.runtime.globalevent.GlobalEventRefusal
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.protocol.PeerId

/**
 * Self-service control plane (GUI-facing remove-own-device / remove-this-device /
 * remove-own-account, plus the cascade-ban source query). Stateless: every method is a
 * one-shot suspend call — no `StateFlow` of its own, no collector, no `start`/`stop`.
 *
 * Thin fail-fast wrappers over the [GlobalEventProjector] publish calls (same pattern as
 * the sponsor side of the onboarding service): the projector appends to the global DAG,
 * folds, and broadcasts. The fold itself *ignores* authorization-invalid events silently
 * (stored VERIFIED, no shadow effect), so these pre-checks are the GUI's only refusal
 * feedback — every domain refusal is a value returned BEFORE any write; generic throws
 * are reserved for infrastructure failures.
 */
internal class DefaultAccountService(
    private val projector: GlobalEventProjector,
    private val identityResolver: IdentityResolver,
    private val identityKeyRepository: IdentityKeyRepository,
    private val onboardingState: StateFlow<OnboardingState>,
) : AccountService {

    override suspend fun removeOwnDevice(deviceId: PeerId): GlobalEventOutcome {
        // 1. Onboarding: the fold has not anchored the local device yet — a removal now
        //    would be ignored (unresolvable author) or garbage (detached root). Fail fast.
        if (isOnboardingActive()) return GlobalEventOutcome.Refused(GlobalEventRefusal.OnboardingActive)
        // 2. The local device goes down the self-removal path (different post-condition:
        //    this node becomes chain-dead), never the sibling path.
        if (deviceId == identityResolver.getLocalDeviceId()) return removeThisDevice()
        // 3. Ownership: the fold's RemoveDevice(own) branch requires the signer to belong
        //    to the target's account — check it here so the GUI gets a refusal, not a
        //    silent no-op reported as Published.
        val owner = identityResolver.getAccountIdForDevice(deviceId)
            ?: return GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotFound)
        if (owner != identityResolver.getLocalAccountId()) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.NotOwnDevice)
        }
        // 4. Provisional target: its AddDevice has not folded here yet, so the removal
        //    would be ignored. On an established node this is an invariant assertion —
        //    peer rows are committed non-provisional by the fold, never seeded.
        if (identityKeyRepository.isDeviceProvisional(deviceId)) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady)
        }

        try {
            projector.publishRemoveDevice(deviceId)
        } catch (_: DagException.FrontierUnavailable) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady)
        }
        return GlobalEventOutcome.Published
    }

    override suspend fun removeThisDevice(): GlobalEventOutcome {
        if (isOnboardingActive()) return GlobalEventOutcome.Refused(GlobalEventRefusal.OnboardingActive)
        val localDeviceId = identityResolver.getLocalDeviceId()
        // Unanchored local device (still provisional): the fold has no AddDevice for us,
        // so the removal would be ignored.
        if (identityKeyRepository.isDeviceProvisional(localDeviceId)) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady)
        }

        try {
            projector.publishRemoveDevice(localDeviceId)
        } catch (_: DagException.FrontierUnavailable) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady)
        }
        // Published means the local fold already committed our tombstone: the
        // orchestrator's stateChanges collector flips OrchestratorState to ResetRequired
        // asynchronously — the GUI observes Orchestrator.state, not this return value.
        return GlobalEventOutcome.Published
    }

    override suspend fun removeOwnAccount(): GlobalEventOutcome {
        if (isOnboardingActive()) return GlobalEventOutcome.Refused(GlobalEventRefusal.OnboardingActive)
        // Device-anchored implies account-anchored: the fold commits AddAccount and
        // AddDevice together, so one provisional predicate covers both.
        if (identityKeyRepository.isDeviceProvisional(identityResolver.getLocalDeviceId())) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady)
        }

        try {
            projector.publishRemoveAccount(identityResolver.getLocalAccountId())
        } catch (_: DagException.FrontierUnavailable) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady)
        }
        return GlobalEventOutcome.Published
    }

    override suspend fun devicesAddedBy(deviceId: PeerId): List<PeerId> =
        projector.activeDevicesAddedBy(deviceId)

    private fun isOnboardingActive(): Boolean =
        onboardingState.value == OnboardingState.AWAITING_INTRO ||
            onboardingState.value == OnboardingState.SYNCING
}
