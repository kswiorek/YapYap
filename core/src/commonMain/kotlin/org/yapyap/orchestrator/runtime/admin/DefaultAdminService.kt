package org.yapyap.orchestrator.runtime.admin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * Admin-gated control plane: thin fail-fast wrappers over the
 * [GlobalEventProjector] publish calls (same pattern as the account service).
 * The fold *ignores* authorization-invalid events silently (stored VERIFIED,
 * no shadow effect), so these pre-checks are the GUI's only refusal feedback —
 * every domain refusal is a value returned BEFORE any write; generic throws
 * are reserved for infrastructure failures. Advisory like every pre-check: a
 * race (e.g. a concurrent demotion) degrades to a no-op publish, never a fork.
 *
 * Stateful in exactly one field: [localIsAdmin] is refreshed off the projector's
 * stateChanges (the chain-derived flag is the read model), hence start/stop —
 * everything else is one-shot suspend calls.
 */
internal class DefaultAdminService(
    private val projector: GlobalEventProjector,
    private val identityResolver: IdentityResolver,
    private val identityKeyRepository: IdentityKeyRepository,
    private val onboardingState: StateFlow<OnboardingState>,
) : AdminService {

    private val _localIsAdmin = MutableStateFlow(false)
    override val localIsAdmin: StateFlow<Boolean> = _localIsAdmin.asStateFlow()

    private var collectJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (collectJob?.isActive == true) return
        collectJob = scope.launch {
            refreshLocalIsAdmin()
            launch { projector.stateChanges.collect { refreshLocalIsAdmin() } }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    override suspend fun grantAdmin(target: AccountId): GlobalEventOutcome {
        commonRefusal()?.let { return it }
        // Fold mirror (GrantAdmin): the target must exist, be ACTIVE, and be a
        // MEMBER — anything else is a silent no-op the GUI would report as
        // Published. (OWNER implies admin: granting to the owner is a no-op.)
        val status = identityKeyRepository.getAccountStatus(target)
            ?: return GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotFound)
        if (status != IdentityStatus.ACTIVE || identityKeyRepository.isAccountAdmin(target)) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotEligible)
        }
        return publish { projector.publishGrantAdmin(target) }
    }

    override suspend fun revokeAdmin(target: AccountId): GlobalEventOutcome {
        commonRefusal()?.let { return it }
        val status = identityKeyRepository.getAccountStatus(target)
            ?: return GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotFound)
        // Owner irrevocable (§3): no forgery — and no admin — can strip the
        // network's repair path. The owner's exit is the self-service handover.
        if (identityKeyRepository.isAccountOwner(target)) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.OwnerIrrevocable)
        }
        // Fold mirror: the target must be ACTIVE and currently ADMIN. Self
        // step-down is legal — the fold honors an admin demoting their own
        // account (the demotion seal is interval-scoped, reversible by re-grant).
        if (status != IdentityStatus.ACTIVE || !identityKeyRepository.isAccountAdmin(target)) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotEligible)
        }
        return publish { projector.publishRemoveAdmin(target) }
    }

    override suspend fun removeAccount(target: AccountId): GlobalEventOutcome {
        commonRefusal()?.let { return it }
        // Own account: self-service owns it — it carries the owner-handover
        // gate (the successor field is owner-only; this signature takes none,
        // so an owner self-leave here would be a guaranteed silent no-op).
        if (target == identityResolver.getLocalAccountId()) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.SelfServiceRequired)
        }
        val status = identityKeyRepository.getAccountStatus(target)
            ?: return GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotFound)
        if (identityKeyRepository.isAccountOwner(target)) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.OwnerIrrevocable)
        }
        if (status != IdentityStatus.ACTIVE) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotEligible)
        }
        return publish { projector.publishRemoveAccount(target) }
    }

    override suspend fun removeDevice(target: PeerId): GlobalEventOutcome {
        commonRefusal()?.let { return it }
        val targetAccount = identityResolver.getAccountIdForDevice(target)
            ?: return GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotFound)
        // Own-account device: self-service owns it — the admin path would
        // bypass the last-device recovery-key gate (self-harm prevention).
        if (targetAccount == identityResolver.getLocalAccountId()) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.SelfServiceRequired)
        }
        // The owner's devices are unbannable by admins (§3) — the owner removes
        // their own via self-service. A null owner (nothing committed yet) passes.
        if (targetAccount == identityKeyRepository.getOwnerAccountId()) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.OwnerIrrevocable)
        }
        // Provisional target: its AddDevice has not folded here yet, so the
        // removal would be ignored. On an established node this is an invariant
        // assertion — peer rows are committed non-provisional by the fold, never
        // seeded (the only seeding path is the newcomer's own intro handler,
        // mid-onboarding, which [commonRefusal] already refuses).
        if (identityKeyRepository.isDeviceProvisional(target)) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady)
        }
        // Deliberately NO ACTIVE gate: the fold honors RemoveDevice of an
        // already-BANNED target — the seal mints even when the ban's target is
        // already dead (§3 rule 1) — so refusing would be stricter than the fold.
        return publish { projector.publishRemoveDevice(target) }
    }

    /** Shared preamble: onboarding in flight → unanchored local device → not admin. */
    private suspend fun commonRefusal(): GlobalEventOutcome.Refused? {
        if (isOnboardingActive()) return GlobalEventOutcome.Refused(GlobalEventRefusal.OnboardingActive)
        if (identityKeyRepository.isDeviceProvisional(identityResolver.getLocalDeviceId())) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady)
        }
        if (!identityResolver.isLocalAccountAdmin()) {
            return GlobalEventOutcome.Refused(GlobalEventRefusal.NotAdmin)
        }
        return null
    }

    private suspend fun publish(call: suspend () -> Unit): GlobalEventOutcome {
        return try {
            call()
            GlobalEventOutcome.Published
        } catch (_: DagException.FrontierUnavailable) {
            GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady)
        }
    }

    private suspend fun refreshLocalIsAdmin() {
        _localIsAdmin.value = identityKeyRepository.isLocalAccountAdmin()
    }

    private fun isOnboardingActive(): Boolean =
        onboardingState.value == OnboardingState.AWAITING_INTRO ||
                onboardingState.value == OnboardingState.SYNCING
}
