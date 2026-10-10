package org.yapyap.orchestrator.runtime.account

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.IdentityProvisioning
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.orchestrator.dag.DagException
import org.yapyap.orchestrator.fold.global.GlobalEventProjector
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.orchestrator.runtime.globalevent.GlobalEventOutcome
import org.yapyap.orchestrator.runtime.globalevent.GlobalEventRefusal
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.IdentityStatus
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
    private val identityProvisioning: IdentityProvisioning,
    private val onboardingState: StateFlow<OnboardingState>,
) : AccountService {

    override suspend fun removeOwnDevice(deviceId: PeerId, recoveryKey: String?): GlobalEventOutcome {
        // 1. Onboarding: the fold has not anchored the local device yet — a removal now
        //    would be ignored (unresolvable author) or garbage (detached root). Fail fast.
        if (isOnboardingActive()) return GlobalEventOutcome.Refused(GlobalEventRefusal.OnboardingActive)
        // 2. The local device goes down the self-removal path (different post-condition:
        //    this node becomes chain-dead), never the sibling path.
        if (deviceId == identityResolver.getLocalDeviceId()) return removeThisDevice(recoveryKey)
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
        // 5. Last-device gate: removing the account's last confirmed device without
        //    proving possession of the recovery key bricks the account once the key is
        //    lost too. The fold would honor the removal — this gate is self-harm
        //    prevention, not authorization (a bypass bricks only the caller's account).
        lastDeviceRefusal(owner, deviceId, recoveryKey)?.let { return it }

        try {
            projector.publishRemoveDevice(deviceId)
        } catch (_: DagException.FrontierUnavailable) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady)
        }
        return GlobalEventOutcome.Published
    }

    override suspend fun removeThisDevice(recoveryKey: String?): GlobalEventOutcome {
        if (isOnboardingActive()) return GlobalEventOutcome.Refused(GlobalEventRefusal.OnboardingActive)
        val localDeviceId = identityResolver.getLocalDeviceId()
        // Unanchored local device (still provisional): the fold has no AddDevice for us,
        // so the removal would be ignored.
        if (identityKeyRepository.isDeviceProvisional(localDeviceId)) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady)
        }
        lastDeviceRefusal(identityResolver.getLocalAccountId(), localDeviceId, recoveryKey)?.let { return it }

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

    override suspend fun removeOwnAccount(successor: AccountId?): GlobalEventOutcome {
        if (isOnboardingActive()) return GlobalEventOutcome.Refused(GlobalEventRefusal.OnboardingActive)
        // Device-anchored implies account-anchored: the fold commits AddAccount and
        // AddDevice together, so one provisional predicate covers both.
        if (identityKeyRepository.isDeviceProvisional(identityResolver.getLocalDeviceId())) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady)
        }
        // Owner handover gate: the owner's own leave without a fold-valid successor is a
        // fold no-op (the owner slot is never empty) — refuse here so the GUI can prompt
        // for one instead of reporting a silent Published.
        if (identityResolver.isLocalAccountOwner()) {
            if (!isFoldValidSuccessor(successor)) {
                return GlobalEventOutcome.Refused(GlobalEventRefusal.InvalidSuccessor)
            }
        } else if (successor != null) {
            // The successor field is owner-only: a non-owner leave carrying one is
            // ignored whole by the fold (no smuggling) — refuse it here.
            return GlobalEventOutcome.Refused(GlobalEventRefusal.InvalidSuccessor)
        }

        try {
            projector.publishRemoveAccount(identityResolver.getLocalAccountId(), successor)
        } catch (_: DagException.FrontierUnavailable) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady)
        }
        return GlobalEventOutcome.Published
    }

    override suspend fun devicesAddedBy(deviceId: PeerId): List<PeerId> =
        projector.activeDevicesAddedBy(deviceId)

    /**
     * The last-device recovery-key gate: null when the target is not the account's last
     * confirmed device (siblings survive — recovery stays possible) or when [recoveryKey]
     * proves possession of the account recovery key; `RecoveryKeyRequired` otherwise.
     * Advisory like every pre-check here — the fold is authoritative.
     */
    private suspend fun lastDeviceRefusal(
        accountId: AccountId,
        target: PeerId,
        recoveryKey: String?,
    ): GlobalEventOutcome.Refused? {
        val hasConfirmedSibling = identityKeyRepository.getAllPeerDevicesForAccount(accountId)
            .any { it != target && !identityKeyRepository.isDeviceProvisional(it) }
        if (hasConfirmedSibling) return null
        if (recoveryKey != null && identityProvisioning.verifyRecoveryKey(recoveryKey)) return null
        return GlobalEventOutcome.Refused(GlobalEventRefusal.RecoveryKeyRequired)
    }

    /**
     * Advisory mirror of the fold's handover validity: ACTIVE successor, never the leaver,
     * holding at least one confirmed device. The fold decides; this only shapes GUI feedback.
     */
    private suspend fun isFoldValidSuccessor(successor: AccountId?): Boolean {
        if (successor == null || successor == identityResolver.getLocalAccountId()) return false
        if (identityKeyRepository.getAccountStatus(successor) != IdentityStatus.ACTIVE) return false
        return identityKeyRepository.getAllPeerDevicesForAccount(successor)
            .any { !identityKeyRepository.isDeviceProvisional(it) }
    }

    private fun isOnboardingActive(): Boolean =
        onboardingState.value == OnboardingState.AWAITING_INTRO ||
            onboardingState.value == OnboardingState.SYNCING
}
